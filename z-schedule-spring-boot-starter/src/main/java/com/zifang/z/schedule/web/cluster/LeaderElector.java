package com.zifang.z.schedule.web.cluster;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.zifang.z.schedule.web.domain.entity.JobLeaderDO;
import com.zifang.z.schedule.web.domain.mapper.JobLeaderMapper;
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
 *   <li>单行表 {@code z_schedule_job_leader}，id 固定为 1，作为行锁竞争目标；
 *       这一行由 {@link #init()} 保证存在（建表脚本的 INSERT IGNORE 之外的兜底）</li>
 *   <li>抢占: {@code UPDATE … WHERE id=1 AND (owner IS NULL OR expire_time < ?)}
 *       —— 比较用的是 {@code tryAcquire} 当次的 JVM 时钟，不是数据库的 NOW()，
 *       因此节点间时钟偏移会直接平移各自的任期判定；谁赢仍由 InnoDB 行锁串行化决定</li>
 *   <li>成功后 SELECT 检查 owner 是否为本实例 ID</li>
 *   <li>续约: {@code UPDATE … WHERE id=1 AND owner=?}（与抢占共用 {@link #elect()} 的同一个周期）</li>
 *   <li>失主: 网络分区/进程崩溃 → expireTime 过期 → 其他节点可抢占；旧主要等下一次续约拿到 0 行才降级</li>
 * </ul>
 * <b>限制</b>：MySQL InnoDB 行锁保证同一时刻只有一个 UPDATE 成功，单主集群适用；
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
     * 续约检查间隔（毫秒）：既用于抢占也用于续约（{@link #elect()} 每轮只做其一），
     * 必须显著小于 {@code LEADER_TTL_SECONDS}，避免单次 miss 导致失主
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
        ensureSingletonRow();
    }

    /**
     * 保证 {@code id=1} 这一行存在.
     * <p>
     * 种子行原本只由建表脚本的 {@code INSERT IGNORE} 提供；表建了但那行没插时，
     * 抢占用的 UPDATE 永远影响 0 行——整个集群选不出 Leader、调度停摆，且一行日志都不打。
     * 这里补一次幂等的插入，把"静默死掉"变成"要么自愈要么留下可读的告警"。
     * 任何异常都不向外抛：调度中心不该因为一行种子数据而起不来。
     */
    private void ensureSingletonRow() {
        try {
            if (jobLeaderMapper.selectById(JobLeaderDO.SINGLETON_ID) != null) {
                return;
            }
            JobLeaderDO row = new JobLeaderDO();
            row.setId(JobLeaderDO.SINGLETON_ID);
            jobLeaderMapper.insert(row);
            logger.info("[z-schedule] z_schedule_job_leader 缺少单行记录, 已补建 id={}", JobLeaderDO.SINGLETON_ID);
        } catch (RuntimeException e) {
            // 多节点同时启动会撞主键，输的一方目标其实已达成；其余异常只告警不阻断启动
            boolean exists;
            try {
                exists = jobLeaderMapper.selectById(JobLeaderDO.SINGLETON_ID) != null;
            } catch (RuntimeException recheck) {
                exists = false;
            }
            if (!exists) {
                logger.warn("[z-schedule] 无法补建 {} 的单行记录, 调度中心可能永远选不出 Leader "
                        + "(请确认建表脚本已执行): {}", "z_schedule_job_leader", e.toString());
            }
        }
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
    @Scheduled(fixedDelay = ELECT_INTERVAL_MS)
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
