package com.adaiadai.core.application;

import com.adaiadai.core.domain.trading.Position;
import com.adaiadai.core.domain.trading.PositionRepository;
import com.adaiadai.core.infrastructure.ai.interaction.AiTraceContext;
import com.adaiadai.core.infrastructure.ai.llm.LlmResponseParser;
import com.adaiadai.core.kernel.ai.AiClient;
import com.adaiadai.core.kernel.context.engine.ContextPackage;
import com.adaiadai.core.kernel.market.MarketData;
import com.adaiadai.core.kernel.market.MarketDataSource;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * TradingAdviceAppService — 持仓建议应用服务（交易模块核心定位：建议引擎）。
 * <p>
 * 编排建议流程：读用户持仓 + 实时行情 + 只读 {@code os/trading-engine/11-context/rules.md} 与
 * {@code strategy.md} → 把止损规则（R66-R80）与仓位规则（R81-R95）作为 LLM 决策硬约束注入
 * prompt → LLM 结构化生成逐票建议（suggestion / reason / rules 必须引用规则号）。
 * <p>
 * 规则匹配是硬约束：建议输出必须引用规则号；本服务不做任何"执行"动作，建议是输出不是指令。
 * <p>
 * 兜底：LLM 失败/输出不可解析时降级返回基础数据（symbol / name / position_percent 后端计算，
 * 无建议字段），不抛错——建议引擎永远返回 200，诚实优于编造。
 * <p>
 * os/trading-engine 只读：adai-core 对该目录只读（唯一例外是 promote 写 99-inbox/）。
 */
@Service
public class TradingAdviceAppService {

    private static final Logger log = LoggerFactory.getLogger(TradingAdviceAppService.class);

    /** os/trading-engine 11-context 只读路径（相对 gradle 运行 cwd services/adai-core）。 */
    static final Path RULES_PATH = Paths.get("../../os/trading-engine/11-context/rules.md")
            .toAbsolutePath().normalize();
    static final Path STRATEGY_PATH = Paths.get("../../os/trading-engine/11-context/strategy.md")
            .toAbsolutePath().normalize();

    /** 决策硬约束规则区间：止损 R66-R80 + 仓位 R81-R95（与 RFC 20260815 §0 建议类型对齐）。 */
    private static final int CONSTRAINT_RULE_MIN = 66;
    private static final int CONSTRAINT_RULE_MAX = 95;

    /** 建议 system 指令：角色 + 规则硬约束语义（生成语义，非 understand 的 JSON 摘要语义）。 */
    private static final String ADVICE_SYSTEM_PROMPT = """
            你是一个个人交易建议助手。基于用户消息中的真实持仓与实时行情，结合交易系统规则（止损 R66-R80 / 仓位 R81-R95 是决策硬约束）给出逐票建议。
            规则是决策约束：每条建议必须引用具体规则号；只依据规则与数据给建议，不做荐股、不做主观预测。
            输出必须是合法 JSON 对象，不要用 markdown 代码块围栏包裹，不要输出 JSON 以外的任何文字。
            """.strip();

    /** 输出契约（拼在 user prompt 末尾，随规则与数据一起下发）。 */
    private static final String OUTPUT_CONTRACT = """
            【输出要求】
            输出 JSON（不要 markdown 代码块围栏、不要输出其他文字）：
            {
              "advice": [
                {
                  "symbol": "股票代码（6位，必须来自上方持仓列表）",
                  "suggestion": "buy | hold | reduce | clear 之一",
                  "reason": "自然语言理由，必须引用具体规则号（如 R81）",
                  "rules": ["R66", "R81"]
                }
              ],
              "summary": "持仓总览一句话（持仓数 + 仓位结构）"
            }
            suggestion 取值语义：
            - buy：建议加仓/买入（仅在规则支持且持仓占比明显偏低时）
            - hold：正常持有
            - reduce：建议减仓（单票超仓位上限 R81-R95、触发止损信号 R66-R80 等）
            - clear：建议清仓（已跌破止损位、严重违反仓位纪律）
            rules 数组列出本建议引用的规则号（应在 R66-R95 范围内）。
            """.strip();

