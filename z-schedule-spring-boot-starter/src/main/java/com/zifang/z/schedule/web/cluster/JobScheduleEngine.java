package com.zifang.z.schedule.web.cluster;

import com.zifang.z.schedule.core.config.ScheduleProperties;
import com.zifang.z.schedule.core.enums.ExecutorBlockStrategyEnum;
import com.zifang.z.schedule.core.enums.MisfireStrategyEnum;
import com.zifang.z.schedule.core.enums.TriggerTypeEnum;
import com.zifang.z.schedule.core.model.JobInfo;
import com.zifang.z.schedule.core.util.CronExpression;
import com.zifang.z.schedule.web.service.JobInfoService;
import com.zifang.z.schedule.web.service.JobTriggerService;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.text.ParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 调度引擎：时间轮 + 快/慢执行线程池 + 每任务串行链。
 * <p>
 * <b>tick 线程不做任何 I/O</b>：任务快照与下次触发时间的计算全部在内存里完成，
 * {@code trigger_last_time/trigger_next_time} 由独立的 flush 线程批量落库。
 * 旧实现在 tick 线程上对每个到期任务做一次 {@code getById} + 一次 UPDATE + 一次 cron 重解析，
 * 单个慢查询就会让整个时间轮停摆，且所有其它任务的触发时间一起被拖后。
 * <p>
 * <b>阻塞策略</b>由 {@link #dispatch} 兑现（SERIAL_EXECUTION 排队 / DISCARD_LATER 丢弃 /
 * COVER_EARLY 中断在跑的那次），排队不占用线程池 worker：一个任务同时只有一个链式任务在池里。
 */
public class JobScheduleEngine {

    private static final Logger logger = LogManager.getLogger(JobScheduleEngine.class);

    /** 单任务排队上限，避免调度器长时间停滞后来不及消费的触发把内存吃满。 */
    static final int SERIAL_QUEUE_MAX = 100;

    /** 已解析 cron 的共享上限；CronExpression 不可变且线程安全，同表达式的任务共用一个实例。 */
    private static final int CRON_CACHE_MAX = 512;
    private static final Map<String, CronExpression> CRON_CACHE =
            Collections.synchronizedMap(new LinkedHashMap<String, CronExpression>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, CronExpression> eldest) {
                    return size() > CRON_CACHE_MAX;
                }
            });

    private final ScheduleRing ring = new ScheduleRing();

    /** jobId -> 调度快照（含 cron 与下次触发时间），tick 线程只读它。 */
    private final Map<Integer, ScheduledJob> scheduled = new ConcurrentHashMap<Integer, ScheduledJob>();

    /** jobId -> 在跑/排队状态。 */
    private final Map<Integer, RunState> runs = new ConcurrentHashMap<Integer, RunState>();

    /** jobId -> {lastFireMs, nextFireMs}，由 flush 线程批量落库。 */
    private final Map<Integer, long[]> pendingPersist = new ConcurrentHashMap<Integer, long[]>();

    /** jobId -> 平均执行耗时（ms），用于快/慢池路由。 */
    private final Map<Integer, Long> jobAvgCostMap = new ConcurrentHashMap<Integer, Long>();

    private JobInfoService jobInfoService;
    private JobTriggerService jobTriggerService;
    private LeaderElector leaderElector;

    private ScheduleProperties properties = new ScheduleProperties();

    private ScheduledExecutorService schedulePool;
    private ScheduledExecutorService persistPool;
    private ThreadPoolExecutor fastPool;
    private ThreadPoolExecutor slowPool;

    private volatile boolean running = false;
    private volatile boolean initialized = false;

    // ---- 依赖注入 ----

    public void setJobInfoService(JobInfoService jobInfoService) {
        this.jobInfoService = jobInfoService;
    }

    public void setJobTriggerService(JobTriggerService jobTriggerService) {
        this.jobTriggerService = jobTriggerService;
    }

    public void setLeaderElector(LeaderElector leaderElector) {
        this.leaderElector = leaderElector;
    }

    public void setProperties(ScheduleProperties properties) {
        if (properties != null) {
            this.properties = properties;
        }
    }

    // ---- 启动 / 停止 ----

    public synchronized void start() {
        start(true);
    }

    /**
     * @param withTickLoop false 时只建线程池、不起 tick 循环，由外部驱动 {@link #tick(long)}
     *                     （嵌入式部署与测试用它避免和真实时钟抢同一轮）
     */
    public synchronized void start(boolean withTickLoop) {
        if (running) {
            return;
        }
        int fastMax = positive(properties.getTriggerPoolFastMax(), 200);
        int slowMax = positive(properties.getTriggerPoolSlowMax(), 200);

        schedulePool = Executors.newScheduledThreadPool(1, namedFactory("z-schedule-tick"));
        if (withTickLoop) {
            schedulePool.scheduleAtFixedRate(new Runnable() {
                public void run() {
                    tick();
                }
            }, 1, 1, TimeUnit.SECONDS);
        }

        persistPool = Executors.newScheduledThreadPool(1, namedFactory("z-schedule-persist"));
        if (withTickLoop) {
            persistPool.scheduleAtFixedRate(new Runnable() {
                public void run() {
                    flushPersistedTimes();
                }
            }, 2, 2, TimeUnit.SECONDS);
        }

        fastPool = boundedPool(fastMax, "z-schedule-fast-");
        slowPool = boundedPool(slowMax, "z-schedule-slow-");

        running = true;
        initialized = true;
        logger.info("[z-schedule] Engine started: fastPool={}, slowPool={}, slowThresholdMs={}, ringSlots={}",
                fastMax, slowMax, properties.getTriggerPoolSlowThreshold(), ScheduleRing.RING_SIZE);
    }

    private static ThreadPoolExecutor boundedPool(int max, String prefix) {
        ThreadPoolExecutor pool = new ThreadPoolExecutor(max, max, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<Runnable>(), namedFactory(prefix));
        pool.allowCoreThreadTimeOut(false);
        return pool;
    }

    private static int positive(int value, int fallback) {
        return value > 0 ? value : fallback;
    }

    private static ThreadFactory namedFactory(final String prefix) {
        return new ThreadFactory() {
            private int seq;

            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, prefix + (++seq));
                t.setDaemon(true);
                return t;
            }
        };
    }

    public synchronized void stop() {
        if (!running) {
            return;
        }
        running = false;
        shutdown(schedulePool);
        shutdown(persistPool);
        shutdown(fastPool);
        shutdown(slowPool);
        ring.clear();
        scheduled.clear();
        runs.clear();
        flushPersistedTimes();
        logger.info("[z-schedule] Engine stopped");
    }

    private static void shutdown(ExecutorService pool) {
        if (pool == null) {
            return;
        }
        pool.shutdown();
        try {
            if (!pool.awaitTermination(10, TimeUnit.SECONDS)) {
                pool.shutdownNow();
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    // ---- 核心调度 ----

    private void tick() {
        tick(System.currentTimeMillis());
    }

    /**
     * 每秒一次：取到期任务、在内存里算好下次触发时间并重新挂轮，然后派发执行。
     * 本方法内没有任何 DB 调用。
     */
    void tick(long now) {
        if (!running) {
            return;
        }
        try {
            List<Integer> dueJobs = ring.poll(now);
            for (int i = 0; i < dueJobs.size(); i++) {
                int jobId = dueJobs.get(i);
                ScheduledJob job = scheduled.get(jobId);
                if (job == null) {
                    continue;
                }
                dispatch(job, now);

                long nextMs = computeNextFireMs(job, now);
                if (nextMs > 0) {
                    job.nextFireMs = nextMs;
                    ring.push(jobId, nextMs, now);
                    pendingPersist.put(jobId, new long[]{now, nextMs});
                } else if (TriggerTypeEnum.FIX_DELAY.getCode().equals(triggerTypeOf(job.info))) {
                    // 等 completeJob() 在本次执行结束后重新挂轮
                    job.nextFireMs = 0L;
                    pendingPersist.put(jobId, new long[]{now, 0L});
                } else {
                    // cron 已无未来时间 / fixInterval 被改成非法值：彻底摘除
                    scheduled.remove(jobId);
                    pendingPersist.remove(jobId);
                }
            }
            sweepTimeouts(now);
        } catch (Exception e) {
            logger.error("[z-schedule] Tick error", e);
        }
    }

    /**
     * 计算下次触发时间；返回 0 表示不自动重挂（FIX_DELAY / cron 解析失败 / 无未来时间）。
     */
    private long computeNextFireMs(ScheduledJob job, long nowMs) {
        String triggerType = triggerTypeOf(job.info);
        if (TriggerTypeEnum.FIX_DELAY.getCode().equals(triggerType)) {
            return 0L;
        }
        if (TriggerTypeEnum.FIX_RATE.getCode().equals(triggerType)) {
            long interval = job.info.getFixInterval();
            return interval > 0 ? nowMs + interval : 0L;
        }
        if (job.cron == null) {
            return 0L;
        }
        Date next = job.cron.getNextValidTimeAfter(new Date(nowMs));
        return next == null ? 0L : next.getTime();
    }

    private static String triggerTypeOf(JobInfo info) {
        String type = info.getTriggerType();
        return (type == null || type.isEmpty()) ? TriggerTypeEnum.CRON.getCode() : type;
    }

    /**
     * 按阻塞策略派发一次执行。快/慢池由该任务的平均耗时决定。
     */
    private void dispatch(ScheduledJob job, long fireMs) {
        final int jobId = job.info.getId();
        RunState state = runs.get(jobId);
        if (state == null) {
            RunState fresh = new RunState();
            state = runs.putIfAbsent(jobId, fresh);
            if (state == null) {
                state = fresh;
            }
        }

        boolean submit;
        synchronized (state) {
            ExecutorBlockStrategyEnum strategy = blockStrategyOf(job.info);
            if (state.executing) {
                if (strategy == ExecutorBlockStrategyEnum.DISCARD_LATER) {
                    state.discardedCount++;
                    logger.info("[z-schedule] DISCARD_LATER, jobId={} still running, fireMs={} dropped",
                            jobId, fireMs);
                    return;
                }
                if (strategy == ExecutorBlockStrategyEnum.COVER_EARLY) {
                    state.coveredCount++;
                    abortCurrentRun(state);
                }
                if (state.pending.size() >= SERIAL_QUEUE_MAX) {
                    state.droppedCount++;
                    logger.warn("[z-schedule] serial queue full ({}), jobId={}, fire dropped",
                            SERIAL_QUEUE_MAX, jobId);
                    return;
                }
            }
            state.pending.addLast(fireMs);
            submit = !state.executing;
            if (submit) {
                state.executing = true;
            }
        }

        if (!submit) {
            return;
        }
        final ScheduledJob jobRef = job;
        final RunState stateRef = state;
        Runnable chain = new Runnable() {
            public void run() {
                runChain(jobRef, stateRef);
            }
        };
        ExecutorService pool = poolFor(jobId);
        try {
            Future<?> future = pool.submit(chain);
            synchronized (state) {
                state.future = future;
            }
        } catch (RuntimeException e) {
            synchronized (state) {
                state.executing = false;
                state.pending.clear();
            }
            logger.error("[z-schedule] submit failed, jobId={}", jobId, e);
        }
    }

    /**
     * 打断某任务当前这一轮执行，但绝不取消"还没起跑"的执行链：
     * 链一旦在起跑前被取消，就没人再消费 {@code pending}、也没人复位 {@code executing}，
     * 该任务会被永久搁置。未起跑时改为打标，由链自身在起跑瞬间跳过这次触发。
     * 调用方必须持有 {@code state} 锁。
     */
    private static void abortCurrentRun(RunState state) {
        if (state.chainStarted && state.future != null) {
            state.future.cancel(true);
        } else {
            state.coverBeforeStart = true;
        }
    }

    /**
     * 链式执行：一个任务同一时刻只有一个链式任务在池里，排队靠 {@link RunState#pending}，
     * 因此不会有 worker 线程被"等上一个跑完"占住。
     */
    private void runChain(ScheduledJob job, RunState state) {
        int jobId = job.info.getId();
        synchronized (state) {
            state.chainStarted = true;
        }
        try {
            while (running) {
                long fireMs;
                boolean interruptedByCover;
                synchronized (state) {
                    if (state.pending.isEmpty()) {
                        return;
                    }
                    fireMs = state.pending.pollFirst();
                    interruptedByCover = state.coverBeforeStart;
                    state.coverBeforeStart = false;
                }
                if (interruptedByCover) {
                    // 这次触发已被 COVER_EARLY 的新触发取代
                    continue;
                }
                long start = System.currentTimeMillis();
                state.startedAtMs = start;
                state.firing = true;
                try {
                    jobTriggerService.triggerJob(job.info);
                } catch (Throwable t) {
                    logger.error("[z-schedule] Trigger failed, jobId={}", jobId, t);
                } finally {
                    state.firing = false;
                    recordCost(jobId, System.currentTimeMillis() - start);
                    // COVER_EARLY/超时的中断只针对这一次执行：处理器若把标志留着，
                    // 池线程带着中断位去跑下一个任务会把无关的等待全打断。
                    if (Thread.interrupted() && !running) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        } finally {
            synchronized (state) {
                state.future = null;
                state.chainStarted = false;
                state.firing = false;
                state.startedAtMs = 0L;
                state.executing = false;
                if (!state.pending.isEmpty() && running) {
                    // 收尾瞬间又有新触发入队，续一条链，避免任务被永久搁置
                    RunState self = state;
                    ScheduledJob jobRef = job;
                    self.executing = true;
                    try {
                        self.future = poolFor(jobRef.info.getId()).submit(new Runnable() {
                            public void run() {
                                runChain(jobRef, self);
                            }
                        });
                    } catch (RuntimeException e) {
                        self.executing = false;
                        self.pending.clear();
                        logger.error("[z-schedule] re-submit failed, jobId={}", jobRef.info.getId(), e);
                    }
                }
            }
        }
    }

    /** 指数滑动平均，权重 1/2：一次慢执行不会永久把任务钉在慢池。 */
    private void recordCost(int jobId, long costMs) {
        Long previous = jobAvgCostMap.get(jobId);
        long avg = previous == null ? costMs : (previous + costMs) / 2;
        jobAvgCostMap.put(jobId, avg);
    }

    private ExecutorService poolFor(int jobId) {
        Long avgCost = jobAvgCostMap.get(jobId);
        return (avgCost != null && avgCost > properties.getTriggerPoolSlowThreshold()) ? slowPool : fastPool;
    }

    private static ExecutorBlockStrategyEnum blockStrategyOf(JobInfo info) {
        ExecutorBlockStrategyEnum matched =
                ExecutorBlockStrategyEnum.match(info.getExecutorBlockStrategy());
        return matched == null ? ExecutorBlockStrategyEnum.SERIAL_EXECUTION : matched;
    }

    /**
     * 超时看护：平均耗时的检查在 tick 里做，只读内存，超时则中断该任务的链。
     */
    private void sweepTimeouts(long nowMs) {
        for (Map.Entry<Integer, RunState> entry : runs.entrySet()) {
            RunState state = entry.getValue();
            ScheduledJob job = scheduled.get(entry.getKey());
            if (job == null || !state.firing) {
                continue;
            }
            int timeoutSec = job.info.getExecutorTimeout();
            if (timeoutSec <= 0) {
                timeoutSec = properties.getExecutorTimeout();
            }
            if (timeoutSec <= 0) {
                continue;
            }
            long startedAt = state.startedAtMs;
            if (startedAt > 0 && nowMs - startedAt > timeoutSec * 1000L) {
                synchronized (state) {
                    state.timedOutCount++;
                    abortCurrentRun(state);
                }
                logger.warn("[z-schedule] jobId={} exceeded {}s, interrupting", entry.getKey(), timeoutSec);
            }
        }
    }

    /** 批量落库 trigger_last_time / trigger_next_time（同任务多次触发只写最后一版）。 */
    void flushPersistedTimes() {
        if (pendingPersist.isEmpty() || jobInfoService == null) {
            return;
        }
        List<Map.Entry<Integer, long[]>> batch =
                new ArrayList<Map.Entry<Integer, long[]>>(pendingPersist.entrySet());
        for (int i = 0; i < batch.size(); i++) {
            Map.Entry<Integer, long[]> entry = batch.get(i);
            long[] times = entry.getValue();
            pendingPersist.remove(entry.getKey(), times);
            try {
                jobInfoService.updateTriggerTimes(entry.getKey(), times[0], times[1]);
            } catch (Exception e) {
                logger.warn("[z-schedule] persist trigger times failed, jobId={}", entry.getKey(), e);
            }
        }
    }

    // ---- 时间轮装载 ----

    /**
     * 增量装载单个任务（启动/修改任务时调用），不做全表 reload。
     *
     * @return true 表示该任务已进入调度
     */
    public boolean scheduleJob(JobInfo info) {
        return scheduleJob(info, System.currentTimeMillis());
    }

    /** 显式给定"当前时间"的装载入口，供测试与 {@link #reloadJobs()} 复用同一时钟。 */
    public boolean scheduleJob(JobInfo info, long nowMs) {
        if (info == null || info.getId() <= 0) {
            return false;
        }
        long now = nowMs;
        if (info.getTriggerStatus() != 1) {
            unscheduleJob(info.getId());
            return false;
        }
        ScheduledJob job = toScheduledJob(info, now);
        if (job == null) {
            unscheduleJob(info.getId());
            return false;
        }
        scheduled.put(job.info.getId(), job);
        if (job.nextFireMs > 0) {
            ring.push(job.info.getId(), job.nextFireMs, now);
            pendingPersist.put(job.info.getId(), new long[]{now, job.nextFireMs});
        }
        return true;
    }

    /**
     * 摘下单个任务（停止/删除任务时调用），替代全量 reload。
     *
     * @return true 表示确实摘下了一个在调度的任务
     */
    public boolean unscheduleJob(int jobId) {
        boolean changed = scheduled.remove(jobId) != null;
        changed |= ring.remove(jobId);
        pendingPersist.remove(jobId);
        return changed;
    }

    /**
     * 任务定义变更后重新装载（改 cron / 改触发类型）。
     */
    public boolean rescheduleJob(JobInfo info) {
        if (info == null) {
            return false;
        }
        unscheduleJob(info.getId());
        return scheduleJob(info);
    }

    /**
     * 由 cron / 固定间隔算出首次触发时间的快照；cron 非法或已无未来时间返回 null。
     */
    private ScheduledJob toScheduledJob(JobInfo info, long nowMs) {
        String triggerType = triggerTypeOf(info);
        CronExpression cron = null;

        if (TriggerTypeEnum.FIX_RATE.getCode().equals(triggerType)) {
            long interval = info.getFixInterval();
            if (interval <= 0) {
                return null;
            }
            long base = info.getTriggerLastTime() > 0 ? info.getTriggerLastTime() : nowMs;
            long nextMs = base + interval;
            if (nextMs <= nowMs) {
                if (MisfireStrategyEnum.FIRE_ONCE_NOW.getCode().equals(info.getMisfireStrategy())) {
                    nextMs = nowMs + 1000L;
                } else {
                    // DO_NOTHING：跳过已经错过的整周期，直接对齐到下一个未来时间
                    long missed = (nowMs - nextMs) / interval + 1;
                    nextMs += missed * interval;
                }
            }
            return new ScheduledJob(info, null, nextMs);
        }

        if (TriggerTypeEnum.FIX_DELAY.getCode().equals(triggerType)) {
            // 启动即跑一次（没有"上一次完成时间"可延迟），之后每轮由 completeJob() 挂下一轮
            return info.getFixInterval() > 0 ? new ScheduledJob(info, null, nowMs) : null;
        }

        if (info.getJobCron() == null || info.getJobCron().trim().isEmpty()) {
            return null;
        }
        try {
            cron = parseCron(info.getJobCron());
        } catch (ParseException e) {
            logger.warn("[z-schedule] Skip job {}: invalid cron [{}]: {}",
                    info.getId(), info.getJobCron(), e.getMessage());
            return null;
        }
        Date next = cron.getNextValidTimeAfter(new Date(nowMs));
        if (next == null) {
            return null;
        }
        long nextMs = next.getTime();
        if (info.getTriggerNextTime() > 0 && info.getTriggerNextTime() < nowMs
                && MisfireStrategyEnum.FIRE_ONCE_NOW.getCode().equals(info.getMisfireStrategy())) {
            nextMs = nowMs + 1000L;
        }
        return new ScheduledJob(info, cron, nextMs);
    }

    private static CronExpression parseCron(String expression) throws ParseException {
        CronExpression cached = CRON_CACHE.get(expression);
        if (cached != null) {
            return cached;
        }
        CronExpression parsed = new CronExpression(expression);
        CRON_CACHE.put(expression, parsed);
        return parsed;
    }

    /**
     * 全量对齐 DB（Leader 切换、周期性 reconcile）。只查 {@code trigger_status=1} 的行，
     * 不再全表捞出后在内存里过滤。
     * <p>
     * <b>调度输入没变的任务沿用原排期</b>，不按 reconcile 时刻重算：新建/新启动的 FIX_RATE 行
     * {@code trigger_last_time} 还是 0，重算得到"当前时间 + 间隔"，于是每 15 秒的 reconcile 都把首次
     * 触发往后推 15 秒，间隔大于该周期的任务永远跑不到（而 trigger_status 一直是 1）。FIX_DELAY 同理：
     * 它在执行期间故意不挂轮，重算等于"现在再跑一次"，间隔被踩成每 15 秒一轮，COVER_EARLY 下还会打断在跑的那次。
     */
    public void reloadJobs() {
        reloadJobs(System.currentTimeMillis());
    }

    /** 显式时钟版本的 {@link #reloadJobs()}。 */
    public void reloadJobs(long wallClockMs) {
        if (!initialized) {
            start();
        }
        long now = wallClockMs;
        List<JobInfo> running;
        try {
            running = jobInfoService.listRunning();
        } catch (Exception e) {
            logger.warn("[z-schedule] reloadJobs skipped, listRunning failed: {}", e.toString());
            return;
        }
        if (running == null) {
            running = Collections.emptyList();
        }

        Map<Integer, ScheduledJob> previous = new HashMap<Integer, ScheduledJob>(scheduled);
        Map<Integer, Long> armedAt = new HashMap<Integer, Long>();
        for (Integer jobId : previous.keySet()) {
            // 0 有两种含义：还没挂上，或这一次正在跑（poll 会把已触发的条目摘出 scheduledSec）
            armedAt.put(jobId, ring.nextFireTime(jobId));
        }

        Map<Integer, ScheduledJob> reloaded = new ConcurrentHashMap<Integer, ScheduledJob>();
        ring.clear();
        int loaded = 0;
        for (JobInfo info : running) {
            try {
                ScheduledJob kept = previous.get(info.getId());
                Long armed = armedAt.get(info.getId());
                ScheduledJob job;
                if (kept == null || armed == null || !sameSchedulePlan(kept.info, info)) {
                    job = toScheduledJob(info, now);
                } else if (armed > 0L) {
                    job = new ScheduledJob(info, kept.cron, armed);
                } else if (TriggerTypeEnum.FIX_DELAY.getCode().equals(triggerTypeOf(info))) {
                    // 在跑的这一轮还没回调 completeJob，保持不挂轮
                    job = new ScheduledJob(info, kept.cron, 0L);
                } else {
                    // 刚触发完、还没来得及重挂：按 DB 里的 last_time 算，别停在原地
                    job = toScheduledJob(info, now);
                }
                if (job == null) {
                    continue;
                }
                reloaded.put(info.getId(), job);
                if (job.nextFireMs > 0) {
                    ring.push(info.getId(), job.nextFireMs, now);
                }
                loaded++;
            } catch (Exception e) {
                logger.warn("[z-schedule] Skip job {} during reload: {}", info.getId(), e.toString());
            }
        }
        // DB 里已停止/删除的任务从轮和快照一起摘除；正在跑的那次不强行中断
        for (Integer jobId : scheduled.keySet()) {
            if (!reloaded.containsKey(jobId)) {
                ring.remove(jobId);
                scheduled.remove(jobId);
                pendingPersist.remove(jobId);
            }
        }
        scheduled.putAll(reloaded);

        logger.info("[z-schedule] Engine loaded {} jobs into ring (ringTotal={}, overflow={}, dropped={})",
                loaded, ring.totalSize(), ring.overflowSize(), scheduled.size() - loaded);
    }

    /** 两个定义里"会改变排期结果"的字段是否一致；不一致才允许 reconcile 重算下次触发时间。 */
    private static boolean sameSchedulePlan(JobInfo a, JobInfo b) {
        return triggerTypeOf(a).equals(triggerTypeOf(b))
                && a.getFixInterval() == b.getFixInterval()
                && eq(a.getJobCron(), b.getJobCron())
                && eq(a.getMisfireStrategy(), b.getMisfireStrategy());
    }

    private static boolean eq(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    // ---- FIX_DELAY 回调 ----

    /**
     * FIX_DELAY 模式：本次执行结束后按 {@code fixInterval} 挂入下一轮。
     */
    public void completeJob(int jobId) {
        completeJob(jobId, System.currentTimeMillis());
    }

    /** 显式时钟版本的 {@link #completeJob(int)}。 */
    public void completeJob(int jobId, long nowMs) {
        ScheduledJob job = scheduled.get(jobId);
        if (job == null || job.info.getTriggerStatus() != 1) {
            return;
        }
        if (!TriggerTypeEnum.FIX_DELAY.getCode().equals(triggerTypeOf(job.info))) {
            return;
        }
        long interval = job.info.getFixInterval();
        if (interval <= 0) {
            return;
        }
        long now = nowMs;
        long nextMs = now + interval;
        job.nextFireMs = nextMs;
        ring.push(jobId, nextMs, now);
        pendingPersist.put(jobId, new long[]{now, nextMs});
    }

    // ---- 监控 / 诊断 ----

    public int scheduledJobCount() {
        return ring.totalSize();
    }

    public int overflowJobCount() {
        return ring.overflowSize();
    }

    /** 某任务当前登记的下次触发时间（epoch ms），未调度返回 0。 */
    public long nextFireTime(int jobId) {
        return ring.nextFireTime(jobId);
    }

    /** 任务是否已在时间轮里排期。 */
    public boolean isScheduled(int jobId) {
        return scheduled.containsKey(jobId) && ring.nextFireTime(jobId) > 0;
    }

    public Long averageCost(int jobId) {
        return jobAvgCostMap.get(jobId);
    }

    public int fastPoolSize() {
        return fastPool == null ? 0 : fastPool.getActiveCount();
    }

    public int slowPoolSize() {
        return slowPool == null ? 0 : slowPool.getActiveCount();
    }

    public int fastQueueSize() {
        return fastPool == null ? 0 : fastPool.getQueue().size();
    }

    public int slowQueueSize() {
        return slowPool == null ? 0 : slowPool.getQueue().size();
    }

    /** 因阻塞策略被丢弃/覆盖/排队的计数快照。 */
    public Map<String, Object> blockStrategyCounters() {
        long discarded = 0;
        long covered = 0;
        long dropped = 0;
        long timedOut = 0;
        int queued = 0;
        for (RunState state : runs.values()) {
            synchronized (state) {
                discarded += state.discardedCount;
                covered += state.coveredCount;
                dropped += state.droppedCount;
                timedOut += state.timedOutCount;
                queued += state.pending.size();
            }
        }
        Map<String, Object> counters = new LinkedHashMap<String, Object>();
        counters.put("discarded", discarded);
        counters.put("covered", covered);
        counters.put("dropped", dropped);
        counters.put("timedOut", timedOut);
        counters.put("queued", (long) queued);
        return counters;
    }

    public boolean isRunning() {
        return running;
    }

    /** 一个任务的调度快照：cron 只解析一次，tick 线程不再回查 DB。 */
    private static final class ScheduledJob {
        final JobInfo info;
        final CronExpression cron;
        volatile long nextFireMs;

        ScheduledJob(JobInfo info, CronExpression cron, long nextFireMs) {
            this.info = info;
            this.cron = cron;
            this.nextFireMs = nextFireMs;
        }
    }

    /** 一个任务的在跑/排队状态；所有字段只在持有本对象锁时读写（firing/startedAtMs 由链式任务写、tick 读，故 volatile）。 */
    private static final class RunState {
        final java.util.Deque<Long> pending = new java.util.ArrayDeque<Long>();
        volatile boolean firing;
        volatile boolean chainStarted;
        volatile long startedAtMs;
        boolean executing;
        boolean coverBeforeStart;
        Future<?> future;
        long discardedCount;
        long coveredCount;
        long droppedCount;
        long timedOutCount;
    }
}
