package com.zifang.z.schedule.core.param;

import java.io.Serializable;

/**
 * 任务终止参数
 */
public class KillParam implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 任务ID
     */
    private int jobId;

    /**
     * 日志ID
     */
    private long logId;

    public int getJobId() {
        return jobId;
    }

    public void setJobId(int jobId) {
        this.jobId = jobId;
    }

    public long getLogId() {
        return logId;
    }

    public void setLogId(long logId) {
        this.logId = logId;
    }

    @Override
    public String toString() {
        return "KillParam{" +
                "jobId=" + jobId +
                ", logId=" + logId +
                '}';
    }
}
