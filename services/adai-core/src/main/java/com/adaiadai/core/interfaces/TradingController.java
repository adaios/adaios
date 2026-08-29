package com.adaiadai.core.interfaces;

import com.adaiadai.core.application.TradingAdviceAppService;
import com.adaiadai.core.application.TradingParseAppService;
import com.adaiadai.core.application.TradingAppService;
import com.adaiadai.core.application.WatchlistBuyPointService;
import com.adaiadai.core.application.SoldScoreService;
import com.adaiadai.core.application.TradingReviewAppService;
import com.adaiadai.core.application.TradingLotService;
import com.adaiadai.core.domain.trading.Position;
import com.adaiadai.core.domain.trading.SoldTrade;
import com.adaiadai.core.domain.trading.TradingRuleSettings;
import com.adaiadai.core.domain.trading.TradeDirection;
import com.adaiadai.core.domain.trading.TradeRecord;
import com.adaiadai.core.domain.trading.WatchlistItem;
import com.adaiadai.core.domain.trading.TransferRecord;
import com.adaiadai.core.infrastructure.storage.StorageException;
import com.adaiadai.core.kernel.plugin.PluginRegistry;
import com.adaiadai.core.kernel.plugin.PluginService;
import com.adaiadai.core.domain.trading.PushSettings;
import com.adaiadai.core.infrastructure.storage.MarketPushRepository;
import com.adaiadai.core.infrastructure.storage.PushSettingsRepository;
import com.adaiadai.core.infrastructure.storage.TradingRuleSettingsRepository;
import com.adaiadai.core.application.TradeLogCollectService;
import com.adaiadai.core.application.TradingScreenshotAppService;
import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
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
    private final TradingRuleSettingsRepository ruleSettingsRepository;
    /** RFC 20260817：交易日志自动归集（当日候选/确认落库）。 */
    private final TradeLogCollectService tradeLogCollectService;
    /** 2026-08-26 截图入账：券商截图 → VLM → 当日候选（不建记录）。 */
    private final TradingScreenshotAppService screenshotAppService;
    /** B10-1（2026-08-23，P1-推送2）：推送删除持久化（app 左滑删/web 忽略按钮）。 */
    private final MarketPushRepository marketPushRepository;
    /** RFC 20260825：批次推导与行为标注（批次视图 / 导入同步模式）。 */
    private final TradingLotService tradingLotService;
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
                             TradingRuleSettingsRepository ruleSettingsRepository,
                             TradeLogCollectService tradeLogCollectService,
                             TradingScreenshotAppService screenshotAppService,
                             MarketPushRepository marketPushRepository,
                             TradingLotService tradingLotService,
                             @Value("${adai.knowledge.trading-engine-path:../../os/trading-engine/knowledge/context}") String knowledgeDir) {
        this.tradingAppService = tradingAppService;
        this.reviewAppService = reviewAppService;
        this.adviceAppService = adviceAppService;
        this.parseAppService = parseAppService;
        this.pluginService = pluginService;
        this.buyPointService = buyPointService;
        this.soldScoreService = soldScoreService;
        this.pushSettingsRepository = pushSettingsRepository;
        this.ruleSettingsRepository = ruleSettingsRepository;
        this.tradeLogCollectService = tradeLogCollectService;
        this.screenshotAppService = screenshotAppService;
        this.marketPushRepository = marketPushRepository;
        this.tradingLotService = tradingLotService;
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
     * 一键按流水重建持仓（2026-08-25 用户场景：导入历史成交后持仓快照过期——
     * 中电电机已清仓但快照残留被当初始底仓）。
     * POST /api/v1/trading/sync
     * <p>
     * 以流水为准（结合 INIT 底仓兜底）重建 positions：流水已全部卖出的 symbol 从持仓移除，
     * 开放批次汇总为持仓；返回同步报告（removed 已清仓残留 / keptInitial 保留底仓）。
     * 与「每日导当天成交 sync 模式」互补：sync 处理增量，本端点一次性对齐存量账本。
     */
    @PostMapping("/sync")
    public ResponseEntity<?> syncPositions(
            @RequestHeader(value = "X-User-Id", defaultValue = "default") String userId) {
        ResponseEntity<?> denied = requireTradingPlugin(userId);
        if (denied != null) return denied;
        TradingAppService.SyncResult r = tradingAppService.syncPositionsFromFlow(userId);
        return ResponseEntity.ok(Map.of(
                "positionCount", r.positionCount(),
                "removed", r.removed(),
                "keptInitial", r.keptInitial()));
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
        // C3（2026-08-23，隔离审查 P2-9）：空 trades 不再静默 200 成功 0——显式 400 人话
        if (items.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "没有可导入的交易（trades 不能为空）"));
        }
        List<Map<String, Object>> results = new java.util.ArrayList<>();
        int success = 0;
        for (int i = 0; i < items.size(); i++) {
            BatchTradeRequest.BatchTradeItem it = items.get(i);
            try {
                // P1-2（2026-08-23 走查修复）：batch 逐字段校验——此前无任何校验，
                // symbol=null 落盘污染 positions.md、price=null NPE 500
                String rowError = validateBatchItem(it);
                if (rowError != null) {
                    throw new IllegalArgumentException(rowError);
                }
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

    /** P1-2（2026-08-23）：batch 单行字段校验——返回人话错误；null 表示通过。
     *  C4（2026-08-23，隔离审查 P2-10）：name 超长（>32）校验——与单笔 TradeRequest 同口径。 */
    private static String validateBatchItem(BatchTradeRequest.BatchTradeItem it) {
        if (it.symbol() == null || it.symbol().isBlank()) {
            return "代码不能为空";
        }
        if (it.direction() == null) {
            return "方向不能为空（BUY/SELL）";
        }
        if (it.price() == null || it.price().signum() <= 0) {
            return "价格必须大于 0";
        }
        if (it.volume() <= 0) {
            return "数量必须大于 0";
        }
        if (it.name() != null && it.name().length() > 32) {
            return "名称不能超过 32 字符";
        }
        return null;
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
        // RFC 20260825：响应扩展 syncMode（sync 同步持仓 | append 只补流水）+ 每日操作总结（客观聚合 + 行为标注）
        java.util.Map<String, Object> resp = new java.util.LinkedHashMap<>();
        resp.put("imported", result.imported());
        resp.put("updated", result.updated());
        resp.put("skipped", result.skipped());
        resp.put("nonTrades", result.nonTrades());
        resp.put("lines", result.lines());
        resp.put("syncMode", result.syncMode() != null ? result.syncMode() : "append");
        if (result.summary() != null) resp.put("summary", result.summary());
        return ResponseEntity.ok(resp);
    }

    /**
     * 批次视图（RFC 20260825 逐笔批次跟踪）：每笔买入独立跟踪/止损/盈亏。
     * GET /api/v1/trading/lots?state=open|closed|all&symbol=
     * <p>
     * 返回 {"lots": [...], "reconcile": [...]}——批次（注入现价，含已关回合 realizedPnl）
     * + 流水重放 vs 持仓快照对账提示（防漏导静默错）。
     */
    @GetMapping("/lots")
    public ResponseEntity<?> lots(
            @RequestHeader(value = "X-User-Id", defaultValue = "default") String userId,
            @RequestParam(required = false) String state,
            @RequestParam(required = false) String symbol) {
        ResponseEntity<?> denied = requireTradingPlugin(userId);
        if (denied != null) return denied;
        List<TradingLotService.TradingLotView> lots = tradingLotService.lots(userId, state);
        if (symbol != null && !symbol.isBlank()) {
            lots = lots.stream().filter(l -> symbol.equals(l.symbol())).toList();
        }
        return ResponseEntity.ok(Map.of(
                "lots", lots,
                "reconcile", tradingLotService.reconcile(userId)));
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
        return ResponseEntity.ok(soldScoreService.score(trades, userId));
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
        return ResponseEntity.ok(buyPointService.scanWatchlist(watchlist, userId));
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

    /** 交易规则参数（第三阶段，GET /api/v1/trading/rules：用户自己的交易系统参数）。 */
    @GetMapping("/rules")
    public ResponseEntity<?> tradingRules(
            @RequestHeader(value = "X-User-Id", defaultValue = "default") String userId) {
        ResponseEntity<?> denied = requireTradingPlugin(userId);
        if (denied != null) return denied;
        TradingRuleSettings s = ruleSettingsRepository.findByUser(userId);
        java.util.LinkedHashMap<String, Object> params = new java.util.LinkedHashMap<>();
        params.put("positionLimitPercent", s.positionLimitPercent().toPlainString());
        params.put("defaultStopLossRatio", s.defaultStopLossRatio().toPlainString());
        params.put("givebackPeakPct", s.givebackPeakPct().toPlainString());
        params.put("givebackRatioPct", s.givebackRatioPct().toPlainString());
        params.put("shortOverdueDays", String.valueOf(s.shortOverdueDays()));
        params.put("soldStopLossPct", String.valueOf(s.soldStopLossPct()));
        params.put("soldShortHoldDays", String.valueOf(s.soldShortHoldDays()));
        params.put("buyPullbackPct", String.valueOf(s.buyPullbackPct()));
        params.put("buyShrinkRatio", String.valueOf(s.buyShrinkRatio()));
        params.put("buyKdjLow", String.valueOf(s.buyKdjLow()));
        params.put("buyVolumeSurge", String.valueOf(s.buyVolumeSurge()));
        params.put("buyPriorHighDays", String.valueOf(s.buyPriorHighDays()));
        params.put("scoreBuyWeight", String.valueOf(s.scoreBuyWeight()));
        params.put("scoreExecWeight", String.valueOf(s.scoreExecWeight()));
        params.put("constraintRuleMin", String.valueOf(s.constraintRuleMin()));
        params.put("constraintRuleMax", String.valueOf(s.constraintRuleMax()));
        return ResponseEntity.ok(Map.of(
                "exists", ruleSettingsRepository.exists(userId),
                "params", params));
    }

    /** 交易规则参数更新（第三阶段，PUT /api/v1/trading/rules：覆盖非空字段，落 rules.yaml）。 */
    @PutMapping("/rules")
    public ResponseEntity<?> updateTradingRules(
            @RequestHeader(value = "X-User-Id", defaultValue = "default") String userId,
            @RequestBody Map<String, Object> body) {
        ResponseEntity<?> denied = requireTradingPlugin(userId);
        if (denied != null) return denied;
        Object paramsObj = body.getOrDefault("params", Map.of());
        if (!(paramsObj instanceof Map<?, ?> paramsRaw)) {
            // P0-1（2026-08-30 审查）：params 非 Map → 400（原 ClassCastException 500）
            return ResponseEntity.badRequest().body(Map.of("error", "params 必须是对象（如 {\"params\":{...}}）"));
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> params = (Map<String, Object>) paramsRaw;
        if (params.isEmpty()) {
            // P0-1（2026-08-30 审查）：空提交 → 400（原静默 200 啥也没改，用户以为生效）
            return ResponseEntity.badRequest().body(Map.of("error", "没有要更新的参数——想恢复默认请传完整参数或点「恢复默认」"));
        }
        // P0-1（2026-08-30 审查）：NaN/Infinity 拒绝（原 1e400 → 500 / NaN 穿透 fail-closed）
        for (Map.Entry<String, Object> e : params.entrySet()) {
            Number n = num(e.getValue());
            if (n != null && (Double.isNaN(n.doubleValue()) || Double.isInfinite(n.doubleValue()))) {
                return ResponseEntity.badRequest().body(Map.of("error", "参数 " + e.getKey() + " 不是有效数字"));
            }
        }
        TradingRuleSettings current = ruleSettingsRepository.findByUser(userId);
        // 逐字段覆盖（缺省保持原值）；值经 TradingRuleSettings 构造器 fail-closed 校验（非法回落默认）
        TradingRuleSettings updated = new TradingRuleSettings(
                num(params.get("positionLimitPercent")) != null
                        ? new java.math.BigDecimal(String.valueOf(params.get("positionLimitPercent")))
                        : current.positionLimitPercent(),
                num(params.get("defaultStopLossRatio")) != null
                        ? new java.math.BigDecimal(String.valueOf(params.get("defaultStopLossRatio")))
                        : current.defaultStopLossRatio(),
                num(params.get("givebackPeakPct")) != null
                        ? new java.math.BigDecimal(String.valueOf(params.get("givebackPeakPct")))
                        : current.givebackPeakPct(),
                num(params.get("givebackRatioPct")) != null
                        ? new java.math.BigDecimal(String.valueOf(params.get("givebackRatioPct")))
                        : current.givebackRatioPct(),
                num(params.get("shortOverdueDays")) != null
                        ? num(params.get("shortOverdueDays")).intValue() : current.shortOverdueDays(),
                num(params.get("soldStopLossPct")) != null
                        ? num(params.get("soldStopLossPct")).doubleValue() : current.soldStopLossPct(),
                num(params.get("soldShortHoldDays")) != null
                        ? num(params.get("soldShortHoldDays")).intValue() : current.soldShortHoldDays(),
                num(params.get("buyPullbackPct")) != null
                        ? num(params.get("buyPullbackPct")).doubleValue() : current.buyPullbackPct(),
                num(params.get("buyShrinkRatio")) != null
                        ? num(params.get("buyShrinkRatio")).doubleValue() : current.buyShrinkRatio(),
                num(params.get("buyKdjLow")) != null
                        ? num(params.get("buyKdjLow")).doubleValue() : current.buyKdjLow(),
                num(params.get("buyVolumeSurge")) != null
                        ? num(params.get("buyVolumeSurge")).doubleValue() : current.buyVolumeSurge(),
                num(params.get("buyPriorHighDays")) != null
                        ? num(params.get("buyPriorHighDays")).intValue() : current.buyPriorHighDays(),
                num(params.get("scoreBuyWeight")) != null
                        ? num(params.get("scoreBuyWeight")).doubleValue() : current.scoreBuyWeight(),
                num(params.get("scoreExecWeight")) != null
                        ? num(params.get("scoreExecWeight")).doubleValue() : current.scoreExecWeight(),
                num(params.get("constraintRuleMin")) != null
                        ? num(params.get("constraintRuleMin")).intValue() : current.constraintRuleMin(),
                num(params.get("constraintRuleMax")) != null
                        ? num(params.get("constraintRuleMax")).intValue() : current.constraintRuleMax());
        // P0-1（2026-08-30 审查）：写盘失败抛 StorageException → GlobalExceptionHandler 500（不再静默 updated=true）
        ruleSettingsRepository.save(userId, updated);
        return ResponseEntity.ok(Map.of("updated", true));
    }

    /** 数值提取（规则参数 body 可为 Number）。 */
    /** 数值提取（P3-2：GET 返回 String，PUT 也应接受字符串数字——第三方回传 GET 结构不再静默忽略）。 */
    private static Number num(Object o) {
        if (o instanceof Number n) return n;
        if (o instanceof String str) {
            try {
                String t = str.trim();
                return t.contains(".") ? Double.parseDouble(t) : Long.parseLong(t);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    /** 交易日志归集（RFC 20260817）：当日候选（GET /api/v1/trading/trade-log）。 */
    @GetMapping("/trade-log")
    public ResponseEntity<?> tradeLogCandidates(
            @RequestHeader(value = "X-User-Id", defaultValue = "default") String userId) {
        ResponseEntity<?> denied = requireTradingPlugin(userId);
        if (denied != null) return denied;
        return ResponseEntity.ok(tradeLogCollectService.todayCandidates(userId));
    }

    /**
     * 截图入账（2026-08-26，交易闭环第一环）：券商「当日委托/历史成交」截图（1-3 张 multipart）
     * → VLM 识别 → 归集为当日候选。POST /api/v1/trading/screenshots。
     * <p>
     * 与首页发图（POST /records/media）的关键差异：**不建记录、不落原图、不沉淀记忆**——
     * 截图入账是交易动作，候选确认落库后即权威数据，不污染 Feed/时间线。
     * 响应：{total, processed, candidates:[{symbol,name,direction,price,volume,source,complete}], errors:[...]}
     */
    @PostMapping(value = "/screenshots", consumes = org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> collectScreenshots(
            @RequestHeader(value = "X-User-Id", defaultValue = "default") String userId,
            @RequestParam("files") List<org.springframework.web.multipart.MultipartFile> files) {
        ResponseEntity<?> denied = requireTradingPlugin(userId);
        if (denied != null) return denied;
        try {
            if (files == null || files.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "请选择截图"));
            }
            List<byte[]> images = new java.util.ArrayList<>();
            List<String> contentTypes = new java.util.ArrayList<>();
            for (org.springframework.web.multipart.MultipartFile f : files) {
                images.add(f.getBytes());
                contentTypes.add(f.getContentType() != null ? f.getContentType() : "image/png");
            }
            TradingScreenshotAppService.ScreenshotCollectResult r = screenshotAppService.collect(userId, images, contentTypes);
            return ResponseEntity.ok(Map.of(
                    "total", r.total(),
                    "processed", r.processed(),
                    "candidates", r.candidates(),
                    "errors", r.errors()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.warn("截图入账失败 | userId={} | {}", userId, e.getMessage());
            return ResponseEntity.internalServerError().body(Map.of("error", "截图处理失败: " + e.getMessage()));
        }
    }

    /** 交易日志归集（B6-5，2026-08-23，P1-交易18）：丢弃一条保留候选（失败/不完整钉子户）。
     *  DELETE /api/v1/trading/trade-log?symbol=&direction= → {"discarded":true}；无此候选 404。 */
    @DeleteMapping("/trade-log")
    public ResponseEntity<?> discardTradeLogCandidate(
            @RequestHeader(value = "X-User-Id", defaultValue = "default") String userId,
            @RequestParam(required = false) String symbol,
            @RequestParam(required = false) String direction) {
        ResponseEntity<?> denied = requireTradingPlugin(userId);
        if (denied != null) return denied;
        boolean removed = tradeLogCollectService.discard(userId, symbol, direction);
        return removed ? ResponseEntity.ok(Map.of("discarded", true))
                : ResponseEntity.notFound().build();
    }

    /** 交易日志归集（RFC 20260817）：确认落库（POST /api/v1/trading/trade-log/confirm）——
     *  当日候选逐笔走 recordTrade；P0-1（2026-08-23）：失败/不完整候选保留不丢，返回明细。
     *  2026-08-27 二修：截图候选缺成交日期 → 禁止落库（skipped + failures 提示），补日期后可再确认。 */
    @PostMapping("/trade-log/confirm")
    public ResponseEntity<?> confirmTradeLog(
            @RequestHeader(value = "X-User-Id", defaultValue = "default") String userId) {
        ResponseEntity<?> denied = requireTradingPlugin(userId);
        if (denied != null) return denied;
        TradeLogCollectService.ConfirmResult r = tradeLogCollectService.confirm(userId);
        return ResponseEntity.ok(Map.of(
                "confirmed", r.confirmed(),
                "failed", r.failed(),
                "skipped", r.skipped(),
                "failures", r.failures()));
    }

    /** 交易日志候选补日期（2026-08-27 二修，用户拍板「截图缺日期禁止落库，补充日期后再确认」）：
     *  截图归集候选无日期列被 confirm 拒后，前端提供日期选择 → 补写当日候选 tradeDate → 再次确认。
     *  PUT /api/v1/trading/trade-log/date，body {"symbol":"600206","direction":"SELL","tradeDate":"2026-08-26"}
     *  → {"updated":true}；无此候选/参数非法 → 404/400。 */
    @PutMapping("/trade-log/date")
    public ResponseEntity<?> setTradeLogCandidateDate(
            @RequestHeader(value = "X-User-Id", defaultValue = "default") String userId,
            @RequestBody java.util.Map<String, String> body) {
        ResponseEntity<?> denied = requireTradingPlugin(userId);
        if (denied != null) return denied;
        String symbol = body.get("symbol");
        String direction = body.get("direction");
        String dateStr = body.get("tradeDate");
        if (symbol == null || symbol.isBlank() || direction == null || dateStr == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "symbol/direction/tradeDate 必填"));
        }
        java.time.LocalDate tradeDate;
        try {
            tradeDate = java.time.LocalDate.parse(dateStr);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", "tradeDate 格式应为 yyyy-MM-dd"));
        }
        boolean updated = tradeLogCollectService.setTradeDate(userId, symbol, direction, tradeDate);
        return updated ? ResponseEntity.ok(Map.of("updated", true))
                : ResponseEntity.notFound().build();
    }

    /** 推送删除持久化（B10-1，2026-08-23，P1-推送2）：单条推送已读/忽略——
     *  app 左滑删 / web 忽略按钮调用，刷新/重启不再复活。DELETE /api/v1/trading/pushes/{id} */
    @DeleteMapping("/pushes/{id}")
    public ResponseEntity<?> dismissPush(
            @RequestHeader(value = "X-User-Id", defaultValue = "default") String userId,
            @PathVariable String id) {
        ResponseEntity<?> denied = requireTradingPlugin(userId);
        if (denied != null) return denied;
        boolean removed = marketPushRepository.dismiss(userId, java.time.LocalDate.now(), id);
        return removed ? ResponseEntity.ok(Map.of("dismissed", true))
                : ResponseEntity.notFound().build();
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
     * <p>
     * P1-1（2026-08-23 走查修复）：direction 加 @NotNull——此前 null 未持仓静默 200 no-op、
     * 已持仓 500，同请求两种行为。
     */
    public record TradeRequest(
            @NotBlank String symbol,
            @Size(max = 32) String name,
            @NotNull TradeDirection direction,
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
