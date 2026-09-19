package com.zifang.z.schedule.web.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.util.Date;

/**
 * 用户 DO（持久化层映射 z_schedule_user 表）
 * <p>
 * 区别于 core/model/User（API DTO）——本类只用于 ORM 映射，
 * service 层负责 DTO ↔ DO 转换。
 */
@TableName("z_schedule_user")
public class UserDO {

    @TableId(type = IdType.AUTO)
    private Integer id;

    private String username;

    private String password;

    /**
     * 角色，默认 NORMAL
     */
    private String role;

    private String permission;

    private Date addTime;

    private Date updateTime;

    public Integer getId() {
        return id;
    }

    public void setId(Integer id) {
        this.id = id;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public String getPermission() {
        return permission;
    }

    public void setPermission(String permission) {
        this.permission = permission;
    }

    public Date getAddTime() {
        return addTime;
    }

    public void setAddTime(Date addTime) {
        this.addTime = addTime;
    }

    public Date getUpdateTime() {
        return updateTime;
    }

    public void setUpdateTime(Date updateTime) {
        this.updateTime = updateTime;
    }
}
