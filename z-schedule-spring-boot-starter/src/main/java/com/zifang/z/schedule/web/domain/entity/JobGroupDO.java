package com.zifang.z.schedule.web.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.util.Date;

/**
 * 执行器分组 DO（持久化层映射 z_schedule_job_group 表）
 */
@TableName("z_schedule_job_group")
public class JobGroupDO {

    @TableId(type = IdType.AUTO)
    private Integer id;

    private String appName;
    private String title;
    private Integer orderNum;
    /**
     * 0-自动注册 1-手动录入
     */
    private Integer addressType;
    private String addressList;
    private Date updateTime;

    public Integer getId() {
        return id;
    }

    public void setId(Integer id) {
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

    public Integer getOrderNum() {
        return orderNum;
    }

    public void setOrderNum(Integer orderNum) {
        this.orderNum = orderNum;
    }

    public Integer getAddressType() {
        return addressType;
    }

    public void setAddressType(Integer addressType) {
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
}
