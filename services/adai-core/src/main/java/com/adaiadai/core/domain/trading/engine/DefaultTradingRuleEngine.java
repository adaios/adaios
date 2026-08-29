package com.adaiadai.core.domain.trading.engine;

import com.adaiadai.core.domain.trading.TradingRuleSettings;
import com.adaiadai.core.domain.trading.TradingRuleSettingsPort;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * DefaultTradingRuleEngine — 规则引擎默认实现（G-3 能力抽离）。
 * <p>
 * 判定口径（与 {@code os/trading-engine/knowledge/context/rules.md} 一致）：
 * <ul>
 *   <li>R66 只输一根K线：收盘跌破止损位就走 → 现价 &lt; 止损位 → BREACHED</li>
 *   <li>R68 入场即设止损：止损位缺失不硬判（OK，无据可判）</li>
 *   <li>R81 100万以下分4-5个仓位：单票 1/4~1/5（上限 25%）→ 占比 &gt; 上限 → OVER_WEIGHT</li>
 * </ul>
 * 引擎只产出判定信号（verdict），建议是输出不是执行——不做任何交易动作。
 * <p>
 * 第三阶段（用户规则层，蓝图 trading-plugin-architecture.md §六）：仓位上限从
 * {@code data/{userId}/trading/rules.yaml} 的 {@code params.positionLimitPercent} 读取
 * （按 userId 隔离，无规则/损坏 → 默认 25%）。R66 止损判定本身无阈值参数，判定口径不变。
 */
@Component
public class DefaultTradingRuleEngine implements TradingRuleEngine {

    /** R81 单票仓位上限默认：100万以下分 4-5 仓，单票 1/4~1/5 → 25%。 */
    static final BigDecimal DEFAULT_R81_MAX_POSITION_PERCENT = new BigDecimal("25");

    /** rules.md 规则条目格式：{@code **R{n} 标题** + > 描述}。 */
    private static final Pattern RULE_PATTERN = Pattern.compile(
            "\\*\\*R(\\d+)\\s+([^*\\n]+?)\\s*\\*\\*(?:\\n>\\s*([^\\n]+))?");

    private final TradingRuleSettingsPort settingsRepository;

    public DefaultTradingRuleEngine(TradingRuleSettingsPort settingsRepository) {
        this.settingsRepository = settingsRepository;
    }

    @Override
    public StopLossResult evaluateStopLoss(String userId, BigDecimal currentPrice, BigDecimal stopLossPrice) {
        if (stopLossPrice == null) {
            // R68：止损位未设置 → 无据可判，不硬判（建议引擎已在买入时强制用户填写）
            return new StopLossResult(StopLossVerdict.OK, "R68",
                    "止损位未设置，无法判定（R68 入场即设止损）");
        }
        if (currentPrice == null || currentPrice.compareTo(BigDecimal.ZERO) <= 0) {
            return new StopLossResult(StopLossVerdict.OK, "R66",
                    "现价不可用，无法判定（R66 只输一根K线）");
        }
        if (currentPrice.compareTo(stopLossPrice) < 0) {
            return new StopLossResult(StopLossVerdict.BREACHED, "R66",
                    "现价 " + currentPrice.stripTrailingZeros().toPlainString()
                            + " < 止损位 " + stopLossPrice.stripTrailingZeros().toPlainString()
                            + "，已跌破止损位（R66，现价口径）");
        }
        return new StopLossResult(StopLossVerdict.OK, "R66",
                "现价未跌破止损位（R66，现价口径）");
    }

    @Override
    public PositionResult evaluatePosition(String userId, BigDecimal positionPercent) {
        if (positionPercent == null) {
            return new PositionResult(PositionVerdict.OK, "R81", "持仓占比不可用，无法判定");
        }
        PositionLimit limit = positionLimitPercent(userId);
        // P2-7（2026-08-30 审查）：文案区分「用户规则」vs「默认」——
        // 原「（默认 25%，用户规则可调）」在用户已设 40% 时仍显示，误导
        String limitDesc = limit.fromUserRule
                ? "你的仓位上限 " + limit.value.stripTrailingZeros().toPlainString() + "%"
                : "默认仓位上限 " + limit.value.stripTrailingZeros().toPlainString() + "%";
        if (positionPercent.compareTo(limit.value) > 0) {
            return new PositionResult(PositionVerdict.OVER_WEIGHT, "R81",
                    "持仓占比 " + positionPercent.setScale(1, java.math.RoundingMode.HALF_UP).toPlainString()
                            + "% 超" + limitDesc);
        }
        return new PositionResult(PositionVerdict.OK, "R81",
                "持仓占比未超" + limitDesc);
    }

    /** 用户仓位上限（rules.yaml params.positionLimitPercent，缺失/损坏 → 默认 25%）。 */
    private PositionLimit positionLimitPercent(String userId) {
        if (userId == null) return new PositionLimit(DEFAULT_R81_MAX_POSITION_PERCENT, false);
        TradingRuleSettings settings = cachedSettings(userId);
        boolean fromUserRule = settingsRepository.exists(userId);
        return new PositionLimit(settings.positionLimitPercent(), fromUserRule);
    }

    /** P2-3（2026-08-30 审查）：per-user TTL 缓存（5 秒）——建议引擎每持仓调 evaluatePosition
     * 原每次读盘+YAML 解析（10 持仓 = 10 次 IO）；TTL 内复用，PUT 后 ≤5 秒生效（可接受）。 */
    private static final long SETTINGS_TTL_MILLIS = 5000;
    private final java.util.Map<String, CachedSettings> settingsCache = new java.util.concurrent.ConcurrentHashMap<>();

    private TradingRuleSettings cachedSettings(String userId) {
        long now = System.currentTimeMillis();
        CachedSettings cached = settingsCache.get(userId);
        if (cached != null && now - cached.cachedAt < SETTINGS_TTL_MILLIS) {
            return cached.settings;
        }
        TradingRuleSettings fresh = settingsRepository.findByUser(userId);
        settingsCache.put(userId, new CachedSettings(fresh, now));
        return fresh;
    }

    private record CachedSettings(TradingRuleSettings settings, long cachedAt) {}

    /** 仓位上限 + 是否来自用户规则（默认值兜底 vs 用户自定义，P2-7 文案区分）。 */
    private record PositionLimit(BigDecimal value, boolean fromUserRule) {}

    @Override
    public List<RuleEntry> parseRules(String content) {
        if (content == null || content.isBlank()) return List.of();
        Matcher matcher = RULE_PATTERN.matcher(content);
        List<RuleEntry> rules = new ArrayList<>();
        while (matcher.find()) {
            rules.add(new RuleEntry(
                    Integer.parseInt(matcher.group(1)),
                    matcher.group(2).strip(),
                    matcher.group(3) != null ? matcher.group(3).strip() : ""
            ));
        }
        return rules;
    }
}
