package com.adaiadai.core.interfaces;

import com.adaiadai.core.application.TradingAdviceAppService;
import com.adaiadai.core.application.TradingParseAppService;
import com.adaiadai.core.application.TradingAppService;
import com.adaiadai.core.application.WatchlistBuyPointService;
import com.adaiadai.core.application.SoldScoreService;
import com.adaiadai.core.application.TradingReviewAppService;
import com.adaiadai.core.domain.trading.Position;
import com.adaiadai.core.domain.trading.SoldTrade;
import com.adaiadai.core.domain.trading.TradeDirection;
import com.adaiadai.core.domain.trading.TradeRecord;
import com.adaiadai.core.domain.trading.WatchlistItem;
import com.adaiadai.core.domain.trading.TransferRecord;
import com.adaiadai.core.infrastructure.storage.StorageException;
import com.adaiadai.core.kernel.plugin.PluginRegistry;
import com.adaiadai.core.kernel.plugin.PluginService;
import com.adaiadai.core.domain.trading.PushSettings;
import com.adaiadai.core.infrastructure.storage.PushSettingsRepository;
import com.adaiadai.core.application.TradeLogCollectService;
import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.beans.factory.annotation.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

/**
 * TradingController — 交易相关的 REST API。
 */
@RestController
@RequestMapping("/api/v1/trading")
public class TradingController {

    private static final Logger log = LoggerFactory.getLogger(TradingController.class);

    private final TradingAppService tradingAppService;
    private final TradingReviewAppService reviewAppService;
    private final TradingAdviceAppService adviceAppService;
    private final TradingParseAppService parseAppService;
    private final PluginService pluginService;
    private final WatchlistBuyPointService buyPointService;
    private final SoldScoreService soldScoreService;
    /** RFC 20260817：推送开关（用户可关闭各类型推送）。 */
    private final PushSettingsRepository pushSettingsRepository;
    /** RFC 20260817：交易日志自动归集（当日候选/确认落库）。 */
    private final TradeLogCollectService tradeLogCollectService;
    /** P1-1（2026-08-17 走查）：99-inbox 路径配置驱动（生产 /opt/adaios/os/... 由 .env 注入，防硬编码相对路径失效） */
    private final Path inboxDir;

    public TradingController(TradingAppService tradingAppService,
                             TradingReviewAppService reviewAppService,
                             TradingAdviceAppService adviceAppService,
                             TradingParseAppService parseAppService,
                             PluginService pluginService,
                             WatchlistBuyPointService buyPointService,
                             SoldScoreService soldScoreService,
                             PushSettingsRepository pushSettingsRepository,
                             TradeLogCollectService tradeLogCollectService,
                             @Value("${adai.knowledge.trading-engine-path:../../os/trading-engine/knowledge/context}") String knowledgeDir) {
        this.tradingAppService = tradingAppService;
        this.reviewAppService = reviewAppService;
        this.adviceAppService = adviceAppService;
        this.parseAppService = parseAppService;
        this.pluginService = pluginService;
        this.buyPointService = buyPointService;
        this.soldScoreService = soldScoreService;
        this.pushSettingsRepository = pushSettingsRepository;
        this.tradeLogCollectService = tradeLogCollectService;
        // knowledgeDir 形如 .../knowledge/context → 99-inbox 在其上两级（os/trading-engine/99-inbox）
        this.inboxDir = Paths.get(knowledgeDir, "../..", "99-inbox").toAbsolutePath().normalize();
    }

    /**
     * 查询当前持仓。
     * G-2（2026-08-16）：读端点按 20260814 边界表门控——交易闭环端点（含读）只暴露给 trading 插件用户。
     */
    @GetMapping("/positions")
    public ResponseEntity<?> getPositions(
            @RequestHeader(value = "X-User-Id", defaultValue = "default") String userId) {
        ResponseEntity<?> denied = requireTradingPlugin(userId);
        if (denied != null) return denied;
        return ResponseEntity.ok(tradingAppService.getPositions(userId));
    }

    /**
     * 查询投资组合快照（G-2：读端点门控）。
     */
    @GetMapping("/portfolio")
    public ResponseEntity<?> getPortfolio(
            @RequestHeader(value = "X-User-Id", defaultValue = "default") String userId) {
        ResponseEntity<?> denied = requireTradingPlugin(userId);
        if (denied != null) return denied;
        return ResponseEntity.ok(tradingAppService.getPortfolioSnapshot(userId));
    }

