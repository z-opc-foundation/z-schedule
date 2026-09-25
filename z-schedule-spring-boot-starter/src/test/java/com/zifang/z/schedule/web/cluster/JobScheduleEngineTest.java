package com.zifang.z.schedule.web.cluster;

import com.zifang.z.schedule.core.config.ScheduleProperties;
import com.zifang.z.schedule.core.enums.ExecutorBlockStrategyEnum;
import com.zifang.z.schedule.core.enums.MisfireStrategyEnum;
import com.zifang.z.schedule.core.enums.TriggerTypeEnum;
import com.zifang.z.schedule.core.model.JobInfo;
import com.zifang.z.schedule.core.model.ReturnT;
import com.zifang.z.schedule.web.service.JobInfoService;
import com.zifang.z.schedule.web.service.JobTriggerService;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * 调度引擎行为测试：时间轮装载、tick 的 I/O 预算、阻塞策略、超时与快慢池路由。
 * <p>
 * 统一用 {@code start(false)} + {@code tick(模拟时间)} 驱动，因此所有断言都落在"同一个模拟秒"上，
 * 不需要 sleep 等真实时钟（除了等待异步派发的 await）。
 */
public class JobScheduleEngineTest {

    private static final long DAY_MS = 24 * 3600_000L;

    private JobScheduleEngine engine;
    private FakeJobInfoService jobInfoService;
    private RecordingTriggerService triggerService;
    private FakeLeaderElector leaderElector;
    private long base;

    @Before
    public void setUp() {
        base = (System.currentTimeMillis() / 1000L) * 1000L;
        jobInfoService = new FakeJobInfoService();
        triggerService = new RecordingTriggerService();
        leaderElector = new FakeLeaderElector();

        ScheduleProperties properties = new ScheduleProperties();
        properties.setTriggerPoolFastMax(8);
        properties.setTriggerPoolSlowMax(8);
        properties.setTriggerPoolSlowThreshold(200L);

        engine = new JobScheduleEngine();
        engine.setJobInfoService(jobInfoService);
        engine.setJobTriggerService(triggerService);
        engine.setLeaderElector(leaderElector);
        engine.setProperties(properties);
        engine.start(false);
    }

    @After
    public void tearDown() {
        engine.stop();
    }

    // ---- tick 的 I/O 预算 ----

    @Test
    public void tick线程全程不碰DB() {
        engine.scheduleJob(fixRateJob(1, 5000L), base);

        for (int sec = 0; sec <= 10; sec++) {
            engine.tick(base + sec * 1000L);
        }
        assertEquals(2, awaitCount(triggerService.invocations, 2));

        assertEquals("tick 不该回查任务定义", 0, jobInfoService.getByIdCalls.get());
        assertEquals("tick 不该同步写库", 0, jobInfoService.updateTriggerTimesCalls.get());
        assertEquals("不该走全表 reload", 0, jobInfoService.listRunningCalls.get());
        assertEquals("不该全表捞取", 0, jobInfoService.getAllCalls.get());

        engine.flushPersistedTimes();
        assertTrue("下次触发时间应由 flush 线程批量落库", jobInfoService.updateTriggerTimesCalls.get() > 0);
    }

    @Test
    public void 每日cron不会被每分钟重复触发() {
        // 用"今天中午"作模拟时钟，避免真实运行时刻落在 02:00 前后一分钟而误判
        long noon = (System.currentTimeMillis() / DAY_MS) * DAY_MS + 12L * 3600_000L;
        JobInfo job = cronJob(11, "0 0 2 * * ?");
        assertTrue(engine.scheduleJob(job, noon));

        for (int sec = 0; sec < 180; sec++) {
            engine.tick(noon + sec * 1000L);
        }
        triggerService.awaitQuietly();

        assertEquals("每天一次的任务在两分钟内被重复触发", 0, triggerService.invocations.size());
        assertEquals(1, engine.scheduledJobCount());
        assertEquals("距下次触发超过一分钟，应在 overflow 里排队", 1, engine.overflowJobCount());
        assertTrue(engine.nextFireTime(11) > noon + 60_000L);
    }

    @Test
    public void 固定间隔按秒精确重复触发() {
        engine.scheduleJob(fixRateJob(21, 3000L), base);

        for (int sec = 0; sec <= 9; sec++) {
            engine.tick(base + sec * 1000L);
        }
        assertEquals(3, awaitCount(triggerService.invocations, 3));
        List<Long> fireTimes = new ArrayList<Long>(triggerService.fireAt);
        assertEquals(3, fireTimes.size());
    }

