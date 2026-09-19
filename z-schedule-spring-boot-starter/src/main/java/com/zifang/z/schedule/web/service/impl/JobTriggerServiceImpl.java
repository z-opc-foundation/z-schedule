package com.zifang.z.schedule.web.service.impl;

import com.zifang.z.schedule.core.enums.TriggerCodeEnum;
import com.zifang.z.schedule.core.enums.TriggerTypeEnum;
import com.zifang.z.schedule.core.model.JobInfo;
import com.zifang.z.schedule.core.model.JobLog;
import com.zifang.z.schedule.core.model.ReturnT;
import com.zifang.z.schedule.web.cluster.JobScheduleEngine;
import com.zifang.z.schedule.web.cluster.LeaderElector;
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
import org.springframework.context.annotation.Lazy;
import javax.annotation.Resource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Date;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;

/**
 * 定时任务触发器 — 集群感知 + 独立调度引擎
 * <p>
 * <b>架构演进</b>：
 * <pre>
 * v1 (已废弃)：Spring TaskScheduler + ConcurrentHashMap → 单机内存，重启丢失
 * v2 (旧版)：Spring TaskScheduler + DB 持久化 → 多节点重复执行风险
 * v3 (当前)：独立 ScheduleEngine + 时间轮 + Leader 选举 → 集群安全，DB 持久化
 * </pre>
 * <p>
 * <b>关键设计</b>：
 * <ul>
 *   <li>独立 {@link JobScheduleEngine}：含独立 ScheduleThreadPool (1线程 tick) + ExecuteThreadPool (4线程异步)</li>
 *   <li>仅 Leader 节点执行调度（通过 {@link LeaderElector} 判断）</li>
 *   <li>DB 持久化：任务在 z_schedule_job_info 表，重启后自动恢复</li>
 *   <li>手动触发：允许在任意节点触发（运维场景）</li>
 * </ul>
 */
@Service
public class JobTriggerServiceImpl implements JobTriggerService {

    private static final Logger logger = LogManager.getLogger(JobTriggerServiceImpl.class);

    @Resource
    private ApplicationContext applicationContext;
    @Resource
    @Lazy
    private JobLogService jobLogService;
    @Resource
    @Lazy
    private JobInfoService jobInfoService;
    @Resource
    private LeaderElector leaderElector;

    /**
     * 调度引擎（独立线程池 + 时间轮）
     */
    private JobScheduleEngine engine;

    /**
     * 正在运行的任务：key=logId, value=Future
     */
    private final Map<Long, Future<?>> runningTasks = new ConcurrentHashMap<>();

