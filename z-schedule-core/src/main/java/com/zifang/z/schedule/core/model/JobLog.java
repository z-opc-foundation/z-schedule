package com.zifang.z.schedule.core.model;

import java.io.Serializable;
import java.util.Date;

/**
 * 任务调度日志实体类
 */
public class JobLog implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 主键ID
     */
    private long id;

    /**
     * 执行器主键ID
     */
    private int jobGroup;

    /**
     * 任务ID
     */
    private int jobId;

    /**
     * 执行器地址
     */
    private String executorAddress;

    /**
     * 执行器任务handler
     */
    private String executorHandler;

    /**
     * 执行器任务参数
     */
    private String executorParam;

    /**
     * 执行器分片参数，格式如 1/2
     */
    private String executorShardingParam;

    /**
     * 失败重试次数
     */
    private int executorFailRetryCount;

    /**
     * 调度时间
     */
    private Date triggerTime;

    /**
     * 调度结果代码
     */
    private int triggerCode;

    /**
     * 调度日志
     */
    private String triggerMsg;

    /**
     * 执行时间
     */
    private Date handleTime;

    /**
     * 执行状态代码
     */
    private int handleCode;

    /**
     * 执行日志
     */
    private String handleMsg;

    /**
     * 告警状态：0-默认、1-无需告警、2-告警成功、3-告警失败
     */
    private int alarmStatus;

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public int getJobGroup() {
        return jobGroup;
    }

    public void setJobGroup(int jobGroup) {
        this.jobGroup = jobGroup;
    }

    public int getJobId() {
        return jobId;
    }

    public void setJobId(int jobId) {
        this.jobId = jobId;
    }

    public String getExecutorAddress() {
        return executorAddress;
    }

    public void setExecutorAddress(String executorAddress) {
        this.executorAddress = executorAddress;
    }

    public String getExecutorHandler() {
        return executorHandler;
    }

    public void setExecutorHandler(String executorHandler) {
        this.executorHandler = executorHandler;
    }

    public String getExecutorParam() {
        return executorParam;
    }

    public void setExecutorParam(String executorParam) {
        this.executorParam = executorParam;
    }

    public String getExecutorShardingParam() {
        return executorShardingParam;
    }

    public void setExecutorShardingParam(String executorShardingParam) {
        this.executorShardingParam = executorShardingParam;
    }

    public int getExecutorFailRetryCount() {
        return executorFailRetryCount;
    }

    public void setExecutorFailRetryCount(int executorFailRetryCount) {
        this.executorFailRetryCount = executorFailRetryCount;
    }

    public Date getTriggerTime() {
        return triggerTime;
    }

    public void setTriggerTime(Date triggerTime) {
        this.triggerTime = triggerTime;
    }

    public int getTriggerCode() {
        return triggerCode;
    }

    public void setTriggerCode(int triggerCode) {
        this.triggerCode = triggerCode;
    }

    public String getTriggerMsg() {
        return triggerMsg;
    }

    public void setTriggerMsg(String triggerMsg) {
        this.triggerMsg = triggerMsg;
    }

    public Date getHandleTime() {
        return handleTime;
    }

    public void setHandleTime(Date handleTime) {
        this.handleTime = handleTime;
    }

    public int getHandleCode() {
        return handleCode;
    }

    public void setHandleCode(int handleCode) {
        this.handleCode = handleCode;
    }

    public String getHandleMsg() {
        return handleMsg;
    }

    public void setHandleMsg(String handleMsg) {
        this.handleMsg = handleMsg;
    }

    public int getAlarmStatus() {
        return alarmStatus;
    }

    public void setAlarmStatus(int alarmStatus) {
        this.alarmStatus = alarmStatus;
    }

    @Override
    public String toString() {
        return "JobLog{" +
                "id=" + id +
                ", jobId=" + jobId +
                ", executorAddress='" + executorAddress + '\'' +
                ", executorHandler='" + executorHandler + '\'' +
                ", triggerTime=" + triggerTime +
                ", triggerCode=" + triggerCode +
                ", handleCode=" + handleCode +
                '}';
    }
}
