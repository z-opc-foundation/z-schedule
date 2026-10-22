package com.zifang.z.schedule.core.config;

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

    // ---- 暴露面 ----

    /**
     * 是否暴露<b>根级裸路径</b>的管理面（{@code /jobinfo}、{@code /jobgroup}、{@code /joblog}、
     * {@code /glue}、{@code /user}）。
     *
     * <p><b>默认 false 是安全默认</b>。原因：这些 controller 的类注释写着「鉴权: 由调度管理端统一拦截」，
     * 而这个前提只在<b>独立部署</b>的 z-schedule-admin 里成立——它自己带登录口与前端，
     * 裸路径就是它的正式 API 形状。starter 被嵌进聚合宿主（例如 z-opc 的单 JVM 聚合）时，
     * 那句前提没有任何承接方：宿主的登录闸门按路径前缀匹配（z-opc 侧是斜杠 + 双星号 + api + 双星号那种形态），
     * 根级裸路径一条都不匹配 ⇒ 匿名直达业务层，含写端点。
     *
     * <p>所以嵌入式用法应当：<b>关掉裸路径（保持默认），改走 {@code /api/schedule/**} 别名</b>，
     * 那条路径落在宿主的登录闸门内。独立部署的 z-schedule-admin 需要裸路径，
     * 在自己的 application.yml 里显式置 {@code z.schedule.expose-bare-paths=true}。
     *
     * <p>本开关<b>只影响管理面</b>：{@code /executor/**}（执行器 M2M 回调，没有用户会话）
     * 与 {@code /dashboard*}（根级页面路径）不受它影响——关掉它们会打断执行器注册链路。
     */
    private boolean exposeBarePaths = false;

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

    public boolean isExposeBarePaths() {
        return exposeBarePaths;
    }

    public void setExposeBarePaths(boolean exposeBarePaths) {
        this.exposeBarePaths = exposeBarePaths;
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
