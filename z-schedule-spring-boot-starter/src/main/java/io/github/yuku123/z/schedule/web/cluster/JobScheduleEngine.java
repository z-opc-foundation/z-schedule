package io.github.yuku123.z.schedule.web.cluster;

import io.github.yuku123.z.schedule.core.model.JobInfo;
import io.github.yuku123.z.schedule.web.service.JobInfoService;
import io.github.yuku123.z.schedule.web.service.JobTriggerService;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

/**
 * 调度引擎（独立线程池 + 时间轮）
 * <p>
 * <b>架构</b>：
 * <pre>
 *   ┌──────────────────────────────────────────────────────┐
 *   │  JobScheduleEngine                                   │
 *   │                                                      │
 *   │  ┌──────────┐   每 5s   ┌──────────────────────┐   │
 *   │  │ DB 加载   │ ←─────── │ LeaderElector.isLeader │   │
 *   │  │ running   │          └──────────────────────┘   │
 *   │  │ jobs      │                                      │
 *   │  └────┬─────┘                                       │
 *   │       │ 填充                                        │
 *   │       ▼                                             │
 *   │  ┌──────────┐   每 1s    ┌──────────────┐         │
 *   │  │ Schedule  │ ←──────── │ ScheduleRing  │         │
 *   │  │ Ring      │            │ (60 slots)   │         │
 *   │  └────┬─────┘            └──────────────┘         │
 *   │       │ 到期                                       │
 *   │       ▼                                             │
 *   │  ┌──────────┐   异步      ┌──────────────┐         │
 *   │  │ Execute   │ ─────────→ │ JobTriggerService │   │
 *   │  │ Pool      │            │ .triggerJob()     │   │
 *   │  └──────────┘            └──────────────┘         │
 *   └──────────────────────────────────────────────────────┘
 * </pre>
 * <p>
 * <b>关键设计</b>：
 * <ul>
 *   <li>独立 ScheduledExecutorService：每秒 tick，不依赖 Spring TaskScheduler</li>
 *   <li>独立 ExecutorService：任务执行线程池，避免阻塞调度线程</li>
 *   <li>时间轮（ScheduleRing）：60 槽位秒级调度，高效精确</li>
 *   <li>DB 感知：Leader 加载 DB 中 trigger_status=1 的任务，非 Leader 不调度</li>
 * </ul>
 * <p>
 * <b>生命周期</b>：
 * <ul>
 *   <li>start()：启动调度线程 + 执行线程池</li>
 *   <li>stop()：优雅关闭（等待任务完成）</li>
 *   <li>reloadJobs()：Leader 切换时重新加载 DB</li>
 * </ul>
 */
public class JobScheduleEngine {

    private static final Logger logger = LogManager.getLogger(JobScheduleEngine.class);

    // ---- 核心组件 ----
    private final ScheduleRing ring = new ScheduleRing();
    private final Map<Integer, Long> jobTriggerTimes = new ConcurrentHashMap<>();

    /**
     * 独立调度线程池：1 个线程，每秒 tick
     */
    private ScheduledExecutorService schedulePool;
    private ScheduledFuture<?> tickFuture;

    /**
     * 独立执行线程池：异步触发任务，避免阻塞调度线程
     */
    private ExecutorService executePool;

    /**
     * 外部依赖（通过 setter 注入）
     */
    private JobInfoService jobInfoService;
    private JobTriggerService jobTriggerService;
    private LeaderElector leaderElector;

    private volatile boolean running = false;
    private volatile boolean initialized = false;

    // ---- 注入依赖 ----
    public void setJobInfoService(JobInfoService jobInfoService) {
        this.jobInfoService = jobInfoService;
    }

    public void setJobTriggerService(JobTriggerService jobTriggerService) {
        this.jobTriggerService = jobTriggerService;
    }

    public void setLeaderElector(LeaderElector leaderElector) {
        this.leaderElector = leaderElector;
    }

    // ---- 启动 / 停止 ----

