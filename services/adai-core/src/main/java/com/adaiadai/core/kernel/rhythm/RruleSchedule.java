package com.adaiadai.core.kernel.rhythm;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * RruleSchedule — RRULE 子集：解析 + 命中判定（RFC 20260923 B 批）。
 * <p>
 * <b>为什么用 RRULE</b>：周期性是工业界已有标准表示（iCalendar，RFC 5545 §3.3.10，
 * Todoist / Google Calendar 通用），项目内不新造字段名（RFC §三 R1）。
 * 本类**只借表示，不建日历系统**（不做时区/例外日/冲突检测——见 RFC §六 边界）。
 * <p>
 * 支持子集：
 * <pre>
 *   FREQ=DAILY | WEEKLY | MONTHLY      （必填）
 *   INTERVAL=n                          （可选，默认 1：隔 n 个周期一次）
 *   BYDAY=MO,TU,WE,TH,FR,SA,SU          （可选，WEEKLY 用；省略 = 生效日那天）
 *   BYMONTHDAY=n                        （可选 1-31，MONTHLY 用；省略 = 生效日的日；该月无此日则跳过）
 *   UNTIL=yyyyMMdd 或 yyyy-MM-dd        （可选，含当日）
 * </pre>
 * 未列出的部件一律**拒绝**（宁可报错，不要静默忽略——静默忽略会造出一个"看起来对、其实不是你说的那个周期"）。
 */
