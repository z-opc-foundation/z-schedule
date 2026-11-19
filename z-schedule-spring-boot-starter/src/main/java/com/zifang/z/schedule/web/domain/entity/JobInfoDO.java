package com.zifang.z.schedule.web.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.zifang.z.meta.api.annotation.BizField;
import com.zifang.z.meta.api.annotation.BizModel;

import java.util.Date;

/**
 * 任务定义 DO（持久化层映射 z_schedule_job_info 表）
 * <p>
 * 区别于 core/model/JobInfo（API DTO）——本类只用于 ORM 映射，
 * 字段命名 snake_case 对应表列，service 层负责 DTO ↔ DO 转换。
 */
@BizModel(name = "任务定义 DO（持久化层映射 z_schedule_job_info 表）", module = "z-schedule", description = "任务定义 DO（持久化层映射 z_schedule_job_info 表） 区别于 core/model/JobInfo（API DTO）——本类只用于 ORM 映射， 字段命名 snake_case 对应表列，service 层负责 DTO ↔ DO 转换。")
@TableName("z_schedule_job_info")
public class JobInfoDO {

    @TableId(type = IdType.AUTO)
    @BizField(name = "主键ID", type = BizField.FieldType.INTEGER)
    private Integer id;

    @BizField(name = "执行器主键ID", type = BizField.FieldType.INTEGER)
    private Integer jobGroup;
    @BizField(name = "任务描述", type = BizField.FieldType.STRING)
    private String jobDesc;
    @BizField(name = "Cron表达式", type = BizField.FieldType.STRING)
    private String jobCron;
    @BizField(name = "作者", type = BizField.FieldType.STRING)
    private String author;
    @BizField(name = "报警邮件", type = BizField.FieldType.STRING)
    private String alarmEmail;
    @BizField(name = "路由策略", type = BizField.FieldType.STRING)
    private String executorRouteStrategy;
    @BizField(name = "执行器Handler名称", type = BizField.FieldType.STRING)
    private String executorHandler;
    @BizField(name = "执行参数", type = BizField.FieldType.STRING)
    private String executorParam;
    @BizField(name = "阻塞策略", type = BizField.FieldType.STRING)
    private String executorBlockStrategy;
    @BizField(name = "超时时间(秒), 0=不限", type = BizField.FieldType.INTEGER)
    private Integer executorTimeout;
    @BizField(name = "失败重试次数", type = BizField.FieldType.INTEGER)
    private Integer executorFailRetryCount;
    /**
     * 0-停止 1-运行
     */
    @BizField(name = "0-停止 1-运行", type = BizField.FieldType.INTEGER)
    private Integer triggerStatus;
    /**
     * 上次触发时间 (epoch ms, 0=未触发)
     */
    @BizField(name = "上次触发时间(ms)", type = BizField.FieldType.INTEGER)
    private Long triggerLastTime;
    /**
     * 下次触发时间 (epoch ms, 0=未计算)
     */
    @BizField(name = "下次触发时间(ms)", type = BizField.FieldType.INTEGER)
    private Long triggerNextTime;
    /**
     * 触发类型：CRON / FIX_RATE / FIX_DELAY
     */
    @BizField(name = "触发类型：CRON / FIX_RATE / FIX_DELAY", type = BizField.FieldType.STRING)
    private String triggerType;
    /**
     * FIX_RATE/FIX_DELAY 间隔时间 (毫秒)
     */
    @BizField(name = "FIX_RATE/FIX_DELAY 间隔时间 (毫秒)", type = BizField.FieldType.INTEGER)
    private Long fixInterval;
    /**
     * 调度过期策略：DO_NOTHING / FIRE_ONCE_NOW
     */
    @BizField(name = "调度过期策略：DO_NOTHING / FIRE_ONCE_NOW", type = BizField.FieldType.STRING)
    private String misfireStrategy;
    /**
     * 子任务ID（逗号分隔），父任务成功后自动触发
     */
    @BizField(name = "子任务ID（逗号分隔），父任务成功后自动触发", type = BizField.FieldType.STRING)
    private String childJobId;
    @BizField(name = "添加时间", type = BizField.FieldType.DATETIME)
    private Date addTime;
    @BizField(name = "更新时间", type = BizField.FieldType.DATETIME)
    private Date updateTime;

    public Integer getId() {
        return id;
    }

    public void setId(Integer id) {
        this.id = id;
    }

    public Integer getJobGroup() {
        return jobGroup;
    }

    public void setJobGroup(Integer jobGroup) {
        this.jobGroup = jobGroup;
    }

    public String getJobDesc() {
        return jobDesc;
    }

    public void setJobDesc(String jobDesc) {
        this.jobDesc = jobDesc;
    }

    public String getJobCron() {
        return jobCron;
    }

    public void setJobCron(String jobCron) {
        this.jobCron = jobCron;
    }

    public String getAuthor() {
        return author;
    }

    public void setAuthor(String author) {
        this.author = author;
    }

    public String getAlarmEmail() {
        return alarmEmail;
    }

    public void setAlarmEmail(String alarmEmail) {
        this.alarmEmail = alarmEmail;
    }

    public String getExecutorRouteStrategy() {
        return executorRouteStrategy;
    }

    public void setExecutorRouteStrategy(String executorRouteStrategy) {
        this.executorRouteStrategy = executorRouteStrategy;
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

    public String getExecutorBlockStrategy() {
        return executorBlockStrategy;
    }

    public void setExecutorBlockStrategy(String executorBlockStrategy) {
        this.executorBlockStrategy = executorBlockStrategy;
    }

    public Integer getExecutorTimeout() {
        return executorTimeout;
    }

    public void setExecutorTimeout(Integer executorTimeout) {
        this.executorTimeout = executorTimeout;
    }

    public Integer getExecutorFailRetryCount() {
        return executorFailRetryCount;
    }

    public void setExecutorFailRetryCount(Integer executorFailRetryCount) {
        this.executorFailRetryCount = executorFailRetryCount;
    }

    public Integer getTriggerStatus() {
        return triggerStatus;
    }

    public void setTriggerStatus(Integer triggerStatus) {
        this.triggerStatus = triggerStatus;
    }

    public Long getTriggerLastTime() {
        return triggerLastTime;
    }

    public void setTriggerLastTime(Long triggerLastTime) {
        this.triggerLastTime = triggerLastTime;
    }

    public Long getTriggerNextTime() {
        return triggerNextTime;
    }

    public void setTriggerNextTime(Long triggerNextTime) {
        this.triggerNextTime = triggerNextTime;
    }

    public String getTriggerType() {
        return triggerType;
    }

    public void setTriggerType(String triggerType) {
        this.triggerType = triggerType;
    }

    public Long getFixInterval() {
        return fixInterval;
    }

    public void setFixInterval(Long fixInterval) {
        this.fixInterval = fixInterval;
    }

    public String getMisfireStrategy() {
        return misfireStrategy;
    }

    public void setMisfireStrategy(String misfireStrategy) {
        this.misfireStrategy = misfireStrategy;
    }

    public String getChildJobId() {
        return childJobId;
    }

    public void setChildJobId(String childJobId) {
        this.childJobId = childJobId;
    }

    public Date getAddTime() {
        return addTime;
    }

    public void setAddTime(Date addTime) {
        this.addTime = addTime;
    }

    public Date getUpdateTime() {
        return updateTime;
    }

    public void setUpdateTime(Date updateTime) {
        this.updateTime = updateTime;
    }
}



