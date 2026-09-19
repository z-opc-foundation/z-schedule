package com.zifang.z.schedule.web.service;

import com.zifang.z.schedule.core.model.JobInfo;
import com.zifang.z.schedule.core.model.JobLog;

/**
 * 告警服务接口
 */
public interface AlarmService {

    /**
     * 发送告警通知
     *
     * @param job 任务信息
     * @param log 调度日志
     */
    void sendAlarm(JobInfo job, JobLog log);
}
