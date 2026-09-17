package io.github.yuku123.z.schedule.core.model;

import java.io.Serializable;
import java.util.Date;

/**
 * 任务信息实体类
 */
public class JobInfo implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 主键ID
     */
    private int id;

    /**
     * 执行器主键ID
     */
    private int jobGroup;

    /**
     * 任务执行CRON表达式
     */
    private String jobCron;

    /**
     * 任务描述
     */
    private String jobDesc;

    /**
     * 添加时间
     */
    private Date addTime;

    /**
     * 更新时间
     */
    private Date updateTime;

    /**
     * 作者
     */
    private String author;

    /**
     * 报警邮件
     */
    private String alarmEmail;

    /**
     * 执行器路由策略
     */
    private String executorRouteStrategy;

    /**
     * 执行器任务handler
     */
    private String executorHandler;

    /**
     * 执行器任务参数
     */
    private String executorParam;

    /**
     * 阻塞处理策略
     */
    private String executorBlockStrategy;

    /**
     * 任务执行超时时间，单位秒
     */
    private int executorTimeout;

    /**
     * 失败重试次数
     */
    private int executorFailRetryCount;

    /**
     * 调度日志主键
     */
    private long logId;

    /**
     * 调度状态：0-停止，1-运行
     */
    private int triggerStatus;

    /**
     * 上次调度时间
     */
    private long triggerLastTime;

    /**
     * 下次调度时间
     */
    private long triggerNextTime;

    /**
     * 触发类型：CRON / FIX_RATE / FIX_DELAY / MANUAL / API / RETRY / PARENT
     */
    private String triggerType;

    /**
     * FIX_RATE/FIX_DELAY 模式的间隔时间（毫秒）
     */
    private long fixInterval;

    /**
     * 调度过期策略：DO_NOTHING / FIRE_ONCE_NOW
     */
    private String misfireStrategy;

    /**
     * 子任务ID（逗号分隔），父任务执行成功后自动触发
     */
    private String childJobId;

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public int getJobGroup() {
        return jobGroup;
    }

    public void setJobGroup(int jobGroup) {
        this.jobGroup = jobGroup;
    }

    public String getJobCron() {
        return jobCron;
    }

    public void setJobCron(String jobCron) {
        this.jobCron = jobCron;
    }

    public String getJobDesc() {
        return jobDesc;
    }

    public void setJobDesc(String jobDesc) {
        this.jobDesc = jobDesc;
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

    public int getExecutorTimeout() {
        return executorTimeout;
    }

    public void setExecutorTimeout(int executorTimeout) {
        this.executorTimeout = executorTimeout;
    }

    public int getExecutorFailRetryCount() {
        return executorFailRetryCount;
    }

    public void setExecutorFailRetryCount(int executorFailRetryCount) {
        this.executorFailRetryCount = executorFailRetryCount;
    }

    public long getLogId() {
        return logId;
    }

    public void setLogId(long logId) {
        this.logId = logId;
    }

    public int getTriggerStatus() {
        return triggerStatus;
    }

    public void setTriggerStatus(int triggerStatus) {
        this.triggerStatus = triggerStatus;
    }

    public long getTriggerLastTime() {
        return triggerLastTime;
    }

    public void setTriggerLastTime(long triggerLastTime) {
        this.triggerLastTime = triggerLastTime;
    }

    public long getTriggerNextTime() {
        return triggerNextTime;
    }

    public void setTriggerNextTime(long triggerNextTime) {
        this.triggerNextTime = triggerNextTime;
    }

    public String getTriggerType() {
        return triggerType;
    }

    public void setTriggerType(String triggerType) {
        this.triggerType = triggerType;
    }

    public long getFixInterval() {
        return fixInterval;
    }

    public void setFixInterval(long fixInterval) {
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

    @Override
    public String toString() {
        return "JobInfo{" +
                "id=" + id +
                ", jobGroup=" + jobGroup +
                ", jobCron='" + jobCron + '\'' +
                ", jobDesc='" + jobDesc + '\'' +
                ", executorHandler='" + executorHandler + '\'' +
                ", triggerStatus=" + triggerStatus +
                '}';
    }
}
