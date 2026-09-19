package com.zifang.z.schedule.core.model;

import java.io.Serializable;
import java.util.Date;

/**
 * 执行器分组实体类
 */
public class JobGroup implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 主键ID
     */
    private int id;

    /**
     * 执行器AppName
     */
    private String appName;

    /**
     * 执行器名称
     */
    private String title;

    /**
     * 排序
     */
    private int order;

    /**
     * 执行器地址类型：0=自动注册、1=手动录入
     */
    private int addressType;

    /**
     * 执行器地址列表，多地址逗号分隔
     */
    private String addressList;

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

    public String getAppName() {
        return appName;
    }

    public void setAppName(String appName) {
        this.appName = appName;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public int getOrder() {
        return order;
    }

    public void setOrder(int order) {
        this.order = order;
    }

    public int getAddressType() {
        return addressType;
    }

    public void setAddressType(int addressType) {
        this.addressType = addressType;
    }

    public String getAddressList() {
        return addressList;
    }

    public void setAddressList(String addressList) {
        this.addressList = addressList;
    }

    public Date getUpdateTime() {
        return updateTime;
    }

    public void setUpdateTime(Date updateTime) {
        this.updateTime = updateTime;
    }

    @Override
    public String toString() {
        return "JobGroup{" +
                "id=" + id +
                ", appName='" + appName + '\'' +
                ", title='" + title + '\'' +
                ", addressType=" + addressType +
                ", addressList='" + addressList + '\'' +
                '}';
    }
}
