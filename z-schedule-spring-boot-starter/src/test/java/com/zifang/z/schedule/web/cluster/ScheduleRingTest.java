package com.zifang.z.schedule.web.cluster;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 时间轮语义测试。
 * <p>
 * 重点是"视界"：槽位号只是 {@code 触发秒 % 60}，同一槽位每分钟被轮询一次。
 * 旧实现把任意远的触发时间直接取模塞进槽位，于是"每天 02:00"的任务会在本分钟的
 * 第 0 秒被提前触发，此后每分钟一次；这里用一组用例把该行为钉死。
 */
public class ScheduleRingTest {

    /** 对齐到整秒，避免测试自身跨越秒边界。 */
    private static long secondAlignedMs() {
        return (System.currentTimeMillis() / 1000L) * 1000L;
    }

    @Test
    public void 未来若干秒的任务在不到触发秒时不触发() {
        ScheduleRing ring = new ScheduleRing();
        long base = secondAlignedMs();
        ring.push(7, base + 5000L, base);

        for (int sec = 0; sec < 5; sec++) {
            assertEquals("第 " + sec + " 秒不应到期", Collections.emptyList(), ring.poll(base + sec * 1000L));
        }
        assertEquals(Collections.singletonList(7), ring.poll(base + 5000L));
    }

    @Test
    public void 超过一分钟后的任务不会被提前触发() {
        ScheduleRing ring = new ScheduleRing();
        long base = secondAlignedMs();
        // 每天 02:00 这一类任务：下次触发在 24 小时后
        ring.push(42, base + 24 * 3600_000L, base);

        assertEquals(1, ring.totalSize());
        assertEquals(1, ring.overflowSize());

        // 连续轮询两整分钟（120 个秒刻度）——旧实现会在同号槽位立刻吐出 42
        for (int sec = 0; sec < 120; sec++) {
            List<Integer> due = ring.poll(base + sec * 1000L);
            assertTrue("第 " + sec + " 秒提前触发了 24 小时后的任务: " + due, due.isEmpty());
        }
    }

    @Test
    public void 视界边界正好六十秒后仍然按秒精确触发() {
        ScheduleRing ring = new ScheduleRing();
        long base = secondAlignedMs();
        ring.push(3, base + 60_000L, base);
        assertEquals(1, ring.overflowSize());

        for (int sec = 0; sec < 60; sec++) {
            assertTrue(ring.poll(base + sec * 1000L).isEmpty());
        }
        assertEquals(Collections.singletonList(3), ring.poll(base + 60_000L));
    }

    @Test
    public void 已经过期或同秒的调度按下一秒兜底而不是延后一分钟() {
        ScheduleRing ring = new ScheduleRing();
        long base = secondAlignedMs();
        ring.push(9, base - 30_000L, base);

        assertTrue(ring.poll(base).isEmpty());
        assertEquals("应在下一秒兜底触发", Collections.singletonList(9), ring.poll(base + 1000L));
    }

    @Test
    public void 同一任务重复挂载只保留最后一次调度() {
        ScheduleRing ring = new ScheduleRing();
        long base = secondAlignedMs();
        ring.push(5, base + 5000L, base);
        ring.push(5, base + 7000L, base);

        assertEquals(1, ring.totalSize());
        assertTrue("旧调度已失效", ring.poll(base + 5000L).isEmpty());
        assertEquals(Collections.singletonList(5), ring.poll(base + 7000L));
        assertEquals(0, ring.totalSize());
    }

    @Test
    public void 同一秒内多个任务按挂入顺序全部触发() {
        ScheduleRing ring = new ScheduleRing();
        long base = secondAlignedMs();
        ring.push(11, base + 2000L, base);
        ring.push(12, base + 2000L, base);
        ring.push(13, base + 2000L, base);

        List<Integer> due = ring.poll(base + 2000L);
        assertEquals(3, due.size());
        assertTrue(due.containsAll(java.util.Arrays.asList(11, 12, 13)));
    }

    @Test
    public void 同槽位重复挂载不会让任务一分钟触发两次() {
        ScheduleRing ring = new ScheduleRing();
        long base = secondAlignedMs();
        ring.push(21, base + 3000L, base);
        ring.push(21, base + 3000L, base);

        assertEquals(1, ring.totalSize());
        assertEquals(Collections.singletonList(21), ring.poll(base + 3000L));
        assertTrue(ring.poll(base + 3000L).isEmpty());
    }

    @Test
    public void 单个任务可被精准摘除() {
        ScheduleRing ring = new ScheduleRing();
        long base = secondAlignedMs();
        ring.push(31, base + 5000L, base);
        ring.push(32, base + 24 * 3600_000L, base);

        assertTrue(ring.remove(31));
        assertTrue(ring.remove(32));
        assertFalse("未调度的任务摘除应返回 false", ring.remove(999));
        assertEquals(0, ring.totalSize());
        assertEquals(0, ring.overflowSize());
        assertTrue(ring.poll(base + 5000L).isEmpty());
    }

