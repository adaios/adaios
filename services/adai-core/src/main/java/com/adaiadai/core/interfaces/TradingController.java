package com.adaiadai.core.interfaces;

import com.adaiadai.core.application.TradingAdviceAppService;
import com.adaiadai.core.application.TradingParseAppService;
import com.adaiadai.core.application.TradingAppService;
import com.adaiadai.core.application.WatchlistBuyPointService;
import com.adaiadai.core.application.SoldScoreService;
import com.adaiadai.core.application.TradingReviewAppService;
import com.adaiadai.core.application.TradingLotService;
import com.adaiadai.core.domain.trading.TradingProfileService;
import com.adaiadai.core.application.TradePsychologyService;
import com.adaiadai.core.domain.trading.Position;
import com.adaiadai.core.domain.trading.SoldTrade;
import com.adaiadai.core.domain.trading.TradingRuleSettings;
import com.adaiadai.core.domain.trading.TradeDirection;
import com.adaiadai.core.domain.trading.TradingException;
import com.adaiadai.core.domain.trading.TradeRecord;
import com.adaiadai.core.domain.trading.WatchlistItem;
import com.adaiadai.core.domain.trading.TransferRecord;
import com.adaiadai.core.infrastructure.storage.StorageException;
import com.adaiadai.core.kernel.plugin.PluginRegistry;
import com.adaiadai.core.kernel.plugin.PluginService;
import com.adaiadai.core.kernel.storage.FileStorage;
import com.adaiadai.core.domain.trading.PushSettings;
import com.adaiadai.core.domain.trading.TradingMarketStage;
import com.adaiadai.core.infrastructure.storage.MarketPushRepository;
import com.adaiadai.core.infrastructure.storage.TradingMarketStageRepository;
import com.adaiadai.core.infrastructure.storage.PushSettingsRepository;
import com.adaiadai.core.infrastructure.storage.TradingRuleSettingsRepository;
import com.adaiadai.core.application.TradeLogCollectService;
import com.adaiadai.core.application.TradingScreenshotAppService;
import com.adaiadai.core.application.KlineService;
import com.adaiadai.core.application.TradingSessionPushService;
import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
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
    /** v3.41（2026-09-04）：活跃市值区间（用户手动判定，推送择时状态权威源）。 */
    private final TradingMarketStageRepository marketStageRepository;
    /** 2026-08-30 标的搜索：名称/拼音首字母/代码 → 候选（标注/匹配输入用）。 */
    private final com.adaiadai.core.infrastructure.market.NameToSymbolResolver nameToSymbolResolver;
    /** §10.2（2026-10-06 设计 final）：promote 候选落点改走用户分层文件存储（data/{userId}/trading/reviews/promote/）——服务端不再直写 git 跟踪的 os/。 */
    private final FileStorage fileStorage;
    /** RFC 20260905 A 层：个人交易画像（读写端点 + 客观统计）。 */
    private final TradingProfileService profileService;
    /** RFC 20260905 P2：清仓情绪采集（提问生成 + 回答回填）。 */
    private final TradePsychologyService psychologyService;
    /** RFC 20260922 B 批 B3：账同步完成 → 触发收盘复盘（不再 15:30 到点硬发一份基于旧账的复盘）。 */
    private final TradingSessionPushService sessionPushService;
    /** RFC 20260923 D 批：行情（K 线）链路可用性——让「整段拿不到行情」在用户侧可见。 */
    private final KlineService klineService;

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
                             com.adaiadai.core.infrastructure.market.NameToSymbolResolver nameToSymbolResolver,
                             TradingMarketStageRepository marketStageRepository,
                             TradingProfileService profileService,
                             TradePsychologyService psychologyService,
                             TradingSessionPushService sessionPushService,
                             KlineService klineService,
                             FileStorage fileStorage) {
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
        this.nameToSymbolResolver = nameToSymbolResolver;
        this.marketStageRepository = marketStageRepository;
        this.profileService = profileService;
        this.psychologyService = psychologyService;
        this.sessionPushService = sessionPushService;
        this.klineService = klineService;
        this.fileStorage = fileStorage;
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
     * 持仓列表视图（2026-09-14 用户拍板）：逐股「当日盈亏 + 今日涨跌幅 + 仓位比例」+ 顶部总仓位/现金比例。
     * <p>
     * 独立端点（不动 {@code /positions} 的 List 形状）——app/web/admin 三处都在消费那个形状。
     */
    @GetMapping("/positions/daily")
    public ResponseEntity<?> getPositionsDaily(
            @RequestHeader(value = "X-User-Id", defaultValue = "default") String userId) {
        ResponseEntity<?> denied = requireTradingPlugin(userId);
        if (denied != null) return denied;
        return ResponseEntity.ok(tradingAppService.getPositionsDailyView(userId));
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
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody TradeRequest request) {
        ResponseEntity<?> denied = requireTradingPlugin(userId);
        if (denied != null) return denied;
        // RFC 20261003 C6（2026-10-03）：可选 Idempotency-Key——同一 key 在 10 分钟窗口内只落一笔。
        // **覆盖范围（独立审查 2026-10-03 纠正，勿夸大）**：本端点只覆盖「手动 / 一句话记录」这条路径的
        // 重复提交；**截图确认（`/trade-log/confirm`）与批量（`/trades/batch`）不走这里**——
        // 前者的防重由 2026-09-15 的指纹判定承担（治本），后者**目前没有幂等键**（已登记遗留）。
        // 未带 key 时走原路径，行为与既有版本完全一致（向后兼容，既有测试与调用方零改动）。
        boolean hasIdemKey = idempotencyKey != null && !idempotencyKey.isBlank();
        List<Position> updated = hasIdemKey
                ? tradingAppService.recordTradeIdempotent(
                        userId, request.symbol(), request.name(),
                        request.direction(), request.price(), request.volume(),
                        request.entryDate(), request.tradeTime(),
                        request.stopLossPrice(), request.buyPoint(),
                        request.targetPrice(), request.reason(), idempotencyKey)
                : tradingAppService.recordTrade(
                        userId, request.symbol(), request.name(),
                        request.direction(), request.price(), request.volume(),
                        request.entryDate(), request.tradeTime(),
                        request.stopLossPrice(), request.buyPoint(),
                        request.targetPrice(), request.reason());
        // 三官深审 P1-1（2026-09-09）：当日成交落库后触发当日盈亏随流水重算（best-effort）
        tradingAppService.refreshTodayPnl(userId);
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
     * 标的搜索（2026-08-30 验收反馈：记不住代码只记得名字）——q 支持 代码/中文名/拼音首字母
     * （东财 suggest，如 gzmt → 贵州茅台）。GET /api/v1/trading/search?q=gzmt → 候选列表。
     */
    @GetMapping("/search")
    public ResponseEntity<?> searchSymbol(
            @RequestHeader(value = "X-User-Id", defaultValue = "default") String userId,
            @RequestParam String q) {
        ResponseEntity<?> denied = requireTradingPlugin(userId);
        if (denied != null) return denied;
        List<Map<String, String>> result = nameToSymbolResolver.search(q).stream()
                .map(c -> Map.of("symbol", c.code(), "name", c.name()))
                .toList();
        return ResponseEntity.ok(result);
    }

    /**
     * 持仓初始化导入（通达信导出 → 持仓快照）。
     * POST /api/v1/trading/positions/import?replace=true
     * body: [{"symbol":"600519","name":"贵州茅台","quantity":100,"avgCost":1400,"stopLossPrice":1350,"buyPoint":"B1"}]
     * name 缺失行情补全；止损/买点可选——导入结果返回 missingStopLoss 列表（R68 提示补设）。
     * <p>
     * {@code replace=true}（2026-08-18 确认批次）= 全量覆盖：以文件为准，
     * 导入后移除文件里不存在的持仓（含 0 股残留），解决 upsert 无删除语义的漂移。
     * <p>
     * 2026-09-13：query 加 {@code todayPnl} = 券商「持仓股」导出「当日盈亏」列之和（权威口径，含 0 股行）——
     * 前端解析该列后传上来，后端只在与账户快照同一天时写入（避免混日期）；缺列不传即保留账户旧值。
     */
    @PostMapping("/positions/import")
    public ResponseEntity<?> importPositions(
            @RequestHeader(value = "X-User-Id", defaultValue = "default") String userId,
            @RequestParam(defaultValue = "false") boolean replace,
            @RequestParam(required = false) String snapshotDate,
            // 2026-10-05（P2-交易84）：显式数据基准日（可选）。给了它就优先于「导入时刻」推断——
            // 09:26 导出、09:28 导入不再被退到上一交易日；不给则沿用既有归一化并标「无据」。
            @RequestParam(required = false) String basedOn,
            @RequestParam(required = false) String todayPnl,
            @RequestParam(defaultValue = "false") boolean dryRun,
            @RequestBody(required = false) List<TradingAppService.PositionImportItem> items) {
        ResponseEntity<?> denied = requireTradingPlugin(userId);
        if (denied != null) return denied;
        // P1-交易90（2026-10-06）：品种门（§11.2 账只接主板）对老端点同样生效——
        // 此前只护统一入口，curl/旧客户端打本端点能绕开门把科创/ETF/北交所写进账。
        // 非主板行不入账；文件里出现的非主板存量按系统现值保留（replace 不删）。
        List<TradingAppService.PositionImportItem> requested = items != null ? items : List.of();
        // RFC 20261003 C4「持仓同理」（2026-10-03）：dryRun=true → **只对账、不落盘**——
        // 先看「文件 vs 系统」逐只差多少、replace 会怎么改，人看过再决定覆盖。
        if (dryRun) {
            TradingAppService.MainboardGate gate = tradingAppService.gatePositions(userId, requested);
            // 回执构建收口到 service（2026-10-06）：与统一入口 POST /trading/import 共用同一份字段口径
            TradingAppService.PositionsReconcile rec = tradingAppService.reconcilePositions(
                    userId, gate.mainboardItems());
            return ResponseEntity.ok(TradingAppService.mergeExtra(
                    TradingAppService.positionsReconcileReceipt(rec),
                    TradingAppService.mainboardGateExtra(gate)));
        }
        // 2026-09-12：锚定日 = 快照自身日期（通达信「持仓股」文件名里的日期）——补导几天前的文件时
        // 不能把锚定日写成今天，否则锚定日之后、快照之前的成交会被误判为「已含在快照内」而丢掉增量
        java.time.LocalDate snapshot = parseOptionalDate(snapshotDate, "snapshotDate");
        // 2026-10-05（P2-交易84）：显式基准日（可选，用户/前端说清「这份快照是哪天的」）——
        // 未来日期不可信由 service 的 decideAnchor 统一裁决（回执会如实说明被忽略）。
        java.time.LocalDate basis = parseOptionalDate(basedOn, "basedOn");
        // 2026-09-13：非数字即 400 人话——宁可显式报错也不静默丢弃。
        // 「字段被无声忽略」正是本次事故的成因之一：用户的文件一直有「当日盈亏」列，系统从来没读它。
        java.math.BigDecimal brokerTodayPnl = null;
        if (todayPnl != null && !todayPnl.isBlank()) {
            try {
                brokerTodayPnl = new java.math.BigDecimal(todayPnl.trim());
            } catch (NumberFormatException e) {
                throw new TradingException("todayPnl 需为数字（收到「" + todayPnl + "」）");
            }
        }
        // P1-交易90：过品种门后才落盘（与统一入口同一判据）——非主板行不入账、存量按现值保留
        TradingAppService.MainboardGate gate = tradingAppService.gatePositions(userId, requested);
        TradingAppService.PositionImportResult result = tradingAppService.importPositions(
                userId, gate.mainboardItems(), replace, snapshot, brokerTodayPnl, basis);
        // RFC 20260922 B 批 B3：账同步完成 → 交给推送服务决定是否出复盘（收盘后立即出 / 收盘前留给 15:30）
        if (result.imported() > 0) sessionPushService.afterDataSync(userId);
        // 回执构建收口到 service（2026-10-06）：锚定依据如实回执（有据/无据）+ 品种门如实上报
        return ResponseEntity.ok(TradingAppService.mergeExtra(
                TradingAppService.positionImportReceipt(result),
                TradingAppService.mainboardGateExtra(gate)));
    }

    /**
     * 批量文件导入统一入口（R-12「一次把导出的文件交给它就行」· 2026-10-06 ingest 批）。
     * <p>
     * POST /api/v1/trading/import?dryRun=false&mode=auto（multipart/form-data）：
     * <ul>
     *   <li>{@code files} 可多选（也接受单份 {@code file}——兼容只传一个的客户端）；</li>
     *   <li>{@code mode}：历史成交的导入模式（auto=按锚定分派 · append=只补流水），默认 auto；</li>
     *   <li>{@code dryRun=true}：只识别 + 只报「会做什么」（各链对账/预检），**不落盘、不留存**。</li>
     * </ul>
     * 语义：逐份识别（表头 fail-closed）→ 内部排序（**快照在前、流水在后**：资金股份 → 持仓股 →
     * 历史成交 → 清仓股 → 自选股）→ 逐份处理 → 逐份回执；**一份失败不影响其他份**。
     * 认不出的文件先留存、后如实拒绝（原始文件不丢，但绝不当成任何一类静默入库）。
     * 保留既有五端点不变（本端点与它们共用同一 service 链路与回执口径）。
     */
    @PostMapping(value = "/import", consumes = org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> importBundle(
            @RequestHeader(value = "X-User-Id", defaultValue = "default") String userId,
            @RequestParam(value = "files", required = false) List<org.springframework.web.multipart.MultipartFile> files,
            @RequestParam(value = "file", required = false) org.springframework.web.multipart.MultipartFile file,
            @RequestParam(defaultValue = "false") boolean dryRun,
            @RequestParam(defaultValue = "auto") String mode) {
        ResponseEntity<?> denied = requireTradingPlugin(userId);
        if (denied != null) return denied;
        // 兼容两种提交形状：多选 files + 单份 file
        List<org.springframework.web.multipart.MultipartFile> effective = new java.util.ArrayList<>();
        if (files != null) effective.addAll(files);
        if (file != null) effective.add(file);
        if (effective.isEmpty()) {
            throw new TradingException("没有收到文件——请把通达信导出的文件选进来（可一次多选）");
        }
        List<TradingAppService.BundleFileInput> inputs = new java.util.ArrayList<>(effective.size());
        for (org.springframework.web.multipart.MultipartFile mf : effective) {
            byte[] bytes;
            try {
                bytes = mf.getBytes();
            } catch (java.io.IOException e) {
                // 读一份失败不拖累其他份——以「没能读取」进入统一链路，逐份回执里如实报告
                log.warn("统一入口：文件读取失败 | userId={} | {} | {}",
                        userId, mf.getOriginalFilename(), e.getMessage());
                bytes = null;
            }
            inputs.add(new TradingAppService.BundleFileInput(mf.getOriginalFilename(), bytes));
        }
        TradingAppService.ImportMode importMode = "append".equalsIgnoreCase(mode)
                ? TradingAppService.ImportMode.APPEND : TradingAppService.ImportMode.AUTO;
        TradingAppService.BundleImportResult result =
                tradingAppService.importBundle(userId, inputs, importMode, dryRun);
        // RFC 20260922 B 批 B3：统一入口也是一次账同步（dryRun 只算计划、不算同步；全部失败不算）
        if (!dryRun && result.okCount() > 0) {
            sessionPushService.afterDataSync(userId);
        }
        return ResponseEntity.ok(TradingAppService.bundleReceipt(result));
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
        // 三官深审 P1-1（2026-09-09）：当日成交落库后触发当日盈亏随流水重算（best-effort）
        tradingAppService.refreshTodayPnl(userId);
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
     * 历史成交日志导入（第五份文件：通达信「历史成交查询」导出，2026-08-18；
     * 2026-09-12 账实一致性批扩展：锚定 fail-closed + 统一幂等 + 卖超不丢数据 + 预检）。
     * POST /api/v1/trading/trades/import?mode=auto|append&dryRun=false
     * body {"content":"...（UTF-8 转码后文本）"}
     * <p>
     * 语义：
     * <ul>
     *   <li>{@code mode=auto}（默认）：按券商快照锚定分派——≤ 锚定日的成交只补流水，晚于锚定日才回放持仓/现金；
     *       锚定缺失而系统已有账目状态 → 400 人话（防静默重放双计，见 RFC 20260912）</li>
     *   <li>{@code mode=append}：全部只补流水（不动持仓/现金）——锚定缺失时的安全模式</li>
     *   <li>{@code dryRun=true}：只返回计划（新增/合并/跳过/非交易/无法归属 + 锚定状态），不写任何文件</li>
     * </ul>
     * 响应在原有 imported/updated/skipped/nonTrades/lines/syncMode/summary 之上新增
     * {@code rejected}（真实成交无法归属持仓的行级明细——**已落流水、未动持仓/现金**）与 {@code anchor}
     * （锚定状态）+ dryRun 时的 {@code plan}。
     */
    @PostMapping("/trades/import")
    public ResponseEntity<?> importHistoricalTrades(
            @RequestHeader(value = "X-User-Id", defaultValue = "default") String userId,
            @RequestParam(defaultValue = "auto") String mode,
            @RequestParam(defaultValue = "false") boolean dryRun,
            @RequestBody(required = false) Map<String, String> body) {
        ResponseEntity<?> denied = requireTradingPlugin(userId);
        if (denied != null) return denied;
        String content = body != null ? body.get("content") : null;
        TradingAppService.ImportMode importMode = "append".equalsIgnoreCase(mode)
                ? TradingAppService.ImportMode.APPEND : TradingAppService.ImportMode.AUTO;
        TradingAppService.HistoricalTradeImportResult result =
                tradingAppService.importHistoricalTrades(userId, content != null ? content : "",
                        importMode, dryRun);
        // RFC 20260922 B 批 B3：历史成交导入也是一次账同步（dryRun 只算计划、不算同步）
        if (!dryRun && (result.imported() > 0 || result.updated() > 0)) {
            sessionPushService.afterDataSync(userId);
        }
        // RFC 20260825：响应由 service 统一组装（syncMode / 总结 / rejected / unparsed / anchor / plan）
        return ResponseEntity.ok(TradingAppService.historicalImportReceipt(result, dryRun));
    }

    /**
     * 账实一致性自检（2026-09-12，RFC 20260912 §4.2）：应有持仓（券商快照基线 + 锚定日之后流水净增减）
     * 与落地持仓逐标的比对，差异即「账实不符」；同时报出重放缺口（卖超/未持有）。
     * GET /api/v1/trading/integrity
     * <p>
     * 把口径崩坏变成当天可见的闸门（本次生产事故：三条真源互相矛盾三天，靠用户肉眼发现）。
     * 降级诚实：锚定/基线缺失 → note 说明「无法判定」，不误报差异。
     */
    /**
     * 行情（K 线）链路可用性（RFC `20260923` D 批，2026-09-23）。
     *
     * <p><b>为什么要有它</b>：2026-09-22 深夜生产实测三条 K 线来源同时失效（腾讯 K 线域名被 WAF 拦 501 ·
     * 东财长期被限 · tdx 数据包滞后），而后端**只在日志里**知道——用户侧看到的是资金曲线平了、
     * 自选信号没了、案例匹配不了，**没有任何提示**（与 P1-交易60 同族：「不知道」没有被渲染成「不知道」）。
     *
     * <p>双端交易页只在 {@code ok=false} 时出横幅（无异常零显示，不制造噪音）。
     * 早盘推送那一半已由 B 批收口（买点段取不到行情会如实说）。
     */
    @GetMapping("/market-data/health")
    public ResponseEntity<?> marketDataHealth(
            @RequestHeader(value = "X-User-Id", defaultValue = "default") String userId) {
        ResponseEntity<?> denied = requireTradingPlugin(userId);
        if (denied != null) return denied;
        return ResponseEntity.ok(klineService.health());
    }

    @GetMapping("/integrity")
    public ResponseEntity<?> integrity(
            @RequestHeader(value = "X-User-Id", defaultValue = "default") String userId) {
        ResponseEntity<?> denied = requireTradingPlugin(userId);
        if (denied != null) return denied;
        return ResponseEntity.ok(tradingAppService.integrity(userId));
    }

    /** 锚定状态查询（GET /api/v1/trading/anchor，2026-09-12）：部署自检与前端提示用，不写任何数据。 */
    @GetMapping("/anchor")
    public ResponseEntity<?> anchorStatus(
            @RequestHeader(value = "X-User-Id", defaultValue = "default") String userId) {
        ResponseEntity<?> denied = requireTradingPlugin(userId);
        if (denied != null) return denied;
        return ResponseEntity.ok(tradingAppService.anchorStatus(userId));
    }

    /**
     * 锚点回填（PUT /api/v1/trading/anchor，2026-09-12）：body
     * {"positionsReplace":"yyyy-MM-dd","cashImport":"yyyy-MM-dd","holdings":[{"symbol","name","quantity"}]}
     * <p>
     * 存量环境（升级前导过快照但没写锚定文件）的**显式**自愈手段：只改元信息，不动持仓/现金/流水；
     * 日期只前进不后退。用于解开 fail-closed 拒绝，避免用户只能靠重导一遍快照文件绕。
     */
    @PutMapping("/anchor")
    public ResponseEntity<?> backfillAnchor(
            @RequestHeader(value = "X-User-Id", defaultValue = "default") String userId,
            @RequestBody(required = false) Map<String, Object> body) {
        ResponseEntity<?> denied = requireTradingPlugin(userId);
        if (denied != null) return denied;
        try {
            java.time.LocalDate positionsReplace = parseAnchorDate(body, "positionsReplace");
            java.time.LocalDate cashImport = parseAnchorDate(body, "cashImport");
            List<com.adaiadai.core.domain.trading.SnapshotHolding> holdings = new java.util.ArrayList<>();
            Object raw = body != null ? body.get("holdings") : null;
            if (raw instanceof List<?> list) {
                for (Object o : list) {
                    if (!(o instanceof Map<?, ?> m)) continue;
                    Object sym = m.get("symbol");
                    if (sym == null || String.valueOf(sym).isBlank()) continue;
                    Object qty = m.get("quantity");
                    int quantity = qty instanceof Number n ? n.intValue() : 0;
                    Object nm = m.get("name");
                    holdings.add(new com.adaiadai.core.domain.trading.SnapshotHolding(
                            String.valueOf(sym), nm != null ? String.valueOf(nm) : null, quantity));
                }
            }
            return ResponseEntity.ok(tradingAppService.backfillAnchor(
                    userId, positionsReplace, cashImport, holdings));
        } catch (TradingException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** 可选日期参数解析（null/空串 → null；格式错 → 400 人话；兼容 yyyyMMdd）。 */
    private static java.time.LocalDate parseOptionalDate(String raw, String field) {
        if (raw == null || raw.isBlank()) return null;
        String v = raw.trim();
        try {
            if (v.matches("\\d{8}")) {
                return java.time.LocalDate.parse(v, java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd"));
            }
            return java.time.LocalDate.parse(v);
        } catch (Exception e) {
            throw new TradingException("日期格式不正确（" + field + " 需 yyyy-MM-dd 或 yyyyMMdd）");
        }
    }

    /** 锚点回填日期解析（缺省/空串 → null；格式错 → 400 人话）。 */
    private static java.time.LocalDate parseAnchorDate(Map<String, Object> body, String key) {
        Object v = body != null ? body.get(key) : null;
        if (v == null || String.valueOf(v).isBlank()) return null;
        try {
            return java.time.LocalDate.parse(String.valueOf(v).trim());
        } catch (Exception e) {
            throw new TradingException("日期格式不正确（" + key + " 需 yyyy-MM-dd）");
        }
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
                // 2026-09-16：各标的累计手续费（买入/卖出/合计）——批次弹窗底部展示。
                // 卖出含印花税万 5（仅卖出收），所以卖出费率约为买入 6 倍（用户实测 442 vs 2732）。
                "fees", tradingLotService.symbolFees(userId),
                "reconcile", tradingLotService.reconcile(userId)));
    }

    /**
     * 设/改批次止损（2026-09-04 按批次止损批）：给某个买入批次单独设/改止损位，
     * 事后可调——不污染流水（覆盖层 lot-stoploss.json），推送/预警/行为标注/复盘自动跟随。
     * PUT /api/v1/trading/lots/{lotId}/stop-loss，body {"stopLossPrice": 12.34}
     */
    @PutMapping("/lots/{lotId}/stop-loss")
    public ResponseEntity<?> updateLotStopLoss(
            @RequestHeader(value = "X-User-Id", defaultValue = "default") String userId,
            @PathVariable String lotId,
            @RequestBody(required = false) Map<String, Object> body) {
        ResponseEntity<?> denied = requireTradingPlugin(userId);
        if (denied != null) return denied;
        if (body == null || body.get("stopLossPrice") == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "缺少 stopLossPrice"));
        }
        BigDecimal stopLoss;
        try {
            stopLoss = new BigDecimal(body.get("stopLossPrice").toString());
        } catch (NumberFormatException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "止损位不是有效数字"));
        }
        if (!(stopLoss.compareTo(BigDecimal.ZERO) > 0) || !stopLoss.stripTrailingZeros().toPlainString().matches("\\d+(\\.\\d{1,4})?")) {
            return ResponseEntity.badRequest().body(Map.of("error", "止损位需为正数且至多 4 位小数"));
        }
        if (!tradingLotService.setLotStopLoss(userId, lotId, stopLoss)) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(Map.of("lotId", lotId, "stopLossPrice", stopLoss));
    }

    /**
     * 清除批次止损覆盖（回退该批流水止损/默认 −7%）。
     * DELETE /api/v1/trading/lots/{lotId}/stop-loss
     */
    @DeleteMapping("/lots/{lotId}/stop-loss")
    public ResponseEntity<?> deleteLotStopLoss(
            @RequestHeader(value = "X-User-Id", defaultValue = "default") String userId,
            @PathVariable String lotId) {
        ResponseEntity<?> denied = requireTradingPlugin(userId);
        if (denied != null) return denied;
        if (!tradingLotService.clearLotStopLoss(userId, lotId)) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(Map.of("lotId", lotId, "cleared", true));
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

    /**
     * 清仓股列表（GET /api/v1/trading/sold，RFC 20260909 批 1 双轨）。
     * 响应：{"sold": [SoldTrade(含 provenance)], "pendingClearances": [{symbol,name,sellDate,reason}]}
     */
    @GetMapping("/sold")
    public ResponseEntity<?> sold(
            @RequestHeader(value = "X-User-Id", defaultValue = "default") String userId) {
        ResponseEntity<?> denied = requireTradingPlugin(userId);
        if (denied != null) return denied;
        return ResponseEntity.ok(Map.of(
                "sold", tradingAppService.soldList(userId),
                "pendingClearances", tradingAppService.soldPendingClearances(userId)));
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
        java.util.Map<String, Object> resp = new java.util.LinkedHashMap<>();
        resp.put("imported", r.imported());
        // P2-交易83（2026-10-04）：解析层「没看懂的行」带行号+原因透出——「导入 42 笔」不再掩盖被丢的行
        // （字段名与 POST /trading/trades/import 的 unparsed/unparsedCount 完全一致，前端可复用同一解析）
        if (r.unparsedRows() != null && !r.unparsedRows().isEmpty()) {
            resp.put("unparsed", r.unparsedRows());
            resp.put("unparsedCount", r.unparsedRows().size());
        }
        return ResponseEntity.ok(resp);
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

    /** 清仓情绪提问（RFC 20260905 P2，GET /api/v1/trading/sold/{symbol}/psychology-questions）：
     *  按该笔交易结构（盈亏/天数/行为签名）生成 3~5 个「当时为什么」提问——前端清仓卡展示，
     *  用户回答后 POST /psychology/answer 回填。提问确定性生成，不耗 LLM。 */
    @GetMapping("/sold/{symbol}/psychology-questions")
    public ResponseEntity<?> soldPsychologyQuestions(
            @RequestHeader(value = "X-User-Id", defaultValue = "default") String userId,
            @PathVariable String symbol) {
        ResponseEntity<?> denied = requireTradingPlugin(userId);
        if (denied != null) return denied;
        // 💥6（2026-09-05 对抗审）：同 symbol 多次清仓 → 取最近一笔（用户在清仓卡看到的就是最近）
        SoldTrade trade = tradingAppService.soldList(userId).stream()
                .filter(t -> t.symbol().equals(symbol) && t.sellDate() != null)
                .max(java.util.Comparator.comparing(SoldTrade::sellDate))
                .orElse(null);
        if (trade == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(Map.of(
                "symbol", symbol,
                "questions", psychologyService.questionsFor(trade)));
    }

    /** 清仓情绪回答（RFC 20260905 P2，POST /api/v1/trading/sold/{symbol}/psychology/answer）：
     *  用户回答回填 sold.psychology（追加式）+ 沉淀 profile.md 主观层。 */
    @PostMapping("/sold/{symbol}/psychology/answer")
    public ResponseEntity<?> soldPsychologyAnswer(
            @RequestHeader(value = "X-User-Id", defaultValue = "default") String userId,
            @PathVariable String symbol,
            @RequestBody(required = false) Map<String, String> body) {
        ResponseEntity<?> denied = requireTradingPlugin(userId);
        if (denied != null) return denied;
        String psychology = body == null ? null : body.get("psychology");
        if (psychology == null || psychology.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "psychology 不能为空"));
        }
        boolean ok = psychologyService.submitAnswer(userId, symbol, psychology);
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
        // RFC 20261003 C2/C3（用户 2026-10-03 拍板 D1：**警告 + 引导，不硬拒**）：转出前看「可取」够不够。
        // 账面「现金」与「可取」不是一回事——A 股 T+1：当日卖出所得可继续买、但要次一交易日才能转出。
        // 这里**只警告不改行为**（硬拒会挡住补录历史等真实习惯），并把差额与原因讲清楚。
        String warning = null;
        if ("OUT".equals(type)) {
            // ⚠️ P1（2026-10-03 增量深审）：没有资金快照时 accountSnapshot 的 withdrawable 是**占位 0**，
            // 于是「任何金额的转出」都会被告知「可取只有 0」——纯误导。现在：没有快照就不比、不警告。
            BigDecimal withdrawable = tradingAppService.accountSnapshot(userId).withdrawable();
            boolean hasSnapshot = tradingAppService.hasAccountSnapshot(userId);
            if (hasSnapshot && withdrawable.compareTo(amount) < 0) {
                warning = "可取资金只有 " + withdrawable.stripTrailingZeros().toPlainString()
                        + "，这次要转出 " + amount.stripTrailingZeros().toPlainString()
                        + "——现实中券商不会放行（当日卖出的钱要次一交易日才能取）。已按你说的记上了；"
                        + "导一次「资金股份查询」就能把可取对齐。";
            }
        }
        TransferRecord record = tradingAppService.recordTransfer(
                userId, type, amount, date, body.get("note"));
        java.util.Map<String, Object> resp = new java.util.LinkedHashMap<>();
        resp.put("id", record.id());
        resp.put("type", record.type());
        resp.put("amount", record.amount());
        resp.put("date", record.date().toString());
        if (warning != null) resp.put("warning", warning);
        return ResponseEntity.ok(resp);
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

    /**
     * 自选买点**完整扫描**（RFC `20260922` B 批 B4，收口 P1-交易60）：
     * 命中 {@code hits} + **没能判定的标的** {@code unavailable} + 判定所用数据日期 {@code dataDate}。
     * <p>
     * 为什么另开端点而不是改 {@code GET /buy-points}：后者的「JSON 数组」形状被 web 与 app 两处消费，
     * 改形状是 breaking；而对用户的意义是**「今天没机会」与「今天我没取到行情」终于能分开说**
     * （生产实据 2026-09-18 10:37 双源失败就砸在用户看盘的同一分钟）。
     */
    @GetMapping("/buy-points/scan")
    public ResponseEntity<?> buyPointsScan(
            @RequestHeader(value = "X-User-Id", defaultValue = "default") String userId) {
        ResponseEntity<?> denied = requireTradingPlugin(userId);
        if (denied != null) return denied;
        List<WatchlistItem> watchlist = tradingAppService.watchlistList(userId);
        WatchlistBuyPointService.ScanResult r = buyPointService.scanWatchlistDetailed(watchlist, userId);
        java.util.Map<String, Object> resp = new java.util.LinkedHashMap<>();
        resp.put("hits", r.hits());
        resp.put("unavailable", r.unavailable());
        resp.put("dataDate", r.dataDate() != null ? r.dataDate() : "");
        return ResponseEntity.ok(resp);
    }

    /** 账户总体快照（资产/可用/可取/参考市值/盈亏/当日盈亏，GET /api/v1/trading/account）。 */
    @GetMapping("/account")
    public ResponseEntity<?> accountSnapshot(
            @RequestHeader(value = "X-User-Id", defaultValue = "default") String userId) {
        ResponseEntity<?> denied = requireTradingPlugin(userId);
        if (denied != null) return denied;
        // P2-交易69（2026-09-23）：改走 accountView——在快照字段之外补 cashDate（现金这个数的券商日期）
        // 与 cashNote（负现金/过期/无来源的人话提示）。起因：现金在两次「资金股份查询」之间会漂，
        // 而用户侧看不到「这个数是什么时候的」，生产上曾虚高 23,686.15（总盈亏少报 2.37 万）。
        return ResponseEntity.ok(tradingAppService.accountView(userId));
    }

    /**
     * 设置本金——**已退役（2026-10-06，设计 §9#4）**。
     * PUT /api/v1/trading/principal
     * <p>
     * 原因（G-06）：本金现在由**事件**推出——「转入/转出」自动累计净投入，不再靠手填；
     * 手填的数与真实出入金两张皮，是「总盈亏基准失真」的源头（P2-交易66）。存量手填值会在
     * 读账户时**自动迁移为一条一次性出入金调整事件**（资金页可见），之后本金 = 转账净额 + 迁移调整。
     * <p>
     * 返回 410 Gone + 人话指路；**读侧保留**（GET /account 的 principal 字段照常返回）。
     */
    @PutMapping("/principal")
    public ResponseEntity<?> setPrincipal(
            @RequestHeader(value = "X-User-Id", defaultValue = "default") String userId) {
        ResponseEntity<?> denied = requireTradingPlugin(userId);
        if (denied != null) return denied;
        return ResponseEntity.status(410).body(Map.of("error",
                "设置本金已退役——本金现在由转入/转出自动算出（净投入 = 转存 − 转取）；"
                        + "要修正本金请到「资金」里补记转入/转出，存量手填的本金会自动迁移进账"));
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
        // RFC 20261003 §5.5（2026-10-03）：复盘阈值（R55「曾赚过」/ R69「被套」）
        params.put("reviewPeakMinPct", String.valueOf(s.reviewPeakMinPct()));
        params.put("reviewTrapMinPct", String.valueOf(s.reviewTrapMinPct()));
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
                        ? num(params.get("constraintRuleMax")).intValue() : current.constraintRuleMax(),
                // RFC 20261003 §5.5（2026-10-03）：复盘阈值（未传则保持现值）
                num(params.get("reviewPeakMinPct")) != null
                        ? num(params.get("reviewPeakMinPct")).doubleValue() : current.reviewPeakMinPct(),
                num(params.get("reviewTrapMinPct")) != null
                        ? num(params.get("reviewTrapMinPct")).doubleValue() : current.reviewTrapMinPct());
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

    /**
     * 个人交易画像（RFC 20260905 A 层，GET /api/v1/trading/profile）：
     * 客观统计（系统从清仓史推导）+ 主观层原文（profile.md）。未建画像 → subjective=null。
     */
    @GetMapping("/profile")
    public ResponseEntity<?> tradingProfile(
            @RequestHeader(value = "X-User-Id", defaultValue = "default") String userId) {
        ResponseEntity<?> denied = requireTradingPlugin(userId);
        if (denied != null) return denied;
        TradingProfileService.TradingProfileStats stats = profileService.computeStats(userId);
        java.util.Map<String, Object> resp = new java.util.HashMap<>();
        resp.put("stats", stats);
        resp.put("objectiveText", profileService.objectiveProfileText(userId));
        // RFC 20260905 P2：建议遵守率（B 反哺 A）
        TradingProfileService.AdviceAdherence adherence = profileService.computeAdviceAdherence(userId);
        java.util.Map<String, Object> adherenceResp = new java.util.HashMap<>();
        adherenceResp.put("withAdviceCount", adherence.withAdviceCount());
        adherenceResp.put("followedCount", adherence.followedCount());
        adherenceResp.put("followRatePct", adherence.followRatePct());
        resp.put("adviceAdherence", adherenceResp);
        String subjective = profileService.rawProfile(userId);
        resp.put("subjective", subjective != null && !subjective.isBlank() ? subjective : null);
        return ResponseEntity.ok(resp);
    }

    /**
     * 保存个人画像主观层（RFC 20260905 A 层，PUT /api/v1/trading/profile）：
     * body {"content": "..."}——用户/AI 回填的行为签名与情绪记忆，落 profile.md。
     */
    @PutMapping("/profile")
    public ResponseEntity<?> saveTradingProfile(
            @RequestHeader(value = "X-User-Id", defaultValue = "default") String userId,
            @RequestBody java.util.Map<String, String> body) {
        ResponseEntity<?> denied = requireTradingPlugin(userId);
        if (denied != null) return denied;
        String content = body == null ? null : body.get("content");
        // P2-交易47（2026-09-14）：原只判 null → **空串/纯空白可通过**，把 profile.md 整文件覆盖清空
        //（画像主观层 = 用户手写/AI 回填的行为签名与情绪记忆，清空无从恢复）。空串与 null 同拒。
        if (content == null || content.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "画像内容不能为空——想保留原内容就别提交空白；确实要清空请直接说明"));
        }
        profileService.saveProfile(userId, content);
        return ResponseEntity.ok(Map.of("updated", true));
    }

    /**
     * 活跃市值区间（v3.41，2026-09-04）：用户手动判定的多空区间（指南针活跃市值口径）。
     * GET /api/v1/trading/market-stage——读用户判定；无记录 → exists=false（推送回退 current.md 推断）。
     */
    @GetMapping("/market-stage")
    public ResponseEntity<?> marketStage(
            @RequestHeader(value = "X-User-Id", defaultValue = "default") String userId) {
        ResponseEntity<?> denied = requireTradingPlugin(userId);
        if (denied != null) return denied;
        TradingMarketStage s = marketStageRepository.findByUser(userId);
        // 注意：Map.of 不接受 null 值——用 HashMap（无记录时 stage/updatedAt=null 是契约语义）
        java.util.Map<String, Object> resp = new java.util.HashMap<>();
        if (s == null) {
            resp.put("exists", false);
            resp.put("stage", null);
            resp.put("updatedAt", null);
        } else {
            resp.put("exists", true);
            resp.put("stage", s.stage());
            resp.put("updatedAt", s.updatedAt());
        }
        return ResponseEntity.ok(resp);
    }

    /**
     * 设定活跃市值区间（v3.41，2026-09-04）：用户亲手切多头/空头。
     * PUT /api/v1/trading/market-stage，body {"stage":"bull"|"bear"}，两档；非法 → 400。
     * 落 data/{userId}/trading/market-stage.json；推送择时状态以用户判定优先（三级读取）。
     */
    @PutMapping("/market-stage")
    public ResponseEntity<?> updateMarketStage(
            @RequestHeader(value = "X-User-Id", defaultValue = "default") String userId,
            @RequestBody java.util.Map<String, Object> body) {
        ResponseEntity<?> denied = requireTradingPlugin(userId);
        if (denied != null) return denied;
        Object stageObj = body.get("stage");
        String stage = stageObj instanceof String str ? str.trim() : null;
        if (stage == null || !TradingMarketStage.isValid(stage)) {
            return ResponseEntity.badRequest().body(java.util.Map.of(
                    "error", "stage 必须是 bull（多头区间）或 bear（空头区间）"));
        }
        TradingMarketStage s = new TradingMarketStage(stage, TradingMarketStageRepository.now());
        // P0-1 审查模式：写盘失败抛 StorageException → GlobalExceptionHandler 500（不再静默 updated=true）
        marketStageRepository.save(userId, s);
        return ResponseEntity.ok(java.util.Map.of(
                "updated", true, "stage", s.stage(), "updatedAt", s.updatedAt()));
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
            java.util.Map<String, Object> resp = new java.util.LinkedHashMap<>();
            resp.put("total", r.total());
            resp.put("processed", r.processed());
            resp.put("candidates", r.candidates());
            resp.put("errors", r.errors());
            // P2-交易44（2026-09-14）：被表格规则丢弃的行（原文+原因）——「识别出 N 笔」不再掩盖丢行
            if (r.dropped() != null && !r.dropped().isEmpty()) {
                resp.put("dropped", r.dropped());
                resp.put("droppedCount", r.dropped().size());
            }
            return ResponseEntity.ok(resp);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.warn("截图入账失败 | userId={} | {}", userId, e.getMessage());
            return ResponseEntity.internalServerError().body(Map.of("error", "截图处理失败: " + e.getMessage()));
        }
    }

    /** 交易日志归集（B6-5，2026-08-23，P1-交易18）：丢弃一条保留候选（失败/不完整钉子户）。
     *  DELETE /api/v1/trading/trade-log?id=&symbol=&direction= → {"discarded":true}；无此候选 404。
     *  <p>P1-交易54（2026-09-17）：**优先按 {@code id} 行级定位**——同标的同方向的多笔候选
     *  （生产实据：当日三笔亨通光电买入各 100 股）只有 id 能精确删到其中一条；
     *  {@code symbol+direction} 是旧口径（同代码同方向的多笔会一起删掉、symbol 为空更会删光该方向），
     *  仅为兼容旧客户端保留。 */
    @DeleteMapping("/trade-log")
    public ResponseEntity<?> discardTradeLogCandidate(
            @RequestHeader(value = "X-User-Id", defaultValue = "default") String userId,
            @RequestParam(required = false) String id,
            @RequestParam(required = false) String symbol,
            @RequestParam(required = false) String direction) {
        ResponseEntity<?> denied = requireTradingPlugin(userId);
        if (denied != null) return denied;
        boolean removed = (id != null && !id.isBlank())
                ? tradeLogCollectService.discardById(userId, id)
                : tradeLogCollectService.discard(userId, symbol, direction);
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
        // 三官深审 P1-1（2026-09-09）：确认落库的当日成交 → 触发当日盈亏随流水重算（best-effort）
        tradingAppService.refreshTodayPnl(userId);
        return ResponseEntity.ok(Map.of(
                "confirmed", r.confirmed(),
                "failed", r.failed(),
                "skipped", r.skipped(),
                "duplicated", r.duplicated(),
                // 2026-09-18（P0-交易59）：命中券商快照锚定 → 只落流水不改账（持仓/现金以快照为准）
                "ledgerOnly", r.ledgerOnly(),
                "failures", r.failures(),
                "duplicates", r.duplicates()));
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
        // P1-交易54 收尾（2026-09-17）：**优先按 id 行级定位**——同标的同方向的多笔候选
        // （当日三笔亨通光电各 100 股）只有它能区分；symbol+direction 是旧口径（会把那几笔
        // **一起补上同一日期**），仅为兼容旧客户端保留。
        String id = body.get("id");
        String symbol = body.get("symbol");
        String direction = body.get("direction");
        String dateStr = body.get("tradeDate");
        boolean byId = id != null && !id.isBlank();
        if (!byId && (symbol == null || symbol.isBlank() || direction == null || direction.isBlank())) {
            return ResponseEntity.badRequest().body(Map.of("error", "id 或 symbol/direction 必填"));
        }
        if (dateStr == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "tradeDate 必填"));
        }
        java.time.LocalDate tradeDate;
        try {
            tradeDate = java.time.LocalDate.parse(dateStr);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", "tradeDate 格式应为 yyyy-MM-dd"));
        }
        boolean updated = byId
                ? tradeLogCollectService.setTradeDateById(userId, id, tradeDate)
                : tradeLogCollectService.setTradeDate(userId, symbol, direction, tradeDate);
        return updated ? ResponseEntity.ok(Map.of("updated", true))
                : ResponseEntity.notFound().build();
    }

    /** 交易日志候选补成交元信息（P2-交易36 治本，2026-09-09）：截图入账/手动确认成交缺
     *  成交编号(orderId)与手续费(fee)——确认前在候选上补填，确认落库时透传流水。
     *  **2026-09-18（RFC 20260918 A1-4）**：同一端点兼作**候选就地编辑**——改正识别错的
     *  价格 / 数量 / 方向 / 成交日期（截图 VLM 对这三样都可能出错，原来只能丢弃重录）。
     *  PUT /api/v1/trading/trade-log/meta，
     *  body {"id":"cand_...","price":56.67,"volume":100,"direction":"BUY","tradeDate":"2026-09-18",
     *        "orderId":"1234567890","fee":5.5}（全部可选，只覆盖非空值）
     *  → {"updated":true}；无此候选/都无可写值 → {"updated":false}；参数非法 → 400。
     *  **就地编辑必须带 `id`**（同代码同方向的多笔必须逐条改）；旧客户端传 symbol+direction 时
     *  只支持元信息补填（orderId/fee），不支持改核心字段。 */
    @PutMapping("/trade-log/meta")
    public ResponseEntity<?> updateTradeLogCandidateMeta(
            @RequestHeader(value = "X-User-Id", defaultValue = "default") String userId,
            @RequestBody(required = false) Map<String, Object> body) {
        ResponseEntity<?> denied = requireTradingPlugin(userId);
        if (denied != null) return denied;
        if (body == null) return ResponseEntity.badRequest().body(Map.of("error", "请求体为空"));
        // P1-交易54 收尾（2026-09-17）：优先按 id 行级定位（旧口径会把同代码同方向的多笔一起补）
        String id = body.get("id") != null ? String.valueOf(body.get("id")) : null;
        String symbol = body.get("symbol") != null ? String.valueOf(body.get("symbol")) : null;
        String direction = body.get("direction") != null ? String.valueOf(body.get("direction")) : null;
        boolean byId = id != null && !id.isBlank();
        if (!byId && (symbol == null || symbol.isBlank() || direction == null || direction.isBlank())) {
            return ResponseEntity.badRequest().body(Map.of("error", "id 或 symbol/direction 必填"));
        }
        String orderId = body.get("orderId") != null ? String.valueOf(body.get("orderId")) : null;
        BigDecimal fee = null;
        if (body.get("fee") != null) {
            try {
                fee = new BigDecimal(String.valueOf(body.get("fee")).trim());
            } catch (NumberFormatException e) {
                return ResponseEntity.badRequest().body(Map.of("error", "fee 不是有效数字"));
            }
        }
        if (!byId) {
            // P2-1（2026-09-19 后端审查）：旧口径带**核心字段**时必须明确 400——原来这些字段在
            // 下面才解析，走到这里已被**静默丢弃**；若同时带 orderId/fee 还会回 `updated:true`，
            // 客户端以为改价成功了（api-spec 早已写明「就地编辑必须带 id，否则 400」）。
            if (body.get("price") != null || body.get("volume") != null || body.get("tradeDate") != null) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "就地编辑请带上 id（同代码同方向的多笔必须逐条改）"));
            }
            // 旧口径（symbol+direction）：只补元信息，不做就地编辑（无行级定位，会误改同代码同向的多笔）
            boolean legacy = tradeLogCollectService.updateMeta(userId, symbol, direction, orderId, fee);
            return ResponseEntity.ok(Map.of("updated", legacy));
        }
        // ── A1-4（2026-09-18）：核心字段就地编辑（按 id 行级定位）──
        BigDecimal price = null;
        Integer volume = null;
        java.time.LocalDate tradeDate = null;
        try {
            if (body.get("price") != null) {
                price = new BigDecimal(String.valueOf(body.get("price")).trim());
            }
            if (body.get("volume") != null) {
                volume = Integer.valueOf(String.valueOf(body.get("volume")).trim());
            }
            if (body.get("tradeDate") != null) {
                String d = String.valueOf(body.get("tradeDate")).trim();
                if (!d.isEmpty()) tradeDate = java.time.LocalDate.parse(d);
            }
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", "price/volume/tradeDate 不是有效值"));
        }
        // 带 id 时 `direction` 的语义 = **改成这个方向**（定位已由 id 承担）。
        // P1-5（2026-09-19 对抗审查）：**白名单校验**——不校验的话 "卖出"/"sell"/任意字符串都能存进候选，
        // 而 confirm 里 `"SELL".equals(...) ? SELL : BUY` 会把它当**买入**入账（方向反转 = 持仓差 2×股数）。
        String newDirection = null;
        if (direction != null && !direction.isBlank()) {
            String d = direction.trim().toUpperCase();
            if (!"BUY".equals(d) && !"SELL".equals(d)) {
                return ResponseEntity.badRequest().body(Map.of("error", "direction 只能是 BUY 或 SELL"));
            }
            newDirection = d;
        }
        boolean hasCore = price != null || volume != null || tradeDate != null || newDirection != null;
        boolean updated = false;
        if (hasCore) {
            updated |= tradeLogCollectService.updateFieldsById(
                    userId, id, price, volume, newDirection, tradeDate, fee);
        }
        boolean hasOrder = orderId != null && !orderId.isBlank();
        if (hasOrder || (fee != null && !hasCore)) {
            updated |= tradeLogCollectService.updateMetaById(userId, id, orderId, fee);
        }
        return ResponseEntity.ok(Map.of("updated", updated));
    }

    /** 交易流水补成交元信息（P2-交易36 治本，2026-09-09）：对**已落库**流水按 tradeId 补填
     *  成交编号(orderId)/手续费(fee)——候选已确认入账但缺字段的历史场景。
     *  PUT /api/v1/trading/trades/{tradeId}/meta，body {"orderId":"1234567890","fee":5.5}
     *  （只覆盖非空值，不改其它字段）→ {"updated":true,"tradeId":"trade_..."}；
     *  orderId 空白且 fee 为空 → 400；fee 解析失败 → 400。 */
    @PutMapping("/trades/{tradeId}/meta")
    public ResponseEntity<?> updateTradeMeta(
            @RequestHeader(value = "X-User-Id", defaultValue = "default") String userId,
            @PathVariable String tradeId,
            @RequestBody(required = false) Map<String, Object> body) {
        ResponseEntity<?> denied = requireTradingPlugin(userId);
        if (denied != null) return denied;
        if (body == null) return ResponseEntity.badRequest().body(Map.of("error", "请求体为空"));
        String orderId = body.get("orderId") != null ? String.valueOf(body.get("orderId")) : null;
        BigDecimal fee = null;
        if (body.get("fee") != null) {
            try {
                fee = new BigDecimal(String.valueOf(body.get("fee")).trim());
            } catch (NumberFormatException e) {
                return ResponseEntity.badRequest().body(Map.of("error", "fee 不是有效数字"));
            }
        }
        boolean hasOrder = orderId != null && !orderId.isBlank();
        if (!hasOrder && fee == null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "orderId 与 fee 不能都为空——请至少补填成交编号或手续费"));
        }
        int updated = tradingAppService.updateTradeMeta(userId, tradeId, orderId, fee);
        return ResponseEntity.ok(Map.of("updated", updated > 0, "tradeId", tradeId));
    }

    /**
     * 流水纠错 · 就地改（R-08 · 设计 §3④ §4.3 §9#24，2026-10-06）：改核心字段（price / volume /
     * direction / entryDate / tradeTime / fee / orderId / reason 等），**用户面不留痕**，
     * 系统侧写不可见修改日志，持仓/现金派生自动重算（不可精确撤销的场景 → 400 指路重导快照）。
     * PUT /api/v1/trading/trades/{tradeId}，body 为要改的字段（未给的字段原样保留）。
     */
    @PutMapping("/trades/{tradeId}")
    public ResponseEntity<?> editTrade(
            @RequestHeader(value = "X-User-Id", defaultValue = "default") String userId,
            @PathVariable String tradeId,
            @RequestBody(required = false) Map<String, Object> body) {
        ResponseEntity<?> denied = requireTradingPlugin(userId);
        if (denied != null) return denied;
        if (body == null || body.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "请求体为空——请给出要修改的字段"));
        }
        return ResponseEntity.ok(tradingAppService.editTradeRecord(userId, tradeId, body));
    }

    /** 流水纠错 · 就地删（R-08）：DELETE /api/v1/trading/trades/{tradeId}（用户面不留痕，系统侧留痕）。 */
    @DeleteMapping("/trades/{tradeId}")
    public ResponseEntity<?> deleteTrade(
            @RequestHeader(value = "X-User-Id", defaultValue = "default") String userId,
            @PathVariable String tradeId) {
        ResponseEntity<?> denied = requireTradingPlugin(userId);
        if (denied != null) return denied;
        return ResponseEntity.ok(tradingAppService.deleteTradeRecord(userId, tradeId));
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
        // 2026-09-12：账户快照日期/现金锚定日 = 快照自身日期（前端从文件名取，如 20260909）
        java.time.LocalDate snapshot = parseOptionalDate(body != null ? body.get("snapshotDate") : null,
                "snapshotDate");
        // 2026-10-05（P2-交易84）：显式数据基准日（可选）——给了它就优先于「导入时刻」推断。
        java.time.LocalDate basis = parseOptionalDate(body != null ? body.get("basedOn") : null, "basedOn");
        // RFC 20261003 C4（2026-10-03）：dryRun=true → **只对账、不落盘**——先把「券商现金 vs 系统推算」
        // 与差额摆出来，人看过再决定要不要覆盖（治「静默覆盖 → 只能反复导全量」）。
        if (body != null && "true".equalsIgnoreCase(String.valueOf(body.get("dryRun")))) {
            // 回执构建收口到 service（2026-10-06）：与统一入口共用同一份字段口径
            TradingAppService.CashReconcile rec = tradingAppService.reconcileCash(
                    userId, content != null ? content : "", snapshot, basis);
            return ResponseEntity.ok(TradingAppService.cashReconcileReceipt(rec));
        }
        TradingAppService.CashImportResult r = tradingAppService.importCashQuery(
                userId, content != null ? content : "", snapshot, basis);
        // RFC 20260922 B 批 B3：资金股份快照是一次账同步（同步完成 → 可出复盘）
        sessionPushService.afterDataSync(userId);
        // 回执构建收口到 service（2026-10-06）：unparsedRows(int) / unparsed / anchor 与统一入口同口径
        return ResponseEntity.ok(TradingAppService.cashImportReceipt(r));
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
     * 持仓解读（建议引擎机制，已建能力——模块定位见 RFC 20260902 交易记忆）。
     * <p>
     * 读用户持仓 + 实时行情 + 只读 {@code os/trading-engine/knowledge/context/rules.md} 与 {@code strategy.md}，
     * 将止损规则（R66-R80）与仓位规则（R81-R95）作为决策硬约束注入 LLM，结构化生成逐票解读
     * （suggestion / reason / rules 必须引用规则号）。输出是解读不是指令，本端点不做任何执行动作。
     * <p>
     * 兜底：LLM 失败时降级返回基础数据（symbol / name / position_percent，无解读字段），不抛错。
     */
    @PostMapping("/advice")
    public ResponseEntity<?> generateAdvice(
            @RequestHeader(value = "X-User-Id", defaultValue = "default") String userId) {
        ResponseEntity<?> denied = requireTradingPlugin(userId);
        if (denied != null) return denied;
        return ResponseEntity.ok(adviceAppService.generateAdvice(userId));
    }

    /** 建议留痕查询（RFC 20260905 B①，GET /api/v1/trading/advice-history）：
     *  查某标的最近 N 天建议（默认 30）——「阿呆当时说 X」的数据源，供卖出回查/复盘对照。 */
    @GetMapping("/advice-history")
    public ResponseEntity<?> adviceHistory(
            @RequestHeader(value = "X-User-Id", defaultValue = "default") String userId,
            @RequestParam(defaultValue = "") String symbol,
            @RequestParam(defaultValue = "30") int days) {
        ResponseEntity<?> denied = requireTradingPlugin(userId);
        if (denied != null) return denied;
        if (symbol.isBlank()) {
            // 未指定标的 → 返回最近 30 天全部（倒序）
            java.time.LocalDate from = java.time.LocalDate.now().minusDays(Math.max(1, Math.min(days, 90))); // ⚠️13: 上限 90 对齐仓储 3 个月窗口
            java.util.ArrayList<com.adaiadai.core.domain.trading.AdviceEntry> all = new java.util.ArrayList<>();
            java.time.YearMonth m = java.time.YearMonth.from(from);
            java.time.YearMonth end = java.time.YearMonth.now();
            for (java.time.YearMonth ym = m; !ym.isAfter(end); ym = ym.plusMonths(1)) {
                all.addAll(adviceAppService.adviceHistoryByMonth(userId, ym));
            }
            return ResponseEntity.ok(all.stream()
                    .filter(e -> e.date() == null || !e.date().isBefore(from))
                    // 跨月拼接后统一 createdAt 降序（各月组内已倒序，跨月需全局排——docs 审查 2026-09-05）
                    .sorted(java.util.Comparator.comparing(
                            (com.adaiadai.core.domain.trading.AdviceEntry e) -> e.createdAt(),
                            java.util.Comparator.nullsLast(java.util.Comparator.reverseOrder())))
                    .toList());
        }
        return ResponseEntity.ok(adviceAppService.adviceHistoryRecent(userId, symbol, Math.max(1, Math.min(days, 90)))); // ⚠️13: 上限 90 对齐仓储 3 个月窗口
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
     * <p>
     * 2026-09-07 复盘超时修复批：AI 生成实测 77~176s，远超前端客户端超时（原同步阻塞导致
     * 前端先断、「点击没反应」而复盘实际已生成）。改为<b>提交即返回</b>（{@code status}：
     * {@code exists} 已有复盘不重跑 / {@code running} 同日在生成中 / {@code pending} 已受理后台生成），
     * 前端轮询 {@code GET /trading/review?date=}（404=未就绪，200=内容）。
     */
    @PostMapping("/review")
    public ResponseEntity<?> generateReview(
            @RequestHeader(value = "X-User-Id", defaultValue = "default") String userId,
            @RequestParam(defaultValue = "#{T(java.time.LocalDate).now()}") LocalDate date) {
        ResponseEntity<?> denied = requireTradingPlugin(userId);
        if (denied != null) return denied;
        TradingReviewAppService.ReviewSubmitResult result = reviewAppService.submitReview(userId, date);
        return ResponseEntity.ok(new ReviewSubmitResponse(result.date(), result.status()));
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
     * <p>
     * §11.3 T19（2026-10-06）：本端点为交易域读端点、直接暴露「有没有交易」→ **补门控**
     * （与写侧清单同口径）；admin 跨用户查看走 admin 侧端点，不走该用户端点。
     */
    @GetMapping("/has-activity")
    public ResponseEntity<?> hasTradingActivity(
            @RequestHeader(value = "X-User-Id", defaultValue = "default") String userId,
            @RequestParam(defaultValue = "#{T(java.time.LocalDate).now()}") LocalDate date) {
        ResponseEntity<?> denied = requireTradingPlugin(userId);
        if (denied != null) return denied;
        boolean hasActivity = reviewAppService.hasTradingActivity(userId, date);
        return ResponseEntity.ok(new ActivityCheckResponse(date.toString(), hasActivity));
    }

    // ── 知识反哺 API ──

    /**
     * 将复盘笔记中的内容提升为入库候选。
     * <p>
     * §10.2（2026-10-06 设计 final）：唯一落点
     * {@code data/{userId}/trading/reviews/promote/{date}_{主题}.md}
     * ——按用户隔离；**永不覆盖**（同日同名追加 {@code -2}/{@code -3} 序号，取代旧的
     * 「文件名只带日期 + REPLACE_EXISTING」）。
     * <p>
     * **服务端不写 {@code os/}**：{@code os/trading-engine/99-inbox/} 的人工审核融合步骤保留，
     * 改由 admin / 人工流程把候选提升进 {@code os/}（服务端不再直写 git 跟踪目录）。
     * <p>
     * §10.3：非 owner **不 403**——落自己的候选区（各自隔离；owner 的候选才有人工提升的下一跳）。
     * 无 trading 插件用户仍 403（写侧清单）。
     */
    @PostMapping("/reviews/{date}/promote")
    public ResponseEntity<?> promoteToInbox(
            @RequestHeader(value = "X-User-Id", defaultValue = "default") String userId,
            @PathVariable LocalDate date,
            @RequestBody PromoteRequest request) {
        // RFC 20260814：promote 属交易写入侧 → 仅启用 trading 插件用户可用（§11.3 写侧清单）
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
            // §10.2 唯一落点：data/{userId}/trading/reviews/promote/{date}_{主题}.md
            // （FileStorage 按 userId 分层，路径为相对用户层）
            // P2-交易100（2026-10-06）：{主题} 占位落到实处——request.theme 过了就用它，
            // 未给/空白 → 「交易复盘」（与旧行为逐字兼容）；危险字符剔除 + 限长 32
            String stem = "trading/reviews/promote/" + date + "_" + safeTheme(request != null ? request.theme() : null);
            String path = stem + ".md";
            // 永不覆盖：同日同名 → -2 / -3 序号（不用 REPLACE_EXISTING，不丢旧候选）
            // 已知窗口（如实记录，不修）：exists 检查与 write 之间无锁，同一用户**恰好在同一瞬间**
            // 并发两次 promote 时两者可能取同一序号、后写覆盖前者（单用户 UI 串行触发，概率极低；
            // FileStorage.write 自身是原子写，不会写坏文件）；为此加锁不值得。
            for (int seq = 2; fileStorage.exists(userId, path); seq++) {
                path = stem + "-" + seq + ".md";
            }
            fileStorage.write(userId, path, content);

            log.info("复盘内容已提升为入库候选 | userId={} | date={} | path={}", userId, date, path);
            // #178：提示入库候选不会自动融入 AI context——需人工审核融合后重建 knowledge/context
            String message = "已写入你的入库候选区，不会自动进入 AI 上下文：需人工审核后归入交易知识库正式目录，并在收敛时重建 knowledge/context。";
            return ResponseEntity.ok(new PromoteResponse("ok", path, message));
        } catch (Exception e) {
            log.error("入库候选写入失败 | userId={} | date={} | {}", userId, date, e.getMessage());
            throw new StorageException("入库候选写入失败: " + e.getMessage(), e);
        }
    }

    // ── 内部方法 ──

    private String buildPromoteContent(LocalDate date, PromoteRequest request, String reviewContent) {
        StringBuilder sb = new StringBuilder();
        sb.append("# 入库候选：").append(date).append(" 交易复盘\n\n");
        sb.append("> 此文件由 adai-core 自动生成，待人工审核后归入正式目录。\n");
        sb.append("> 生成时间：").append(java.time.LocalDateTime.now()).append("\n\n");
        // §10.1 规则 5/6：note 与 sections 全文过 1–4（此前完全不过滤——消除「备注/章节标题带数字直进候选」）
        if (request.note() != null && !request.note().isBlank()) {
            sb.append("**用户备注：** ").append(sanitizeReviewContent(request.note())).append("\n\n");
        }
        if (request.sections() != null && !request.sections().isEmpty()) {
            sb.append("## 入选章节\n\n");
            for (String section : request.sections()) {
                sb.append("- ").append(sanitizeReviewContent(section)).append("\n");
            }
            sb.append("\n");
        }
        sb.append("## 完整复盘内容\n\n");
        // #184：promote 内容脱敏——复盘含真实持仓（股数/市值/成本/现价/现金），
        // 候选文件必须替换为占位符。
        // 知识价值在 R/E 规则引用与仓位结构讨论，不在具体持仓数字。
        // 标的名保留（公开信息 + 规则引用需要标的语境）；大盘指数等公开行情不误伤。
        sb.append(sanitizeReviewContent(reviewContent));
        return sb.toString();
    }

    /**
     * §10.1（2026-10-06 设计 final）：promote 内容脱敏——六类规则族，替换真实持仓数字为占位符。
     * <p>
     * 命中面共 6 类：① 股数（书面语 + 口语变体）② 价格（含买入/卖出/成交价）
     * ③ 金额（含「现金余额」宽形态兜底，兼容「余额为零」这类无数字表述）④ 持仓规模句
     * ⑤⑥ {@code note()} / {@code sections()} 全文（由 {@link #buildPromoteContent} 逐条代入本方法）。
     * <p>
     * 只命中持仓数字，不误伤大盘指数等公开行情（不含关键词）；标的名保留（公开信息）。
     * 比例不脱（设计 V3：比例可出）。
     */
    static String sanitizeReviewContent(String content) {
        if (content == null || content.isBlank()) return content;
        String s = content;
        // ① 股数（书面语 + 口语变体 + 标的名紧贴数字）：卖出 500 股 → 卖出 N 股；有研新材600股 → 有研新材N 股
        // W-P3-20（2026-08-17）：数字正则兼容千分位逗号（1,400 此前漏脱敏）
        // P1-交易89（2026-10-06）：原「动词紧贴数字」形态对「标的名+数字+股」完全失效
        // （真实复盘「有研新材600股」「加仓中国稀土100股」整句漏脱）→ 改为「数字+股」独立匹配，动词列表不再参与
        s = s.replaceAll("[\\d,]+(?:\\.\\d+)?\\s*万?\\s*股", "N 股");
        // ② 价格：成本 1400 → 成本（已脱敏）；含买入价/卖出价/成交价
        s = s.replaceAll("(成本|现价|止损位|止损价|买入价|卖出价|成交价)\\s*[\\d,.]+", "$1（已脱敏）");
        // ③ 现金余额宽形态兜底：现金余额为零 → 现金余额（已脱敏）（带数字的钱为「5200 元」也一并吞）
        s = s.replaceAll("现金余额[^，。；\\n]*", "现金余额（已脱敏）");
        // ③ 金额：市值 14 万 → 市值（已脱敏）；含成交金额/浮动盈亏/本金/总资产
        s = s.replaceAll("(市值|成交金额|浮动盈亏|本金|总资产)\\s*[\\d,]+\\s*(?:万|千|亿)?", "$1（已脱敏）");
        // ④ 持仓规模句（动词列表不含「持仓」的形态）：持仓 14 万 → 持仓（已脱敏）
        s = s.replaceAll("持仓\\s*[\\d,.]+\\s*(?:万|千|亿)", "持仓（已脱敏）");
        return s;
    }

    /**
     * promote 落点主题（P2-交易100，2026-10-06）：{主题} 占位落到实处。
     * <p>
     * 未给/空白 → {@code 交易复盘}（与旧行为逐字兼容）；剔除路径危险字符（{@code / \ : * ? " &lt; &gt; | .}
     * 与空白）并限长 32（防越层/超长文件名）；剔完为空 → 回退默认。
     */
    static String safeTheme(String theme) {
        if (theme == null || theme.isBlank()) return "交易复盘";
        String cleaned = theme.trim().replaceAll("[\\\\/:*?\"<>|.\\s]+", "");
        if (cleaned.isBlank()) return "交易复盘";
        return cleaned.length() > 32 ? cleaned.substring(0, 32) : cleaned;
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

    /** POST /trading/review 提交响应（2026-09-07：date + status=exists|running|pending）。 */
    public record ReviewSubmitResponse(String date, String status) {}

    public record ActivityCheckResponse(String date, boolean hasActivity) {}

    /** promote 请求：note 备注 + sections 入选章节 + theme 主题（可选，落点文件名用；P2-交易100）。 */
    public record PromoteRequest(String note, List<String> sections, String theme) {}

    public record PromoteResponse(String status, String path, String message) {}
}
