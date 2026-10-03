package com.adaiadai.core.domain.trading;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * CashAdjustment — 资金快照对账调整（RFC 20261003-trading-cash-position-linkage C4 + D3 拍板
 * 「独立文件」，2026-10-03）。
 *
 * <p>一条调整 = 一次「券商现金 − 系统推算现金」的差额**落账**。它让下面这条恒等式成立且可查：
 * <pre>系统现金 = 上次快照值 + Σ(已计入现金的流水与转账) + Σ(调整)</pre>
 *
 * <p><b>为什么必须落账</b>：原 {@code importCashQuery} 是**静默覆盖**——差额被抹掉、不留痕，
 * 于是只能反复导全量（生产实据：两次导入之间系统现金漂到 **−37,226.29 / +24,101.01**，
 * 而券商真值全程 ≤ 2,278.16）。落账后，「我解释不了的那部分」变成一条有日期、有金额、
 * 有原因的记录，可以回看、可以统计，而不是凭空消失。
 *
 * @param id        调整 ID
 * @param date      对应哪次导入（券商快照日期）
 * @param amount    差额（券商 − 系统）；正 = 券商比系统多
 * @param reason    当时能给出的归类（未记录股息/利息 · 降级流水 · 费用口径差 · 未知）——**不编造**
 * @param note      对账人话（留痕用）
 * @param createdAt 落账时刻
 */
public record CashAdjustment(String id, LocalDate date, BigDecimal amount, String reason,
                             String note, LocalDateTime createdAt) {}
