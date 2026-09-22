package com.adaiadai.core.domain.trading;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * AdviceEntry — 持仓建议留痕条目（RFC 20260905 B 层建议闭环①，2026-09-05）。
 * <p>
 * 「阿呆当时说过什么」的历史依据：每次建议生成（手动 /trading/advice 或定时推送逐票建议）
 * 在出口落一条快照，供卖出回查与「说X做Y得Z」复盘对照使用。
 * <p>
 * 建议只记录不执行——本条目是「记忆与对照」素材（主语是你 vs 你自己的历史），不含任何执行动作。
 *
 * @param id          条目 ID（adv_ + 时间戳，同秒防覆盖加毫秒）
 * @param date        建议日期（生成日）
 * @param symbol      标的代码
 * @param name        标的名称
 * @param suggestion  建议动作（buy/hold/reduce/clear；LLM 降级/无建议字段 → null）
 * @param reason      建议理由（自然语言，引用规则号；可空）
 * @param rules       引用规则号列表
 * @param hardVerdict 是否引擎硬判定覆盖（BREACHED→clear / OVER_WEIGHT→reduce）
 * @param positionPercent 持仓占比%（后端确定性计算）
 * @param source      来源（manual-advice 手动建议 / session-push 定时推送 / degraded 降级）
 * @param createdAt   生成时刻
 */
public record AdviceEntry(
        String id,
        LocalDate date,
        String symbol,
        String name,
        String suggestion,
        String reason,
        List<String> rules,
        boolean hardVerdict,
        BigDecimal positionPercent,
        String source,
        LocalDateTime createdAt,
        /**
         * 铁证④「可追责」的实体（RFC 20260922 A 批 A3，2026-09-22）：**建议发出当时**的依据快照
         * （现价 / 持仓占比 / 止损位 / 买点形态 / 建议动作，JSON 字符串）。
         * <p>
         * 只记录、**不做对错判决**（对错口径需用户拍板，见 RFC 第十一节 A3）。老文件无此字段 → 读作 null。
         */
        String basis,
        /**
         * 铁证④「结果回填」（RFC 20260922 A 批 A3，2026-09-22）：建议发出 **N 个交易日之后**的实际结果
         * ——{@code {"afterDays":5,"priceThen":18.42,"priceAfter":19.10,"pct":3.69,"userActed":"sold|bought|none"}}。
         * <p>
         * **只记录事实，不判对错**（"对错"是用户复盘时的事）；未回填 → null。
         */
        String outcome
) {
    /** 兼容构造（11 参，旧调用/既有测试零改动）：无依据快照、无回填结果。 */
    public AdviceEntry(String id, LocalDate date, String symbol, String name, String suggestion,
                       String reason, List<String> rules, boolean hardVerdict,
                       BigDecimal positionPercent, String source, LocalDateTime createdAt) {
        this(id, date, symbol, name, suggestion, reason, rules, hardVerdict,
                positionPercent, source, createdAt, null, null);
    }

    /** 兼容构造（12 参，A3 前半批的调用）：有依据快照、尚未回填。 */
    public AdviceEntry(String id, LocalDate date, String symbol, String name, String suggestion,
                       String reason, List<String> rules, boolean hardVerdict,
                       BigDecimal positionPercent, String source, LocalDateTime createdAt, String basis) {
        this(id, date, symbol, name, suggestion, reason, rules, hardVerdict,
                positionPercent, source, createdAt, basis, null);
    }

    public AdviceEntry {
        if (symbol == null) symbol = "";
        if (name == null) name = "";
        if (rules == null) rules = List.of();
        if (source == null) source = "";
        // 时间戳默认值收进实体构造（G2：存储层路径推导禁 now()——date 归 createdAt 的日期，
        // createdAt 才是落盘时刻的单一时间源；两者皆缺才兜底当前时间，绝不在存储层出现 now()）
        if (createdAt == null) createdAt = LocalDateTime.now();
        if (date == null) date = createdAt.toLocalDate();
        if (id == null || id.isBlank()) {
            // P1-5（2026-09-05 三官深审）：id 由 createdAt 派生含毫秒（yyyyMMdd_HHmmss_SSS）——
            // 原 substring(0,15) 截到秒（毫秒被切），同秒多条建议 id 可重复（pitfalls「ID 秒级精度」）。
            // 单一时间源：不再第二次 now()。
            id = "adv_" + createdAt.format(ADVICE_ID_FORMAT);
        }
    }

    /** ID 时间戳格式：yyyyMMdd_HHmmss_SSS（含毫秒，同秒防覆盖）。 */
    private static final java.time.format.DateTimeFormatter ADVICE_ID_FORMAT =
            java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS");
}
