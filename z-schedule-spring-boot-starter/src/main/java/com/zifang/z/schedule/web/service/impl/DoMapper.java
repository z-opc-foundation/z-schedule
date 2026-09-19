package com.zifang.z.schedule.web.service.impl;

import com.zifang.z.schedule.core.model.JobGroup;
import com.zifang.z.schedule.core.model.JobInfo;
import com.zifang.z.schedule.core.model.JobLog;
import com.zifang.z.schedule.core.model.User;
import com.zifang.z.schedule.web.domain.entity.JobGroupDO;
import com.zifang.z.schedule.web.domain.entity.JobInfoDO;
import com.zifang.z.schedule.web.domain.entity.JobLogDO;
import com.zifang.z.schedule.web.domain.entity.UserDO;

import java.util.ArrayList;
import java.util.List;

/**
 * DO ↔ DTO 转换工具（持久化层与 core DTO 模型解耦）
 */
final class DoMapper {

    private DoMapper() {
    }

    // ---- JobInfo ----
    static JobInfoDO toDO(JobInfo src) {
        if (src == null) {
            return null;
        }
        JobInfoDO d = new JobInfoDO();
        if (src.getId() > 0) {
            d.setId(src.getId());
        }
        d.setJobGroup(src.getJobGroup());
        d.setJobDesc(src.getJobDesc());
        d.setJobCron(src.getJobCron());
        d.setAuthor(src.getAuthor());
        d.setAlarmEmail(src.getAlarmEmail());
        d.setExecutorRouteStrategy(src.getExecutorRouteStrategy());
        d.setExecutorHandler(src.getExecutorHandler());
        d.setExecutorParam(src.getExecutorParam());
        d.setExecutorBlockStrategy(src.getExecutorBlockStrategy());
        d.setExecutorTimeout(src.getExecutorTimeout());
        d.setExecutorFailRetryCount(src.getExecutorFailRetryCount());
        d.setTriggerStatus(src.getTriggerStatus());
        d.setTriggerLastTime(src.getTriggerLastTime());
        d.setTriggerNextTime(src.getTriggerNextTime());
        d.setTriggerType(src.getTriggerType());
        d.setFixInterval(src.getFixInterval());
        d.setMisfireStrategy(src.getMisfireStrategy());
        d.setChildJobId(src.getChildJobId());
        d.setAddTime(src.getAddTime());
        d.setUpdateTime(src.getUpdateTime());
        return d;
    }

    static JobInfo toDTO(JobInfoDO d) {
        if (d == null) {
            return null;
        }
        JobInfo j = new JobInfo();
        if (d.getId() != null) {
            j.setId(d.getId());
        }
        j.setJobGroup(d.getJobGroup() == null ? 0 : d.getJobGroup());
        j.setJobDesc(d.getJobDesc());
        j.setJobCron(d.getJobCron());
        j.setAuthor(d.getAuthor());
        j.setAlarmEmail(d.getAlarmEmail());
        j.setExecutorRouteStrategy(d.getExecutorRouteStrategy());
        j.setExecutorHandler(d.getExecutorHandler());
        j.setExecutorParam(d.getExecutorParam());
        j.setExecutorBlockStrategy(d.getExecutorBlockStrategy());
        j.setExecutorTimeout(d.getExecutorTimeout() == null ? 0 : d.getExecutorTimeout());
        j.setExecutorFailRetryCount(d.getExecutorFailRetryCount() == null ? 0 : d.getExecutorFailRetryCount());
        j.setTriggerStatus(d.getTriggerStatus() == null ? 0 : d.getTriggerStatus());
        j.setTriggerLastTime(d.getTriggerLastTime() == null ? 0L : d.getTriggerLastTime());
        j.setTriggerNextTime(d.getTriggerNextTime() == null ? 0L : d.getTriggerNextTime());
        j.setTriggerType(d.getTriggerType());
        j.setFixInterval(d.getFixInterval() == null ? 0L : d.getFixInterval());
        j.setMisfireStrategy(d.getMisfireStrategy());
        j.setChildJobId(d.getChildJobId());
        j.setAddTime(d.getAddTime());
        j.setUpdateTime(d.getUpdateTime());
        return j;
    }

    static List<JobInfo> toDTOList(List<JobInfoDO> list) {
        List<JobInfo> out = new ArrayList<>(list.size());
        for (JobInfoDO d : list) {
            out.add(toDTO(d));
        }
        return out;
    }

