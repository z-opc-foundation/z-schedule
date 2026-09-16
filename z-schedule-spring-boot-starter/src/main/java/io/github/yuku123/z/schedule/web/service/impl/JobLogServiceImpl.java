package io.github.yuku123.z.schedule.web.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.github.yuku123.z.schedule.core.model.JobLog;
import io.github.yuku123.z.schedule.web.domain.entity.JobLogDO;
import io.github.yuku123.z.schedule.web.domain.mapper.JobLogMapper;
import io.github.yuku123.z.schedule.web.service.JobLogService;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;

/**
 * 任务日志服务 — 持久化实现
 */
@Service
public class JobLogServiceImpl implements JobLogService {

    @Resource
    private JobLogMapper jobLogMapper;

    @Override
    public long save(JobLog jobLog) {
        JobLogDO d = DoMapper.toDO(jobLog);
        jobLogMapper.insert(d);
        return d.getId() == null ? 0L : d.getId();
    }

    @Override
    public void update(JobLog jobLog) {
        if (jobLog == null || jobLog.getId() <= 0) return;
        JobLogDO d = DoMapper.toDO(jobLog);
        jobLogMapper.updateById(d);
    }

    @Override
    public JobLog getById(long id) {
        return DoMapper.toDTO(jobLogMapper.selectById(id));
    }

    @Override
    public List<JobLog> query(int jobGroup, int jobId, int handleCode, int limit) {
        LambdaQueryWrapper<JobLogDO> wrapper = new LambdaQueryWrapper<>();
        if (jobGroup > 0) wrapper.eq(JobLogDO::getJobGroup, jobGroup);
        if (jobId > 0) wrapper.eq(JobLogDO::getJobId, jobId);
        if (handleCode >= 0) wrapper.eq(JobLogDO::getHandleCode, handleCode);
        wrapper.orderByDesc(JobLogDO::getTriggerTime);
        wrapper.orderByDesc(JobLogDO::getId);
        if (limit > 0) wrapper.last("LIMIT " + limit);
        return DoMapper.toLogDTOList(jobLogMapper.selectList(wrapper));
    }

    @Override
    public int clearByJobId(int jobId) {
        return jobLogMapper.delete(
                new LambdaQueryWrapper<JobLogDO>().eq(JobLogDO::getJobId, jobId));
    }

    @Override
    public int clearAll() {
        return jobLogMapper.delete(null);
    }
}
