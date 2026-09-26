package com.zifang.z.schedule.web.service.impl;

import com.zifang.z.schedule.core.config.ScheduleProperties;
import com.zifang.z.schedule.core.enums.ExecutorRouteStrategyEnum;
import com.zifang.z.schedule.core.enums.TriggerCodeEnum;
import com.zifang.z.schedule.core.enums.TriggerTypeEnum;
import com.zifang.z.schedule.core.model.JobInfo;
import com.zifang.z.schedule.core.model.JobLog;
import com.zifang.z.schedule.core.handler.IJobHandler;
import com.zifang.z.schedule.core.model.ReturnT;
import com.zifang.z.schedule.core.param.TriggerParam;
import com.zifang.z.schedule.web.cluster.JobScheduleEngine;
import com.zifang.z.schedule.web.cluster.LeaderElector;
import com.zifang.z.schedule.web.service.AlarmService;
import com.zifang.z.schedule.web.service.JobInfoService;
import com.zifang.z.schedule.web.service.JobLogService;
import org.junit.Before;
import org.junit.Test;
import org.springframework.context.support.StaticApplicationContext;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * 一次执行的生命周期：日志落库、失败重试、告警、kill、子任务联动、FIX_DELAY 重挂。
 * <p>
 * 钉住的是 {@code JobTriggerServiceImpl} 重写后必须成立的行为——尤其是旧版里
 * "handler bean 找不到也按成功回写"、"killJob 永远找不到在跑的任务"、
 * "告警服务没有任何调用方"、"FIX_DELAY 任务一旦异常就永久停摆" 这四处。
 */
public class JobTriggerServiceImplBehaviorTest {

    private JobTriggerServiceImpl service;
    private StaticApplicationContext context;
    private FakeJobLogService logService;
    private FakeAlarmService alarmService;
    private FakeJobInfoService infoService;
    private RecordingEngine engine;

    @Before
    public void setUp() throws Exception {
        service = new JobTriggerServiceImpl();
        context = new StaticApplicationContext();
        context.getBeanFactory().registerSingleton("okHandler", new OkHandler());
        context.getBeanFactory().registerSingleton("boomHandler", new BoomHandler());
        context.getBeanFactory().registerSingleton("slowHandler", new SlowHandler());
        context.getBeanFactory().registerSingleton("interfaceHandler", new InterfaceHandler());
        context.getBeanFactory().registerSingleton("interfaceFailHandler", new InterfaceFailHandler());
        context.getBeanFactory().registerSingleton("noExecuteHandler", new NoExecuteHandler());

        logService = new FakeJobLogService();
        alarmService = new FakeAlarmService();
        infoService = new FakeJobInfoService();
        engine = new RecordingEngine();

        inject(service, "applicationContext", context);
        inject(service, "jobLogService", logService);
        inject(service, "jobInfoService", infoService);
        inject(service, "alarmService", alarmService);
        inject(service, "leaderElector", new AlwaysLeader());
        inject(service, "scheduleProperties", new ScheduleProperties());
        service.setEngine(engine);
    }

    // ---- 成功路径 ----

    @Test
    public void 成功执行写成功日志并带耗时() {
        OkHandler.calls.set(0);
        service.triggerJob(job(1, "okHandler", "p-1"));

        assertEquals(1, OkHandler.calls.get());
        assertEquals("p-1", OkHandler.lastParam);
        assertEquals(1, logService.saved.size());
        JobLog log = logService.saved.get(0);
        assertEquals(ReturnT.SUCCESS_CODE, log.getHandleCode());
        assertTrue("回写耗时: " + log.getHandleMsg(), log.getHandleMsg().contains("ms"));
        assertEquals("成功不告警", 0, log.getAlarmStatus());
        assertEquals(0, alarmService.calls.get());
        assertEquals("执行结束后不该留下在跑记录", 0, service.runningExecutionCount());
    }

