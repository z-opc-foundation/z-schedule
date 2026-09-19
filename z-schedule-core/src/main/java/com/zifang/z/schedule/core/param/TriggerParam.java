package com.zifang.z.schedule.core.param;

import java.io.Serializable;

/**
 * 任务触发参数
 */
public class TriggerParam implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 任务ID
     */
    private int jobId;

    /**
     * 执行器处理器名称
     */
    private String executorHandler;

    /**
     * 执行器参数
     */
    private String executorParams;

    /**
     * 阻塞处理策略
     */
    private String executorBlockStrategy;

    /**
     * 任务超时时间（秒）
     */
    private int executorTimeout;

    /**
     * 失败重试次数
     */
    private int executorFailRetryCount;

    /**
     * 日志ID
     */
    private long logId;

    /**
     * 调度时间戳
     */
    private long logDateTime;

    /**
     * GLUE类型
     */
    private String glueType;

    /**
     * GLUE源代码
     */
    private String glueSource;

    /**
     * 分片广播索引
     */
    private int broadcastIndex;

    /**
     * 分片广播总数
     */
    private int broadcastTotal;

    public int getJobId() {
        return jobId;
    }

    public void setJobId(int jobId) {
        this.jobId = jobId;
    }

    public String getExecutorHandler() {
        return executorHandler;
    }

    public void setExecutorHandler(String executorHandler) {
        this.executorHandler = executorHandler;
    }

    public String getExecutorParams() {
        return executorParams;
    }

    public void setExecutorParams(String executorParams) {
        this.executorParams = executorParams;
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

    public long getLogDateTime() {
        return logDateTime;
    }

    public void setLogDateTime(long logDateTime) {
        this.logDateTime = logDateTime;
    }

    public String getGlueType() {
        return glueType;
    }

    public void setGlueType(String glueType) {
        this.glueType = glueType;
    }

    public String getGlueSource() {
        return glueSource;
    }

    public void setGlueSource(String glueSource) {
        this.glueSource = glueSource;
    }

    public int getBroadcastIndex() {
        return broadcastIndex;
    }

    public void setBroadcastIndex(int broadcastIndex) {
        this.broadcastIndex = broadcastIndex;
    }

    public int getBroadcastTotal() {
        return broadcastTotal;
    }

    public void setBroadcastTotal(int broadcastTotal) {
        this.broadcastTotal = broadcastTotal;
    }

    @Override
    public String toString() {
        return "TriggerParam{" +
                "jobId=" + jobId +
                ", executorHandler='" + executorHandler + '\'' +
                ", executorParams='" + executorParams + '\'' +
                ", executorBlockStrategy='" + executorBlockStrategy + '\'' +
                ", executorTimeout=" + executorTimeout +
                ", executorFailRetryCount=" + executorFailRetryCount +
                ", logId=" + logId +
                ", logDateTime=" + logDateTime +
                ", glueType='" + glueType + '\'' +
                ", broadcastIndex=" + broadcastIndex +
                ", broadcastTotal=" + broadcastTotal +
                '}';
    }
}
