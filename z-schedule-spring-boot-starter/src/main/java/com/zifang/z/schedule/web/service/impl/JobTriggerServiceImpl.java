package com.zifang.z.schedule.web.service.impl;

import com.zifang.z.schedule.core.config.ScheduleProperties;
import com.zifang.z.schedule.core.enums.TriggerCodeEnum;
import com.zifang.z.schedule.core.enums.TriggerTypeEnum;
import com.zifang.z.schedule.core.model.JobInfo;
import com.zifang.z.schedule.core.model.JobLog;
import com.zifang.z.schedule.core.model.ReturnT;
import com.zifang.z.schedule.core.param.TriggerParam;
import com.zifang.z.schedule.web.cluster.JobScheduleEngine;
import com.zifang.z.schedule.web.cluster.LeaderElector;
import com.zifang.z.schedule.web.service.AlarmService;
import com.zifang.z.schedule.web.service.JobInfoService;
import com.zifang.z.schedule.web.service.JobLogService;
import com.zifang.z.schedule.web.service.JobTriggerService;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.annotation.Resource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Date;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 定时任务触发器 — 集群感知 + 独立调度引擎
 * <p>
 * <b>架构</b>：
 * <ul>
 *   <li>时间轮与线程池在 {@link JobScheduleEngine}；本类负责一次执行的生命周期：
 *       建日志 → 反射调 handler → 重试 → 回写结果/告警 → FIX_DELAY 重挂</li>
 *   <li>仅 Leader 节点排期（{@link LeaderElector}）；手动触发允许在任意节点执行</li>
 *   <li>启停单个任务走 {@code scheduleJob}/{@code unscheduleJob}，不再全表 reload</li>
 * </ul>
 */
@Service
public class JobTriggerServiceImpl implements JobTriggerService {

    private static final Logger logger = LogManager.getLogger(JobTriggerServiceImpl.class);

    @Resource
    private ApplicationContext applicationContext;
    @Resource
    @org.springframework.context.annotation.Lazy
    private JobLogService jobLogService;
    @Resource
    @org.springframework.context.annotation.Lazy
    private JobInfoService jobInfoService;
    @Resource
    @org.springframework.context.annotation.Lazy
    private AlarmService alarmService;
    @Resource
    private LeaderElector leaderElector;
    @Resource
    private ScheduleProperties scheduleProperties;

    /**
     * 调度引擎（独立线程池 + 时间轮）
     */
    private JobScheduleEngine engine;

    /**
     * 正在运行的执行：logId → 执行线程。kill 通过中断该线程生效，执行结束后必须移除。
     */
    private final Map<Long, Thread> runningExecutions = new ConcurrentHashMap<Long, Thread>();

    @PostConstruct
    public void init() {
        engine = new JobScheduleEngine();
        engine.setJobInfoService(jobInfoService);
        engine.setJobTriggerService(this);
        engine.setLeaderElector(leaderElector);
        engine.setProperties(scheduleProperties);
        engine.start();
        logger.info("[z-schedule] JobTriggerService initialized, engine started");
    }

    /**
     * 引擎仅在测试/嵌入式场景下外部提供。
     */
    public void setEngine(JobScheduleEngine engine) {
        this.engine = engine;
    }

    @PreDestroy
    public void destroy() {
        if (engine != null) {
            engine.stop();
        }
        runningExecutions.clear();
    }

    /**
     * 周期性 reconcile：兜住绕过本节点直接改 DB 的变更（其它节点 start/stop、SQL 手工改）。
     */
    @Scheduled(fixedDelay = 15_000L)
    public void reloadRunningJobs() {
        if (!leaderElector.isLeader()) {
            return; // 非 Leader 不排期
        }
        engine.reloadJobs();
    }

    @Override
    public void registerJob(JobInfo jobInfo) {
        if (jobInfo == null) {
            logger.warn("Invalid job info, skip registration");
            return;
        }
        if (!leaderElector.isLeader()) {
            logger.info("[Follower] skip registerJob for jobId={}, leader 将在下次 reconcile 装载", jobInfo.getId());
            return;
        }
        if (engine.scheduleJob(jobInfo)) {
            logger.info("[Leader] jobId={} 已挂入时间轮, nextFire={}", jobInfo.getId(),
                    engine.nextFireTime(jobInfo.getId()));
        } else {
            logger.info("[Leader] jobId={} 未进入调度（cron 非法 / 已无未来触发时间 / 已停止）", jobInfo.getId());
        }
    }

    @Override
    public void cancelJob(int jobId) {
        if (leaderElector.isLeader() && engine.isRunning()) {
            engine.unscheduleJob(jobId);
        }
        logger.info("cancelJob, jobId={}", jobId);
    }

    @Override
    public void completeJob(int jobId) {
        if (engine != null && engine.isRunning()) {
            engine.completeJob(jobId);
        }
        logger.debug("completeJob, jobId={}", jobId);
    }

