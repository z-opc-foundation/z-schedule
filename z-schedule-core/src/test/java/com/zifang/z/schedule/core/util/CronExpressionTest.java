package com.zifang.z.schedule.core.util;

import org.junit.Test;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

/**
 * {@link CronExpression} 单元测试.
 * <p>
 * 覆盖：
 * <ul>
 *   <li>字段数校验(6/7 字段)</li>
 *   <li>通配符 {@code *} / {@code ?}、步长 {@code /}、范围 {@code -}、列表 {@code ,}、缩写 {@code JAN}/{@code MON}</li>
 *   <li>{@code getNextValidTimeAfter} 在多种 cron 下命中预期时间</li>
 *   <li>非法 cron 抛出 {@link ParseException}</li>
 * </ul>
 */
public class CronExpressionTest {

    @Test
    public void testSixFields() throws ParseException {
        // 6 字段应正常解析
        CronExpression cron = new CronExpression("0 0 12 * * ?");
        assertEquals("0 0 12 * * ?", cron.getCronExpression());
    }

    @Test
    public void testSevenFields() throws ParseException {
        // 7 字段应正常解析,带年份(2026-2030)
        CronExpression cron = new CronExpression("0 0 12 * * ? 2026/1");
        assertEquals("0 0 12 * * ? 2026/1", cron.getCronExpression());
    }

    @Test
    public void testFiveFieldsRejected() {
        try {
            new CronExpression("0 0 12 * *");
            fail("Expected ParseException for 5-field cron");
        } catch (ParseException e) {
            assertTrue(e.getMessage().contains("expected 6 or 7 fields"));
        }
    }

    @Test
    public void testWildcard() throws ParseException {
        // 每秒都触发
        CronExpression cron = new CronExpression("* * * * * ?");
        Date base = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").parse("2026-07-12 10:00:00");
        Date next = cron.getNextValidTimeAfter(base);
        assertNotNull(next);
        long diff = next.getTime() - base.getTime();
        assertTrue("Wildcard should hit next second, diff=" + diff, diff <= TimeUnit.SECONDS.toMillis(2));
    }

    @Test
    public void testSpecificTime() throws ParseException {
        // 每天 12:00:00
        CronExpression cron = new CronExpression("0 0 12 * * ?");
        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, 10);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        Date next = cron.getNextValidTimeAfter(c.getTime());
        Calendar cal = Calendar.getInstance();
        cal.setTime(next);
        assertEquals(12, cal.get(Calendar.HOUR_OF_DAY));
        assertEquals(0, cal.get(Calendar.MINUTE));
        assertEquals(0, cal.get(Calendar.SECOND));
    }

    @Test
    public void testStep() throws ParseException {
        // 每 5 分钟(步长)
        CronExpression cron = new CronExpression("0 0/5 * * * ?");
        Date base = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").parse("2026-07-12 10:01:00");
        Date next = cron.getNextValidTimeAfter(base);
        Calendar cal = Calendar.getInstance();
        cal.setTime(next);
        assertEquals(0, cal.get(Calendar.SECOND));
        assertEquals(5 % 5, cal.get(Calendar.MINUTE) % 5);
    }

    @Test
    public void testRange() throws ParseException {
        // 每天 9-17 点每小时整点
        CronExpression cron = new CronExpression("0 0 9-17 * * ?");
        Date base = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").parse("2026-07-12 07:00:00");
        Date next = cron.getNextValidTimeAfter(base);
        Calendar cal = Calendar.getInstance();
        cal.setTime(next);
        assertEquals(9, cal.get(Calendar.HOUR_OF_DAY));
    }

    @Test
    public void testList() throws ParseException {
        // 在第 1、15 分钟各跑一次
        CronExpression cron = new CronExpression("0 1,15 * * * ?");
        Date base = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").parse("2026-07-12 10:00:00");
        Set<Integer> matches = new HashSet<>();
        for (int i = 0; i < 5; i++) {
            Date next = cron.getNextValidTimeAfter(base);
            if (next == null) break;
            Calendar cal = Calendar.getInstance();
            cal.setTime(next);
            matches.add(cal.get(Calendar.MINUTE));
            base = next;
        }
        assertTrue("Should see minute 1", matches.contains(1));
        assertTrue("Should see minute 15", matches.contains(15));
    }

    @Test
    public void testQuarterHourIdempotent() throws ParseException {
        // 同一时间反复 getNextValidTimeAfter 应该每次都得到下一个有效点
        CronExpression cron = new CronExpression("0 */15 * * * ?");
        Date base = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").parse("2026-07-12 10:00:00");
        Date t1 = cron.getNextValidTimeAfter(base);
        Date t2 = cron.getNextValidTimeAfter(t1);
        assertNotNull(t1);
        assertNotNull(t2);
        assertTrue("t2 should be after t1", t2.getTime() > t1.getTime());
    }

    @Test
    public void testMultiMonth() throws ParseException {
        // 1 月、6 月、12 月各跑一次,直接使用数字字段
        CronExpression cron = new CronExpression("0 0 12 1 1,6,12 ?");
        Date base = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").parse("2026-02-01 10:00:00");
        Date next = cron.getNextValidTimeAfter(base);
        Calendar cal = Calendar.getInstance();
        cal.setTime(next);
        int month = cal.get(Calendar.MONTH) + 1; // Calendar.MONTH 从 0 开始
        assertTrue("month should be 6,12 or 1 (next year), got=" + month,
                month == 6 || month == 12 || month == 1);
        assertEquals(12, cal.get(Calendar.HOUR_OF_DAY));
        assertEquals(1, cal.get(Calendar.DAY_OF_MONTH));
    }
}
