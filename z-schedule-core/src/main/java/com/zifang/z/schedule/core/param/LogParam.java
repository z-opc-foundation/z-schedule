package com.zifang.z.schedule.core.param;

import java.io.Serializable;

/**
 * 日志查询参数
 */
public class LogParam implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 日志ID
     */
    private long logId;

    /**
     * 日志时间戳（用于定位日志日期）
     */
    private long logDateTim;

    /**
     * 读取起始行号
     */
    private int fromLineNum;

    public long getLogId() {
        return logId;
    }

    public void setLogId(long logId) {
        this.logId = logId;
    }

    public long getLogDateTim() {
        return logDateTim;
    }

    public void setLogDateTim(long logDateTim) {
        this.logDateTim = logDateTim;
    }

    public int getFromLineNum() {
        return fromLineNum;
    }

    public void setFromLineNum(int fromLineNum) {
        this.fromLineNum = fromLineNum;
    }

    @Override
    public String toString() {
        return "LogParam{" +
                "logId=" + logId +
                ", logDateTim=" + logDateTim +
                ", fromLineNum=" + fromLineNum +
                '}';
    }
}
