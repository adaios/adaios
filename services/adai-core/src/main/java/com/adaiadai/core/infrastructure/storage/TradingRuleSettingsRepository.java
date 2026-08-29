package com.adaiadai.core.infrastructure.storage;

import com.adaiadai.core.domain.trading.TradingRuleSettings;
import com.adaiadai.core.domain.trading.TradingRuleSettingsPort;
import com.adaiadai.core.kernel.storage.FileStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.math.BigDecimal;
import java.util.Map;

/**
 * TradingRuleSettingsRepository — 交易规则参数化配置持久化（第三阶段：规则层按用户隔离）。
 * <p>
 * 文件 {@code data/{userId}/trading/rules.yaml}（File First，可导入导出）：
 * <pre>
 * formatVersion: 1
 * params:
 *   positionLimitPercent: 25
 *   defaultStopLossRatio: 0.93
 *   givebackPeakPct: 20
 *   givebackRatioPct: 50
 *   shortOverdueDays: 5
 * </pre>
 * 缺失/损坏/非法值 → 默认值兜底（fail-closed，不阻断交易功能）；写入为原子（FileStorage 覆盖语义）。
 * <p>
 * 设计参照：PushSettingsRepository（per-user 配置 + 默认值兜底）+ 蓝图 trading-plugin-architecture.md §六。
 */
@Repository
public class TradingRuleSettingsRepository implements TradingRuleSettingsPort {

    private static final Logger log = LoggerFactory.getLogger(TradingRuleSettingsRepository.class);
    private static final String RULES_PATH = "trading/rules.yaml";

    /** P2-1（2026-08-30 审查）：per-user 条带锁（固定 16 条带，对齐 P2-交易28 锁池模式）——
     * 并发 PUT 读-改-写原子化，防后写覆盖先写丢更新（pitfalls「save 无锁」复发信号）。 */
    private final Object[] lockStripes = new Object[16];

    private final FileStorage fileStorage;
    private final Yaml yaml;

    public TradingRuleSettingsRepository(FileStorage fileStorage) {
        this.fileStorage = fileStorage;
        for (int i = 0; i < lockStripes.length; i++) lockStripes[i] = new Object();
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        options.setMaxAliasesForCollections(50);
        this.yaml = new Yaml(new SafeConstructor(options));
    }

    /** userId → 条带锁（固定条带，防 #179 任意 userId 撑爆 map）。 */
    private Object lockFor(String userId) {
        return lockStripes[(userId != null ? userId.hashCode() : 0) & (lockStripes.length - 1)];
    }

    /** 读取用户规则参数配置；无文件/损坏/非法 → 默认值。 */
    public TradingRuleSettings findByUser(String userId) {
        String content = fileStorage.read(userId, RULES_PATH);
        if (content == null || content.isBlank()) return TradingRuleSettings.defaults();
        try {
            Object parsed = yaml.load(content);
            if (!(parsed instanceof Map<?, ?> root)) return TradingRuleSettings.defaults();
            Object paramsObj = root.get("params");
            if (!(paramsObj instanceof Map<?, ?> params)) return TradingRuleSettings.defaults();
            return fromParams(params);
        } catch (Exception e) {
            log.warn("读取交易规则配置失败（回落默认）| userId={} | {}", userId, e.getMessage());
            return TradingRuleSettings.defaults();
        }
    }

