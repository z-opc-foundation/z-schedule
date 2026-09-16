package io.github.yuku123.z.schedule.web.service.impl;

import io.github.yuku123.z.schedule.core.enums.TriggerCodeEnum;
import io.github.yuku123.z.schedule.core.model.JobInfo;
import io.github.yuku123.z.schedule.core.model.JobLog;
import io.github.yuku123.z.schedule.core.model.ReturnT;
import io.github.yuku123.z.schedule.web.cluster.JobScheduleEngine;
import io.github.yuku123.z.schedule.web.cluster.LeaderElector;
import io.github.yuku123.z.schedule.web.service.JobInfoService;
import io.github.yuku123.z.schedule.web.service.JobLogService;
import io.github.yuku123.z.schedule.web.service.JobTriggerService;
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
    public void triggerJob(JobInfo jobInfo) {
        // 手动触发允许在任一节点执行
        logger.info("Job triggered manually, jobId={}", jobInfo.getId());
        executeJob(jobInfo);
    }

    /**
     * 执行任务逻辑（完整 JobLog 记录）。
     */
    private void executeJob(JobInfo jobInfo) {
        JobLog log = new JobLog();
        log.setJobId(jobInfo.getId());
        log.setJobGroup(jobInfo.getJobGroup());
        log.setExecutorHandler(jobInfo.getExecutorHandler());
        log.setExecutorParam(jobInfo.getExecutorParam());
        log.setTriggerCode(TriggerCodeEnum.SUCCESS.getCode());
        log.setTriggerMsg("调度成功");
        log.setTriggerTime(new Date());
        log.setAlarmStatus(0);
        log.setHandleCode(0);
        long logId = jobLogService.save(log);

        long start = System.currentTimeMillis();
        try {
            String handler = jobInfo.getExecutorHandler();
            if (handler == null || handler.trim().isEmpty()) {
                log.setHandleCode(TriggerCodeEnum.FAIL.getCode());
                log.setHandleMsg("未指定 executorHandler");
                log.setAlarmStatus(1);
                return;
            }
            executeHandler(handler, jobInfo.getExecutorParam());
            log.setHandleCode(ReturnT.SUCCESS_CODE);
            log.setHandleMsg("执行成功,耗时 " + (System.currentTimeMillis() - start) + "ms");
            log.setHandleTime(new Date());
        } catch (Throwable e) {
            log.setHandleCode(TriggerCodeEnum.FAIL.getCode());
            log.setHandleMsg(e.getMessage() == null ? e.getClass().getName() : e.getMessage());
            log.setHandleTime(new Date());
            log.setAlarmStatus(0);
            logger.error("Job execution failed, jobId={}", jobInfo.getId(), e);
        } finally {
            jobLogService.update(log);
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
}