    @Override
    public void killJob(long logId) {
        Thread worker = runningExecutions.remove(logId);
        if (worker == null) {
            logger.warn("No running task found for logId={}", logId);
            return;
        }
        worker.interrupt();
        logger.info("Job killed, logId={}, thread={}", logId, worker.getName());
        JobLog log = jobLogService.getById(logId);
        if (log != null) {
            log.setHandleCode(TriggerCodeEnum.TIMEOUT.getCode());
            log.setHandleMsg("任务被终止");
            log.setHandleTime(new Date());
            jobLogService.update(log);
        }
    }

    /** 正在执行中的日志数（监控用）。 */
    public int runningExecutionCount() {
        return runningExecutions.size();
    }

    @Override
    public void triggerJob(JobInfo jobInfo) {
        executeJob(jobInfo);
    }

    /**
     * 执行一个任务：建日志 → 调 handler → 失败重试（指数退避）→ 回写结果与告警。
     */
    private void executeJob(JobInfo jobInfo) {
        Integer configuredRetries = jobInfo.getExecutorFailRetryCount();
        // null=这个 DTO 没经过持久层（外部直接构造/手工触发），按"不重试"处理
        int retryCount = configuredRetries == null || configuredRetries < 0 ? 0 : configuredRetries;
        int maxAttempts = retryCount + 1;
        long logId = 0L;

        try {
            for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                boolean isRetry = attempt > 1;
                String logLabel = isRetry ? "重试第" + (attempt - 1) + "次" : "首次执行";

                JobLog log = new JobLog();
                log.setJobId(jobInfo.getId());
                log.setJobGroup(jobInfo.getJobGroup());
                log.setExecutorHandler(jobInfo.getExecutorHandler());
                log.setExecutorParam(jobInfo.getExecutorParam());
                log.setTriggerCode(TriggerCodeEnum.SUCCESS.getCode());
                log.setTriggerMsg(logLabel);
                log.setTriggerTime(new Date());
                log.setAlarmStatus(0);
                log.setHandleCode(0);
                log.setExecutorFailRetryCount(retryCount);
                logId = jobLogService.save(log);

                registerRunning(logId);
                long start = System.currentTimeMillis();
                try {
                    String handler = jobInfo.getExecutorHandler();
                    if (handler == null || handler.trim().isEmpty()) {
                        finishFailure(jobInfo, log, "未指定 executorHandler", TriggerCodeEnum.INVALID_PARAM.getCode());
                        return; // 配置错误不重试
                    }
                    executeHandler(handler, jobInfo, logId);
                    long costMs = System.currentTimeMillis() - start;
                    log.setHandleCode(ReturnT.SUCCESS_CODE);
                    log.setHandleMsg("执行成功,耗时 " + costMs + "ms" + (isRetry ? " (" + logLabel + ")" : ""));
                    log.setHandleTime(new Date());
                    jobLogService.update(log);

                    triggerChildJobs(jobInfo);
                    return;

                } catch (InterruptedException e) {
                    finishFailure(jobInfo, log, "任务被中断/超时: " + describe(e),
                            TriggerCodeEnum.TIMEOUT.getCode());
                    Thread.currentThread().interrupt();
                    return;

                } catch (Throwable e) {
                    logger.error("Job execution failed, jobId={}, attempt={}/{}",
                            jobInfo.getId(), attempt, maxAttempts, e);
                    TriggerCodeEnum code = e instanceof ExecutorNotFoundException
                            ? TriggerCodeEnum.EXECUTOR_NOT_FOUND : TriggerCodeEnum.FAIL;
                    finishFailure(jobInfo, log,
                            "执行失败" + (isRetry ? " (" + logLabel + ")" : "") + ": " + describe(e),
                            code.getCode());
                    if (attempt < maxAttempts) {
                        long delay = Math.min(1000L * (1L << (attempt - 1)), 30_000L);
                        Thread.sleep(delay);
                    }
                } finally {
                    unregisterRunning(logId);
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } finally {
            // FIX_DELAY 无论成功失败都要重挂，否则一次异常会让该任务永久停止调度
            if (engine != null && TriggerTypeEnum.FIX_DELAY.getCode().equals(jobInfo.getTriggerType())) {
                engine.completeJob(jobInfo.getId());
            }
        }
    }

    private void finishFailure(JobInfo jobInfo, JobLog log, String message, int code) {
        log.setHandleCode(code);
        log.setHandleMsg(message);
        log.setHandleTime(new Date());
        log.setAlarmStatus(1);
        jobLogService.update(log);
        if (alarmService != null) {
            try {
                int statusBeforeAlarm = log.getAlarmStatus();
                alarmService.sendAlarm(jobInfo, log);
                // 只有 sendAlarm 真的改写了 alarmStatus 才值得再发一条 UPDATE：
                // 无条件重写曾让每条失败日志固定产生 2 条 UPDATE（250 真机百任务规模实测
                // 3.02 条语句/次，其中 2 条是 UPDATE），而默认的 DefaultAlarmService
                // 在没配邮箱时直接 return，第二次写的内容和第一次逐字节相同。
                if (log.getAlarmStatus() != statusBeforeAlarm) {
                    jobLogService.update(log); // 持久化 sendAlarm 改写后的 alarmStatus
                }
            } catch (Exception e) {
                logger.warn("[z-schedule] alarm failed, jobId={}: {}", jobInfo.getId(), e.toString());
            }
        }
    }

    private static String describe(Throwable e) {
        return e.getMessage() == null ? e.getClass().getName() : e.getMessage();
    }

    private void registerRunning(long logId) {
        if (logId > 0) {
            runningExecutions.put(logId, Thread.currentThread());
        }
    }

    private void unregisterRunning(long logId) {
        if (logId > 0) {
            runningExecutions.remove(logId);
        }
    }

    /**
     * 触发子任务（父任务执行成功后自动触发）。
     */
    private void triggerChildJobs(JobInfo parentJob) {
        String childJobIds = parentJob.getChildJobId();
        if (childJobIds == null || childJobIds.trim().isEmpty()) {
            return;
        }
        String[] ids = childJobIds.split(",");
        for (String idStr : ids) {
            try {
                int childId = Integer.parseInt(idStr.trim());
                JobInfo childJob = jobInfoService.getById(childId);
                if (childJob != null && childJob.getTriggerStatus() == 1) {
                    logger.info("Triggering child job, parentId={}, childId={}", parentJob.getId(), childId);
                    triggerJob(childJob);
                }
            } catch (NumberFormatException e) {
                logger.warn("Invalid child job id: {}", idStr);
            }
        }
    }

    private void executeHandler(String handler, JobInfo jobInfo, long logId) throws Exception {
        Object bean;
        try {
            bean = applicationContext.getBean(handler);
        } catch (NoSuchBeanDefinitionException e) {
            // 旧实现只打一条 warn 然后按"执行成功"回写日志，任务其实什么都没做
            throw new ExecutorNotFoundException("执行器 bean 未注册: " + handler);
        }
        if (bean == null) {
            throw new ExecutorNotFoundException("执行器 bean 为空: " + handler);
        }
        boolean wantsTriggerParam;
        Method executeMethod;
        try {
            executeMethod = bean.getClass().getMethod("execute", String.class);
            wantsTriggerParam = false;
        } catch (NoSuchMethodException notAStringHandler) {
            // 库里唯一的 handler 契约是 IJobHandler.execute(TriggerParam)：接口随 z-schedule-core
            // 一起发布，但此前这一步只找 execute(String)，所以照接口文档写的业务方会在**派发**这一步
            // 拿到 NoSuchMethodException，日志记成一次普通"执行失败"——接口在库里却没人能实现它。
            try {
                executeMethod = bean.getClass().getMethod("execute", TriggerParam.class);
                wantsTriggerParam = true;
            } catch (NoSuchMethodException neither) {
                throw new ExecutorNotFoundException("执行器 bean 没有可调用的 execute(String) 或 "
                        + "execute(TriggerParam): " + handler + " (" + bean.getClass().getName() + ")");
            }
        }
        try {
            Object result = executeMethod.invoke(bean,
                    wantsTriggerParam ? buildTriggerParam(jobInfo, logId) : (Object) jobInfo.getExecutorParam());
            // 接口版是**有返回**的：handler 自己说失败（ReturnT.fail）就不能回写成成功。
            if (result instanceof ReturnT && ((ReturnT<?>) result).getCode() != ReturnT.SUCCESS_CODE) {
                ReturnT<?> ret = (ReturnT<?>) result;
                throw new RuntimeException("handler 返回非成功码 " + ret.getCode() + ": " + ret.getMsg());
            }
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            if (cause instanceof InterruptedException) {
                throw (InterruptedException) cause;
            }
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            throw new RuntimeException(cause);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private static TriggerParam buildTriggerParam(JobInfo jobInfo, long logId) {
        TriggerParam param = new TriggerParam();
        param.setJobId(jobInfo.getId());
        param.setExecutorHandler(jobInfo.getExecutorHandler());
        param.setExecutorParams(jobInfo.getExecutorParam());
        param.setExecutorBlockStrategy(jobInfo.getExecutorBlockStrategy());
        param.setExecutorTimeout(orZero(jobInfo.getExecutorTimeout()));
        param.setExecutorFailRetryCount(orZero(jobInfo.getExecutorFailRetryCount()));
        param.setLogId(logId);
        param.setLogDateTime(jobInfo.getTriggerLastTime());
        // 派发只在本 JVM 内发生 ⇒ 这次执行就是"唯一那一片"。留 0/0 会让按分片写的
        // handler 一行都不做（它的循环通常是 for (i = index; i < total; i += total)）。
        param.setBroadcastIndex(0);
        param.setBroadcastTotal(1);
        return param;
    }

    /** #11 之后这两个字段是装箱的：库里没值就是 null，直接喂给 int setter 会变成派发期 NPE。 */
    private static int orZero(Integer value) {
        return value == null ? 0 : value;
    }

    /** handler bean 缺失，与"业务执行异常"区分开，便于日志里落到 EXECUTOR_NOT_FOUND。 */
    static class ExecutorNotFoundException extends RuntimeException {
        ExecutorNotFoundException(String message) {
            super(message);
        }
    }
}