    /**
     * 保存用户规则参数配置（YAML 序列化）。
     * <p>
     * P0-1（2026-08-30 审查）：写盘失败**必须抛错**（不再 log.warn 吞掉）——
     * 否则 Controller 返回「updated=true」而文件未写，用户以为生效实际丢失（信任炸弹）。
     * <p>
     * P2-1（2026-08-30 审查）：per-user 条带锁——并发 PUT 读-改-写原子化，防丢更新。
     * <p>
     * P2-5（2026-08-30 审查）：**读改写保留未知键**——只更新 params 键，不动
     * rules.yaml 里未来的 rules/signals/behaviors 键与注释（原固定模板整文件覆盖会抹掉它们）。
     *
     * @throws StorageException 写盘失败
     */
    public void save(String userId, TradingRuleSettings settings) {
        synchronized (lockFor(userId)) {
            try {
                // P2-5：读原文件保留未知键（无文件/损坏 → 新建仅 params）
                Map<String, Object> root = new java.util.LinkedHashMap<>();
                String existing = fileStorage.read(userId, RULES_PATH);
                if (existing != null && !existing.isBlank()) {
                    Object parsed = yaml.load(existing);
                    if (parsed instanceof Map<?, ?> m) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> loaded = (Map<String, Object>) m;
                        root.putAll(loaded);
                    }
                }
                root.put("formatVersion", 1);
                // 更新 params 键（保留 params 内其它未知键）
                @SuppressWarnings("unchecked")
                Map<String, Object> params = (Map<String, Object>) root.computeIfAbsent("params",
                        k -> new java.util.LinkedHashMap<String, Object>());
                params.put("positionLimitPercent", settings.positionLimitPercent().toPlainString());
                params.put("defaultStopLossRatio", settings.defaultStopLossRatio().toPlainString());
                params.put("givebackPeakPct", settings.givebackPeakPct().toPlainString());
                params.put("givebackRatioPct", settings.givebackRatioPct().toPlainString());
                params.put("shortOverdueDays", settings.shortOverdueDays());
                params.put("soldStopLossPct", settings.soldStopLossPct());
                params.put("soldShortHoldDays", settings.soldShortHoldDays());
                params.put("buyPullbackPct", settings.buyPullbackPct());
                params.put("buyShrinkRatio", settings.buyShrinkRatio());
                params.put("buyKdjLow", settings.buyKdjLow());
                params.put("buyVolumeSurge", settings.buyVolumeSurge());
                params.put("buyPriorHighDays", settings.buyPriorHighDays());
                params.put("scoreBuyWeight", settings.scoreBuyWeight());
                params.put("scoreExecWeight", settings.scoreExecWeight());
                params.put("constraintRuleMin", settings.constraintRuleMin());
                params.put("constraintRuleMax", settings.constraintRuleMax());
                // dump（保留根级未知键；注释无法程序化保留——文档注明手写注释会被归一化，与 positions.md 同策略）
                String yamlText = new Yaml().dump(root);
                fileStorage.write(userId, RULES_PATH, yamlText);
            } catch (StorageException e) {
                throw e;
            } catch (Exception e) {
                throw new StorageException("保存交易规则配置失败 | userId=" + userId + " | " + e.getMessage(), e);
            }
        }
    }

    /** 用户是否有规则配置（无 = 未启用任何自定义规则，走默认/降级）。 */
    public boolean exists(String userId) {
        return fileStorage.exists(userId, RULES_PATH);
    }

    @SuppressWarnings("unchecked")
    private TradingRuleSettings fromParams(Map<?, ?> params) {
        BigDecimal positionLimit = asBigDecimal(params.get("positionLimitPercent"));
        BigDecimal stopLossRatio = asBigDecimal(params.get("defaultStopLossRatio"));
        BigDecimal givebackPeak = asBigDecimal(params.get("givebackPeakPct"));
        BigDecimal givebackRatio = asBigDecimal(params.get("givebackRatioPct"));
        Integer shortOverdue = asInt(params.get("shortOverdueDays"));
        Double soldStopLoss = asDouble(params.get("soldStopLossPct"));
        Integer soldShortHold = asInt(params.get("soldShortHoldDays"));
        Double buyPullback = asDouble(params.get("buyPullbackPct"));
        Double buyShrink = asDouble(params.get("buyShrinkRatio"));
        Double buyKdj = asDouble(params.get("buyKdjLow"));
        Double buySurge = asDouble(params.get("buyVolumeSurge"));
        Integer buyWindow = asInt(params.get("buyPriorHighDays"));
        Double scoreBuy = asDouble(params.get("scoreBuyWeight"));
        Double scoreExec = asDouble(params.get("scoreExecWeight"));
        Integer constraintMin = asInt(params.get("constraintRuleMin"));
        Integer constraintMax = asInt(params.get("constraintRuleMax"));
        return new TradingRuleSettings(
                positionLimit,
                stopLossRatio,
                givebackPeak,
                givebackRatio,
                shortOverdue != null ? shortOverdue : -1,
                soldStopLoss != null ? soldStopLoss : -1,
                soldShortHold != null ? soldShortHold : -1,
                buyPullback != null ? buyPullback : -1,
                buyShrink != null ? buyShrink : -1,
                buyKdj != null ? buyKdj : -1,
                buySurge != null ? buySurge : -1,
                buyWindow != null ? buyWindow : -1,
                scoreBuy != null ? scoreBuy : -1,
                scoreExec != null ? scoreExec : -1,
                constraintMin != null ? constraintMin : -1,
                constraintMax != null ? constraintMax : -1);
    }

    private BigDecimal asBigDecimal(Object o) {
        if (o == null) return null;
        if (o instanceof BigDecimal bd) return bd;
        if (o instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        if (o instanceof String str) {
            try { return new BigDecimal(str.trim()); } catch (NumberFormatException e) { return null; }
        }
        return null;
    }

    private Integer asInt(Object o) {
        if (o == null) return null;
        if (o instanceof Integer i) return i;
        if (o instanceof Number n) return n.intValue();
        if (o instanceof String str) {
            try { return Integer.parseInt(str.trim()); } catch (NumberFormatException e) { return null; }
        }
        return null;
    }

    private Double asDouble(Object o) {
        if (o == null) return null;
        if (o instanceof Double d) return d;
        if (o instanceof Number n) return n.doubleValue();
        if (o instanceof String str) {
            try { return Double.parseDouble(str.trim()); } catch (NumberFormatException e) { return null; }
        }
        return null;
    }
}