    /**
     * 记录一笔交易（买入/卖出）。
     */
    @PostMapping("/trades")
    public ResponseEntity<?> recordTrade(
            @RequestHeader(value = "X-User-Id", defaultValue = "default") String userId,
            @Valid @RequestBody TradeRequest request) {
        ResponseEntity<?> denied = requireTradingPlugin(userId);
        if (denied != null) return denied;
        List<Position> updated = tradingAppService.recordTrade(
                userId, request.symbol(), request.name(),
                request.direction(), request.price(), request.volume(),
                request.entryDate(), request.tradeTime(),
                request.stopLossPrice(), request.buyPoint(),
                request.targetPrice(), request.reason()
        );
        return ResponseEntity.ok(updated);
    }

    /**
     * 查询交易逐笔流水（RFC 20260816：web 交易历史）。
     * GET /api/v1/trading/trades?from=yyyy-MM-dd&to=yyyy-MM-dd（均可选）
     * RFC 20260822：加 ?date=yyyy-MM-dd → 返回 {trades, daily}（当日复盘聚合，纯客观）。
     * G-2：读端点门控。
     */
    @GetMapping("/trades")
    public ResponseEntity<?> getTrades(
            @RequestHeader(value = "X-User-Id", defaultValue = "default") String userId,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String date) {
        ResponseEntity<?> denied = requireTradingPlugin(userId);
        if (denied != null) return denied;
        java.time.LocalDate fromDate = null, toDate = null;
        if (from != null && !from.isBlank()) fromDate = java.time.LocalDate.parse(from);
        if (to != null && !to.isBlank()) toDate = java.time.LocalDate.parse(to);
        // RFC 20260822：指定日期 → 当日复盘聚合（trades + daily 时段分桶）
        if (date != null && !date.isBlank()) {
            java.time.LocalDate d = java.time.LocalDate.parse(date);
            List<TradeRecord> dayTrades = tradingAppService.getTradeHistory(userId, d, d);
            TradingAppService.DailyTradeSummary daily = tradingAppService.getDailyTradeSummary(userId, d);
            return ResponseEntity.ok(Map.of("trades", dayTrades, "daily", daily));
        }
        return ResponseEntity.ok(tradingAppService.getTradeHistory(userId, fromDate, toDate));
    }

    /**
     * 按股票代码查询名称（代码输入带出名称 + 二次确认）。
     * GET /api/v1/trading/lookup?symbol=000725 → {"symbol":"000725","name":"京东方A"}
     */
    @GetMapping("/lookup")
    public ResponseEntity<?> lookupName(
            @RequestHeader(value = "X-User-Id", defaultValue = "default") String userId,
            @RequestParam String symbol) {
        ResponseEntity<?> denied = requireTradingPlugin(userId);
        if (denied != null) return denied;
        String name = tradingAppService.lookupName(symbol);
        return ResponseEntity.ok(Map.of("symbol", symbol, "name", name != null ? name : ""));
    }

    /**
     * 持仓初始化导入（通达信导出 → 持仓快照）。
     * POST /api/v1/trading/positions/import?replace=true
     * body: [{"symbol":"600519","name":"贵州茅台","quantity":100,"avgCost":1400,"stopLossPrice":1350,"buyPoint":"B1"}]
     * name 缺失行情补全；止损/买点可选——导入结果返回 missingStopLoss 列表（R68 提示补设）。
     * <p>
     * {@code replace=true}（2026-08-18 确认批次）= 全量覆盖：以文件为准，
     * 导入后移除文件里不存在的持仓（含 0 股残留），解决 upsert 无删除语义的漂移。
     */
    @PostMapping("/positions/import")
    public ResponseEntity<?> importPositions(
            @RequestHeader(value = "X-User-Id", defaultValue = "default") String userId,
            @RequestParam(defaultValue = "false") boolean replace,
            @RequestBody(required = false) List<TradingAppService.PositionImportItem> items) {
        ResponseEntity<?> denied = requireTradingPlugin(userId);
        if (denied != null) return denied;
        TradingAppService.PositionImportResult result = tradingAppService.importPositions(
                userId, items != null ? items : List.of(), replace);
        return ResponseEntity.ok(Map.of(
                "imported", result.imported(),
                "missingStopLoss", result.missingStopLoss()));
    }