    @Test
    public void fixDelay只在执行完成后重挂() {
        JobInfo job = new JobInfo();
        job.setId(31);
        job.setTriggerStatus(1);
        job.setTriggerType(TriggerTypeEnum.FIX_DELAY.getCode());
        job.setFixInterval(2000L);
        job.setExecutorHandler("demoHandler");
        engine.scheduleJob(job, base);

        for (int sec = 0; sec <= 2; sec++) {
            engine.tick(base + sec * 1000L);
        }
        assertEquals(1, awaitCount(triggerService.invocations, 1));

        for (int sec = 3; sec <= 8; sec++) {
            engine.tick(base + sec * 1000L);
        }
        triggerService.awaitQuietly();
        assertEquals("FIX_DELAY 在完成回调之前不得自行重挂", 1, triggerService.invocations.size());

        engine.completeJob(31, base + 8_000L);
        assertEquals("下一轮应挂在完成之后第 2 秒", base + 10_000L, engine.nextFireTime(31));
        for (int sec = 9; sec <= 12; sec++) {
            engine.tick(base + sec * 1000L);
        }
        assertEquals(2, awaitCount(triggerService.invocations, 2));
    }

    @Test
    public void misfire跳过周期与立即补偿两种策略() {
        JobInfo skip = fixRateJob(41, 5000L);
        skip.setTriggerLastTime(base - 35_000L);
        skip.setMisfireStrategy(MisfireStrategyEnum.DO_NOTHING.getCode());
        engine.scheduleJob(skip, base);

        JobInfo compensate = fixRateJob(42, 5000L);
        compensate.setTriggerLastTime(base - 35_000L);
        compensate.setMisfireStrategy(MisfireStrategyEnum.FIRE_ONCE_NOW.getCode());
        engine.scheduleJob(compensate, base);

        long skipNext = engine.nextFireTime(41);
        long compensateNext = engine.nextFireTime(42);
        assertTrue("DO_NOTHING 应对齐到未来周期: " + skipNext, skipNext > base);
        assertTrue("FIRE_ONCE_NOW 应在一秒后补偿: " + compensateNext, compensateNext <= base + 1000L);

        for (int sec = 0; sec <= 3; sec++) {
            engine.tick(base + sec * 1000L);
        }
        assertEquals("只补偿一次", 1, awaitJobCount(42, 1));
        assertEquals("DO_NOTHING 不该立即补触发", 0, countIds(41));
    }

    @Test
    public void 非法cron的任务被跳过且不影响其它任务() {
        JobInfo broken = cronJob(51, "every day at two");
        assertFalse(engine.scheduleJob(broken, base));
        engine.scheduleJob(fixRateJob(52, 3000L), base);

        engine.reloadJobs(base);
        jobInfoService.store.put(51, broken);
        jobInfoService.store.put(52, fixRateJob(52, 3000L));
        engine.reloadJobs(base);

        for (int sec = 0; sec <= 4; sec++) {
            engine.tick(base + sec * 1000L);
        }
        assertEquals(1, awaitCount(triggerService.invocations, 1));
        assertEquals(0, countIds(51));
        assertEquals("合法任务照常调度", 1, countIds(52));
    }

    // ---- 阻塞策略 ----

    @Test
    public void 串行策略下同一任务不并发且后续排队() {
        triggerService.blockOnGate = true;
        engine.scheduleJob(fixRateJob(61, 1000L, ExecutorBlockStrategyEnum.SERIAL_EXECUTION), base);

        for (int sec = 1; sec <= 3; sec++) {
            engine.tick(base + sec * 1000L);
        }
        assertEquals(1, awaitCount(triggerService.invocations, 1));
        triggerService.awaitStarted();

        assertEquals("第一次执行未结束时不得并发第二次", 1, triggerService.invocations.size());
        assertEquals("并发度必须为 1", 1, triggerService.maxConcurrency.get());
        assertEquals("后续触发应排队", 2L, engine.blockStrategyCounters().get("queued"));

        triggerService.releaseGate();
        assertEquals(3, awaitCount(triggerService.invocations, 3));
        assertEquals("排队期间也不能并发", 1, triggerService.maxConcurrency.get());
    }