public record RruleSchedule(
        Freq freq,
        int interval,
        Set<DayOfWeek> byDay,
        Integer byMonthDay,
        LocalDate until
) {

    /** 目前支持的三种频率。 */
    public enum Freq { DAILY, WEEKLY, MONTHLY }

    private static final Map<String, DayOfWeek> DAY_CODES = Map.of(
            "MO", DayOfWeek.MONDAY, "TU", DayOfWeek.TUESDAY, "WE", DayOfWeek.WEDNESDAY,
            "TH", DayOfWeek.THURSDAY, "FR", DayOfWeek.FRIDAY, "SA", DayOfWeek.SATURDAY,
            "SU", DayOfWeek.SUNDAY);

    /** byDay 允许为 null（调用方少写一个 List.of()）。 */
    public RruleSchedule {
        if (byDay == null) byDay = Set.of();
    }

    /**
     * 解析 RRULE。非法输入抛 {@link RhythmException}（人话），不返回半个对象。
     */
    public static RruleSchedule parse(String rrule) {
        if (rrule == null || rrule.isBlank()) {
            throw new RhythmException("周期规则不能为空（例如 每周四 写成 FREQ=WEEKLY;BYDAY=TH）");
        }
        Freq freq = null;
        int interval = 1;
        Set<DayOfWeek> byDay = EnumSet.noneOf(DayOfWeek.class);
        Integer byMonthDay = null;
        LocalDate until = null;

        for (String rawPart : rrule.strip().toUpperCase().split(";")) {
            String part = rawPart.strip();
            if (part.isEmpty()) continue;
            String[] kv = part.split("=", 2);
            if (kv.length != 2 || kv[0].isBlank()) {
                throw new RhythmException("周期规则看不懂这一段：" + part
                        + "（要写成 FREQ=WEEKLY;BYDAY=TH 这样）");
            }
            String key = kv[0].strip();
            String value = kv[1].strip();
            switch (key) {
                case "FREQ" -> freq = switch (value) {
                    case "DAILY" -> Freq.DAILY;
                    case "WEEKLY" -> Freq.WEEKLY;
                    case "MONTHLY" -> Freq.MONTHLY;
                    default -> throw new RhythmException("目前只支持 每天 / 每周 / 每月 三种周期（收到 FREQ=" + value + "）");
                };
                case "INTERVAL" -> {
                    interval = parseInt(value, "INTERVAL");
                    if (interval < 1) throw new RhythmException("INTERVAL 要是正整数（1 = 每个周期，2 = 隔一个周期）");
                }
                case "BYDAY" -> {
                    for (String code : value.split(",")) {
                        DayOfWeek day = DAY_CODES.get(code.strip());
                        if (day == null) throw new RhythmException("BYDAY 只认 MO/TU/WE/TH/FR/SA/SU（收到 " + code.strip() + "）");
                        byDay.add(day);
                    }
                }
                case "BYMONTHDAY" -> {
                    byMonthDay = parseInt(value, "BYMONTHDAY");
                    if (byMonthDay < 1 || byMonthDay > 31) throw new RhythmException("BYMONTHDAY 要在 1-31 之间");
                }
                case "UNTIL" -> until = parseUntil(value);
                default -> throw new RhythmException("周期规则里有不认识的字段：" + key
                        + "（本项目只支持 FREQ / INTERVAL / BYDAY / BYMONTHDAY / UNTIL）");
            }
        }
        if (freq == null) {
            throw new RhythmException("周期规则缺 FREQ（要写成 FREQ=WEEKLY;BYDAY=TH 这样）");
        }
        return new RruleSchedule(freq, interval, byDay, byMonthDay, until);
    }

    /**
     * 判定 {@code date} 是否命中本周期。
     *
     * @param anchor 周期起点（节律的生效日，等价于 iCalendar 的 DTSTART）——
     *               INTERVAL 的「第一个周期」与「省略 BYDAY/BYMONTHDAY 时的默认值」都由它决定
     */
    public boolean matches(LocalDate date, LocalDate anchor) {
        if (date == null || anchor == null) return false;
        if (date.isBefore(anchor)) return false;
        if (until != null && date.isAfter(until)) return false;
        return switch (freq) {
            case DAILY -> ChronoUnit.DAYS.between(anchor, date) % interval == 0;
            case WEEKLY -> {
                LocalDate anchorMonday = mondayOf(anchor);
                LocalDate dateMonday = mondayOf(date);
                long weeks = ChronoUnit.WEEKS.between(anchorMonday, dateMonday);
                Set<DayOfWeek> days = byDay.isEmpty() ? Set.of(anchor.getDayOfWeek()) : byDay;
                yield weeks % interval == 0 && days.contains(date.getDayOfWeek());
            }
            case MONTHLY -> {
                int day = byMonthDay != null ? byMonthDay : anchor.getDayOfMonth();
                long months = ChronoUnit.MONTHS.between(
                        anchor.withDayOfMonth(1), date.withDayOfMonth(1));
                // 该月没有这一天（如 31 号遇小月）→ 跳过，不塌到月末（与 iCalendar 一致）
                yield months % interval == 0
                        && day <= date.lengthOfMonth()
                        && date.getDayOfMonth() == day;
            }
        };
    }

    /** 人类可读的一句话（供提示/日志；不参与判定）。 */
    public String explain() {
        String prefix = interval > 1 ? "每隔 " + interval + " 个周期" : "";
        return switch (freq) {
            case DAILY -> (interval > 1 ? "每隔 " + interval + " 天" : "每天");
            case WEEKLY -> {
                String days = byDay.isEmpty() ? "（与生效日同一星期几）"
                        : byDay.stream().map(RruleSchedule::cnDay).sorted().reduce((a, b) -> a + "、" + b).orElse("");
                yield prefix.isBlank() ? "每周" + days : prefix + "的每周" + days;
            }
            case MONTHLY -> prefix.isBlank() ? "每月 " + (byMonthDay != null ? byMonthDay + " 号" : "（与生效日同一日）")
                    : prefix + "的每月 " + (byMonthDay != null ? byMonthDay + " 号" : "（与生效日同一日）");
        };
    }

    // ── 内部 ──

    private static LocalDate mondayOf(LocalDate date) {
        return date.minusDays(date.getDayOfWeek().getValue() - 1L);
    }

    private static int parseInt(String value, String field) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new RhythmException(field + " 要是数字（收到 " + value + "）");
        }
    }

    private static LocalDate parseUntil(String value) {
        try {
            if (value.contains("-")) return LocalDate.parse(value);
            return LocalDate.parse(value, java.time.format.DateTimeFormatter.BASIC_ISO_DATE);
        } catch (DateTimeParseException e) {
            throw new RhythmException("UNTIL 要写成 20260930 或 2026-09-30");
        }
    }

    private static String cnDay(DayOfWeek day) {
        return switch (day) {
            case MONDAY -> "一";
            case TUESDAY -> "二";
            case WEDNESDAY -> "三";
            case THURSDAY -> "四";
            case FRIDAY -> "五";
            case SATURDAY -> "六";
            case SUNDAY -> "日";
        };
    }
}