    @Test
    public void 摘除任务不影响同槽位的其它任务() {
        ScheduleRing ring = new ScheduleRing();
        long base = secondAlignedMs();
        ring.push(41, base + 5000L, base);
        ring.push(42, base + 5000L, base);
        ring.remove(41);

        assertEquals(Collections.singletonList(42), ring.poll(base + 5000L));
    }

    @Test
    public void 清空后所有在途调度归零() {
        ScheduleRing ring = new ScheduleRing();
        long base = secondAlignedMs();
        for (int i = 0; i < 20; i++) {
            ring.push(i, base + (i % 60) * 1000L + 1000L, base);
        }
        ring.push(1000, base + 3600_000L, base);
        assertEquals(21, ring.totalSize());

        ring.clear();
        assertEquals(0, ring.totalSize());
        assertEquals(0, ring.overflowSize());
        assertEquals(0L, ring.nextFireTime(1000));
    }

    @Test
    public void nextFireTime反映登记的触发时间() {
        ScheduleRing ring = new ScheduleRing();
        long base = secondAlignedMs();
        ring.push(51, base + 20_000L, base);
        assertEquals(base + 20_000L, ring.nextFireTime(51));

        ring.poll(base + 20_000L);
        assertEquals("触发后不再有待调度", 0L, ring.nextFireTime(51));
    }

    @Test
    public void 每分钟重复轮询同一秒位不会二次触发() {
        ScheduleRing ring = new ScheduleRing();
        long base = secondAlignedMs();
        ring.push(61, base + 61_000L, base);

        List<Integer> all = new ArrayList<Integer>();
        for (int sec = 0; sec < 180; sec++) {
            all.addAll(ring.poll(base + sec * 1000L));
        }
        assertEquals("三个整分钟里只应触发一次", Collections.singletonList(61), all);
    }

    @Test
    public void 批量任务的触发时间互不串扰() {
        ScheduleRing ring = new ScheduleRing();
        long base = secondAlignedMs();
        Random random = new Random(20260925L);
        int count = 2000;
        int[] fireAt = new int[count];
        for (int jobId = 0; jobId < count; jobId++) {
            fireAt[jobId] = random.nextInt(300);
            ring.push(jobId, base + (fireAt[jobId] + 1L) * 1000L, base);
        }
        assertEquals(count, ring.totalSize());

        int[] fired = new int[count];
        for (int sec = 0; sec <= 300; sec++) {
            for (int jobId : ring.poll(base + sec * 1000L)) {
                fired[jobId]++;
                assertEquals("jobId=" + jobId + " 提前/延后触发", fireAt[jobId] + 1, sec);
            }
        }
        for (int jobId = 0; jobId < count; jobId++) {
            assertEquals("jobId=" + jobId + " 触发次数", 1, fired[jobId]);
        }
    }

    @Test
    public void 十万级规模下挂载与轮询的耗时() {
        ScheduleRing ring = new ScheduleRing();
        long base = secondAlignedMs();
        Random random = new Random(7L);
        int count = 100_000;
        int horizonSec = 86_400;

        int[] offsetSec = new int[count];
        long pushStart = System.nanoTime();
        for (int jobId = 0; jobId < count; jobId++) {
            offsetSec[jobId] = 1 + random.nextInt(horizonSec);
            ring.push(jobId, base + offsetSec[jobId] * 1000L, base);
        }
        long pushNanos = System.nanoTime() - pushStart;

        int windowSec = 120;
        int expectedInWindow = 0;
        for (int offset : offsetSec) {
            if (offset <= windowSec) {
                expectedInWindow++;
            }
        }

        int[] fired = new int[count];
        long tickStart = System.nanoTime();
        for (int sec = 0; sec <= windowSec; sec++) {
            for (int jobId : ring.poll(base + sec * 1000L)) {
                fired[jobId]++;
            }
        }
        long tickNanos = System.nanoTime() - tickStart;

        int firedTotal = 0;
        for (int jobId = 0; jobId < count; jobId++) {
            int times = fired[jobId];
            if (offsetSec[jobId] <= windowSec) {
                assertEquals("jobId=" + jobId + " 应在窗口内恰好触发一次", 1, times);
                firedTotal++;
            } else {
                assertEquals("jobId=" + jobId + " 未到时间却被触发", 0, times);
            }
        }
        assertEquals(expectedInWindow, firedTotal);
        // 窗口最后一秒被晋升进槽位、尚未到秒的任务仍算"在调度中"，因此只精确断言总数
        assertEquals(count - expectedInWindow, ring.totalSize());
        assertTrue("overflow 不可能多于在途调度", ring.overflowSize() <= ring.totalSize());

        System.out.println("[ScheduleRing] 100k push = " + (pushNanos / 1_000_000L) + " ms ("
                + (pushNanos / (double) count) + " ns/op), " + (windowSec + 1) + " tick = "
                + (tickNanos / 1_000_000L) + " ms, 窗口内触发 " + firedTotal + " 次, 仍排队 "
                + ring.overflowSize());
        assertTrue("100k 挂载应是线性时间", pushNanos / count < 5_000L);
        assertTrue("121 次轮询总耗时应在毫秒级", tickNanos < 200_000_000L);
    }
}