    @Test
    public void 丢弃策略丢弃仍在运行时的新触发() {
        triggerService.blockOnGate = true;
        engine.scheduleJob(fixRateJob(71, 1000L, ExecutorBlockStrategyEnum.DISCARD_LATER), base);

        for (int sec = 1; sec <= 3; sec++) {
            engine.tick(base + sec * 1000L);
        }
        assertEquals(1, awaitCount(triggerService.invocations, 1));
        triggerService.awaitStarted();

        assertEquals(1, triggerService.invocations.size());
        assertEquals(2L, engine.blockStrategyCounters().get("discarded"));

        triggerService.releaseGate();
        triggerService.awaitQuietly();
        assertEquals("被丢弃的触发不会补跑", 1, triggerService.invocations.size());
    }

    @Test
    public void 覆盖策略打断在跑的执行并用新触发取代() throws Exception {
        triggerService.blockOnGate = true;
        engine.scheduleJob(fixRateJob(81, 1000L, ExecutorBlockStrategyEnum.COVER_EARLY), base);

        engine.tick(base + 1000L);
        triggerService.awaitStarted();
        assertEquals("打断之前只有一次执行在跑", 1, triggerService.invocations.size());

        engine.tick(base + 2000L);
        assertTrue("COVER_EARLY 应中断正在执行的那次",
                triggerService.interrupted.await(5, TimeUnit.SECONDS));
        assertEquals(1L, engine.blockStrategyCounters().get("covered"));

        assertEquals("被打断的这次之外，新的那次要顶上", 2, awaitCount(triggerService.invocations, 2));
        assertEquals("取代不是并发", 1, triggerService.maxConcurrency.get());
        triggerService.releaseGate();

        engine.tick(base + 3000L);
        assertEquals("覆盖之后调度链不能被搁置", 3, awaitCount(triggerService.invocations, 3));
        triggerService.releaseGate();
        triggerService.awaitQuietly();
    }

    @Test
    public void 执行超时被看护打断且任务不被搁置() throws Exception {
        triggerService.blockOnGate = true;
        JobInfo job = fixRateJob(91, 1000L, ExecutorBlockStrategyEnum.SERIAL_EXECUTION);
        job.setExecutorTimeout(1);
        engine.scheduleJob(job, base);

        engine.tick(base + 1000L);
        triggerService.awaitStarted();

        engine.tick(base + 5000L);
        assertTrue("超过 executorTimeout=1s 应被中断",
                triggerService.interrupted.await(5, TimeUnit.SECONDS));
        assertEquals(1L, engine.blockStrategyCounters().get("timedOut"));
        triggerService.releaseGate();
    }

    @Test
    public void 排队上限内的触发不会丢失() {
        triggerService.blockOnGate = true;
        engine.scheduleJob(fixRateJob(101, 1000L, ExecutorBlockStrategyEnum.SERIAL_EXECUTION), base);

        for (int sec = 1; sec <= 6; sec++) {
            engine.tick(base + sec * 1000L);
        }
        assertEquals(1, awaitCount(triggerService.invocations, 1));
        triggerService.awaitStarted();
        triggerService.releaseGate();

        assertEquals("串行队列应把 6 次触发全部跑完", 6, awaitCount(triggerService.invocations, 6));
        assertEquals(0L, engine.blockStrategyCounters().get("dropped"));
    }

    // ---- 快慢池与成本回灌 ----

    @Test
    public void 历史耗时超过阈值的任务被降级到慢池() {
        triggerService.sleepMs = 320L; // > slowThreshold(200ms)
        engine.scheduleJob(fixRateJob(111, 2000L), base);

        for (int sec = 1; sec <= 2; sec++) {
            engine.tick(base + sec * 1000L);
        }
        assertEquals(1, awaitCount(triggerService.invocations, 1));
        assertTrue("首次执行落在快池: " + triggerService.threadNames,
                triggerService.threadNames.get(0).startsWith("z-schedule-fast-"));
        triggerService.awaitFinished(1);
        assertNotNull("耗时统计应回灌给引擎用于池路由", awaitCost(111));
        long avg = awaitCost(111);
        assertTrue("EWMA 应反映实测耗时, 实测 " + avg, avg >= 200L && avg < 5000L);

        for (int sec = 3; sec <= 4; sec++) {
            engine.tick(base + sec * 1000L);
        }
        assertEquals(2, awaitCount(triggerService.invocations, 2));
        assertTrue("平均耗时超阈值后应落到慢池: " + triggerService.threadNames,
                triggerService.threadNames.get(1).startsWith("z-schedule-slow-"));
    }

