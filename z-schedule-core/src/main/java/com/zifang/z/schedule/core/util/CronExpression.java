package com.zifang.z.schedule.core.util;

import java.io.Serializable;
import java.text.ParseException;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

/**
 * Cron 表达式解析器(Quartz 风格 6/7 字段: 秒 分 时 日 月 周 [年]).
 * <p>
 * 解析在构造期一次性完成, 所有字段均为 {@code final} 且解析后不再变更, 因此实例是不可变的、
 * 可在调度线程与 worker 线程间共享; {@link #getNextValidTimeAfter(Date)} 只使用局部
 * {@link Calendar}, 不触碰任何共享状态。
 * <p>
 * 求解按字段推进(TreeSet.ceiling + 进位), 不逐秒暴力递增, 单次求解与"下一次"的间隔无关。
 */
public class CronExpression implements Serializable, Cloneable {

    private static final long serialVersionUID = 1L;

    /** 年份字段与搜索窗口的上下界; 无年份字段时也用它保证"永不匹配"的表达式能够终止. */
    private static final int MIN_YEAR = 1970;
    private static final int MAX_YEAR = 2299;

    /** 推进算法的理论步数远小于此值, 只作为"任何情况下都不会死循环"的兜底. */
    private static final int MAX_CYCLE = 100000;

    private static final Map<String, Integer> MONTH_MAP = new HashMap<String, Integer>();
    private static final Map<String, Integer> DAY_MAP = new HashMap<String, Integer>();

    static {
        MONTH_MAP.put("JAN", 1);
        MONTH_MAP.put("FEB", 2);
        MONTH_MAP.put("MAR", 3);
        MONTH_MAP.put("APR", 4);
        MONTH_MAP.put("MAY", 5);
        MONTH_MAP.put("JUN", 6);
        MONTH_MAP.put("JUL", 7);
        MONTH_MAP.put("AUG", 8);
        MONTH_MAP.put("SEP", 9);
        MONTH_MAP.put("OCT", 10);
        MONTH_MAP.put("NOV", 11);
        MONTH_MAP.put("DEC", 12);

        // 1=周日..7=周六, 与 java.util.Calendar.DAY_OF_WEEK 完全一致, 因此不需要再做位移换算
        DAY_MAP.put("SUN", 1);
        DAY_MAP.put("MON", 2);
        DAY_MAP.put("TUE", 3);
        DAY_MAP.put("WED", 4);
        DAY_MAP.put("THU", 5);
        DAY_MAP.put("FRI", 6);
        DAY_MAP.put("SAT", 7);
    }

    private final String cronExpression;
    private final TreeSet<Integer> seconds;
    private final TreeSet<Integer> minutes;
    private final TreeSet<Integer> hours;
    private final TreeSet<Integer> daysOfMonth;
    private final TreeSet<Integer> months;
    private final TreeSet<Integer> daysOfWeek;
    /** 为 null 表示不限年份(6 字段, 或第 7 字段是 * / ?). */
    private final TreeSet<Integer> years;
    private final boolean dayOfMonthLimited;
    private final boolean dayOfWeekLimited;

