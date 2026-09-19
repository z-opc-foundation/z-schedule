package com.zifang.z.schedule.core.util;

import java.io.Serializable;
import java.text.ParseException;
import java.util.*;

/**
 * Cron表达式解析器
 * 参考Quartz的CronExpression实现
 */
public class CronExpression implements Serializable, Cloneable {

    private static final long serialVersionUID = 1L;

    private static final int SECOND = 0;
    private static final int MINUTE = 1;
    private static final int HOUR = 2;
    private static final int DAY_OF_MONTH = 3;
    private static final int MONTH = 4;
    private static final int DAY_OF_WEEK = 5;
    private static final int YEAR = 6;

    private static final Map<String, Integer> MONTH_MAP = new HashMap<>();
    private static final Map<String, Integer> DAY_MAP = new HashMap<>();

    static {
        MONTH_MAP.put("JAN", 0);
        MONTH_MAP.put("FEB", 1);
        MONTH_MAP.put("MAR", 2);
        MONTH_MAP.put("APR", 3);
        MONTH_MAP.put("MAY", 4);
        MONTH_MAP.put("JUN", 5);
        MONTH_MAP.put("JUL", 6);
        MONTH_MAP.put("AUG", 7);
        MONTH_MAP.put("SEP", 8);
        MONTH_MAP.put("OCT", 9);
        MONTH_MAP.put("NOV", 10);
        MONTH_MAP.put("DEC", 11);

        DAY_MAP.put("SUN", 1);
        DAY_MAP.put("MON", 2);
        DAY_MAP.put("TUE", 3);
        DAY_MAP.put("WED", 4);
        DAY_MAP.put("THU", 5);
        DAY_MAP.put("FRI", 6);
        DAY_MAP.put("SAT", 7);
    }

    private final String cronExpression;
    private TreeSet<Integer> seconds;
    private TreeSet<Integer> minutes;
    private TreeSet<Integer> hours;
    private TreeSet<Integer> daysOfMonth;
    private TreeSet<Integer> months;
    private TreeSet<Integer> daysOfWeek;

    public CronExpression(String cronExpression) throws ParseException {
        this.cronExpression = cronExpression;
        buildExpression(cronExpression);
    }

    private void buildExpression(String expression) throws ParseException {
        String[] parts = expression.trim().split("\\s+");

        if (parts.length != 6 && parts.length != 7) {
            throw new ParseException("Invalid cron expression, expected 6 or 7 fields but got " + parts.length, 0);
        }

        seconds = parseField(parts[0], SECOND, 0, 59);
        minutes = parseField(parts[1], MINUTE, 0, 59);
        hours = parseField(parts[2], HOUR, 0, 23);
        daysOfMonth = parseField(parts[3], DAY_OF_MONTH, 1, 31);
        months = parseField(parts[4], MONTH, 1, 12);
        daysOfWeek = parseField(parts[5], DAY_OF_WEEK, 1, 7);
    }

    private TreeSet<Integer> parseField(String field, int type, int min, int max) throws ParseException {
        TreeSet<Integer> set = new TreeSet<>();

        // 处理特殊字符
        if (field.equals("*") || field.equals("?")) {
            for (int i = min; i <= max; i++) {
                set.add(i);
            }
            return set;
        }

        // 处理逗号分隔的多个值
        String[] parts = field.split(",");
        for (String part : parts) {
            parseRange(part, set, type, min, max);
        }

        return set;
    }

    private void parseRange(String part, TreeSet<Integer> set, int type, int min, int max) throws ParseException {
        // 处理步长
        int step = 1;
        if (part.contains("/")) {
            String[] stepParts = part.split("/");
            part = stepParts[0];
            step = Integer.parseInt(stepParts[1]);
        }

        // 处理范围
        int start = min;
        int end = max;

        if (part.equals("*")) {
            // 全部范围
        } else if (part.contains("-")) {
            String[] rangeParts = part.split("-");
            start = Integer.parseInt(rangeParts[0]);
            end = Integer.parseInt(rangeParts[1]);
        } else if (!part.isEmpty() && !part.equals("?")) {
            // 单个值
            start = end = Integer.parseInt(part);
        }

        // 添加值到集合
        for (int i = start; i <= end; i += step) {
            if (i >= min && i <= max) {
                set.add(i);
            }
        }
    }

    /**
     * 获取下一个有效执行时间
     *
     * @param afterTime 在此时间之后
     * @return 下一个执行时间
     */
    public Date getNextValidTimeAfter(Date afterTime) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTime(afterTime);
        calendar.add(Calendar.SECOND, 1); // 从下一秒开始

        // 最大尝试年份
        int maxYear = calendar.get(Calendar.YEAR) + 5;

        while (calendar.get(Calendar.YEAR) < maxYear) {
            if (isMatch(calendar)) {
                return calendar.getTime();
            }
            calendar.add(Calendar.SECOND, 1);
        }

        return null;
    }

    private boolean isMatch(Calendar calendar) {
        int second = calendar.get(Calendar.SECOND);
        int minute = calendar.get(Calendar.MINUTE);
        int hour = calendar.get(Calendar.HOUR_OF_DAY);
        int dayOfMonth = calendar.get(Calendar.DAY_OF_MONTH);
        int month = calendar.get(Calendar.MONTH) + 1; // Calendar.MONTH从0开始
        int dayOfWeek = calendar.get(Calendar.DAY_OF_WEEK); // 1=周日, 7=周六

        // 转换为Quartz的星期表示（1=周一, 7=周日）
        int quartzDayOfWeek = dayOfWeek - 1;
        if (quartzDayOfWeek == 0) {
            quartzDayOfWeek = 7;
        }

        return seconds.contains(second) &&
                minutes.contains(minute) &&
                hours.contains(hour) &&
                months.contains(month) &&
                (daysOfMonth.contains(dayOfMonth) || daysOfWeek.contains(quartzDayOfWeek));
    }

    public String getCronExpression() {
        return cronExpression;
    }
}