    @Test
    public void 池大小与阈值取自配置而不是硬编码() {
        ScheduleProperties properties = new ScheduleProperties();
        properties.setTriggerPoolFastMax(3);
        properties.setTriggerPoolSlowMax(4);
        JobScheduleEngine configured = new JobScheduleEngine();
        configured.setJobInfoService(jobInfoService);
        configured.setJobTriggerService(triggerService);
        configured.setLeaderElector(leaderElector);
        configured.setProperties(properties);
        configured.start(false);
        try {
            triggerService.blockOnGate = true;
            for (int jobId = 121; jobId <= 125; jobId++) {
                configured.scheduleJob(fixRateJob(jobId, 1000L), base);
            }
            for (int sec = 1; sec <= 1; sec++) {
                configured.tick(base + sec * 1000L);
            }
            triggerService.awaitInFlight(3);
            assertEquals("快池大小应由 z.schedule.triggerPoolFastMax 决定", 3, configured.fastPoolSize());
            assertTrue("超出池容量的执行应排队, 实测队列 " + configured.fastQueueSize(),
                    configured.fastQueueSize() >= 2);
            triggerService.releaseGate();
            triggerService.awaitQuietly();
        } finally {
            configured.stop();
        }
    }

    // ---- 装载与增量变更 ----

    @Test
    public void 停止单个任务只摘它自己() {
        engine.scheduleJob(fixRateJob(131, 3000L), base);
        engine.scheduleJob(fixRateJob(132, 3000L), base);
        assertEquals(2, engine.scheduledJobCount());

        assertTrue(engine.unscheduleJob(131));
        assertEquals(1, engine.scheduledJobCount());
        assertEquals(0, jobInfoService.listRunningCalls.get());

        for (int sec = 0; sec <= 4; sec++) {
            engine.tick(base + sec * 1000L);
        }
        assertEquals(1, awaitCount(triggerService.invocations, 1));
        assertEquals(0, countIds(131));
        assertEquals(1, countIds(132));
    }

    @Test
    public void reload只查运行中的任务且可重复执行() {
        jobInfoService.store.put(141, fixRateJob(141, 2000L));
        jobInfoService.store.put(142, fixRateJob(142, 2000L));
        jobInfoService.store.put(143, stoppedJob(143));

        engine.reloadJobs(base);
        engine.reloadJobs(base);
        engine.reloadJobs(base);

        assertEquals("只查运行中的任务", 2, engine.scheduledJobCount());
        assertTrue(jobInfoService.listRunningCalls.get() >= 3);
        assertEquals("不该用 getAll 全表捞", 0, jobInfoService.getAllCalls.get());
        assertFalse("已停止的任务不进轮", engine.isScheduled(143));

        for (int sec = 0; sec <= 2; sec++) {
            engine.tick(base + sec * 1000L);
        }
        assertEquals(2, awaitCount(triggerService.invocations, 2));
        triggerService.awaitQuietly();
        assertEquals("重复 reload 不得造成重复触发", 2, countIds(141) + countIds(142));
    }

    @Test
    public void DB里消失的任务在reload后被摘除() {
        JobInfo job = fixRateJob(151, 2000L);
        jobInfoService.store.put(151, job);
        engine.reloadJobs(base);
        assertEquals(1, engine.scheduledJobCount());

        jobInfoService.store.remove(151);
        engine.reloadJobs(base);
        assertEquals(0, engine.scheduledJobCount());

        for (int sec = 0; sec <= 5; sec++) {
            engine.tick(base + sec * 1000L);
        }
        triggerService.awaitQuietly();
        assertEquals(0, triggerService.invocations.size());
    }

    @Test
    public void 五千个同秒任务的tick延迟() {
        int count = 5000;
        for (int jobId = 1; jobId <= count; jobId++) {
            engine.scheduleJob(fixRateJob(jobId, 60_000L), base);
        }
        assertEquals(count, engine.scheduledJobCount());

        long start = System.nanoTime();
        engine.tick(base + 60_000L);
        long tickNanos = System.nanoTime() - start;
        int triggered = awaitCount(triggerService.invocations, count);

        System.out.println("[JobScheduleEngine] " + count + " 个任务同秒到期的 tick(仅轮询+派发) = "
                + (tickNanos / 1_000_000L) + " ms; 执行完成 " + triggered + " 次");
        assertEquals(count, triggered);
        assertTrue("tick 只做内存操作，5000 个到期任务应在 200ms 内派发完，实测 "
                + (tickNanos / 1_000_000L) + " ms", tickNanos < 200_000_000L);
    }