    public CronExpression(String cronExpression) throws ParseException {
        if (cronExpression == null) {
            throw new ParseException("Invalid cron expression [null]: expression must not be null", 0);
        }
        this.cronExpression = cronExpression;

        String[] parts = splitFields(cronExpression);

        Spec second = new Spec(cronExpression, "second", 0, 59, false, false);
        Spec minute = new Spec(cronExpression, "minute", 0, 59, false, false);
        Spec hour = new Spec(cronExpression, "hour", 0, 23, false, false);
        Spec dayOfMonth = new Spec(cronExpression, "day-of-month", 1, 31, false, false);
        Spec month = new Spec(cronExpression, "month", 1, 12, true, false);
        // 星期字段允许 0(=周日), 故下界为 0
        Spec dayOfWeek = new Spec(cronExpression, "day-of-week", 0, 7, false, true);
        Spec year = new Spec(cronExpression, "year", MIN_YEAR, MAX_YEAR, false, false);

        Field sec = parseField(parts[0], second);
        Field min = parseField(parts[1], minute);
        Field hr = parseField(parts[2], hour);
        Field dom = parseField(parts[3], dayOfMonth);
        Field mon = parseField(parts[4], month);
        Field dow = parseField(parts[5], dayOfWeek);
        Field yr = parts.length == 7 ? parseField(parts[6], year) : new Field(new TreeSet<Integer>(), false);

        this.seconds = sec.values;
        this.minutes = min.values;
        this.hours = hr.values;
        this.daysOfMonth = dom.values;
        this.months = mon.values;
        this.daysOfWeek = dow.values;
        this.years = yr.limited ? yr.values : null;
        this.dayOfMonthLimited = dom.limited;
        this.dayOfWeekLimited = dow.limited;
    }

    // ------------------------------------------------------------------ 解析

    private static final class Field {
        private final TreeSet<Integer> values;
        private final boolean limited;

        private Field(TreeSet<Integer> values, boolean limited) {
            this.values = values;
            this.limited = limited;
        }
    }

    /** 单个字段的解析上下文: 边界、是否支持名字, 以及出错信息需要的完整表达式. */
    private static final class Spec {
        private final String expression;
        private final String name;
        private final int min;
        private final int max;
        private final boolean monthNames;
        private final boolean dayNames;

        private Spec(String expression, String name, int min, int max, boolean monthNames, boolean dayNames) {
            this.expression = expression;
            this.name = name;
            this.min = min;
            this.max = max;
            this.monthNames = monthNames;
            this.dayNames = dayNames;
        }

        private ParseException fail(String rawField, String detail) {
            return new ParseException("Invalid cron expression [" + expression + "]: "
                    + name + " field [" + rawField + "]: " + detail, 0);
        }
    }

    private static String[] splitFields(String expression) throws ParseException {
        String trimmed = expression.trim();
        String[] parts = trimmed.isEmpty() ? new String[0] : trimmed.split("\\s+");
        if (parts.length != 6 && parts.length != 7) {
            throw new ParseException("Invalid cron expression [" + expression
                    + "]: expected 6 or 7 fields but got " + parts.length, 0);
        }
        return parts;
    }

    private static Field parseField(String rawField, Spec spec) throws ParseException {
        String field = rawField.trim();
        if (field.isEmpty()) {
            throw spec.fail(rawField, "field is empty");
        }
        TreeSet<Integer> values = new TreeSet<Integer>();
        if ("*".equals(field) || "?".equals(field)) {
            for (int v = spec.min; v <= spec.max; v++) {
                values.add(normalize(v, spec.dayNames));
            }
            return new Field(values, false);
        }
        if (field.indexOf('?') >= 0) {
            throw spec.fail(rawField, "'?' is only allowed as the whole field");
        }
        // limit=-1 保留空串, 使 "1,,2" 与尾随逗号能被明确拒绝而不是被静默吞掉
        for (String token : field.split(",", -1)) {
            parseToken(token, values, spec, rawField);
        }
        if (values.isEmpty()) {
            throw spec.fail(rawField, "field matches nothing");
        }
        return new Field(values, true);
    }