    @Test
    public void 父任务成功后按顺序触发启用的子任务() {
        JobInfo parent = job(10, "okHandler", null);
        parent.setChildJobId("11, 12 ,bad");
        infoService.store.put(11, job(11, "okHandler", null));
        JobInfo stopped = job(12, "okHandler", null);
        stopped.setTriggerStatus(0);
        infoService.store.put(12, stopped);

        OkHandler.calls.set(0);
        service.triggerJob(parent);

        assertEquals("只有 trigger_status=1 的子任务被带起来", 2, OkHandler.calls.get());
        assertEquals(2, logService.saved.size());
    }

    // ---- IJobHandler 契约：库里唯一的 handler 接口，此前引擎调不到它 ----

    @Test
    public void 实现IJobHandler的bean能被执行并记成功() {
        InterfaceHandler.calls.set(0);
        service.triggerJob(job(20, "interfaceHandler", "p-20"));

        assertEquals("execute(TriggerParam) 那一支必须也被派发", 1, InterfaceHandler.calls.get());
        JobLog log = logService.saved.get(0);
        assertEquals(ReturnT.SUCCESS_CODE, log.getHandleCode());
        assertEquals("接口返回 ReturnT.success 不该被当成失败", 0, log.getAlarmStatus());
    }

    @Test
    public void 接口型handler拿到的TriggerParam带齐jobId参数与logId() {
        service.triggerJob(job(21, "interfaceHandler", "p-21"));

        TriggerParam seen = InterfaceHandler.lastParam;
        assertEquals(21, seen.getJobId());
        assertEquals("p-21", seen.getExecutorParams());
        assertEquals("interfaceHandler", seen.getExecutorHandler());
        assertTrue("logId 必须是那条日志的真实主键，否则分片/回调对不上行: " + seen.getLogId(),
                seen.getLogId() > 0);
        assertEquals("TriggerParam.logId 要和落库那行的 id 同一个",
                logService.saved.get(0).getId(), seen.getLogId());
    }

    @Test
    public void 接口返回非成功码要记失败并告警() {
        InterfaceFailHandler.calls.set(0);
        service.triggerJob(job(22, "interfaceFailHandler", null));

        assertEquals(1, InterfaceFailHandler.calls.get());
        JobLog log = logService.saved.get(0);
        assertNotEquals("handler 自己说失败，不能按成功回写",
                ReturnT.SUCCESS_CODE, log.getHandleCode());
        assertEquals(1, log.getAlarmStatus());
        assertTrue("失败原因要带 handler 的 msg: " + log.getHandleMsg(),
                log.getHandleMsg().contains("业务侧失败"));
    }

    @Test
    public void 两种execute都没有时报可读错误而不是反射原文() {
        service.triggerJob(job(23, "noExecuteHandler", null));

        JobLog log = logService.saved.get(0);
        assertNotEquals("两个合法形状都没有 ⇒ 不能算成功", ReturnT.SUCCESS_CODE, log.getHandleCode());
        assertTrue("要说清两个合法形状，而不是抛 NoSuchMethodException 原文: " + log.getHandleMsg(),
                log.getHandleMsg().contains("execute(String)") && log.getHandleMsg().contains("TriggerParam"));
    }

    // ---- 路由策略：本版本不参与派发（"装饰"这件事钉成规格，理由见 e2e README 的"路由策略"一节）----

    @Test
    public void 分片广播也只在本机执行一次且分片信息为唯一一片() {
        InterfaceHandler.calls.set(0);
        JobInfo job = job(30, "interfaceHandler", "p-30");
        job.setExecutorRouteStrategy(ExecutorRouteStrategyEnum.SHARDING_BROADCAST.getCode());

        service.triggerJob(job);

        assertEquals("UI 上的“分片广播”不得变成同一 JVM 内跑 N 遍", 1, InterfaceHandler.calls.get());
        assertEquals(ReturnT.SUCCESS_CODE, logService.saved.get(0).getHandleCode());
        TriggerParam seen = InterfaceHandler.lastParam;
        assertEquals("本机派发 ⇒ 这次执行就是唯一那一片；留 0 会让按分片写的 handler 一行都不做",
                1, seen.getBroadcastTotal());
        assertEquals(0, seen.getBroadcastIndex());
    }