    /**
     * 批量记录交易（web 交易 CSV 批量导入，2026-08-18 补实现——此前前端调用一直 404）。
     * POST /api/v1/trading/trades/batch，body {"trades":[...]}
     * <p>
     * 语义：逐笔走 recordTrade 链路（持仓增减 + 现金 + 手续费 + 逐笔流水）——日常多笔录入；
     * 逐条失败不整批回滚：返回每行的成功/失败原因（带行号人话）。
     */
    @PostMapping("/trades/batch")
    public ResponseEntity<?> batchTrades(
            @RequestHeader(value = "X-User-Id", defaultValue = "default") String userId,
            @RequestBody(required = false) BatchTradeRequest body) {
        ResponseEntity<?> denied = requireTradingPlugin(userId);
        if (denied != null) return denied;
        List<BatchTradeRequest.BatchTradeItem> items = body != null && body.trades() != null
                ? body.trades() : List.of();
        List<Map<String, Object>> results = new java.util.ArrayList<>();
        int success = 0;
        for (int i = 0; i < items.size(); i++) {
            BatchTradeRequest.BatchTradeItem it = items.get(i);
            try {
                tradingAppService.recordTrade(userId, it.symbol(), it.name(), it.direction(),
                        it.price(), it.volume(), it.entryDate(), it.tradeTime(),
                        it.stopLossPrice(), it.buyPoint(),
                        it.targetPrice(), it.reason());
                success++;
            } catch (Exception e) {
                String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                results.add(Map.of("row", i + 1, "message", msg));
            }
        }
        return ResponseEntity.ok(Map.of("success", success, "failures", results));
    }

    /**
     * 历史成交日志导入（第五份文件：通达信「历史成交查询」导出，2026-08-18）。
     * POST /api/v1/trading/trades/import，body {"content":"...（UTF-8 转码后文本）"}
     * <p>
     * 只补逐笔流水（entryDate=成交日 / fee=券商实扣 / orderId 幂等），不重算持仓与现金——
     * 持仓/成本/现金以全量覆盖导入为准；返回导入统计 + 对账提示。
     */
    @PostMapping("/trades/import")
    public ResponseEntity<?> importHistoricalTrades(
            @RequestHeader(value = "X-User-Id", defaultValue = "default") String userId,
            @RequestBody(required = false) Map<String, String> body) {
        ResponseEntity<?> denied = requireTradingPlugin(userId);
        if (denied != null) return denied;
        String content = body != null ? body.get("content") : null;
        TradingAppService.HistoricalTradeImportResult result =
                tradingAppService.importHistoricalTrades(userId, content != null ? content : "");
        return ResponseEntity.ok(Map.of(
                "imported", result.imported(),
                "updated", result.updated(),
                "skipped", result.skipped(),
                "nonTrades", result.nonTrades(),
                "lines", result.lines()));
    }

    /**
     * 更新持仓元信息（web 持仓编辑，2026-08-17 补端点——之前前端/测试在调但后端从未实现，一直 404）。
     * PUT /api/v1/trading/positions/{symbol}，body 只带非空字段（role/stopLossPrice），返回更新后持仓。
     */
    @PutMapping("/positions/{symbol}")
    public ResponseEntity<?> updatePosition(
            @RequestHeader(value = "X-User-Id", defaultValue = "default") String userId,
            @PathVariable String symbol,
            @RequestBody(required = false) Map<String, Object> body) {
        ResponseEntity<?> denied = requireTradingPlugin(userId);
        if (denied != null) return denied;
        String role = body == null ? null : (String) body.get("role");
        BigDecimal stopLoss = null;
        if (body != null && body.get("stopLossPrice") != null) {
            try {
                stopLoss = new BigDecimal(body.get("stopLossPrice").toString());
            } catch (NumberFormatException e) {
                return ResponseEntity.badRequest().body(Map.of("error", "止损位不是有效数字"));
            }
        }
        Position updated = tradingAppService.updatePositionMeta(userId, symbol, role, stopLoss);
        return updated != null
                ? ResponseEntity.ok(updated)
                : ResponseEntity.notFound().build();
    }

