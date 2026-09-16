package io.github.yuku123.z.schedule.web.cluster;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 时间轮（环形缓冲区）—— 60 个槽位，每个槽位代表 1 秒。
 * <p>
 * 设计思路：参考 XXL-JOB 的 ScheduleRing，将任务按"下次触发时间的秒数"放入对应槽位。
 * 调度线程每秒 tick 一次，取出当前秒的槽位并触发其中所有任务，然后将任务重新挂入下一个触发秒的槽位。
 * <p>
 * 与 Spring TaskScheduler 的区别：
 * <ul>
 *   <li>单线程遍历：不需要 per-job ScheduledFuture，减少内存开销</li>
 *   <li>DB 感知：任务来自 DB 查询，重启后自动恢复</li>
 *   <li>集群协调：仅 Leader 节点运行调度线程</li>
 * </ul>
 * <p>
 * 使用方式：
 * <pre>
 *   ScheduleRing ring = new ScheduleRing();
 *   ring.push(jobId, triggerTimeMs);  // 将任务放入对应秒的槽位
 *   List&lt;Integer&gt; due = ring.poll(currentSecond);  // 取出当前秒到期的任务
 * </pre>
 */
public class ScheduleRing {

    /**
     * 60 个槽位，索引 0-59 对应当前分钟的第 0-59 秒
     */
    private static final int RING_SIZE = 60;

    /**
     * 每个槽位存储一个任务 ID 列表。
     * <p>
     * 例如 slot[30] 里存了 {jobId=1, jobId=3}，表示 job1 和 job3 在当前分钟的第 30 秒触发。
     * <p>
     * 采用 synchronized copy-on-read：push 时写副本，poll 时取快照后清空原槽位，
     * 避免并发读写导致 ConcurrentModificationException。
     */
    private final List<List<Integer>> ring = new ArrayList<>(RING_SIZE);

    public ScheduleRing() {
        for (int i = 0; i < RING_SIZE; i++) {
            ring.add(new ArrayList<>());
        }
    }

    /**
     * 将任务挂入时间轮的指定秒槽位。
     *
     * @param jobId         任务 ID
     * @param triggerTimeMs 任务下次触发时间（epoch ms），秒部分决定槽位
     */
    public synchronized void push(int jobId, long triggerTimeMs) {
        int ringIndex = (int) ((triggerTimeMs / 1000) % RING_SIZE);
        ring.get(ringIndex).add(jobId);
    }

    /**
     * 取出当前秒槽位中的所有任务，并清空该槽位。
     *
     * @param currentSecond 当前秒数（0-59）
     * @return 到期的任务 ID 列表（不可变快照）
     */
    public synchronized List<Integer> poll(int currentSecond) {
        int idx = currentSecond % RING_SIZE;
        List<Integer> jobs = ring.get(idx);
        if (jobs.isEmpty()) {
            return Collections.emptyList();
        }
        List<Integer> snapshot = new ArrayList<>(jobs);
        jobs.clear();
        return snapshot;
    }

    /**
     * 清空所有槽位（Leader 重新加载 DB 时使用）。
     */
    public synchronized void clear() {
        for (List<Integer> slot : ring) {
            slot.clear();
        }
    }

    /**
     * 统计当前时间轮中的总任务数（监控/诊断用）。
     */
    public synchronized int totalSize() {
        int total = 0;
        for (List<Integer> slot : ring) {
            total += slot.size();
        }
        return total;
    }
}