    /**
     * 启动调度引擎（创建独立线程池，开始 tick）。
     */
    public synchronized void start() {
        if (running) return;

        // 独立调度线程池：1 线程，守护线程，避免 JVM 退出时卡住
        schedulePool = Executors.newScheduledThreadPool(1, r -> {
            Thread t = new Thread(r, "z-schedule-tick");
            t.setDaemon(true);
            return t;
        });
        schedulePool.scheduleAtFixedRate(this::tick, 1, 1, TimeUnit.SECONDS);

        // 独立执行线程池：4 线程，异步触发任务
        executePool = Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "z-schedule-exec-");
            t.setDaemon(true);
            return t;
        });

        running = true;
        initialized = true;
        logger.info("[z-schedule] Engine started: schedulePool=1, executePool=4, ringSlots=60");
    }

    /**
     * 停止调度引擎（等待任务完成）。
     */
    public synchronized void stop() {
        if (!running) return;
        running = false;

        if (tickFuture != null) {
            tickFuture.cancel(false);
        }
        if (schedulePool != null) {
            schedulePool.shutdown();
            try {
                schedulePool.awaitTermination(10, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
            }
        }
        if (executePool != null) {
            executePool.shutdown();
            try {
                executePool.awaitTermination(10, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
            }
        }
        logger.info("[z-schedule] Engine stopped");
    }

    // ---- 核心调度逻辑 ----

    /**
     * 主 tick 方法（每秒执行一次）。
     */
    private void tick() {
        try {
            int second = CalendarSecond.now();
            List<Integer> dueJobs = ring.poll(second);

            if (!dueJobs.isEmpty()) {
                logger.debug("[z-schedule] Tick second={}, dueJobs={}", second, dueJobs);
            }

            for (Integer jobId : dueJobs) {
                // 异步触发，避免阻塞调度线程
                executePool.submit(() -> {
                    try {
                        JobInfo job = jobInfoService.getById(jobId);
                        if (job == null || job.getTriggerStatus() != 1) {
                            return; // 任务已删除或已停止
                        }
                        jobTriggerService.triggerJob(job);
                    } catch (Exception e) {
                        logger.error("[z-schedule] Trigger failed, jobId={}", jobId, e);
                    }
                });
                // 触发后重新挂入时间轮（计算下次触发时间）
                rescheduleJob(jobId);
            }
        } catch (Exception e) {
            logger.error("[z-schedule] Tick error", e);
        }
    }

    /**
     * 将一个已触发的任务重新挂入时间轮（基于 DB 中的 job_cron 计算下次触发时间）。
     */
    private void rescheduleJob(int jobId) {
        try {
            JobInfo job = jobInfoService.getById(jobId);
            if (job == null || job.getTriggerStatus() != 1 || job.getJobCron() == null) {
                return;
            }
            io.github.yuku123.z.schedule.core.util.CronExpression cron =
                    new io.github.yuku123.z.schedule.core.util.CronExpression(job.getJobCron());
            Date now = new Date();
            Date next = cron.getNextValidTimeAfter(now);
            if (next == null) {
                return; // Cron 无后续时间
            }
            long nextMs = next.getTime();
            ring.push(jobId, nextMs);
            jobTriggerTimes.put(jobId, nextMs);
            // 持久化 trigger_next_time
            if (jobInfoService instanceof io.github.yuku123.z.schedule.web.service.impl.JobInfoServiceImpl) {
                ((io.github.yuku123.z.schedule.web.service.impl.JobInfoServiceImpl) jobInfoService)
                        .updateTriggerTimes(jobId, System.currentTimeMillis(), nextMs);
            }
        } catch (Exception e) {
            logger.error("[z-schedule] Reschedule failed, jobId={}", jobId, e);
        }
    }

    // ---- Leader 切换时的加载逻辑 ----

    /**
     * Leader 加载：从 DB 加载所有 trigger_status=1 的任务，填充时间轮。
     * <p>
     * 每次 Leader 切换时调用一次（由 JobTriggerServiceImpl.reloadRunningJobs 触发）。
     */
    public void reloadJobs() {
        if (!initialized) {
            start();
        }
        // 清空时间轮和缓存
        ring.clear();
        jobTriggerTimes.clear();

        // 从 DB 加载所有运行中的任务
        List<JobInfo> running = jobInfoService.getAll().stream()
                .filter(j -> j.getTriggerStatus() == 1)
                .collect(java.util.stream.Collectors.toList());

        // 填充时间轮
        long nowMs = System.currentTimeMillis();
        int loaded = 0;
        for (JobInfo job : running) {
            try {
                io.github.yuku123.z.schedule.core.util.CronExpression cron =
                        new io.github.yuku123.z.schedule.core.util.CronExpression(job.getJobCron());
                Date now = new Date();
                Date next = cron.getNextValidTimeAfter(now);
                if (next == null) continue;

                long nextMs = next.getTime();
                ring.push(job.getId(), nextMs);
                jobTriggerTimes.put(job.getId(), nextMs);
                loaded++;
            } catch (Exception e) {
                logger.warn("[z-schedule] Skip job {}: cron parse error: {}", job.getId(), e.getMessage());
            }
        }

        logger.info("[z-schedule] Engine loaded {} jobs into ring (ringTotal={}, queueSize={})",
                loaded, ring.totalSize(), jobTriggerTimes.size());
    }

    // ---- 监控 / 诊断 ----

    public int scheduledJobCount() {
        return ring.totalSize();
    }

    public boolean isRunning() {
        return running;
    }

    // ---- 内部工具 ----
    private static class CalendarSecond {
        static int now() {
            java.util.Calendar cal = java.util.Calendar.getInstance();
            return cal.get(java.util.Calendar.SECOND);
        }
    }
}