    /**
     * 导入文件上传留存（通达信导出，2026-08-16）。
     * POST /api/v1/trading/imports/save（multipart file）
     * → 留存 data/{userId}/trading/imports/{yyyy-MM}/ + GBK 自动转 UTF-8
     * → 返回 {path, content}（content 供前端填充解析导入）
     */
    @PostMapping(value = "/imports/save", consumes = org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> saveImportFile(
            @RequestHeader(value = "X-User-Id", defaultValue = "default") String userId,
            @RequestParam("file") org.springframework.web.multipart.MultipartFile file) {
        ResponseEntity<?> denied = requireTradingPlugin(userId);
        if (denied != null) return denied;
        try {
            TradingAppService.ImportFileResult result = tradingAppService.saveImportFile(
                    userId, file.getOriginalFilename(), file.getBytes());
            return ResponseEntity.ok(Map.of(
                    "path", result.path(),
                    "content", result.content()));
        } catch (Exception e) {
            log.warn("导入文件留存失败 | {}", e.getMessage());
            return ResponseEntity.internalServerError().body(Map.of("error", "文件处理失败: " + e.getMessage()));
        }
    }

    // ── 自选股 / 清仓股 / 资金查询（RFC 20260816 交易数据智能）──

    /** 自选股列表（GET /api/v1/trading/watchlist）。 */
    @GetMapping("/watchlist")
    public ResponseEntity<?> watchlist(
            @RequestHeader(value = "X-User-Id", defaultValue = "default") String userId) {
        ResponseEntity<?> denied = requireTradingPlugin(userId);
        if (denied != null) return denied;
        return ResponseEntity.ok(tradingAppService.watchlistList(userId));
    }

    /** 自选股导入（通达信导出文本，POST /api/v1/trading/watchlist/import）。 */
    @PostMapping("/watchlist/import")
    public ResponseEntity<?> watchlistImport(
            @RequestHeader(value = "X-User-Id", defaultValue = "default") String userId,
            @RequestBody(required = false) Map<String, String> body) {
        ResponseEntity<?> denied = requireTradingPlugin(userId);
        if (denied != null) return denied;
        String content = body == null ? null : body.get("content");
        TradingAppService.WatchlistImportResult r = tradingAppService.watchlistImport(
                userId, content != null ? content : "");
        return ResponseEntity.ok(Map.of("imported", r.imported()));
    }

    /** 删除自选股（DELETE /api/v1/trading/watchlist/{symbol}）。 */
    @DeleteMapping("/watchlist/{symbol}")
    public ResponseEntity<?> watchlistRemove(
            @RequestHeader(value = "X-User-Id", defaultValue = "default") String userId,
            @PathVariable String symbol) {
        ResponseEntity<?> denied = requireTradingPlugin(userId);
        if (denied != null) return denied;
        boolean removed = tradingAppService.watchlistRemove(userId, symbol);
        return removed ? ResponseEntity.ok(Map.of("removed", true))
                : ResponseEntity.notFound().build();
    }

    /** 清仓股列表（GET /api/v1/trading/sold）。 */
    @GetMapping("/sold")
    public ResponseEntity<?> sold(
            @RequestHeader(value = "X-User-Id", defaultValue = "default") String userId) {
        ResponseEntity<?> denied = requireTradingPlugin(userId);
        if (denied != null) return denied;
        return ResponseEntity.ok(tradingAppService.soldList(userId));
    }

    /** 清仓股导入（通达信导出文本，POST /api/v1/trading/sold/import）。 */
    @PostMapping("/sold/import")
    public ResponseEntity<?> soldImport(
            @RequestHeader(value = "X-User-Id", defaultValue = "default") String userId,
            @RequestBody(required = false) Map<String, String> body) {
        ResponseEntity<?> denied = requireTradingPlugin(userId);
        if (denied != null) return denied;
        String content = body == null ? null : body.get("content");
        TradingAppService.SoldImportResult r = tradingAppService.soldImport(
                userId, content != null ? content : "");
        return ResponseEntity.ok(Map.of("imported", r.imported()));
    }

    /** 清仓股心理标注（PUT /api/v1/trading/sold/{symbol}/psychology）。 */
    @PutMapping("/sold/{symbol}/psychology")
    public ResponseEntity<?> soldPsychology(
            @RequestHeader(value = "X-User-Id", defaultValue = "default") String userId,
            @PathVariable String symbol,
            @RequestBody(required = false) Map<String, String> body) {
        ResponseEntity<?> denied = requireTradingPlugin(userId);
        if (denied != null) return denied;
        String psychology = body == null ? null : body.get("psychology");
        boolean ok = tradingAppService.soldUpdatePsychology(
                userId, symbol, psychology != null ? psychology : "");
        return ok ? ResponseEntity.ok(Map.of("updated", true))
                : ResponseEntity.notFound().build();
    }

    /** 清仓复盘三维打分（D3，GET /api/v1/trading/sold/score：买点/执行/选股，分数是参考不是指令）。 */
    @GetMapping("/sold/score")
    public ResponseEntity<?> soldScore(
            @RequestHeader(value = "X-User-Id", defaultValue = "default") String userId) {
        ResponseEntity<?> denied = requireTradingPlugin(userId);
        if (denied != null) return denied;
        List<SoldTrade> trades = tradingAppService.soldList(userId);
        return ResponseEntity.ok(soldScoreService.score(trades));
    }

    /** 银证转账（转入/转出，净投入跟踪，POST /api/v1/trading/transfer）。 */
    @PostMapping("/transfer")
    public ResponseEntity<?> recordTransfer(
            @RequestHeader(value = "X-User-Id", defaultValue = "default") String userId,
            @RequestBody(required = false) Map<String, String> body) {
        ResponseEntity<?> denied = requireTradingPlugin(userId);
        if (denied != null) return denied;
        if (body == null) return ResponseEntity.badRequest().body(Map.of("error", "请求体为空"));
        String type = body.get("type");
        if (!"IN".equals(type) && !"OUT".equals(type)) {
            return ResponseEntity.badRequest().body(Map.of("error", "type 必须为 IN（转入）或 OUT（转出）"));
        }
        BigDecimal amount;
        try {
            amount = new BigDecimal(body.getOrDefault("amount", "0"));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", "金额不是有效数字"));
        }
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            return ResponseEntity.badRequest().body(Map.of("error", "金额必须大于 0"));
        }
        java.time.LocalDate date = null;
        if (body.get("date") != null && !body.get("date").isBlank()) {
            try {
                date = java.time.LocalDate.parse(body.get("date"));
            } catch (Exception e) {
                return ResponseEntity.badRequest().body(Map.of("error", "日期格式应为 yyyy-MM-dd"));
            }
        }
        TransferRecord record = tradingAppService.recordTransfer(
                userId, type, amount, date, body.get("note"));
        return ResponseEntity.ok(Map.of(
                "id", record.id(),
                "type", record.type(),
                "amount", record.amount(),
                "date", record.date().toString()));
    }

