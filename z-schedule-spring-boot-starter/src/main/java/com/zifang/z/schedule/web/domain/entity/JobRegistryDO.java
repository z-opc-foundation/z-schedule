package com.zifang.z.schedule.web.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.util.Date;

/**
 * 执行器心跳注册 DO（持久化层映射 z_schedule_job_registry 表）
 */
@TableName("z_schedule_job_registry")
public class JobRegistryDO {

    @TableId(type = IdType.AUTO)
    private Integer id;

    private String registryGroup;
    /**
     * 执行器AppName
     */
    private String registryKey;
    /**
     * 执行器地址
     */
    private String registryValue;
    /**
     * 心跳时间
     */
    private Date updateTime;

    public Integer getId() {
        return id;
    }

    public void setId(Integer id) {
        this.id = id;
    }

    public String getRegistryGroup() {
        return registryGroup;
    }

    public void setRegistryGroup(String registryGroup) {
        this.registryGroup = registryGroup;
    }

    public String getRegistryKey() {
        return registryKey;
    }

    public void setRegistryKey(String registryKey) {
        this.registryKey = registryKey;
    }

    public String getRegistryValue() {
        return registryValue;
    }

    public void setRegistryValue(String registryValue) {
        this.registryValue = registryValue;
    }

    public Date getUpdateTime() {
        return updateTime;
    }

    public void setUpdateTime(Date updateTime) {
        this.updateTime = updateTime;
    }
}
