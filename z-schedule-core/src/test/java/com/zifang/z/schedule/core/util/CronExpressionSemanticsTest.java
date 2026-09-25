package com.zifang.z.schedule.core.util;

import org.junit.Test;

import java.text.ParseException;
import java.util.Calendar;
import java.util.Date;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Cron 语义回归：日/周组合、月份与星期名称、以及"下次触发"的求解代价。
 * <p>
 * 这里钉住的是解析器改写成字段集跳跃求解之后必须成立的行为——尤其是旧实现里
 * {@code 0 0 12 * * MON} 会退化成"每天 12 点"、{@code JAN}/{@code MON} 直接抛
 * {@link NumberFormatException}（而调用方只捕 {@link ParseException}）这两处。
 */
public class CronExpressionSemanticsTest {

    /** 固定基准时刻：2026-09-25（周五）09:00:00.000 本地时区。 */
    private static final Date BASE = dateOf(2026, Calendar.SEPTEMBER, 25, 9, 0, 0);

    @Test
    public void 仅限定星期时日字段不得放行每一天() throws ParseException {
        CronExpression cron = new CronExpression("0 0 12 * * MON");

        Date cursor = BASE;
        long spanMillis = 0L;
        for (int i = 0; i < 6; i++) {
            Date previous = cursor;
            cursor = cron.getNextValidTimeAfter(cursor);
            assertNotNull(cursor);
            assertEquals("第 " + i + " 次触发必须落在周一", Calendar.MONDAY, dayOfWeekOf(cursor));
            assertEquals("时间固定 12:00:00", 0, secondOf(cursor) + minuteOf(cursor));
            assertEquals(12, hourOf(cursor));
            if (i > 0) {
                long gapDays = TimeUnit.MILLISECONDS.toDays(cursor.getTime() - previous.getTime());
                assertTrue("相邻两个周一应相隔 6-8 天，实测 " + gapDays, gapDays >= 6 && gapDays <= 8);
            }
            spanMillis += cursor.getTime() - previous.getTime();
        }
        assertTrue("6 次触发就跨了一个多月，旧实现会当成每天触发",
                spanMillis >= TimeUnit.DAYS.toMillis(35));
    }

    @Test
    public void 仅限定几号时星期字段不得放行其它日() throws ParseException {
        CronExpression cron = new CronExpression("0 0 0 15 * ?");

        Date first = cron.getNextValidTimeAfter(BASE);
        assertEquals(2026, yearOf(first));
        assertEquals(Calendar.OCTOBER, monthOf(first));
        assertEquals(15, dayOfMonthOf(first));

        Date second = cron.getNextValidTimeAfter(first);
        assertEquals("每月一次", Calendar.NOVEMBER, monthOf(second));
        assertEquals(15, dayOfMonthOf(second));
    }

    @Test
    public void 日与周都被限定时取并集() throws ParseException {
        // Quartz 语义："每月 15 号 或 每周三" 是两类触发条件的合集，不是交集
        CronExpression cron = new CronExpression("0 0 0 15 * 4");

        Date cursor = BASE;
        boolean sawWednesday = false;
        boolean saw15th = false;
        for (int i = 0; i < 45; i++) {
            cursor = cron.getNextValidTimeAfter(cursor);
            assertNotNull(cursor);
            boolean wednesday = dayOfWeekOf(cursor) == Calendar.WEDNESDAY;
            boolean fifteenth = dayOfMonthOf(cursor) == 15;
            assertTrue("并集里不该出现既非周三又非 15 号的日子: " + cursor, wednesday || fifteenth);
            sawWednesday |= wednesday;
            saw15th |= fifteenth;
        }
        assertTrue("45 次触发覆盖了 3 个月，周三这条分支必须命中", sawWednesday);
        assertTrue("15 号这条分支也必须命中", saw15th);
    }

    @Test
    public void 月份与星期支持英文名() throws ParseException {
        CronExpression feb = new CronExpression("0 0 0 1 FEB ?");
        Date next = feb.getNextValidTimeAfter(BASE);
        assertNotNull(next);
        assertEquals("FEB 应解析成 2 月", Calendar.FEBRUARY, monthOf(next));
        assertEquals(2027, yearOf(next));
        assertEquals(1, dayOfMonthOf(next));

        CronExpression sun = new CronExpression("0 0 12 ? * SUN");
        Date sunday = sun.getNextValidTimeAfter(BASE);
        assertEquals("SUN 应解析成周日", Calendar.SUNDAY, dayOfWeekOf(sunday));

        CronExpression range = new CronExpression("0 0 12 ? * MON-FRI");
        Date weekday = range.getNextValidTimeAfter(BASE);
        assertTrue("MON-FRI 之内",
                dayOfWeekOf(weekday) >= Calendar.MONDAY && dayOfWeekOf(weekday) <= Calendar.FRIDAY);

        // 0 与 1 同为周日（POSIX 写法）
        CronExpression zero = new CronExpression("0 0 12 ? * 0");
        assertEquals(Calendar.SUNDAY, dayOfWeekOf(zero.getNextValidTimeAfter(BASE)));

        CronExpression lowerCase = new CronExpression("0 0 0 1 feb ?");
        assertEquals(Calendar.FEBRUARY, monthOf(lowerCase.getNextValidTimeAfter(BASE)));
    }

    @Test
    public void 年字段跳跃到目标年而非逐年扫描() throws ParseException {
        CronExpression cron = new CronExpression("0 0 0 1 1 ? 2030");

        long start = System.nanoTime();
        Date next = cron.getNextValidTimeAfter(BASE);
        long costMs = (System.nanoTime() - start) / 1_000_000L;

        assertNotNull(next);
        assertEquals(2030, yearOf(next));
        assertEquals(Calendar.JANUARY, monthOf(next));
        assertEquals(1, dayOfMonthOf(next));
        assertEquals(0, hourOf(next));
        assertTrue("跳到 4 年后不该秒级扫描，实测 " + costMs + " ms", costMs < 200L);
    }

