package com.zifang.z.schedule.web.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.zifang.z.meta.api.annotation.BizField;
import com.zifang.z.meta.api.annotation.BizModel;

import java.util.Date;

/**
 * 任务执行日志 DO（持久化层映射 z_schedule_job_log 表）
 */
@BizModel(name = "任务执行日志 DO（持久化层映射 z_schedule_job_log 表）", module = "z-schedule", description = "任务执行日志 DO（持久化层映射 z_schedule_job_log 表）")
@TableName("z_schedule_job_log")
public class JobLogDO {

    @TableId(type = IdType.AUTO)
    @BizField(name = "日志ID", type = BizField.FieldType.INTEGER)
    private Long id;

    @BizField(name = "执行器主键ID", type = BizField.FieldType.INTEGER)
    private Integer jobGroup;
    @BizField(name = "任务ID", type = BizField.FieldType.INTEGER)
    private Integer jobId;
    @BizField(name = "执行器地址", type = BizField.FieldType.STRING)
    private String executorAddress;
    @BizField(name = "Handler", type = BizField.FieldType.STRING)
    private String executorHandler;
    @BizField(name = "执行参数", type = BizField.FieldType.STRING)
    private String executorParam;
    @BizField(name = "分片参数 1/2", type = BizField.FieldType.STRING)
    private String executorShardingParam;
    @BizField(name = "失败重试次数", type = BizField.FieldType.INTEGER)
    private Integer executorFailRetryCount;
    @BizField(name = "调度时间", type = BizField.FieldType.DATETIME)
    private Date triggerTime;
    /**
     * 200=调度成功 500=调度失败
     */
    @BizField(name = "调度结果", type = BizField.FieldType.INTEGER)
    private Integer triggerCode;
    @BizField(name = "调度日志", type = BizField.FieldType.STRING)
    private String triggerMsg;
    @BizField(name = "执行时间", type = BizField.FieldType.DATETIME)
    private Date handleTime;
    /**
     * 200=执行成功 500=执行失败
     */
    @BizField(name = "执行结果", type = BizField.FieldType.INTEGER)
    private Integer handleCode;
    @BizField(name = "执行日志", type = BizField.FieldType.STRING)
    private String handleMsg;
    /**
     * 0-默认 1-无需告警 2-告警成功 3-告警失败
     */
    @BizField(name = "0-默认 1-无需告警 2-告警成功 3-告警失败", type = BizField.FieldType.INTEGER)
    private Integer alarmStatus;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Integer getJobGroup() {
        return jobGroup;
    }

    public void setJobGroup(Integer jobGroup) {
        this.jobGroup = jobGroup;
    }

    public Integer getJobId() {
        return jobId;
    }

    public void setJobId(Integer jobId) {
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

    public Integer getExecutorFailRetryCount() {
        return executorFailRetryCount;
    }

    public void setExecutorFailRetryCount(Integer executorFailRetryCount) {
        this.executorFailRetryCount = executorFailRetryCount;
    }

    public Date getTriggerTime() {
        return triggerTime;
    }

    public void setTriggerTime(Date triggerTime) {
        this.triggerTime = triggerTime;
    }

    public Integer getTriggerCode() {
        return triggerCode;
    }

    public void setTriggerCode(Integer triggerCode) {
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

    public Integer getHandleCode() {
        return handleCode;
    }

    public void setHandleCode(Integer handleCode) {
        this.handleCode = handleCode;
    }

    public String getHandleMsg() {
        return handleMsg;
    }

    public void setHandleMsg(String handleMsg) {
        this.handleMsg = handleMsg;
    }

    public Integer getAlarmStatus() {
        return alarmStatus;
    }

    public void setAlarmStatus(Integer alarmStatus) {
        this.alarmStatus = alarmStatus;
    }
}