    private static final Pattern RULE_PATTERN = Pattern.compile(
            "\\*\\*R(\\d+)\\s+([^*\\n]+?)\\s*\\*\\*(?:\\n>\\s*([^\\n]+))?");

    private final PositionRepository positionRepository;
    private final MarketDataSource marketDataSource;
    private final AiClient aiClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public TradingAdviceAppService(PositionRepository positionRepository,
                                   MarketDataSource marketDataSource,
                                   AiClient aiClient) {
        this.positionRepository = positionRepository;
        this.marketDataSource = marketDataSource;
        this.aiClient = aiClient;
    }

    /**
     * 生成持仓建议。
     *
     * @param userId 用户 ID
     * @return 逐票建议 + 持仓总览；LLM 失败时降级为基础数据（无建议字段），不抛错
     */
    public TradingAdviceResponse generateAdvice(String userId) {
        List<Position> positions = positionRepository.findAll(userId);
        if (positions == null || positions.isEmpty()) {
            return new TradingAdviceResponse(List.of(), "当前空仓，无持仓建议。");
        }

        // 1. 实时行情（数据源接口约定"安全"：异常返回空 Map，这里再兜一层防御）
        Map<String, MarketData> quotes;
        try {
            quotes = marketDataSource.quote(positions.stream().map(Position::symbol).toList());
        } catch (Exception e) {
            log.warn("持仓建议：行情查询失败，将基于持仓存储价 | {}", e.getMessage());
            quotes = Map.of();
        }
        if (quotes == null) quotes = Map.of();

        List<PositionView> views = buildPositionViews(positions, quotes);

        // 2. 只读 os/trading-engine 规则与策略，抽取 R66-R95 作为决策硬约束
        String rulesText = readKnowledgeFile(RULES_PATH);
        String strategyText = readKnowledgeFile(STRATEGY_PATH);
        List<RuleInfo> constraintRules = parseRules(rulesText).stream()
                .filter(r -> r.number() >= CONSTRAINT_RULE_MIN && r.number() <= CONSTRAINT_RULE_MAX)
                .sorted(Comparator.comparingInt(RuleInfo::number))
                .toList();

        // 3. LLM 结构化生成（失败/不可解析 → 降级基础数据）
        try {
            String prompt = buildPrompt(views, constraintRules, strategyOverview(strategyText));
            ContextPackage ctx = ContextPackage.simple(
                    "trading", null, "持仓建议", prompt,
                    List.of("trading", "建议"), prompt);
            AiTraceContext.set(userId, null, null, "trading_advice");
            String raw = aiClient.generate(ctx, ADVICE_SYSTEM_PROMPT);
            TradingAdviceResponse response = parseLlmAdvice(raw, views);
            log.info("持仓建议生成完成 | userId={} | 持仓数={} | 建议数={}",
                    userId, views.size(), response.advice().size());
            return response;
        } catch (Exception e) {
            log.warn("持仓建议 LLM 生成失败，降级返回基础数据 | userId={} | {}", userId, e.getMessage());
            return fallback(views);
        }
    }

    // ── LLM 输出解析 ──