    private static void parseToken(String token, TreeSet<Integer> values, Spec spec, String rawField)
            throws ParseException {
        String part = token.trim();
        if (part.isEmpty()) {
            throw spec.fail(rawField, "empty value in list");
        }
        if (part.indexOf('#') >= 0) {
            throw spec.fail(rawField, "'#' (the n-th weekday of a month, e.g. 6#3) is not supported");
        }

        int step = 1;
        boolean stepped = false;
        int slash = part.indexOf('/');
        if (slash >= 0) {
            if (part.indexOf('/', slash + 1) >= 0) {
                throw spec.fail(rawField, "repeated '/'");
            }
            stepped = true;
            String stepText = part.substring(slash + 1).trim();
            part = part.substring(0, slash).trim();
            if (stepText.isEmpty()) {
                throw spec.fail(rawField, "missing step after '/'");
            }
            step = parseStep(stepText, spec, rawField);
        }
        if (part.isEmpty()) {
            throw spec.fail(rawField, "missing value before '/' in [" + token + "]");
        }

        int start;
        int end;
        if ("*".equals(part)) {
            start = spec.min;
            end = spec.max;
        } else {
            int dash = part.indexOf('-');
            if (dash >= 0) {
                if (part.indexOf('-', dash + 1) >= 0) {
                    throw spec.fail(rawField, "repeated '-'");
                }
                start = parseValue(part.substring(0, dash), spec, rawField);
                end = parseValue(part.substring(dash + 1), spec, rawField);
                if (start > end) {
                    throw spec.fail(rawField, "reversed range [" + part + "]: start must not exceed end");
                }
            } else {
                start = parseValue(part, spec, rawField);
                // Quartz 语义: "5/2" 是"从 5 起按步长走到上界", 不是只匹配 5
                end = stepped ? spec.max : start;
            }
        }

        for (int v = start; v <= end; v += step) {
            values.add(normalize(v, spec.dayNames));
        }
    }

    private static int normalize(int value, boolean dayNames) {
        // 0 与 1 同为周日(兼容 POSIX cron 写法); 其余数字保持 Quartz 语义 1=周日..7=周六,
        // 该值域与 Calendar.DAY_OF_WEEK 完全一致, 因此无需再做位移换算
        return dayNames && value == 0 ? 1 : value;
    }

    private static int parseStep(String text, Spec spec, String rawField) throws ParseException {
        return parseNumber(text, spec, rawField, "step", 1, spec.max - spec.min + 1);
    }

    private static int parseValue(String token, Spec spec, String rawField) throws ParseException {
        String value = token.trim();
        if (value.isEmpty()) {
            throw spec.fail(rawField, "empty value");
        }
        if (isAllDigits(value)) {
            return parseNumber(value, spec, rawField, "value", spec.min, spec.max);
        }
        String key = value.toUpperCase(Locale.ROOT);
        Integer mapped = spec.monthNames ? MONTH_MAP.get(key) : null;
        if (mapped == null && spec.dayNames) {
            mapped = DAY_MAP.get(key);
        }
        if (mapped != null) {
            return mapped.intValue();
        }
        throw spec.fail(rawField, describeUnknownValue(value, key, spec));
    }

    private static String describeUnknownValue(String value, String upperKey, Spec spec) {
        if (upperKey.indexOf('L') >= 0 || upperKey.indexOf('W') >= 0) {
            return "[" + value + "] uses 'L'/'W', which is not supported";
        }
        if (upperKey.length() > 3) {
            String prefix = upperKey.substring(0, 3);
            boolean monthName = MONTH_MAP.containsKey(prefix);
            boolean dayName = DAY_MAP.containsKey(prefix);
            if ((spec.monthNames && monthName) || (spec.dayNames && dayName)) {
                return "[" + value + "]: only the 3-letter names (JAN-DEC / SUN-SAT) are supported";
            }
            if (monthName || dayName) {
                return "[" + value + "] is not a valid " + (monthName ? "month" : "day-of-week") + " name";
            }
        }
        return "[" + value + "] is not a valid value";
    }

    private static int parseNumber(String text, Spec spec, String rawField, String what, int min, int max)
            throws ParseException {
        if (!isAllDigits(text)) {
            throw spec.fail(rawField, what + " [" + text + "] must be a number");
        }
        int value;
        try {
            value = Integer.parseInt(text);
        } catch (NumberFormatException e) {
            // 纯数字但溢出(如 99999999999)也归为越界: 绝不把 NumberFormatException 抛给调用方
            throw spec.fail(rawField, what + " [" + text + "] must be in [" + min + "," + max + "]");
        }
        if (value < min || value > max) {
            throw spec.fail(rawField, what + " [" + text + "] must be in [" + min + "," + max + "]");
        }
        return value;
    }