    // ---- 失败与重试 ----

    @Test
    public void 失败按配置次数重试并逐次落日志() {
        BoomHandler.calls.set(0);
        JobInfo job = job(2, "boomHandler", null);
        job.setExecutorFailRetryCount(2);

        long start = System.currentTimeMillis();
        service.triggerJob(job);
        long costMs = System.currentTimeMillis() - start;

        assertEquals("首次 + 2 次重试", 3, BoomHandler.calls.get());
        assertEquals("每次尝试一条日志", 3, logService.saved.size());
        for (int i = 0; i < 3; i++) {
            JobLog log = logService.saved.get(i);
            assertEquals("第 " + i + " 次尝试应为失败码",
                    TriggerCodeEnum.FAIL.getCode(), log.getHandleCode());
            assertEquals("失败必须置告警状态", 1, log.getAlarmStatus());
        }
        assertEquals("每条失败日志都要发一次告警", 3, alarmService.calls.get());
        assertTrue("指数退避至少等了两轮 (1s + 2s)，实测 " + costMs + " ms", costMs >= 2500L);
    }

    @Test
    public void 缺少executorHandler时不重试也不计成功() {
        JobInfo job = job(3, "   ", null);
        job.setExecutorFailRetryCount(3);

        service.triggerJob(job);

        assertEquals(1, logService.saved.size());
        JobLog log = logService.saved.get(0);
        assertEquals(TriggerCodeEnum.INVALID_PARAM.getCode(), log.getHandleCode());
        assertTrue(log.getHandleMsg().contains("executorHandler"));
        assertEquals("配置错误不该重试", 1, alarmService.calls.get());
    }

    @Test
    public void handlerBean未注册记EXECUTOR_NOT_FOUND而不是成功() {
        service.triggerJob(job(4, "noSuchHandler", null));

        assertEquals(1, logService.saved.size());
        JobLog log = logService.saved.get(0);
        assertNotEquals("旧实现把这里按成功回写", ReturnT.SUCCESS_CODE, log.getHandleCode());
        assertEquals(TriggerCodeEnum.EXECUTOR_NOT_FOUND.getCode(), log.getHandleCode());
        assertTrue(log.getHandleMsg(), log.getHandleMsg().contains("noSuchHandler"));
        assertEquals(1, alarmService.calls.get());
    }

    // ---- kill 与 FIX_DELAY ----

    @Test
    public void killJob能中断正在执行的那次() throws Exception {
        SlowHandler.entered = new CountDownLatch(1);
        SlowHandler.interrupted = new CountDownLatch(1);
        final JobInfo job = job(5, "slowHandler", null);

        Thread worker = new Thread(new Runnable() {
            public void run() {
                service.triggerJob(job);
            }
        }, "test-exec-worker");
        worker.start();

        assertTrue("执行未起来", SlowHandler.entered.await(5, TimeUnit.SECONDS));
        assertEquals("在跑记录应可被查到", 1, service.runningExecutionCount());
        long logId = logService.saved.get(0).getId();

        service.killJob(logId);
        assertTrue("kill 必须真正中断执行线程（旧实现 runningTasks 从未 put 过）",
                SlowHandler.interrupted.await(5, TimeUnit.SECONDS));

        worker.join(5000L);
        assertFalse(worker.isAlive());
        assertEquals(0, service.runningExecutionCount());
        assertEquals("kill 后日志落 TIMEOUT", TriggerCodeEnum.TIMEOUT.getCode(),
                logService.byId(logId).getHandleCode());
    }