    /**
     * 任务执行耗时记录（ms）：用于快/慢线程池分级
     */
    private final Map<Integer, Long> jobExecutionTimes = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        engine = new JobScheduleEngine();
        engine.setJobInfoService(jobInfoService);
        engine.setJobTriggerService(this);
        engine.setLeaderElector(leaderElector);
        // 启动调度引擎
        engine.start();
        logger.info("[z-schedule] JobTriggerService initialized, engine started");
    }

    @PreDestroy
    public void destroy() {
        if (engine != null) {
            engine.stop();
        }
    }

    /**
     * Leader 切换时重新加载 DB 任务到时间轮。
     * <p>
     * 由 LeaderElector 的 elect() 间接触发，或由 JobInfoServiceImpl.start() 直接触发。
     */
    @Scheduled(fixedDelay = 15_000L)
    public void reloadRunningJobs() {
        if (!leaderElector.isLeader()) {
            return; // 非 Leader 不调度
        }
        engine.reloadJobs();
    }

    @Override
    public void registerJob(JobInfo jobInfo) {
        if (jobInfo == null || jobInfo.getJobCron() == null) {
            logger.warn("Invalid job info, skip registration");
            return;
        }
        if (!leaderElector.isLeader()) {
            logger.info("[Follower] skip registerJob for jobId={}", jobInfo.getId());
            return;
        }
        // 委托给引擎：引擎会重新加载 DB 任务
        engine.reloadJobs();
        logger.info("[Leader] registerJob delegated to engine, jobId={}", jobInfo.getId());
    }

    @Override
    public void cancelJob(int jobId) {
        // 委托给引擎：引擎会重新加载 DB 任务
        if (leaderElector.isLeader() && engine.isRunning()) {
            engine.reloadJobs();
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
        Future<?> future = runningTasks.remove(logId);
        if (future != null) {
            future.cancel(true);
            logger.info("Job killed, logId={}", logId);
            // 更新日志
            JobLog log = jobLogService.getById(logId);
            if (log != null) {
                log.setHandleCode(TriggerCodeEnum.TIMEOUT.getCode());
                log.setHandleMsg("任务被终止");
                log.setHandleTime(new Date());
                jobLogService.update(log);
            }
        } else {
            logger.warn("No running task found for logId={}", logId);
        }
    }

    @Override
    public void triggerJob(JobInfo jobInfo) {
        // 手动触发允许在任一节点执行
        logger.info("Job triggered manually, jobId={}", jobInfo.getId());
        executeJob(jobInfo);
    }

    /**
     * 执行任务逻辑（完整 JobLog 记录 + 失败重试 + 父任务触发 + FIX_DELAY 回调）。
     */
    private void executeJob(JobInfo jobInfo) {
        int retryCount = jobInfo.getExecutorFailRetryCount();
        if (retryCount < 0) retryCount = 0;
        int maxAttempts = retryCount + 1; // 至少执行 1 次

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
            long logId = jobLogService.save(log);

            long start = System.currentTimeMillis();
            try {
                String handler = jobInfo.getExecutorHandler();
                if (handler == null || handler.trim().isEmpty()) {
                    log.setHandleCode(TriggerCodeEnum.FAIL.getCode());
                    log.setHandleMsg("未指定 executorHandler");
                    log.setAlarmStatus(1);
                    jobLogService.update(log);
                    break; // 无 handler 不重试
                }
                executeHandler(handler, jobInfo.getExecutorParam());
                long costMs = System.currentTimeMillis() - start;
                log.setHandleCode(ReturnT.SUCCESS_CODE);
                log.setHandleMsg("执行成功,耗时 " + costMs + "ms" + (isRetry ? " (" + logLabel + ")" : ""));
                log.setHandleTime(new Date());
                jobLogService.update(log);

                // 记录执行耗时
                jobExecutionTimes.put(jobInfo.getId(), costMs);

                // 成功后触发父任务的子任务
                triggerChildJobs(jobInfo);

                // FIX_DELAY 模式：执行完成后挂入时间轮
                if (engine != null) {
                    engine.completeJob(jobInfo.getId());
                }
                return; // 成功，退出重试循环

            } catch (Throwable e) {
                long costMs = System.currentTimeMillis() - start;
                log.setHandleCode(TriggerCodeEnum.FAIL.getCode());
                log.setHandleMsg("执行失败" + (isRetry ? " (" + logLabel + ")" : "") + ": "
                        + (e.getMessage() == null ? e.getClass().getName() : e.getMessage()));
                log.setHandleTime(new Date());
                log.setAlarmStatus(0);
                jobLogService.update(log);
                logger.error("Job execution failed, jobId={}, attempt={}/{}", jobInfo.getId(), attempt, maxAttempts, e);

                if (attempt < maxAttempts) {
                    // 重试间隔：指数退避，最小 1 秒
                    long delay = Math.min(1000L * (1L << (attempt - 1)), 30_000L);
                    try {
                        Thread.sleep(delay);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
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

    private void executeHandler(String handler, String param) {
        Object bean;
        try {
            bean = applicationContext.getBean(handler);
        } catch (NoSuchBeanDefinitionException e) {
            logger.warn("Handler bean not registered: {}", handler);
            return;
        }
        if (bean == null) {
            return;
        }
        try {
            Method executeMethod = bean.getClass().getMethod("execute", String.class);
            executeMethod.invoke(bean, param);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            if (cause instanceof RuntimeException) throw (RuntimeException) cause;
            throw new RuntimeException(cause);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * 获取任务平均执行耗时（ms），供引擎判断快/慢线程池。
     */
    public Long getJobAvgCost(int jobId) {
        return jobExecutionTimes.get(jobId);
    }
}
