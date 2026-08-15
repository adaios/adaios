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
            Integer volume
    ) {}

    private static final Pattern TRADE_PATTERN = Pattern.compile(
            "(买(?:入|了|进)?|卖(?:出|了|掉)?)"                         // 1 动词
                    + "\\s*([\\u4e00-\\u9fa5A-Za-z]{2,12}|\\d{6})?"   // 2 名称/代码（可选，位置1）
                    + "\\s*(\\d+)\\s*(?:股|手|份)?"                   // 3 数量
                    + "\\s*([\\u4e00-\\u9fa5A-Za-z]{2,12}|\\d{6})?"   // 4 名称/代码（可选，位置2）
                    + "\\s*[@＠]?\\s*(\\d+(?:\\.\\d+)?)",        // 5 价格
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SYMBOL_PATTERN = Pattern.compile("\\d{6}");

    /**
     * 解析一句话交易。
     */
    public ParseResult parse(String userId, String text) {
        if (text == null || text.isBlank()) {
            return new ParseResult(false, null, null, null, null, null);
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
                - 无法确定 direction 或缺少关键数字时 matched=false，其余字段 null
                - 必须包含 matched 字段

                用户输入：%s

                输出 JSON 格式：
                {"matched": true, "symbol": "000725", "name": "京东方A", "direction": "BUY", "price": 5.2, "volume": 1000}
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
            return new ParseResult(false, null, null, null, null, null);
        }
        if (node == null || !node.has("matched") || !node.get("matched").asBoolean(false)) {
            return new ParseResult(false, null, null, null, null, null);
        }
        String direction = node.hasNonNull("direction") ? node.get("direction").asText().trim().toUpperCase(Locale.ROOT) : null;
        if (!"BUY".equals(direction) && !"SELL".equals(direction)) {
            return new ParseResult(false, null, null, null, null, null);
        }
        BigDecimal price = node.hasNonNull("price") ? node.get("price").decimalValue() : null;
        Integer volume = node.hasNonNull("volume") ? node.get("volume").asInt() : null;
        if (price == null || price.compareTo(BigDecimal.ZERO) <= 0 || volume == null || volume <= 0) {
            return new ParseResult(false, null, null, null, null, null);
        }
        String symbol = node.hasNonNull("symbol") && !node.get("symbol").asText().isBlank() ? node.get("symbol").asText().trim() : null;
        String name = node.hasNonNull("name") && !node.get("name").asText().isBlank() ? node.get("name").asText().trim() : null;
        return new ParseResult(true, symbol, name, direction, price, volume);
    }

    private ParseResult parseWithRegex(String text) {
        Matcher m = TRADE_PATTERN.matcher(text);
        if (!m.find()) {
            return new ParseResult(false, null, null, null, null, null);
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
            return new ParseResult(false, null, null, null, null, null);
        }
        BigDecimal price;
        try {
            price = new BigDecimal(m.group(5));
        } catch (NumberFormatException e) {
            return new ParseResult(false, null, null, null, null, null);
        }
        if (price.compareTo(BigDecimal.ZERO) <= 0 || volume <= 0) {
            return new ParseResult(false, null, null, null, null, null);
        }
        return new ParseResult(true, symbol, name, direction, price, volume);
    }
}
