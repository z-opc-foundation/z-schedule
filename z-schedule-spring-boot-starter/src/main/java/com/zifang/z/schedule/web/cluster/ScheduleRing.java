package com.zifang.z.schedule.web.cluster;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * 时间轮 —— 60 个秒槽位 + 一个远期待调度队列（overflow wheel）。
 * <p>
 * 槽位索引由触发时间的<b>整秒</b>决定（{@code fireSec % 60}），因此一条记录只能被放入
 * 未来 60 秒之内的槽位；超过这个视界的任务必须留在 {@link #overflow} 里，等它进入视界再晋升。
 * 若直接把 24 小时后的时间取模塞进槽位，任务会被提前最多 59 秒触发，并且此后每分钟触发一次。
 * <p>
 * 晋升条件 {@code 0 < fireSec - nowSec < 60} 保证记录首次被轮询到时必有 {@code fireSec == nowSec}
 * （两者对 60 同余，差值是 60 的倍数且落在 [0,60) 内），因此既不提前也不延后。
 * <p>
 * 每个 jobId 只有一个生效中的调度：{@link #push} 覆盖 {@link #scheduledSec}，
 * 槽位里残留的旧记录在轮询时因秒数不匹配被丢弃，所以 Leader 反复 reload 不会造成重复触发。
 */
public class ScheduleRing {

    static final int RING_SIZE = 60;

    private final List<List<Job>> ring = new ArrayList<List<Job>>(RING_SIZE);

    /** 视界之外的任务，按触发秒升序；每 tick 从队首晋升。 */
    private final PriorityQueue<Job> overflow = new PriorityQueue<Job>(64, Job.BY_FIRE_SEC);

    /** jobId -> 当前生效的触发秒；用于去重与失效旧记录。 */
    private final Map<Integer, Long> scheduledSec = new HashMap<Integer, Long>();

    public ScheduleRing() {
        for (int i = 0; i < RING_SIZE; i++) {
            ring.add(new ArrayList<Job>(4));
        }
    }

    public boolean push(int jobId, long triggerTimeMs) {
        return push(jobId, triggerTimeMs, System.currentTimeMillis());
    }

    /**
     * 将任务挂入指定触发时间。
     *
     * @param triggerTimeMs 下次触发时间（epoch ms）；早于 {@code nowMs} 所在秒时按下一秒兜底，
     *                      避免落入已经轮询过的槽位而延后整整一分钟
     * @return true 表示该任务本次登记改变了它的触发时间
     */
    public synchronized boolean push(int jobId, long triggerTimeMs, long nowMs) {
        long nowSec = floorSec(nowMs);
        long fireSec = floorSec(triggerTimeMs);
        if (fireSec <= nowSec) {
            fireSec = nowSec + 1;
        }
        Long previous = scheduledSec.put(jobId, fireSec);

        Job job = new Job(jobId, fireSec);
        if (fireSec - nowSec < RING_SIZE) {
            ring.get((int) (fireSec % RING_SIZE)).add(job);
        } else {
            overflow.add(job);
        }
        return previous == null || !previous.equals(fireSec);
    }

    /**
     * 移出任务的所有待触发记录（停止/删除任务时调用，避免整轮 reload）。
     *
     * @return true 表示确实移除了一个在途调度
     */
    public synchronized boolean remove(int jobId) {
        boolean changed = false;
        for (List<Job> slot : ring) {
            if (!slot.isEmpty()) {
                changed |= removeAll(slot, jobId);
            }
        }
        changed |= overflow.remove(new Job(jobId, 0L));
        if (changed) {
            scheduledSec.remove(jobId);
        }
        return changed;
    }

    private static boolean removeAll(List<Job> slot, int jobId) {
        boolean removed = false;
        for (int i = slot.size() - 1; i >= 0; i--) {
            if (slot.get(i).jobId == jobId) {
                slot.remove(i);
                removed = true;
            }
        }
        return removed;
    }

    public List<Integer> poll() {
        return poll(System.currentTimeMillis());
    }

    /**
     * 取出到期的任务并清空对应槽位。槽位号与"当前秒"取自同一次读数，避免跨秒边界时二者错位。
     *
     * @return 到期的任务 ID 列表（不可变快照，按挂入顺序）
     */
    public synchronized List<Integer> poll(long nowMs) {
        long nowSec = floorSec(nowMs);
        promote(nowSec);

        List<Job> slot = ring.get((int) (nowSec % RING_SIZE));
        if (slot.isEmpty()) {
            return Collections.emptyList();
        }
        List<Integer> due = null;
        for (int i = slot.size() - 1; i >= 0; i--) {
            Job job = slot.get(i);
            Long active = scheduledSec.get(job.jobId);
            if (active == null || !active.equals(job.fireSec)) {
                slot.remove(i);
            } else if (job.fireSec <= nowSec) {
                slot.remove(i);
                scheduledSec.remove(job.jobId);
                if (due == null) {
                    due = new ArrayList<Integer>(4);
                }
                due.add(job.jobId);
            }
        }
        if (due == null) {
            return Collections.emptyList();
        }
        Collections.reverse(due);
        return due;
    }

    /** 把进入视界的任务从 overflow 搬到槽位。 */
    private void promote(long nowSec) {
        Job head;
        while ((head = overflow.peek()) != null && head.fireSec - nowSec < RING_SIZE) {
            overflow.poll();
            Long active = scheduledSec.get(head.jobId);
            if (active == null || !active.equals(head.fireSec)) {
                continue;
            }
            ring.get((int) (head.fireSec % RING_SIZE)).add(head);
        }
    }

    /**
     * 清空所有待触发记录（Leader 重新加载 DB 时使用）。
     */
    public synchronized void clear() {
        for (List<Job> slot : ring) {
            slot.clear();
        }
        overflow.clear();
        scheduledSec.clear();
    }

    /** 统计当前时间轮中的总任务数（监控/诊断用）。 */
    public synchronized int totalSize() {
        return scheduledSec.size();
    }

    /** 视界之外、仍在 overflow 中排队的任务数。 */
    public synchronized int overflowSize() {
        return overflow.size();
    }

    /** 任务当前登记的触发时间（epoch ms），未调度返回 0。 */
    public synchronized long nextFireTime(int jobId) {
        Long sec = scheduledSec.get(jobId);
        return sec == null ? 0L : sec * 1000L;
    }

    private static long floorSec(long ms) {
        return Math.floorDiv(ms, 1000L);
    }

    /** 槽位记录：fireSec 相同的同一 jobId 由 scheduledSec 判活。 */
    private static final class Job {
        static final Comparator<Job> BY_FIRE_SEC = new Comparator<Job>() {
            public int compare(Job a, Job b) {
                return Long.compare(a.fireSec, b.fireSec);
            }
        };

        final int jobId;
        final long fireSec;

        Job(int jobId, long fireSec) {
            this.jobId = jobId;
            this.fireSec = fireSec;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Job && ((Job) o).jobId == jobId;
        }

        @Override
        public int hashCode() {
            return jobId;
        }
    }
}
