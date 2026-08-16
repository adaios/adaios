package com.adaiadai.core.application;

import com.adaiadai.core.infrastructure.ai.interaction.AiTraceContext;
import com.adaiadai.core.infrastructure.ai.llm.LlmResponseParser;
import com.adaiadai.core.kernel.context.engine.ContextPackage;
import com.adaiadai.core.kernel.ai.AiClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * TradingParseAppService — 一句话交易解析应用服务（RFC 20260815 通道 A）。
 * <p>
 * 把用户自然语言（「买了 1000 股京东方 @5.2」）结构化为 {@link ParseResult}（symbol/name/direction/price/volume）。
 * LLM 结构化优先，失败降级正则兜底；仍无法解析 → matched=false，前端转精确表单（正确性由确认步兜底）。
 * <p>
 * 本服务只解析不落库——写入仍走 {@code POST /trading/trades}（同一确认链路，正确性在确认步拦截）。
 */
@Service
public class TradingParseAppService {

    private static final Logger log = LoggerFactory.getLogger(TradingParseAppService.class);

    private final AiClient aiClient;
    private final ObjectMapper objectMapper;

    public TradingParseAppService(AiClient aiClient, ObjectMapper objectMapper) {
        this.aiClient = aiClient;
        this.objectMapper = objectMapper;
    }

    /** 解析结果：matched=false 时其余字段可为 null（前端转精确表单）。 */
    public record ParseResult(
            boolean matched,
            String symbol,
            String name,
            String direction, // "BUY" / "SELL"
            BigDecimal price,
            Integer volume,
            BigDecimal stopLossPrice,
            String buyPoint,
            BigDecimal targetPrice,
            String reason
    ) {
        /** 未匹配结果（matched=false，其余字段全 null）。 */
        public static ParseResult unmatched() {
            return new ParseResult(false, null, null, null, null, null, null, null, null, null);
        }
    }

