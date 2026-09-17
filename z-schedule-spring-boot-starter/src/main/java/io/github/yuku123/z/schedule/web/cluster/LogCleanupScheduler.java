package io.github.yuku123.z.schedule.web.cluster;

import io.github.yuku123.z.schedule.core.config.ScheduleProperties;
import io.github.yuku123.z.schedule.web.service.JobLogService;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

/**
 * 调度日志自动清理器
 * <p>
 * 每天凌晨 2 点自动清理超过保留天数的调度日志。
 * 保留天数通过 {@code z.schedule.logRetentionDays} 配置（最小 7 天）。
 */
@Component
public class LogCleanupScheduler {

    private static final Logger logger = LogManager.getLogger(LogCleanupScheduler.class);

    @Resource
    private JobLogService jobLogService;

    @Resource
    private ScheduleProperties scheduleProperties;

    /**
     * 每天凌晨 2 点执行日志清理。
     */
    @Scheduled(cron = "0 0 2 * * ?")
    public void cleanup() {
        int retentionDays = scheduleProperties.getLogRetentionDays();
        if (retentionDays < 7) {
            retentionDays = 7;
        }
        try {
            int deleted = jobLogService.clearLogByDays(retentionDays);
            if (deleted > 0) {
                logger.info("[z-schedule] Log cleanup completed, deleted {} logs (retentionDays={})",
                        deleted, retentionDays);
            }
        } catch (Exception e) {
            logger.error("[z-schedule] Log cleanup failed", e);
        }
    }
}