    private TradingAdviceResponse parseLlmAdvice(String raw, List<PositionView> views) throws Exception {
        String json = extractJson(raw);
        if (json == null) {
            throw new IllegalStateException("LLM 输出中未找到 JSON");
        }
        JsonNode root = objectMapper.readTree(json);
        String summary = root.path("summary").asText("");
        if (summary.isBlank()) summary = buildFallbackSummary(views);

        List<TradingAdviceItem> items = new ArrayList<>();
        JsonNode adviceNode = root.get("advice");
        if (adviceNode != null && adviceNode.isArray()) {
            for (JsonNode node : adviceNode) {
                PositionView view = findBySymbol(views, node.path("symbol").asText(""));
                // 只保留持仓中真实存在的标的（防 LLM 幻觉输出不存在的票）
                if (view == null) continue;
                items.add(new TradingAdviceItem(
                        view.symbol(), view.name(), view.positionPercent(),
                        normalizeSuggestion(node.path("suggestion").asText("")),
                        node.path("reason").asText(""),
                        parseRulesArray(node.get("rules"))
                ));
            }
        }

        // LLM 漏掉的持仓补齐为基础数据（无建议字段）——保证 advice 与持仓一一对应
        for (PositionView view : views) {
            if (items.stream().noneMatch(i -> i.symbol().equals(view.symbol()))) {
                items.add(new TradingAdviceItem(
                        view.symbol(), view.name(), view.positionPercent(), null, null, List.of()));
            }
        }
        return new TradingAdviceResponse(items, summary);
    }