    private static final Pattern TRADE_PATTERN = Pattern.compile(
            "(买(?:入|了|进)?|卖(?:出|了|掉)?)"                         // 1 动词
                    + "\\s*([\\u4e00-\\u9fa5A-Za-z]{2,12}|\\d{6})?"   // 2 名称/代码（可选，位置1）
                    + "\\s*(\\d+)\\s*(?:股|手|份)?"                   // 3 数量
                    + "\\s*([\\u4e00-\\u9fa5A-Za-z]{2,12}|\\d{6})?"   // 4 名称/代码（可选，位置2）
                    + "\\s*[@＠]?\\s*(\\d+(?:\\.\\d+)?)",        // 5 价格
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SYMBOL_PATTERN = Pattern.compile("\\d{6}");
    /** 正则兜底：止损位（RFC 20260816 §4.3：「止损 Z」→ stopLossPrice）。 */
    private static final Pattern STOP_LOSS_PATTERN = Pattern.compile("止损\\s*([\\d.]+)");
    /** 正则兜底：买点（RFC 20260816 §4.3：「，B1/B2/B3/SB1」→ buyPoint）。 */
    private static final Pattern BUY_POINT_PATTERN = Pattern.compile("[，,]\\s*(B1|B2|B3|SB1)(?![A-Za-z0-9])");

    /**
     * 解析一句话交易。
     */
    public ParseResult parse(String userId, String text) {
        if (text == null || text.isBlank()) {
            return ParseResult.unmatched();
        }
        String trimmed = text.trim();

        // 1) LLM 结构化优先
        try {
            ParseResult llm = parseWithLlm(userId, trimmed);
            if (llm != null && llm.matched()) {
                return llm;
            }
        } catch (Exception e) {
            log.warn("一句话交易 LLM 解析失败，降级正则 | {}", e.getMessage());
        }

        // 2) 正则兜底
        return parseWithRegex(trimmed);
    }

    private ParseResult parseWithLlm(String userId, String text) {
        String prompt = """
                你是 AdaiOS 的交易记录解析器。把用户一句话交易意图结构化为 JSON。
                只输出 JSON，不要任何其他文字。

                规则：
                - direction 只允许 "BUY" 或 "SELL"（买入=BUY，卖出=SELL）
                - price 是每股价格（数字）
                - volume 是数量（整数，股数）
                - symbol 是 6 位代码（若有）；name 是股票名称（若有）；都没有则 null
                - stopLossPrice 是止损位（数字，若有；买入通常必填）
                - buyPoint 是买点类型（B1/B2/B3/SB1/暴力特噗/深水炸弹/单针/其他，若有）
                - targetPrice 是目标价（数字，若有）
                - reason 是交易原因/预期（文本，若有）
                - 无法确定 direction 或缺少关键数字时 matched=false，其余字段 null
                - 必须包含 matched 字段

                用户输入：%s

                输出 JSON 格式：
                {"matched": true, "symbol": "000725", "name": "京东方A", "direction": "BUY", "price": 5.2, "volume": 1000, "stopLossPrice": 4.9, "buyPoint": "B1", "targetPrice": 6.0, "reason": "突破买入"}
                """.formatted(text);

        ContextPackage ctx = ContextPackage.simple(
                "trading", null, "一句话交易解析", prompt,
                List.of("trading", "parse"), prompt);
        AiTraceContext.set(userId, null, null, "trading_parse");
        String raw = aiClient.generate(ctx, "你是交易记录解析器，只输出 JSON。");
        JsonNode node;
        try {
            node = objectMapper.readTree(LlmResponseParser.stripCodeFences(raw));
        } catch (Exception e) {
            log.warn("一句话交易 LLM 输出不可解析 | {}", e.getMessage());
            return ParseResult.unmatched();
        }
        if (node == null || !node.has("matched") || !node.get("matched").asBoolean(false)) {
            return ParseResult.unmatched();
        }
        String direction = node.hasNonNull("direction") ? node.get("direction").asText().trim().toUpperCase(Locale.ROOT) : null;
        if (!"BUY".equals(direction) && !"SELL".equals(direction)) {
            return ParseResult.unmatched();
        }
        BigDecimal price = node.hasNonNull("price") ? node.get("price").decimalValue() : null;
        Integer volume = node.hasNonNull("volume") ? node.get("volume").asInt() : null;
        if (price == null || price.compareTo(BigDecimal.ZERO) <= 0 || volume == null || volume <= 0) {
            return ParseResult.unmatched();
        }
        String symbol = node.hasNonNull("symbol") && !node.get("symbol").asText().isBlank() ? node.get("symbol").asText().trim() : null;
        String name = node.hasNonNull("name") && !node.get("name").asText().isBlank() ? node.get("name").asText().trim() : null;
        BigDecimal stopLossPrice = node.hasNonNull("stopLossPrice") ? node.get("stopLossPrice").decimalValue() : null;
        String buyPoint = node.hasNonNull("buyPoint") && !node.get("buyPoint").asText().isBlank()
                ? node.get("buyPoint").asText().trim() : null;
        BigDecimal targetPrice = node.hasNonNull("targetPrice") ? node.get("targetPrice").decimalValue() : null;
        String reason = node.hasNonNull("reason") && !node.get("reason").asText().isBlank()
                ? node.get("reason").asText().trim() : null;
        return new ParseResult(true, symbol, name, direction, price, volume,
                stopLossPrice, buyPoint, targetPrice, reason);
    }

    private ParseResult parseWithRegex(String text) {
        Matcher m = TRADE_PATTERN.matcher(text);
        if (!m.find()) {
            return ParseResult.unmatched();
        }
        String verb = m.group(1);
        String position1 = m.group(2);
        String position2 = m.group(4);
        String direction = verb.startsWith("买") ? "BUY" : "SELL";

        // 名称/代码取位置2优先（数量后），否则位置1
        String symbolOrName = (position2 != null && !position2.isBlank()) ? position2 : position1;
        String symbol = null;
        String name = null;
        if (symbolOrName != null && !symbolOrName.isBlank()) {
            if (SYMBOL_PATTERN.matcher(symbolOrName).matches()) {
                symbol = symbolOrName;
            } else {
                name = symbolOrName;
            }
        }

        Integer volume;
        try {
            volume = Integer.parseInt(m.group(3));
        } catch (NumberFormatException e) {
            return ParseResult.unmatched();
        }
        BigDecimal price;
        try {
            price = new BigDecimal(m.group(5));
        } catch (NumberFormatException e) {
            return ParseResult.unmatched();
        }
        if (price.compareTo(BigDecimal.ZERO) <= 0 || volume <= 0) {
            return ParseResult.unmatched();
        }

        // 正则兜底：止损/买点（RFC 20260816 §4.3）
        BigDecimal stopLossPrice = null;
        Matcher stopLossMatcher = STOP_LOSS_PATTERN.matcher(text);
        if (stopLossMatcher.find()) {
            try {
                stopLossPrice = new BigDecimal(stopLossMatcher.group(1));
            } catch (NumberFormatException e) {
                stopLossPrice = null;
            }
        }
        String buyPoint = null;
        Matcher buyPointMatcher = BUY_POINT_PATTERN.matcher(text);
        if (buyPointMatcher.find()) {
            buyPoint = buyPointMatcher.group(1);
        }

        return new ParseResult(true, symbol, name, direction, price, volume,
                stopLossPrice, buyPoint, null, null);
    }
}