    // ---- 断言辅助 ----

    private JobInfo cronJob(int id, String cron) {
        JobInfo job = new JobInfo();
        job.setId(id);
        job.setJobGroup(1);
        job.setJobCron(cron);
        job.setTriggerStatus(1);
        job.setTriggerType(TriggerTypeEnum.CRON.getCode());
        job.setMisfireStrategy(MisfireStrategyEnum.DO_NOTHING.getCode());
        job.setExecutorHandler("demoHandler");
        return job;
    }

    private JobInfo fixRateJob(int id, long intervalMs) {
        return fixRateJob(id, intervalMs, null);
    }

    private JobInfo fixRateJob(int id, long intervalMs, ExecutorBlockStrategyEnum strategy) {
        JobInfo job = new JobInfo();
        job.setId(id);
        job.setJobGroup(1);
        job.setTriggerStatus(1);
        job.setTriggerType(TriggerTypeEnum.FIX_RATE.getCode());
        job.setFixInterval(intervalMs);
        job.setMisfireStrategy(MisfireStrategyEnum.DO_NOTHING.getCode());
        job.setExecutorHandler("demoHandler");
        if (strategy != null) {
            job.setExecutorBlockStrategy(strategy.getCode());
        }
        return job;
    }

    private JobInfo stoppedJob(int id) {
        JobInfo job = fixRateJob(id, 1000L);
        job.setTriggerStatus(0);
        return job;
    }

    private List<Integer> idsOf(int jobId) {
        List<Integer> ids = new ArrayList<Integer>();
        List<JobInfo> snapshot;
        synchronized (triggerService.invocations) {
            snapshot = new ArrayList<JobInfo>(triggerService.invocations);
        }
        for (JobInfo info : snapshot) {
            if (info.getId() == jobId) {
                ids.add(jobId);
            }
        }
        return ids;
    }

    private int countIds(int jobId) {
        return idsOf(jobId).size();
    }