    @Test
    public void 非Leader节点不排期但手动触发仍可执行() {
        OkHandler.calls.set(0);
        injectLeaderElectorAsFollower();

        JobInfo job = job(6, "okHandler", null);
        service.registerJob(job);
        assertEquals("Follower 不该把任务挂进自己的轮", 0, engine.scheduleCalls.get());

        service.triggerJob(job);
        assertEquals("手动触发允许在任意节点执行", 1, OkHandler.calls.get());
    }

    @Test
    public void FIX_DELAY任务失败后也必须重挂下一轮() {
        BoomHandler.calls.set(0);
        JobInfo job = job(7, "boomHandler", null);
        job.setTriggerType(TriggerTypeEnum.FIX_DELAY.getCode());
        job.setFixInterval(1000L);

        service.triggerJob(job);

        assertEquals("无论成败都要重挂，否则任务永久停摆", 1, engine.completeCalls.size());
        assertEquals(Integer.valueOf(7), engine.completeCalls.get(0));
    }

    @Test
    public void FIX_DELAY任务成功后重挂一次() {
        JobInfo job = job(8, "okHandler", null);
        job.setTriggerType(TriggerTypeEnum.FIX_DELAY.getCode());
        job.setFixInterval(1000L);

        service.triggerJob(job);

        assertEquals(1, engine.completeCalls.size());
    }

    @Test
    public void cron任务不由触发器重挂() {
        JobInfo job = job(9, "okHandler", null);
        job.setTriggerType(TriggerTypeEnum.CRON.getCode());

        service.triggerJob(job);

        assertEquals("CRON 的下一轮由引擎自己算", 0, engine.completeCalls.size());
    }

    // ---- 测试替身 ----

    private JobInfo job(int id, String handler, String param) {
        JobInfo job = new JobInfo();
        job.setId(id);
        job.setJobGroup(1);
        job.setExecutorHandler(handler);
        job.setExecutorParam(param);
        job.setTriggerStatus(1);
        job.setTriggerType(TriggerTypeEnum.CRON.getCode());
        job.setMisfireStrategy("DO_NOTHING");
        return job;
    }

    private void inject(Object target, String field, Object value) throws Exception {
        Class<?> type = target.getClass();
        while (type != null) {
            try {
                Field declared = type.getDeclaredField(field);
                declared.setAccessible(true);
                declared.set(target, value);
                return;
            } catch (NoSuchFieldException e) {
                type = type.getSuperclass();
            }
        }
        throw new NoSuchFieldException(field);
    }

