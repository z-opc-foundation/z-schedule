package com.zifang.z.schedule.web.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.zifang.z.meta.api.annotation.BizField;
import com.zifang.z.meta.api.annotation.BizModel;

import java.util.Date;

/**
 * 集群 Leader 选举 DO（持久化层映射 z_schedule_job_leader 表）
 * <p>
 * 单行表, id 固定为 1。通过 UPDATE WHERE owner IS NULL OR expire_time&lt;NOW() 抢占,
 * 续约通过 UPDATE WHERE owner=本实例ID。详见 {@link com.zifang.z.schedule.web.cluster.LeaderElector}.
 */
@BizModel(name = "集群 Leader 选举 DO（持久化层映射 z_schedule_job_leader 表）", module = "z-schedule", description = "集群 Leader 选举 DO（持久化层映射 z_schedule_job_leader 表） 单行表, id 固定为 1。通过 UPDATE WHERE owner IS NULL OR expire_time&lt;NOW() 抢占, 续约通过 UPDATE WHERE owner=本实例ID。详见 com.zifang.z.schedule.web.cluster.LeaderElector.")
@TableName("z_schedule_job_leader")
public class JobLeaderDO {

    public static final int SINGLETON_ID = 1;

    @BizField(name = "固定为1, 单行锁", type = BizField.FieldType.INTEGER)
    private Integer id;
    /**
     * Leader 实例 ID (UUID), NULL=空闲
     */
    @BizField(name = "Leader 实例 ID (UUID)", type = BizField.FieldType.STRING)
    private String owner;
    /**
     * Leader 主机名
     */
    @BizField(name = "Leader 主机名", type = BizField.FieldType.STRING)
    private String host;
    /**
     * 过期时间, NOW() &gt; expire_time 表示 Leader 已失效
     */
    @BizField(name = "Leader 过期时间", type = BizField.FieldType.DATETIME)
    private Date expireTime;

    public Integer getId() {
        return id;
    }

    public void setId(Integer id) {
        this.id = id;
    }

    public String getOwner() {
        return owner;
    }

    public void setOwner(String owner) {
        this.owner = owner;
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public Date getExpireTime() {
        return expireTime;
    }

    public void setExpireTime(Date expireTime) {
        this.expireTime = expireTime;
    }
}



