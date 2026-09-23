package com.adaiadai.core.kernel.rhythm;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * RhythmDetector — 「这是节律吗」的**单一真相源**（RFC 20260923）。
 * <p>
 * 两个判据，用途不同，别混：
 * <ul>
 *   <li>{@link #isRhythmLike(String)}——<b>注入侧闸门</b>（A 批已用于简报）。
 *       刻意保守：必须出现明确的重复词（每周/每月/每天/例会/定期…）或「周X＋固定」组合。</li>
 *   <li>{@link #detectRrule(String)}——<b>写入侧分流</b>（B 批）。
 *       先过 {@code isRhythmLike}，再尝试推断 RRULE；<b>推断不出就返回 null</b>，
 *       调用方保持原行为（建待办），不硬转——宁可漏判，不可把一次性任务吞成节律。</li>
 * </ul>
 * 反例（两者都必须放行给待办）：「周四要交周报」（含「周四」但是一次性任务）、「给妈打个电话」。
 */
public final class RhythmDetector {

    private RhythmDetector() {
    }

    /** 判据：周期性习惯表述（= 不该被当待办催）。 */
    private static final Pattern RHYTHM_LIKE = Pattern.compile(
            "每周|每星期|每月|每天|每日|每季度|每年|例行|定期"
                    + "|(周|星期|礼拜)[一二三四五六日天]\\s*固定"
                    + "|固定\\s*(的)?\\s*(周|星期|礼拜|每周|发版|例会|值班)");

    /** 「周四」「星期四」「礼拜四」（容忍中间空格）。 */
    private static final Pattern WEEKDAY = Pattern.compile("(周|星期|礼拜)\\s*([一二三四五六日天])");

    /** 「每月1号」「每月 1 日」。 */
    private static final Pattern MONTH_DAY = Pattern.compile("每(个)?月\\s*(\\d{1,2})\\s*[号日]");

    private static final Map<String, String> DAY_CODE = Map.of(
            "一", "MO", "二", "TU", "三", "WE", "四", "TH",
            "五", "FR", "六", "SA", "日", "SU", "天", "SU");

    /**
     * 是否周期性习惯表述。判据保守——见类注释。
     */
    public static boolean isRhythmLike(String title) {
        return title != null && RHYTHM_LIKE.matcher(title).find();
    }

    /**
     * 尝试把一句话推断成 RRULE。
     *
     * @return RRULE 字符串；<b>推断不出返回 null</b>（调用方按非节律处理）
     */
    public static String detectRrule(String title) {
        if (!isRhythmLike(title)) return null;

        Matcher monthDay = MONTH_DAY.matcher(title);
        if (monthDay.find()) {
            int day = Integer.parseInt(monthDay.group(2));
            if (day >= 1 && day <= 31) {
                return "FREQ=MONTHLY;BYMONTHDAY=" + day;
            }
        }

        if (title.contains("每天") || title.contains("每日")) {
            return "FREQ=DAILY";
        }

        Matcher weekday = WEEKDAY.matcher(title);
        if (weekday.find()) {
            String code = DAY_CODE.get(weekday.group(2));
            if (code != null) {
                return "FREQ=WEEKLY;BYDAY=" + code;
            }
        }

        // 「每周」「每星期」但没说星期几 → 以生效日那天为准（iCalendar 省略 BYDAY 的语义）
        if (title.contains("每周") || title.contains("每星期") || title.contains("每个星期")) {
            return "FREQ=WEEKLY";
        }
        return null;
    }
}
