package io.github.yuku123.z.schedule.web.service;

import io.github.yuku123.z.schedule.core.model.JobInfo;

/**
 * 定时任务触发器服务接口
 * <p>
 * 负责将内存中的 JobInfo 注册为 Spring TaskScheduler 的定时任务，
 * 实现 Cron 表达式的定时触发。
 */
public interface JobTriggerService {

    /**
     * 注册并启动一个定时任务
     *
     * @param jobInfo 任务信息
     */
    void registerJob(JobInfo jobInfo);

    /**
     * 取消已注册的定时任务
     *
     * @param jobId 任务ID
     */
    void cancelJob(int jobId);

    /**
     * 立即手动触发一次任务
     *
     * @param jobInfo 任务信息
     */
    void triggerJob(JobInfo jobInfo);

    /**
     * 任务执行完成回调（用于 FIX_DELAY 模式挂入时间轮）
     *
     * @param jobId 任务ID
     */
    void completeJob(int jobId);

    /**
     * 终止正在运行的任务
     *
     * @param logId 执行日志ID
     */
    void killJob(long logId);
}