    /** 等某个任务的触发次数达到 expected（异步派发，避免把"还没跑到"误判成"没跑到"）。 */
    private int awaitJobCount(int jobId, int expected) {
        long deadline = System.currentTimeMillis() + 8000L;
        while (countIds(jobId) < expected && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(5L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return countIds(jobId);
    }

    /** 等耗时统计回灌到引擎（recordCost 在处理器返回之后才落，别把它当同步）。 */
    private long awaitCost(int jobId) {
        long deadline = System.currentTimeMillis() + 8000L;
        Long avg = engine.averageCost(jobId);
        while (avg == null && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(5L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            avg = engine.averageCost(jobId);
        }
        return avg == null ? -1L : avg;
    }

    /** 等待列表长度达到 expected，返回最终长度（超时则返回实际值让断言失败）。 */
    private static int awaitCount(List<JobInfo> list, int expected) {
        long deadline = System.currentTimeMillis() + 8000L;
        while (System.currentTimeMillis() < deadline) {
            if (list.size() >= expected) {
                return list.size();
            }
            try {
                Thread.sleep(5L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return list.size();
    }

    // ---- 测试替身 ----

    private static class RecordingTriggerService implements JobTriggerService {
        final List<JobInfo> invocations = Collections.synchronizedList(new ArrayList<JobInfo>());
        final List<Long> fireAt = Collections.synchronizedList(new ArrayList<Long>());
        final List<String> threadNames = Collections.synchronizedList(new ArrayList<String>());
        final Map<Integer, JobInfo> lastJob = new ConcurrentHashMap<Integer, JobInfo>();
        final AtomicInteger inFlight = new AtomicInteger();
        final AtomicInteger maxConcurrency = new AtomicInteger();
        final AtomicInteger finished = new AtomicInteger();
        final AtomicBoolean interruptSeen = new AtomicBoolean(false);
        final CountDownLatch interrupted = new CountDownLatch(1);
        final CountDownLatch started = new CountDownLatch(1);

        volatile boolean blockOnGate;
        volatile long sleepMs;
        private volatile CountDownLatch gate = new CountDownLatch(1);

        public void triggerJob(JobInfo jobInfo) {
            int now = inFlight.incrementAndGet();
            maxConcurrency.accumulateAndGet(now, Math::max);
            invocations.add(jobInfo);
            fireAt.add(System.currentTimeMillis());
            threadNames.add(Thread.currentThread().getName());
            lastJob.put(jobInfo.getId(), jobInfo);
            started.countDown();
            try {
                if (blockOnGate) {
                    gate.await(8, TimeUnit.SECONDS);
                }
                if (sleepMs > 0) {
                    Thread.sleep(sleepMs);
                }
            } catch (InterruptedException e) {
                sawInterrupt();
            } finally {
                inFlight.decrementAndGet();
                finished.incrementAndGet();
            }
        }

        private void sawInterrupt() {
            interruptSeen.set(true);
            interrupted.countDown();
            Thread.currentThread().interrupt();
        }

        void releaseGate() {
            gate.countDown();
        }

        void awaitStarted() {
            try {
                assertTrue("任务未被派发", started.await(5, TimeUnit.SECONDS));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        void awaitInFlight(int count) {
            long deadline = System.currentTimeMillis() + 8000L;
            while (inFlight.get() < count && System.currentTimeMillis() < deadline) {
                try {
                    Thread.sleep(5L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            assertTrue("池内并发应达到 " + count + "，实测 " + inFlight.get(), inFlight.get() >= count);
        }

        void awaitFinished(int count) {
            long deadline = System.currentTimeMillis() + 8000L;
            while (finished.get() < count && System.currentTimeMillis() < deadline) {
                try {
                    Thread.sleep(5L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }

        /** 等一切异步派发停下来，避免"还没来得及触发"被误判成"没有触发"。 */
        void awaitQuietly() {
            awaitFinished(Math.max(invocations.size(), 1));
            Thread.yield();
        }

        public void registerJob(JobInfo jobInfo) {
        }

        public void cancelJob(int jobId) {
        }

        public void completeJob(int jobId) {
        }

        public void killJob(long logId) {
        }
    }

    private static class FakeLeaderElector extends LeaderElector {
        volatile boolean leader = true;

        @Override
        public boolean isLeader() {
            return leader;
        }
    }

    private static class FakeJobInfoService implements JobInfoService {
        final Map<Integer, JobInfo> store = new ConcurrentHashMap<Integer, JobInfo>();
        final AtomicInteger getByIdCalls = new AtomicInteger();
        final AtomicInteger getAllCalls = new AtomicInteger();
        final AtomicInteger listRunningCalls = new AtomicInteger();
        final AtomicInteger updateTriggerTimesCalls = new AtomicInteger();

        public JobInfo getById(int id) {
            getByIdCalls.incrementAndGet();
            return store.get(id);
        }

        public List<JobInfo> getAll() {
            getAllCalls.incrementAndGet();
            return new ArrayList<JobInfo>(store.values());
        }

        public List<JobInfo> getByJobGroup(int jobGroup) {
            return getAll();
        }

        public List<JobInfo> listRunning() {
            listRunningCalls.incrementAndGet();
            List<JobInfo> running = new ArrayList<JobInfo>();
            for (JobInfo job : store.values()) {
                if (job.getTriggerStatus() == 1) {
                    running.add(job);
                }
            }
            return running;
        }

        public void updateTriggerTimes(int jobId, long lastTime, long nextTime) {
            updateTriggerTimesCalls.incrementAndGet();
            JobInfo job = store.get(jobId);
            if (job != null) {
                job.setTriggerLastTime(lastTime);
                job.setTriggerNextTime(nextTime);
            }
        }

        public ReturnT<String> add(JobInfo jobInfo) {
            store.put(jobInfo.getId(), jobInfo);
            return ReturnT.success();
        }

        public ReturnT<String> update(JobInfo jobInfo) {
            store.put(jobInfo.getId(), jobInfo);
            return ReturnT.success();
        }

        public ReturnT<String> delete(int id) {
            store.remove(id);
            return ReturnT.success();
        }

        public ReturnT<String> stop(int id) {
            JobInfo job = store.get(id);
            if (job != null) {
                job.setTriggerStatus(0);
            }
            return ReturnT.success();
        }

        public ReturnT<String> start(int id) {
            JobInfo job = store.get(id);
            if (job != null) {
                job.setTriggerStatus(1);
            }
            return ReturnT.success();
        }

        public ReturnT<String> trigger(int id) {
            return ReturnT.success();
        }

        public ReturnT<List<String>> nextTriggerTime(String cron) {
            return ReturnT.success(Collections.<String>emptyList());
        }
    }
}
