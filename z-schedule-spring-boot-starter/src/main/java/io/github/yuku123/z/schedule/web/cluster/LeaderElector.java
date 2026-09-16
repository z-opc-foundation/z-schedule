package io.github.yuku123.z.schedule.web.cluster;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import io.github.yuku123.z.schedule.web.domain.entity.JobLeaderDO;
import io.github.yuku123.z.schedule.web.domain.mapper.JobLeaderMapper;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.net.InetAddress;
import java.util.Date;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 基于 DB 行锁的集群 Leader 选举器
 * <p>
 * <b>算法</b>（经典 XXL-JOB 风格）：
 * <ul>
 *   <li>单行表 {@code z_schedule_job_leader}，id 固定为 1，作为行锁竞争目标</li>
 *   <li>抢占: {@code UPDATE … WHERE id=1 AND (owner IS NULL OR expire_time < NOW())}</li>
 *   <li>成功后 SELECT 检查 owner 是否为本实例 ID</li>
 *   <li>续约: {@code UPDATE … WHERE id=1 AND owner=?}（每 10 秒）</li>
 *   <li>失主: 网络分区/进程崩溃 → expireTime 过期 → 其他节点可抢占</li>
 * </ul>
 * <b>限制</b>：MySQL InnoDB 行锁 + NOW() 时间语义，单主集群适用；
 * 不适用于多写主集群或网络分区脑裂场景（需配合至少 3 节点 + Quorum）。
 */
@Component
public class LeaderElector {

    private static final Logger logger = LogManager.getLogger(LeaderElector.class);

    /**
     * Leader 任期长度（秒）：续约成功时设置 expire_time = NOW() + LEADER_TTL_SECONDS
     */
    private static final int LEADER_TTL_SECONDS = 30;
    /**
     * 续约检查间隔（毫秒）：小于 LEADER_TTL_SECONDS/2，避免单次 miss 导致失主
     */
    private static final long RENEW_INTERVAL_MS = 10_000L;
    /**
     * 选举尝试间隔（毫秒）：非 Leader 节点每隔此时间尝试抢占
     */
    private static final long ELECT_INTERVAL_MS = 5_000L;
    /**
     * 本实例 ID（启动时随机生成，用于 Leader 标识）
     */
    private final String instanceId = UUID.randomUUID().toString().replace("-", "");
    private final String host;
    private final AtomicBoolean isLeader = new AtomicBoolean(false);
    @Resource
    private JobLeaderMapper jobLeaderMapper;

    public LeaderElector() {
        String h = "unknown";
        try {
            h = InetAddress.getLocalHost().getHostName();
        } catch (Exception ignored) {
        }
        this.host = h;
    }

    @PostConstruct
    public void init() {
        logger.info("[z-schedule] LeaderElector init: instanceId={}, host={}", instanceId, host);
    }

    public String getInstanceId() {
        return instanceId;
    }

    public boolean isLeader() {
        return isLeader.get();
    }

    /**
     * 周期续约 + 尝试抢占
     */
    @Scheduled(fixedDelay = 5_000L)
    public void elect() {
        if (isLeader.get()) {
            renew();
        } else {
            tryAcquire();
        }
    }

    /**
     * 续约当前 Leader 身份（owner=本实例）
     */
    private void renew() {
        Date newExpire = new Date(System.currentTimeMillis() + LEADER_TTL_SECONDS * 1000L);
        int rows = jobLeaderMapper.update(null,
                new LambdaUpdateWrapper<JobLeaderDO>()
                        .eq(JobLeaderDO::getId, JobLeaderDO.SINGLETON_ID)
                        .eq(JobLeaderDO::getOwner, instanceId)
                        .set(JobLeaderDO::getExpireTime, newExpire));
        if (rows == 0) {
            logger.warn("[z-schedule] Leader renewal failed, demoting to follower");
            isLeader.set(false);
        }
    }

    /**
     * 尝试抢占 Leader（仅当当前无主或前任 Leader 已过期）
     */
    private void tryAcquire() {
        Date now = new Date();
        Date newExpire = new Date(now.getTime() + LEADER_TTL_SECONDS * 1000L);
        // 关键: WHERE id=1 AND (owner IS NULL OR expire_time < NOW())
        // MySQL InnoDB 行锁原子性保证集群中只有一个 UPDATE 成功
        int rows = jobLeaderMapper.update(null,
                new LambdaUpdateWrapper<JobLeaderDO>()
                        .eq(JobLeaderDO::getId, JobLeaderDO.SINGLETON_ID)
                        .and(w -> w.isNull(JobLeaderDO::getOwner).or().lt(JobLeaderDO::getExpireTime, now))
                        .set(JobLeaderDO::getOwner, instanceId)
                        .set(JobLeaderDO::getHost, host)
                        .set(JobLeaderDO::getExpireTime, newExpire));
        if (rows > 0) {
            isLeader.set(true);
            logger.info("[z-schedule] Became LEADER: instanceId={}, host={}", instanceId, host);
        }
    }

    /**
     * 主动放弃 Leader（应用关闭时）
     */
    public void stepDown() {
        if (isLeader.compareAndSet(true, false)) {
            jobLeaderMapper.update(null,
                    new LambdaUpdateWrapper<JobLeaderDO>()
                            .eq(JobLeaderDO::getId, JobLeaderDO.SINGLETON_ID)
                            .eq(JobLeaderDO::getOwner, instanceId)
                            .set(JobLeaderDO::getOwner, null)
                            .set(JobLeaderDO::getHost, null)
                            .set(JobLeaderDO::getExpireTime, null));
            logger.info("[z-schedule] Stepped down from LEADER: instanceId={}", instanceId);
        }
    }

    /**
     * 当前 Leader 信息（用于 Dashboard）
     */
    public JobLeaderDO currentLeader() {
        return jobLeaderMapper.selectById(JobLeaderDO.SINGLETON_ID);
    }
}