    private void injectLeaderElectorAsFollower() {
        try {
            inject(service, "leaderElector", new LeaderElector() {
                @Override
                public boolean isLeader() {
                    return false;
                }
            });
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static class AlwaysLeader extends LeaderElector {
        @Override
        public boolean isLeader() {
            return true;
        }
    }

    /** 只记录引擎侧调用，不做任何真实调度。 */
    private static class RecordingEngine extends JobScheduleEngine {
        final AtomicInteger scheduleCalls = new AtomicInteger();
        final List<Integer> completeCalls = new CopyOnWriteArrayList<Integer>();

        @Override
        public boolean scheduleJob(JobInfo info) {
            scheduleCalls.incrementAndGet();
            return true;
        }

        @Override
        public void completeJob(int jobId) {
            completeCalls.add(jobId);
        }

        @Override
        public boolean isRunning() {
            return true;
        }
    }

    public static class OkHandler {
        static final AtomicInteger calls = new AtomicInteger();
        static volatile String lastParam;

        public void execute(String param) {
            calls.incrementAndGet();
            lastParam = param;
        }
    }

    public static class BoomHandler {
        static final AtomicInteger calls = new AtomicInteger();

        public void execute(String param) {
            calls.incrementAndGet();
            throw new IllegalStateException("boom");
        }
    }

    public static class SlowHandler {
        static volatile CountDownLatch entered = new CountDownLatch(1);
        static volatile CountDownLatch interrupted = new CountDownLatch(1);

        public void execute(String param) throws InterruptedException {
            entered.countDown();
            try {
                new java.util.concurrent.CountDownLatch(1).await();
            } catch (InterruptedException e) {
                interrupted.countDown();
                throw e;
            }
        }
    }

    /** 库内唯一的 handler 契约（{@code IJobHandler}），业务方照接口文档写的就是这个形状。 */
    public static class InterfaceHandler implements IJobHandler {
        static final AtomicInteger calls = new AtomicInteger();
        static volatile TriggerParam lastParam;

        @Override
        public ReturnT<String> execute(TriggerParam triggerParam) {
            calls.incrementAndGet();
            lastParam = triggerParam;
            return ReturnT.success();
        }
    }

    public static class InterfaceFailHandler implements IJobHandler {
        static final AtomicInteger calls = new AtomicInteger();

        @Override
        public ReturnT<String> execute(TriggerParam triggerParam) {
            calls.incrementAndGet();
            return ReturnT.fail("业务侧失败");
        }
    }

    /** 既没有 execute(String) 也没实现 IJobHandler ⇒ 只能是配置错，报错要能看懂。 */
    public static class NoExecuteHandler {
        public String run(String param) {
            return param;
        }
    }

    private static class FakeAlarmService implements AlarmService {
        final AtomicInteger calls = new AtomicInteger();
        volatile JobInfo lastJob;
        volatile JobLog lastLog;

        public void sendAlarm(JobInfo job, JobLog log) {
            calls.incrementAndGet();
            lastJob = job;
            lastLog = log;
        }
    }

    private static class FakeJobLogService implements JobLogService {
        final List<JobLog> saved = new ArrayList<JobLog>();
        final Map<Long, JobLog> byId = new LinkedHashMap<Long, JobLog>();
        private long sequence = 100L;

        public synchronized long save(JobLog jobLog) {
            long id = ++sequence;
            jobLog.setId(id);
            saved.add(jobLog);
            byId.put(id, jobLog);
            return id;
        }

        public synchronized void update(JobLog jobLog) {
            byId.put(jobLog.getId(), jobLog);
        }

        public synchronized JobLog getById(long id) {
            return byId.get(id);
        }

        JobLog byId(long id) {
            return getById(id);
        }

        public List<JobLog> query(int jobGroup, int jobId, int handleCode, int limit) {
            return new ArrayList<JobLog>(saved);
        }

        public Stats statsBetween(java.util.Date startInclusive, java.util.Date endExclusive) {
            return Stats.empty();
        }

        public Map<String, Stats> dailyStatsSince(java.util.Date startInclusive) {
            return new LinkedHashMap<String, Stats>();
        }

        public int clearByJobId(int jobId) {
            return 0;
        }

        public int clearAll() {
            return 0;
        }

        public int clearLogByDays(int days) {
            return 0;
        }
    }

    private static class FakeJobInfoService implements JobInfoService {
        final Map<Integer, JobInfo> store = new LinkedHashMap<Integer, JobInfo>();

        public JobInfo getById(int id) {
            return store.get(id);
        }

        public List<JobInfo> getAll() {
            return new ArrayList<JobInfo>(store.values());
        }

        public List<JobInfo> getByJobGroup(int jobGroup) {
            return getAll();
        }

        public List<JobInfo> listRunning() {
            List<JobInfo> running = new ArrayList<JobInfo>();
            for (JobInfo job : store.values()) {
                if (job.getTriggerStatus() == 1) {
                    running.add(job);
                }
            }
            return running;
        }

        public void updateTriggerTimes(int jobId, long lastTime, long nextTime) {
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
            return ReturnT.success();
        }

        public ReturnT<String> start(int id) {
            return ReturnT.success();
        }

        public ReturnT<String> trigger(int id) {
            return ReturnT.success();
        }

        public ReturnT<List<String>> nextTriggerTime(String cron) {
            return ReturnT.success(new ArrayList<String>());
        }
    }
}
