package com.zifang.z.schedule.web.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.zifang.z.meta.api.annotation.BizField;
import com.zifang.z.meta.api.annotation.BizModel;

import java.util.Date;

/**
 * 执行器心跳注册 DO（持久化层映射 z_schedule_job_registry 表）
 */
@BizModel(name = "执行器心跳注册 DO（持久化层映射 z_schedule_job_registry 表）", module = "z-schedule", description = "执行器心跳注册 DO（持久化层映射 z_schedule_job_registry 表）")
@TableName("z_schedule_job_registry")
public class JobRegistryDO {

    @TableId(type = IdType.AUTO)
    @BizField(name = "注册ID", type = BizField.FieldType.INTEGER)
    private Integer id;

    @BizField(name = "注册分组", type = BizField.FieldType.STRING)
    private String registryGroup;
    /**
     * 执行器AppName
     */
    @BizField(name = "执行器AppName", type = BizField.FieldType.STRING)
    private String registryKey;
    /**
     * 执行器地址
     */
    @BizField(name = "执行器地址", type = BizField.FieldType.STRING)
    private String registryValue;
    /**
     * 心跳时间
     */
    @BizField(name = "心跳时间", type = BizField.FieldType.DATETIME)
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



