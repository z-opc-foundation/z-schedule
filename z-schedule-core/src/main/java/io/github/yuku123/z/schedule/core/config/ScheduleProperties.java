package io.github.yuku123.z.schedule.core.config;

/**
 * 调度系统配置属性
 * <p>
 * 对应 Spring 配置前缀: z.schedule
 */
public class ScheduleProperties {

    // ---- Token 认证 ----

    /**
     * 调度中心与执行器通信的 Token（非空时启用验证）
     */
    private String accessToken = "";

    // ---- 线程池配置 ----

    /**
     * 快任务线程池最大线程数
     */
    private int triggerPoolFastMax = 200;

    /**
     * 慢任务线程池最大线程数
     */
    private int triggerPoolSlowMax = 200;

    /**
     * 慢任务判定阈值（ms），超过此耗时的任务将被降级到慢线程池
     */
    private long triggerPoolSlowThreshold = 5000L;

    // ---- 日志清理 ----

    /**
     * 调度日志保留天数（最小 7 天）
     */
    private int logRetentionDays = 30;

    // ---- 超时配置 ----

    /**
     * 任务执行默认超时时间（秒），0=不限制
     */
    private int executorTimeout = 0;

    // ---- 国际化 ----

    /**
     * 调度中心语言: zh_CN / en
     */
    private String i18n = "zh_CN";

    public String getAccessToken() {
        return accessToken;
    }

    public void setAccessToken(String accessToken) {
        this.accessToken = accessToken;
    }

    public int getTriggerPoolFastMax() {
        return triggerPoolFastMax;
    }

    public void setTriggerPoolFastMax(int triggerPoolFastMax) {
        this.triggerPoolFastMax = triggerPoolFastMax;
    }

    public int getTriggerPoolSlowMax() {
        return triggerPoolSlowMax;
    }

    public void setTriggerPoolSlowMax(int triggerPoolSlowMax) {
        this.triggerPoolSlowMax = triggerPoolSlowMax;
    }

    public long getTriggerPoolSlowThreshold() {
        return triggerPoolSlowThreshold;
    }

    public void setTriggerPoolSlowThreshold(long triggerPoolSlowThreshold) {
        this.triggerPoolSlowThreshold = triggerPoolSlowThreshold;
    }

    public int getLogRetentionDays() {
        return logRetentionDays;
    }

    public void setLogRetentionDays(int logRetentionDays) {
        this.logRetentionDays = logRetentionDays;
    }

    public int getExecutorTimeout() {
        return executorTimeout;
    }

    public void setExecutorTimeout(int executorTimeout) {
        this.executorTimeout = executorTimeout;
    }

    public String getI18n() {
        return i18n;
    }

    public void setI18n(String i18n) {
        this.i18n = i18n;
    }
}