    private static boolean isAllDigits(String text) {
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) < '0' || text.charAt(i) > '9') {
                return false;
            }
        }
        return !text.isEmpty();
    }

    // ------------------------------------------------------------------ 求解

    /**
     * 获取下一次有效执行时间(严格晚于 afterTime)。
     *
     * @param afterTime 在此时间之后
     * @return 下一个执行时间; 表达式在该时间之后不再触发时返回 {@code null}
     */
    public Date getNextValidTimeAfter(Date afterTime) {
        if (afterTime == null) {
            return null;
        }
        Calendar calendar = Calendar.getInstance();
        calendar.setTime(afterTime);
        calendar.set(Calendar.MILLISECOND, 0);
        calendar.add(Calendar.SECOND, 1);

        int cycles = 0;
        while (true) {
            if (++cycles > MAX_CYCLE) {
                return null;
            }

            int year = calendar.get(Calendar.YEAR);
            if (years != null) {
                Integer targetYear = years.ceiling(year);
                if (targetYear == null) {
                    return null;
                }
                if (targetYear > year) {
                    jumpToYear(calendar, targetYear);
                    continue;
                }
            } else if (year > MAX_YEAR) {
                return null;
            }

            int month = calendar.get(Calendar.MONTH) + 1;
            Integer targetMonth = months.ceiling(month);
            if (targetMonth == null) {
                rollYear(calendar);
                continue;
            }
            if (targetMonth > month) {
                jumpToMonth(calendar, targetMonth);
                continue;
            }

            int maxDayOfMonth = calendar.getActualMaximum(Calendar.DAY_OF_MONTH);
            if (!matchesDay(calendar)) {
                advanceDay(calendar, maxDayOfMonth);
                continue;
            }

            int hour = calendar.get(Calendar.HOUR_OF_DAY);
            Integer targetHour = hours.ceiling(hour);
            if (targetHour == null) {
                advanceDay(calendar, maxDayOfMonth);
                continue;
            }
            if (targetHour > hour) {
                calendar.set(Calendar.HOUR_OF_DAY, targetHour);
                resetTimeFromMinute(calendar);
                continue;
            }

            int minute = calendar.get(Calendar.MINUTE);
            Integer targetMinute = minutes.ceiling(minute);
            if (targetMinute == null) {
                advanceHourOrDay(calendar, hour, maxDayOfMonth);
                continue;
            }
            if (targetMinute > minute) {
                calendar.set(Calendar.MINUTE, targetMinute);
                calendar.set(Calendar.SECOND, seconds.first());
                continue;
            }

            int second = calendar.get(Calendar.SECOND);
            Integer targetSecond = seconds.ceiling(second);
            if (targetSecond == null) {
                advanceMinuteOrHourOrDay(calendar, minute, hour, maxDayOfMonth);
                continue;
            }
            if (targetSecond > second) {
                calendar.set(Calendar.SECOND, targetSecond);
                continue;
            }

            return calendar.getTime();
        }
    }

    /**
     * 日/周匹配。两个字段都是 * 或 ? 时恒真; 只有一个被限定时按那一个匹配;
     * 两者都被限定时取并集(Quartz 语义: "每月 15 号 或 每周一" 是两个触发条件的合集, 而不是交集)。
     */
    private boolean matchesDay(Calendar calendar) {
        boolean dayOfMonthOk = !dayOfMonthLimited || daysOfMonth.contains(calendar.get(Calendar.DAY_OF_MONTH));
        boolean dayOfWeekOk = !dayOfWeekLimited || daysOfWeek.contains(calendar.get(Calendar.DAY_OF_WEEK));
        if (dayOfMonthLimited && dayOfWeekLimited) {
            return dayOfMonthOk || dayOfWeekOk;
        }
        return dayOfMonthOk && dayOfWeekOk;
    }

    /** 把日历推进到"下一个可能是合法日"的日子; 本月内已无候选日时进位到下月. */
    private void advanceDay(Calendar calendar, int maxDayOfMonth) {
        int dayOfMonth = calendar.get(Calendar.DAY_OF_MONTH);
        if (dayOfMonthLimited && !dayOfWeekLimited) {
            Integer next = daysOfMonth.ceiling(dayOfMonth + 1);
            if (next != null && next <= maxDayOfMonth) {
                calendar.set(Calendar.DAY_OF_MONTH, next);
                resetTimeFromHour(calendar);
                return;
            }
            rollMonth(calendar);
            return;
        }
        if (dayOfWeekLimited && !dayOfMonthLimited) {
            int dayOfWeek = calendar.get(Calendar.DAY_OF_WEEK);
            Integer next = daysOfWeek.ceiling(dayOfWeek + 1);
            int target = next != null ? next : daysOfWeek.first();
            int delta = (target - dayOfWeek + 7) % 7;
            calendar.add(Calendar.DAY_OF_MONTH, delta == 0 ? 7 : delta);
            resetTimeFromHour(calendar);
            return;
        }
        if (dayOfMonth < maxDayOfMonth) {
            calendar.add(Calendar.DAY_OF_MONTH, 1);
            resetTimeFromHour(calendar);
            return;
        }
        rollMonth(calendar);
    }

    private void advanceHourOrDay(Calendar calendar, int hour, int maxDayOfMonth) {
        Integer nextHour = hours.higher(hour);
        if (nextHour == null) {
            advanceDay(calendar, maxDayOfMonth);
            return;
        }
        calendar.set(Calendar.HOUR_OF_DAY, nextHour);
        resetTimeFromMinute(calendar);
    }

    private void advanceMinuteOrHourOrDay(Calendar calendar, int minute, int hour, int maxDayOfMonth) {
        Integer nextMinute = minutes.higher(minute);
        if (nextMinute != null) {
            calendar.set(Calendar.MINUTE, nextMinute);
            calendar.set(Calendar.SECOND, seconds.first());
            return;
        }
        advanceHourOrDay(calendar, hour, maxDayOfMonth);
    }

    private void jumpToYear(Calendar calendar, int year) {
        calendar.set(Calendar.DAY_OF_MONTH, 1);
        calendar.set(Calendar.MONTH, 0);
        calendar.set(Calendar.YEAR, year);
        resetTimeFromHour(calendar);
    }

    /** 先把"日"降回 1 再改月份: 否则 1 月 31 日改成 2 月时会被宽松模式卷进 3 月. */
    private void jumpToMonth(Calendar calendar, int month) {
        calendar.set(Calendar.DAY_OF_MONTH, 1);
        calendar.set(Calendar.MONTH, month - 1);
        resetTimeFromHour(calendar);
    }

    private void rollYear(Calendar calendar) {
        jumpToYear(calendar, calendar.get(Calendar.YEAR) + 1);
    }

    private void rollMonth(Calendar calendar) {
        calendar.set(Calendar.DAY_OF_MONTH, 1);
        resetTimeFromHour(calendar);
        calendar.add(Calendar.MONTH, 1);
    }

    private void resetTimeFromHour(Calendar calendar) {
        calendar.set(Calendar.HOUR_OF_DAY, hours.first());
        resetTimeFromMinute(calendar);
    }

    private void resetTimeFromMinute(Calendar calendar) {
        calendar.set(Calendar.MINUTE, minutes.first());
        calendar.set(Calendar.SECOND, seconds.first());
    }

    public String getCronExpression() {
        return cronExpression;
    }
}