    private String extractJson(String text) {
        if (text == null || text.isBlank()) return null;
        String trimmed = text.strip();
        if (trimmed.startsWith("{")) return trimmed;
        String stripped = LlmResponseParser.stripCodeFences(text);
        if (stripped != null && stripped.startsWith("{")) return stripped;
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) return text.substring(start, end + 1);
        return null;
    }

    private String normalizeSuggestion(String raw) {
        if (raw == null) return null;
        String s = raw.strip().toLowerCase();
        return switch (s) {
            case "buy", "加仓", "买入" -> "buy";
            case "hold", "keep", "持有", "继续持有" -> "hold";
            case "reduce", "减仓", "减持" -> "reduce";
            case "clear", "sell", "清仓", "卖出" -> "clear";
            default -> null;
        };
    }

    private List<String> parseRulesArray(JsonNode node) {
        if (node == null || !node.isArray()) return List.of();
        List<String> rules = new ArrayList<>();
        for (JsonNode r : node) {
            if (r.isTextual() && !r.asText().isBlank()) {
                rules.add(r.asText().strip());
            }
        }
        return rules;
    }

    // ── 兜底 ──

    private TradingAdviceResponse fallback(List<PositionView> views) {
        List<TradingAdviceItem> items = views.stream()
                .map(v -> new TradingAdviceItem(v.symbol(), v.name(), v.positionPercent(), null, null, List.of()))
                .toList();
        return new TradingAdviceResponse(items, buildFallbackSummary(views));
    }

    private String buildFallbackSummary(List<PositionView> views) {
        BigDecimal totalValue = views.stream()
                .map(PositionView::marketValue)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return "当前持有 %d 只标的，总市值 %s 元（建议暂不可用，请稍后重试）"
                .formatted(views.size(), totalValue.setScale(2, RoundingMode.HALF_UP).toPlainString());
    }

    // ── 数据准备 ──

    /** 用实时行情价（缺失时用持仓存储价）计算持仓占比、盈亏等建议所需字段。 */
    private List<PositionView> buildPositionViews(List<Position> positions, Map<String, MarketData> quotes) {
        Map<String, BigDecimal> effectivePrices = new LinkedHashMap<>();
        BigDecimal totalValue = BigDecimal.ZERO;
        for (Position p : positions) {
            BigDecimal price = effectivePrice(p, quotes.get(p.symbol()));
            effectivePrices.put(p.symbol(), price);
            totalValue = totalValue.add(price.multiply(BigDecimal.valueOf(p.quantity())));
        }

        List<PositionView> views = new ArrayList<>();
        for (Position p : positions) {
            BigDecimal price = effectivePrices.get(p.symbol());
            BigDecimal marketValue = price.multiply(BigDecimal.valueOf(p.quantity()));
            BigDecimal positionPercent = totalValue.compareTo(BigDecimal.ZERO) > 0
                    ? marketValue.multiply(BigDecimal.valueOf(100)).divide(totalValue, 2, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO;
            MarketData quote = quotes.get(p.symbol());
            String name = (quote != null && quote.name() != null && !quote.name().isBlank())
                    ? quote.name() : p.name();
            BigDecimal changePercent = (quote != null && quote.changePercent() != null)
                    ? quote.changePercent() : null;
            BigDecimal pnl = marketValue.subtract(p.costValue());
            BigDecimal pnlPercent = p.avgCost().compareTo(BigDecimal.ZERO) == 0
                    ? BigDecimal.ZERO
                    : price.subtract(p.avgCost()).divide(p.avgCost(), 4, RoundingMode.HALF_UP)
                            .multiply(BigDecimal.valueOf(100));
            views.add(new PositionView(p.symbol(), name, p.quantity(), marketValue, positionPercent,
                    p.avgCost(), price, changePercent, pnl, pnlPercent,
                    p.stopLossPrice(), p.entryDate(), p.buyPoint()));
        }
        return views;
    }

    private BigDecimal effectivePrice(Position position, MarketData quote) {
        if (quote != null && quote.price() != null && quote.price().compareTo(BigDecimal.ZERO) > 0) {
            return quote.price();
        }
        return position.currentPrice();
    }

    private PositionView findBySymbol(List<PositionView> views, String symbol) {
        return views.stream().filter(v -> v.symbol().equals(symbol)).findFirst().orElse(null);
    }

    // ── os/trading-engine 只读 ──

    private String readKnowledgeFile(Path path) {
        try {
            if (Files.isReadable(path)) {
                return Files.readString(path, StandardCharsets.UTF_8);
            }
            log.warn("建议引擎：os/trading-engine 知识文件不可读 | path={}", path);
        } catch (Exception e) {
            log.warn("建议引擎：读取 os/trading-engine 知识文件失败 | path={} | {}", path, e.getMessage());
        }
        return null;
    }

    /** strategy.md 只取"版本信息 + 交易体系总纲"（第一个大节之前），控制 prompt 体积。 */
    private String strategyOverview(String text) {
        if (text == null || text.isBlank()) return null;
        StringBuilder sb = new StringBuilder();
        for (String line : text.split("\n")) {
            if (line.startsWith("## 第一步")) break;
            sb.append(line).append("\n");
            if (sb.length() > 2000) break;
        }
        String overview = sb.toString().strip();
        return overview.isEmpty() ? null : overview;
    }

    /** 解析 rules.md 规则条目：{@code **R{n} 标题** + > 描述}（与 TradingController 同口径）。 */
    private List<RuleInfo> parseRules(String content) {
        if (content == null || content.isBlank()) return List.of();
        Matcher matcher = RULE_PATTERN.matcher(content);
        List<RuleInfo> rules = new ArrayList<>();
        while (matcher.find()) {
            rules.add(new RuleInfo(
                    Integer.parseInt(matcher.group(1)),
                    matcher.group(2).strip(),
                    matcher.group(3) != null ? matcher.group(3).strip() : ""
            ));
        }
        return rules;
    }

    private void appendRule(StringBuilder sb, RuleInfo rule) {
        sb.append("**R").append(rule.number()).append(" ").append(rule.title()).append("**\n");
        if (!rule.detail().isEmpty()) {
            sb.append("> ").append(rule.detail()).append("\n");
        }
    }

    /** 组装 user prompt：规则硬约束 + 止损硬判定 + 体系总纲 + 持仓/行情（含止损/入场/买点）+ 输出契约。 */
    private String buildPrompt(List<PositionView> views, List<RuleInfo> constraintRules, String strategyOverview) {
        StringBuilder sb = new StringBuilder();
        sb.append("【决策约束——止损规则 R66-R80】\n");
        for (RuleInfo rule : constraintRules) {
            if (rule.number() <= 80) appendRule(sb, rule);
        }
        sb.append("\n【决策约束——仓位规则 R81-R95】\n");
        for (RuleInfo rule : constraintRules) {
            if (rule.number() > 80) appendRule(sb, rule);
        }
        // RFC 20260816 §3.1：止损位/入场日期/买点已注入下方持仓数据，以下为可执行的硬判定口径
        sb.append("\n【止损硬判定（数据已注入，必须按此判定）】\n");
        sb.append("- 现价 < stopLossPrice（止损位）→ 判定已跌破止损位，suggestion=clear（R66）\n");
        sb.append("- 入场后 N 天未涨/持续亏损 → R53 候选（reduce/clear 参考）\n");
        sb.append("- 买点关联应对：B1→持股/白线持有；B2/B3/SB1→S1 就走（R120）\n");
        if (strategyOverview != null) {
            sb.append("\n【交易体系总纲（strategy.md v87）】\n").append(strategyOverview).append("\n");
        }
        sb.append("\n【当前持仓与实时行情】\n");
        for (int i = 0; i < views.size(); i++) {
            PositionView v = views.get(i);
            sb.append(i + 1).append(". ").append(v.name()).append(" (").append(v.symbol()).append(")")
                    .append(" | 持仓占比 ").append(v.positionPercent().setScale(1, RoundingMode.HALF_UP)).append("%")
                    .append(" | 成本 ").append(v.avgCost().stripTrailingZeros().toPlainString())
                    .append(" | 现价 ").append(v.currentPrice().stripTrailingZeros().toPlainString());
            if (v.changePercent() != null) {
                sb.append(" | 当日涨跌 ").append(v.changePercent().setScale(2, RoundingMode.HALF_UP)).append("%");
            }
            sb.append(" | 盈亏 ").append(v.pnl().setScale(2, RoundingMode.HALF_UP)).append(" 元（")
                    .append(v.pnlPercent().setScale(2, RoundingMode.HALF_UP)).append("%）");
            // RFC 20260816：注入用户提供的止损位/入场日期（入场第几天）/买点 → clear 判定有数据可判
            sb.append(" | 止损位 ").append(v.stopLossPrice() != null
                    ? v.stopLossPrice().stripTrailingZeros().toPlainString() : "未设置");
            sb.append(" | 入场 ").append(entryLabel(v));
            sb.append(" | 买点 ").append(v.buyPoint() != null ? v.buyPoint() : "未知");
            sb.append("\n");
        }
        sb.append("\n").append(OUTPUT_CONTRACT);
        return sb.toString();
    }

    /** 入场标签：有入场日期 → 「yyyy-MM-dd（入场第 N 天）」；无 → 「入场日期未知」。 */
    private String entryLabel(PositionView v) {
        if (v.entryDate() == null) return "入场日期未知";
        long days = Math.max(ChronoUnit.DAYS.between(v.entryDate(), LocalDate.now()), 0);
        return v.entryDate() + "（入场第 " + days + " 天）";
    }

    // ── DTO ──

    /** 单票建议。position_percent 由后端按持仓市值/总市值计算（确定性），suggestion/reason/rules 来自 LLM。 */
    public record TradingAdviceItem(
            String symbol,
            String name,
            @JsonProperty("position_percent") BigDecimal positionPercent,
            String suggestion,
            String reason,
            List<String> rules
    ) {}

    /** 持仓建议响应：逐票建议 + 持仓总览一句话。 */
    public record TradingAdviceResponse(
            List<TradingAdviceItem> advice,
            String summary
    ) {}

    /** 建议引擎内部持仓视图（行情价 + 派生指标 + 用户提供的止损/入场/买点，供 prompt 与输出共用）。 */
    private record PositionView(
            String symbol,
            String name,
            int quantity,
            BigDecimal marketValue,
            BigDecimal positionPercent,
            BigDecimal avgCost,
            BigDecimal currentPrice,
            BigDecimal changePercent,
            BigDecimal pnl,
            BigDecimal pnlPercent,
            BigDecimal stopLossPrice,
            LocalDate entryDate,
            String buyPoint
    ) {}

    /** 从 rules.md 解析出的真实规则条目。 */
    private record RuleInfo(int number, String title, String detail) {}
}
