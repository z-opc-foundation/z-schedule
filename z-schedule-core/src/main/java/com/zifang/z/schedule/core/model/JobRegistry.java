package com.zifang.z.schedule.core.model;

import java.io.Serializable;
import java.util.Date;

/**
 * 执行器注册表实体类
 */
public class JobRegistry implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 主键ID
     */
    private int id;

    /**
     * 注册分组
     */
    private String registryGroup;

    /**
     * 注册Key（执行器AppName）
     */
    private String registryKey;

    /**
     * 注册Value（执行器地址）
     */
    private String registryValue;

    /**
     * 更新时间
     */
    private Date updateTime;

    public int getId() {
        return id;
    }

    public void setId(int id) {
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

    @Override
    public String toString() {
        return "JobRegistry{" +
                "id=" + id +
                ", registryGroup='" + registryGroup + '\'' +
                ", registryKey='" + registryKey + '\'' +
                ", registryValue='" + registryValue + '\'' +
                ", updateTime=" + updateTime +
                '}';
    }
}