    /** 转账流水（GET /api/v1/trading/transfers）。 */
    @GetMapping("/transfers")
    public ResponseEntity<?> transferList(
            @RequestHeader(value = "X-User-Id", defaultValue = "default") String userId) {
        ResponseEntity<?> denied = requireTradingPlugin(userId);
        if (denied != null) return denied;
        return ResponseEntity.ok(tradingAppService.transferList(userId));
    }

    /** 自选股买点信号（C2，GET /api/v1/trading/buy-points：B1/B2 命中列表）。 */
    @GetMapping("/buy-points")
    public ResponseEntity<?> buyPoints(
            @RequestHeader(value = "X-User-Id", defaultValue = "default") String userId) {
        ResponseEntity<?> denied = requireTradingPlugin(userId);
        if (denied != null) return denied;
        List<WatchlistItem> watchlist = tradingAppService.watchlistList(userId);
        return ResponseEntity.ok(buyPointService.scanWatchlist(watchlist));
    }

    /** 账户总体快照（资产/可用/可取/参考市值/盈亏/当日盈亏，GET /api/v1/trading/account）。 */
    @GetMapping("/account")
    public ResponseEntity<?> accountSnapshot(
            @RequestHeader(value = "X-User-Id", defaultValue = "default") String userId) {
        ResponseEntity<?> denied = requireTradingPlugin(userId);
        if (denied != null) return denied;
        return ResponseEntity.ok(tradingAppService.accountSnapshot(userId));
    }

    /**
     * 设置本金（累计净投入，2026-08-18）。
     * PUT /api/v1/trading/principal，body {"amount":150000}
     * 只写 principal 字段（总盈亏 = 资产 − 本金），不动现金/资产/市值——本金初始化用。
     */
    @PutMapping("/principal")
    public ResponseEntity<?> setPrincipal(
            @RequestHeader(value = "X-User-Id", defaultValue = "default") String userId,
            @RequestBody(required = false) Map<String, String> body) {
        ResponseEntity<?> denied = requireTradingPlugin(userId);
        if (denied != null) return denied;
        BigDecimal amount = body != null && body.get("amount") != null
                ? new BigDecimal(body.get("amount").trim()) : null;
        return ResponseEntity.ok(tradingAppService.setPrincipal(userId, amount));
    }

    /** 推送开关（RFC 20260817）：读取用户推送类型开关（GET /api/v1/trading/push-settings）。 */
    @GetMapping("/push-settings")
    public ResponseEntity<?> pushSettings(
            @RequestHeader(value = "X-User-Id", defaultValue = "default") String userId) {
        ResponseEntity<?> denied = requireTradingPlugin(userId);
        if (denied != null) return denied;
        return ResponseEntity.ok(pushSettingsRepository.findByUser(userId).enabled());
    }