    // ---- JobLog ----
    static JobLogDO toDO(JobLog src) {
        if (src == null) {
            return null;
        }
        JobLogDO d = new JobLogDO();
        if (src.getId() > 0) {
            d.setId(src.getId());
        }
        d.setJobGroup(src.getJobGroup());
        d.setJobId(src.getJobId());
        d.setExecutorAddress(src.getExecutorAddress());
        d.setExecutorHandler(src.getExecutorHandler());
        d.setExecutorParam(src.getExecutorParam());
        d.setExecutorShardingParam(src.getExecutorShardingParam());
        d.setExecutorFailRetryCount(src.getExecutorFailRetryCount());
        d.setTriggerTime(src.getTriggerTime());
        d.setTriggerCode(src.getTriggerCode());
        d.setTriggerMsg(src.getTriggerMsg());
        d.setHandleTime(src.getHandleTime());
        d.setHandleCode(src.getHandleCode());
        d.setHandleMsg(src.getHandleMsg());
        d.setAlarmStatus(src.getAlarmStatus());
        return d;
    }

    static JobLog toDTO(JobLogDO d) {
        if (d == null) {
            return null;
        }
        JobLog l = new JobLog();
        l.setId(d.getId() == null ? 0L : d.getId());
        l.setJobGroup(d.getJobGroup() == null ? 0 : d.getJobGroup());
        l.setJobId(d.getJobId() == null ? 0 : d.getJobId());
        l.setExecutorAddress(d.getExecutorAddress());
        l.setExecutorHandler(d.getExecutorHandler());
        l.setExecutorParam(d.getExecutorParam());
        l.setExecutorShardingParam(d.getExecutorShardingParam());
        l.setExecutorFailRetryCount(d.getExecutorFailRetryCount() == null ? 0 : d.getExecutorFailRetryCount());
        l.setTriggerTime(d.getTriggerTime());
        l.setTriggerCode(d.getTriggerCode() == null ? 0 : d.getTriggerCode());
        l.setTriggerMsg(d.getTriggerMsg());
        l.setHandleTime(d.getHandleTime());
        l.setHandleCode(d.getHandleCode() == null ? 0 : d.getHandleCode());
        l.setHandleMsg(d.getHandleMsg());
        l.setAlarmStatus(d.getAlarmStatus() == null ? 0 : d.getAlarmStatus());
        return l;
    }

    static List<JobLog> toLogDTOList(List<JobLogDO> list) {
        List<JobLog> out = new ArrayList<>(list.size());
        for (JobLogDO d : list) {
            out.add(toDTO(d));
        }
        return out;
    }

    // ---- JobGroup ----
    static JobGroupDO toDO(JobGroup src) {
        if (src == null) {
            return null;
        }
        JobGroupDO d = new JobGroupDO();
        if (src.getId() > 0) {
            d.setId(src.getId());
        }
        d.setAppName(src.getAppName());
        d.setTitle(src.getTitle());
        d.setOrderNum(src.getOrder());
        d.setAddressType(src.getAddressType());
        d.setAddressList(src.getAddressList());
        d.setUpdateTime(src.getUpdateTime());
        return d;
    }

    static JobGroup toDTO(JobGroupDO d) {
        if (d == null) {
            return null;
        }
        JobGroup g = new JobGroup();
        g.setId(d.getId() == null ? 0 : d.getId());
        g.setAppName(d.getAppName());
        g.setTitle(d.getTitle());
        g.setOrder(d.getOrderNum() == null ? 0 : d.getOrderNum());
        g.setAddressType(d.getAddressType() == null ? 0 : d.getAddressType());
        g.setAddressList(d.getAddressList());
        g.setUpdateTime(d.getUpdateTime());
        return g;
    }

    static List<JobGroup> toGroupDTOList(List<JobGroupDO> list) {
        List<JobGroup> out = new ArrayList<>(list.size());
        for (JobGroupDO d : list) {
            out.add(toDTO(d));
        }
        return out;
    }

    // ---- User ----
    static UserDO toDO(User src) {
        if (src == null) {
            return null;
        }
        UserDO d = new UserDO();
        if (src.getId() > 0) {
            d.setId(src.getId());
        }
        d.setUsername(src.getUsername());
        d.setPassword(src.getPassword());
        d.setRole(src.getRole());
        d.setPermission(src.getPermission());
        d.setAddTime(src.getAddTime());
        d.setUpdateTime(src.getUpdateTime());
        return d;
    }

    static User toDTO(UserDO d) {
        if (d == null) {
            return null;
        }
        User u = new User();
        if (d.getId() != null) {
            u.setId(d.getId());
        }
        u.setUsername(d.getUsername());
        u.setPassword(d.getPassword());
        u.setRole(d.getRole());
        u.setPermission(d.getPermission());
        u.setAddTime(d.getAddTime());
        u.setUpdateTime(d.getUpdateTime());
        return u;
    }

    static List<User> toUserDTOList(List<UserDO> list) {
        List<User> out = new ArrayList<>(list.size());
        for (UserDO d : list) {
            out.add(toDTO(d));
        }
        return out;
    }
}