    @Test
    public void 没有未来触发时间的表达式返回null() throws ParseException {
        CronExpression past = new CronExpression("0 0 0 1 1 ? 2020");
        assertNull(past.getNextValidTimeAfter(BASE));

        CronExpression everySecond = new CronExpression("* * * * * ?");
        Date next = everySecond.getNextValidTimeAfter(BASE);
        assertEquals("秒级表达式应命中下一秒", 1000L, next.getTime() - BASE.getTime());
    }

    @Test
    public void 步长与列表与区间组合() throws ParseException {
        CronExpression step = new CronExpression("0/15 * * * * ?");
        Date first = step.getNextValidTimeAfter(dateOf(2026, Calendar.SEPTEMBER, 25, 9, 7, 3));
        assertEquals(9, hourOf(first));
        assertEquals(7, minuteOf(first));
        assertEquals(15, secondOf(first));

        Date second = step.getNextValidTimeAfter(first);
        assertEquals(30, secondOf(second));

        CronExpression list = new CronExpression("0 1,15,30 9-10 * * ?");
        Date hit = list.getNextValidTimeAfter(dateOf(2026, Calendar.SEPTEMBER, 25, 9, 14, 0));
        assertEquals(9, hourOf(hit));
        assertEquals(15, minuteOf(hit));
        Date nextHour = list.getNextValidTimeAfter(dateOf(2026, Calendar.SEPTEMBER, 25, 9, 30, 0));
        assertEquals(10, hourOf(nextHour));
        assertEquals(1, minuteOf(nextHour));
    }

    @Test
    public void 未知符号给出清晰的拒绝信息() {
        assertParseFails("0 0 0 L * ?", "L");
        assertParseFails("0 0 0 1W * ?", "W");
        assertParseFails("0 0 0 ? * 6#3", "#");
        assertParseFails("0 0 12 * * ?", null);          // 合法，不能抛
        assertParseFails("0 0 12 * 13 ?", "month");       // 越界
        assertParseFails("0 0 12 ? * 8", "day-of-week");   // 越界
        assertParseFails("0 0 12 1,,2 * ?", "empty value");
        assertParseFails("0 0 12 1-? * *", "?");
        assertParseFails("0 60 12 * * ?", "minute");       // 分钟上界 59
    }

    @Test
    public void 五分钟表达式被拒绝而不是被当成六段() {
        try {
            new CronExpression("0 12 * * ?");
            fail("5-field expression must be rejected");
        } catch (ParseException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("expected 6 or 7 fields"));
        }
    }

    @Test
    public void 每日与每年crons的求解耗时() throws ParseException {
        String[] crons = new String[]{
                "0 0 2 * * ?",           // 每日
                "0 0 0 * * ?",           // 每日零点
                "0 15 10 ? * MON-FRI",  // 工作日
                "0 0 0 1 1 ?",           // 每年
                "0 0 0 1 JAN ?",         // 每年（名称）
                "59 59 23 31 12 ?",      // 每年最后一次
        };
        long worstMs = 0L;
        for (String expr : crons) {
            CronExpression cron = new CronExpression(expr);
            Date cursor = BASE;
            long start = System.nanoTime();
            for (int i = 0; i < 50; i++) {
                cursor = cron.getNextValidTimeAfter(cursor);
                assertNotNull(expr, cursor);
            }
            long costNs = (System.nanoTime() - start) / 50;
            worstMs = Math.max(worstMs, costNs / 1_000_000L);
            System.out.println("[CronExpression] " + expr + " 连续 50 次求解 = "
                    + (costNs / 1000.0) + " us/次");
        }
        assertTrue("最坏 " + worstMs + " ms/次，跳跃求解不该到毫秒级", worstMs < 5L);
    }

    // ---- 工具 ----

    private static Date dateOf(int year, int month, int day, int hour, int minute, int second) {
        Calendar cal = Calendar.getInstance();
        cal.clear();
        cal.set(year, month, day, hour, minute, second);
        return cal.getTime();
    }

    private static int yearOf(Date date) {
        return field(date, Calendar.YEAR);
    }

    private static int monthOf(Date date) {
        return field(date, Calendar.MONTH);
    }

    private static int dayOfMonthOf(Date date) {
        return field(date, Calendar.DAY_OF_MONTH);
    }

    private static int dayOfWeekOf(Date date) {
        return field(date, Calendar.DAY_OF_WEEK);
    }

    private static int hourOf(Date date) {
        return field(date, Calendar.HOUR_OF_DAY);
    }

    private static int minuteOf(Date date) {
        return field(date, Calendar.MINUTE);
    }

    private static int secondOf(Date date) {
        return field(date, Calendar.SECOND);
    }

    private static int field(Date date, int field) {
        Calendar cal = Calendar.getInstance();
        cal.setTime(date);
        return cal.get(field);
    }

    private void assertParseFails(String expression, String expectedFragment) {
        try {
            new CronExpression(expression);
            if (expectedFragment == null) {
                return; // 该表达式本就合法
            }
            fail("should reject: " + expression);
        } catch (ParseException e) {
            assertNotNull(e.getMessage());
            if (expectedFragment != null) {
                assertTrue("[" + expression + "] 的报错应包含 \"" + expectedFragment + "\"，实际: "
                        + e.getMessage(), e.getMessage().contains(expectedFragment));
            }
        }
    }
}