    /** 推送开关（RFC 20260817）：设置某类型开/关（PUT /api/v1/trading/push-settings/{type}）。 */
    @PutMapping("/push-settings/{type}")
    public ResponseEntity<?> updatePushSettings(
            @RequestHeader(value = "X-User-Id", defaultValue = "default") String userId,
            @PathVariable String type,
            @RequestBody Map<String, Boolean> body) {
        ResponseEntity<?> denied = requireTradingPlugin(userId);
        if (denied != null) return denied;
        if (!PushSettings.ALL_TYPES.contains(type)) {
            return ResponseEntity.badRequest().body(Map.of("error", "未知推送类型: " + type));
        }
        Boolean on = body.get("enabled");
        if (on == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "缺少 enabled 字段"));
        }
        PushSettings settings = pushSettingsRepository.findByUser(userId).with(type, on);
        pushSettingsRepository.save(userId, settings);
        return ResponseEntity.ok(settings.enabled());
    }

    /** 交易日志归集（RFC 20260817）：当日候选（GET /api/v1/trading/trade-log）。 */
    @GetMapping("/trade-log")
    public ResponseEntity<?> tradeLogCandidates(
            @RequestHeader(value = "X-User-Id", defaultValue = "default") String userId) {
        ResponseEntity<?> denied = requireTradingPlugin(userId);
        if (denied != null) return denied;
        return ResponseEntity.ok(tradeLogCollectService.todayCandidates(userId));
    }

    /** 交易日志归集（RFC 20260817）：确认落库（POST /api/v1/trading/trade-log/confirm）——
     *  当日候选逐笔走 recordTrade（持仓/现金/流水），确认后清空候选。 */
    @PostMapping("/trade-log/confirm")
    public ResponseEntity<?> confirmTradeLog(
            @RequestHeader(value = "X-User-Id", defaultValue = "default") String userId) {
        ResponseEntity<?> denied = requireTradingPlugin(userId);
        if (denied != null) return denied;
        int done = tradeLogCollectService.confirm(userId);
        return ResponseEntity.ok(Map.of("confirmed", done));
    }

    /** 资金股份查询导入（更新现金 + 精确成本，POST /api/v1/trading/imports/cash）。 */
    @PostMapping("/imports/cash")
    public ResponseEntity<?> importCash(
            @RequestHeader(value = "X-User-Id", defaultValue = "default") String userId,
            @RequestBody(required = false) Map<String, String> body) {
        ResponseEntity<?> denied = requireTradingPlugin(userId);
        if (denied != null) return denied;
        String content = body == null ? null : body.get("content");
        TradingAppService.CashImportResult r = tradingAppService.importCashQuery(
                userId, content != null ? content : "");
        return ResponseEntity.ok(Map.of(
                "cash", r.cash(),
                "assets", r.assets(),
                "updatedCost", r.updatedCost()));
    }

    /**
     * 解析一句话交易（RFC 20260815 通道 A）：把自然语言「买了 1000 股京东方 @5.2」
     * 结构化为 symbol/name/direction/price/volume，供前端确认卡回显。
     * LLM 结构化优先，失败降级正则兜底；仍无法解析 → matched=false（前端转精确表单）。
     * 只解析不落库——写入仍走 {@code POST /trades}（正确性由确认步拦截）。
     */
    @PostMapping("/trades/parse")
    public ResponseEntity<?> parseTrade(
            @RequestHeader(value = "X-User-Id", defaultValue = "default") String userId,
            @RequestBody(required = false) Map<String, String> body) {
        ResponseEntity<?> denied = requireTradingPlugin(userId);
        if (denied != null) return denied;
        String text = body == null ? null : body.get("text");
        return ResponseEntity.ok(parseAppService.parse(userId, text));
    }

    /**
     * 生成持仓建议（交易模块核心定位：建议引擎）。
     * <p>
     * 读用户持仓 + 实时行情 + 只读 {@code os/trading-engine/knowledge/context/rules.md} 与 {@code strategy.md}，
     * 将止损规则（R66-R80）与仓位规则（R81-R95）作为决策硬约束注入 LLM，结构化生成逐票建议
     * （suggestion / reason / rules 必须引用规则号）。建议是输出不是指令，本端点不做任何执行动作。
     * <p>
     * 兜底：LLM 失败时降级返回基础数据（symbol / name / position_percent，无建议字段），不抛错。
     */
    @PostMapping("/advice")
    public ResponseEntity<?> generateAdvice(
            @RequestHeader(value = "X-User-Id", defaultValue = "default") String userId) {
        ResponseEntity<?> denied = requireTradingPlugin(userId);
        if (denied != null) return denied;
        return ResponseEntity.ok(adviceAppService.generateAdvice(userId));
    }

    /** REVIEW P2-B1：trading 写入口门控（与 promote 403 同口径）——无 trading 插件用户不得写入持仓/复盘残留。 */
    private ResponseEntity<?> requireTradingPlugin(String userId) {
        if (!pluginService.hasPlugin(userId, PluginRegistry.PLUGIN_TRADING)) {
            return ResponseEntity.status(403).body(Map.of("error", "trading 插件未启用，无法使用交易功能"));
        }
        return null;
    }

    // ── 复盘 API ──

    /**
     * 生成交易复盘笔记。
     * <p>
     * AI 基于当日交易记录 + 持仓变化 + 近期记录生成复盘。
     * 输出写入 {@code data/trading/reviews/YYYY-MM-DD_review.md}。
     */
    @PostMapping("/review")
    public ResponseEntity<?> generateReview(
            @RequestHeader(value = "X-User-Id", defaultValue = "default") String userId,
            @RequestParam(defaultValue = "#{T(java.time.LocalDate).now()}") LocalDate date) {
        ResponseEntity<?> denied = requireTradingPlugin(userId);
        if (denied != null) return denied;
        String content = reviewAppService.generateReview(userId, date);
        return ResponseEntity.ok(new ReviewResponse(date.toString(), content));
    }

    /**
     * 获取指定日期的复盘笔记（G-2：读端点门控）。
     */
    @GetMapping("/review")
    public ResponseEntity<?> getReview(
            @RequestHeader(value = "X-User-Id", defaultValue = "default") String userId,
            @RequestParam(defaultValue = "#{T(java.time.LocalDate).now()}") LocalDate date) {
        ResponseEntity<?> denied = requireTradingPlugin(userId);
        if (denied != null) return denied;
        String content = reviewAppService.getReview(userId, date);
        if (content == null || content.isBlank()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(new ReviewResponse(date.toString(), content));
    }

    /**
     * 列出所有复盘日期（G-2：读端点门控）。
     */
    @GetMapping("/reviews")
    public ResponseEntity<?> listReviews(
            @RequestHeader(value = "X-User-Id", defaultValue = "default") String userId) {
        ResponseEntity<?> denied = requireTradingPlugin(userId);
        if (denied != null) return denied;
        return ResponseEntity.ok(reviewAppService.listReviews(userId));
    }

    /**
     * 检测指定日期是否有交易活动。
     */
    @GetMapping("/has-activity")
    public ResponseEntity<ActivityCheckResponse> hasTradingActivity(
            @RequestHeader(value = "X-User-Id", defaultValue = "default") String userId,
            @RequestParam(defaultValue = "#{T(java.time.LocalDate).now()}") LocalDate date) {
        boolean hasActivity = reviewAppService.hasTradingActivity(userId, date);
        return ResponseEntity.ok(new ActivityCheckResponse(date.toString(), hasActivity));
    }

    // ── 知识反哺 API ──

    /**
     * 将复盘笔记中的内容提升为入库候选。
     * <p>
     * 写入 {@code os/trading-engine/99-inbox/}，供用户在 trading-engine 工作焦点下审核。
     * 尊重 os/ 目录独立性：adai-core 只写入 99-inbox/，不做自动入库。
     */
    @PostMapping("/reviews/{date}/promote")
    public ResponseEntity<?> promoteToInbox(
            @RequestHeader(value = "X-User-Id", defaultValue = "default") String userId,
            @PathVariable LocalDate date,
            @RequestBody PromoteRequest request) {
        // RFC 20260814：promote 写入 os/trading-engine/99-inbox（共享知识库）→ 仅启用 trading 插件用户可用
        if (!pluginService.hasPlugin(userId, PluginRegistry.PLUGIN_TRADING)) {
            return ResponseEntity.status(403).body(Map.of("error", "trading 插件未启用，无法反哺知识"));
        }
        String reviewContent = reviewAppService.getReview(userId, date);
        if (reviewContent == null || reviewContent.isBlank()) {
            return ResponseEntity.notFound().build();
        }

        try {
            // 构建入库候选内容
            String content = buildPromoteContent(date, request, reviewContent);
            // #203：候选文件尾保证换行（markdown 文件约定 EOF newline）
            if (!content.endsWith("\n")) content += "\n";
            // 写入 os/trading-engine/99-inbox/（P1-1：配置驱动，不再硬编码相对路径）
            Path inboxPath = inboxDir;
            Files.createDirectories(inboxPath);
            // #211：文件名符合 trading-engine 全流水线约定 `YYYY-MM-DD_主题.md`
            // （原硬编码 `review-{date}.md` 不符，已入库的候选文件一并按此改名）
            String fileName = date.toString() + "_交易复盘.md";
            // P1-3（2026-08-17 走查）：原子写——tmp+move 防中途崩溃截断候选文件
            Path target = inboxPath.resolve(fileName);
            Path tmp = inboxPath.resolve(fileName + ".tmp");
            Files.writeString(tmp, content, StandardCharsets.UTF_8);
            Files.move(tmp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);

            log.info("复盘内容已提升为入库候选 | date={} | file={}", date, fileName);
            // #178：提示入库候选不会自动融入 AI context——需在 trading-engine 工作流审核融合后重建 knowledge/context
            String message = "已写入入库候选。该内容不会自动进入 AI 上下文：请在交易知识库工作流（os/trading-engine）审核后归入正式目录，并在收敛时重建 knowledge/context。";
            return ResponseEntity.ok(new PromoteResponse("ok", inboxPath.resolve(fileName).toString(), message));
        } catch (Exception e) {
            log.error("入库候选写入失败 | date={} | {}", date, e.getMessage());
            throw new StorageException("入库候选写入失败: " + e.getMessage(), e);
        }
    }

    // ── 内部方法 ──

    private String buildPromoteContent(LocalDate date, PromoteRequest request, String reviewContent) {
        StringBuilder sb = new StringBuilder();
        sb.append("# 入库候选：").append(date).append(" 交易复盘\n\n");
        sb.append("> 此文件由 adai-core 自动生成，待人工审核后归入正式目录。\n");
        sb.append("> 生成时间：").append(java.time.LocalDateTime.now()).append("\n\n");
        if (request.note() != null && !request.note().isBlank()) {
            sb.append("**用户备注：** ").append(request.note()).append("\n\n");
        }
        if (request.sections() != null && !request.sections().isEmpty()) {
            sb.append("## 入选章节\n\n");
            for (String section : request.sections()) {
                sb.append("- ").append(section).append("\n");
            }
            sb.append("\n");
        }
        sb.append("## 完整复盘内容\n\n");
        // #184：promote 内容脱敏——复盘含真实持仓（股数/市值/成本/现价/现金），
        // 入库候选进 git 追踪的 os/ 目录，必须替换为占位符。
        // 知识价值在 R/E 规则引用与仓位结构讨论，不在具体持仓数字。
        // 标的名保留（公开信息 + 规则引用需要标的语境）；大盘指数等公开行情不误伤。
        sb.append(sanitizeReviewContent(reviewContent));
        return sb.toString();
    }

    /**
     * #184：复盘内容脱敏——替换真实持仓数字为占位符。
     * <p>
     * 关键词引导的正则（持有/市值/成本/现价/现金余额），只命中持仓数字，
     * 不误伤大盘指数等公开行情（不含这些关键词）。标的名保留（公开信息）。
     */
    static String sanitizeReviewContent(String content) {
        if (content == null || content.isBlank()) return content;
        String s = content;
        // W-P3-20（2026-08-17）：数字正则兼容千分位逗号（1,400 此前漏脱敏）
        // 持仓数量：持有100股 → 持有N股
        s = s.replaceAll("持有\\s*[\\d,]+(?:\\.\\d+)?\\s*股", "持有N股");
        // 市值：市值14万 → 市值（已脱敏）
        s = s.replaceAll("市值\\s*[\\d,.]+\\s*(?:万|千|亿)?", "市值（已脱敏）");
        // 现金余额：现金余额为零 → 现金余额（已脱敏）
        s = s.replaceAll("现金余额[^，。；\\n]*", "现金余额（已脱敏）");
        // 成本/现价/止损价：成本1400现价1400 → 成本（已脱敏）现价（已脱敏）
        s = s.replaceAll("(成本|现价|止损位|止损价)\\s*[\\d,.]+", "$1（已脱敏）");
        return s;
    }

    // ── DTO ──

    /**
     * TradeRequest — 记录交易请求。
     * <p>
     * RFC 20260815：name 改可空（web 标注"名称（可选）"），缺失时由 TradingAppService 以 symbol 兜底。
     * <p>
     * RFC 20260816：BUY 曾必填止损位/买点——2026-08-18 确认批次放开为可选（app 简化：
     * 手机端只做日常买卖记录，止损位/买点归 web 端设置）；SELL 时两者本就可空。
     */
    public record TradeRequest(
            @NotBlank String symbol,
            @Size(max = 32) String name,
            TradeDirection direction,
            @Positive BigDecimal price,
            @Positive int volume,
            LocalDate entryDate,
            LocalTime tradeTime,
            BigDecimal stopLossPrice,
            String buyPoint,
            BigDecimal targetPrice,
            String reason
    ) {}

    /** 批量记录交易请求体（web 交易 CSV 批量导入）。 */
    public record BatchTradeRequest(List<BatchTradeItem> trades) {
        public record BatchTradeItem(
                String symbol,
                String name,
                TradeDirection direction,
                BigDecimal price,
                int volume,
                LocalDate entryDate,
                LocalTime tradeTime,
                BigDecimal stopLossPrice,
                String buyPoint,
                BigDecimal targetPrice,
                String reason
        ) {}
    }

    public record ReviewResponse(String date, String content) {}

    public record ActivityCheckResponse(String date, boolean hasActivity) {}

    public record PromoteRequest(String note, List<String> sections) {}

    public record PromoteResponse(String status, String path, String message) {}
}
