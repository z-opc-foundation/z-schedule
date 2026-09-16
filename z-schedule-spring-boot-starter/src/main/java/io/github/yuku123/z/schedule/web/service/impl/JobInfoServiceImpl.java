package io.github.yuku123.z.schedule.web.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import io.github.yuku123.z.schedule.core.model.JobInfo;
import io.github.yuku123.z.schedule.core.model.ReturnT;
import io.github.yuku123.z.schedule.core.util.CronExpression;
import io.github.yuku123.z.schedule.web.domain.entity.JobInfoDO;
import io.github.yuku123.z.schedule.web.domain.mapper.JobInfoMapper;
import io.github.yuku123.z.schedule.web.service.JobInfoService;
import io.github.yuku123.z.schedule.web.service.JobTriggerService;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import org.springframework.context.annotation.Lazy;
import javax.annotation.Resource;
import java.text.ParseException;
import java.util.Date;
import java.util.List;

/**
 * 任务信息服务 — 持久化实现
 * <p>
 * 数据落地 z_schedule_job_info 表，支持重启后任务不丢失。
 * 取代原有的 ConcurrentHashMap 内存版本。
 */
@Service
public class JobInfoServiceImpl implements JobInfoService {

    private static final Logger logger = LogManager.getLogger(JobInfoServiceImpl.class);

    @Resource
    private JobInfoMapper jobInfoMapper;

    @Resource
    @Lazy
    private JobTriggerService jobTriggerService;

    @PostConstruct
    public void init() {
        // H2/demo 模式下 z_schedule_job_info 表可能不存在, 包装 try-catch 不阻塞启动
        try {
            logger.info("JobInfoService (DB-backed) initialized, total jobs={}", jobInfoMapper.selectCount(null));
        } catch (Exception e) {
            logger.warn("JobInfoService init skipped (table not migrated yet): {}", e.getMessage());
        }
    }

    @Override
    public JobInfo getById(int id) {
        return DoMapper.toDTO(jobInfoMapper.selectById(id));
    }

    @Override
    public List<JobInfo> getAll() {
        return DoMapper.toDTOList(jobInfoMapper.selectList(null));
    }

    @Override
    public List<JobInfo> getByJobGroup(int jobGroup) {
        return DoMapper.toDTOList(jobInfoMapper.selectList(
                new LambdaQueryWrapper<JobInfoDO>().eq(JobInfoDO::getJobGroup, jobGroup)));
    }

    @Override
    public ReturnT<String> add(JobInfo jobInfo) {
        if (jobInfo.getJobDesc() == null || jobInfo.getJobDesc().trim().isEmpty()) {
            return ReturnT.fail("任务描述不能为空");
        }
        if (jobInfo.getJobCron() == null || jobInfo.getJobCron().trim().isEmpty()) {
            return ReturnT.fail("Cron表达式不能为空");
        }
        try {
            new CronExpression(jobInfo.getJobCron());
        } catch (ParseException e) {
            return ReturnT.fail("Cron表达式格式错误: " + e.getMessage());
        }

        jobInfo.setTriggerStatus(0);
        jobInfo.setTriggerLastTime(0);
        jobInfo.setTriggerNextTime(0);
        Date now = new Date();
        jobInfo.setAddTime(now);
        jobInfo.setUpdateTime(now);

        JobInfoDO d = DoMapper.toDO(jobInfo);
        jobInfoMapper.insert(d);
        int id = d.getId();

        logger.info("Job added, jobId={}, jobDesc={}", id, jobInfo.getJobDesc());
        return new ReturnT<>(ReturnT.SUCCESS_CODE, "success", String.valueOf(id));
    }

    @Override
    public ReturnT<String> update(JobInfo jobInfo) {
        if (jobInfo.getId() <= 0) {
            return ReturnT.fail("任务ID不能为空");
        }
        JobInfoDO exist = jobInfoMapper.selectById(jobInfo.getId());
        if (exist == null) {
            return ReturnT.fail("任务不存在");
        }
        if (exist.getTriggerStatus() != null && exist.getTriggerStatus() == 1) {
            if (jobInfo.getJobCron() != null && !jobInfo.getJobCron().equals(exist.getJobCron())) {
                return ReturnT.fail("请先停止任务再修改Cron表达式");
            }
        }
        if (jobInfo.getJobCron() != null && !jobInfo.getJobCron().isEmpty()) {
            try {
                new CronExpression(jobInfo.getJobCron());
            } catch (ParseException e) {
                return ReturnT.fail("Cron表达式格式错误: " + e.getMessage());
            }
        }

        // 增量更新非空字段
        if (jobInfo.getJobGroup() > 0) exist.setJobGroup(jobInfo.getJobGroup());
        if (jobInfo.getJobCron() != null) exist.setJobCron(jobInfo.getJobCron());
        if (jobInfo.getJobDesc() != null) exist.setJobDesc(jobInfo.getJobDesc());
        if (jobInfo.getAuthor() != null) exist.setAuthor(jobInfo.getAuthor());
        if (jobInfo.getAlarmEmail() != null) exist.setAlarmEmail(jobInfo.getAlarmEmail());
        if (jobInfo.getExecutorRouteStrategy() != null)
            exist.setExecutorRouteStrategy(jobInfo.getExecutorRouteStrategy());
        if (jobInfo.getExecutorHandler() != null) exist.setExecutorHandler(jobInfo.getExecutorHandler());
        if (jobInfo.getExecutorParam() != null) exist.setExecutorParam(jobInfo.getExecutorParam());
        if (jobInfo.getExecutorBlockStrategy() != null)
            exist.setExecutorBlockStrategy(jobInfo.getExecutorBlockStrategy());
        if (jobInfo.getExecutorTimeout() >= 0) exist.setExecutorTimeout(jobInfo.getExecutorTimeout());
        if (jobInfo.getExecutorFailRetryCount() >= 0)
            exist.setExecutorFailRetryCount(jobInfo.getExecutorFailRetryCount());
        exist.setUpdateTime(new Date());

        int rows = jobInfoMapper.updateById(exist);
        logger.info("Job updated, jobId={}, rows={}", jobInfo.getId(), rows);
        return ReturnT.success();
    }

    @Override
    public ReturnT<String> delete(int id) {
        JobInfoDO exist = jobInfoMapper.selectById(id);
        if (exist == null) {
            return ReturnT.fail("任务不存在");
        }
        if (exist.getTriggerStatus() != null && exist.getTriggerStatus() == 1) {
            return ReturnT.fail("请先停止任务再删除");
        }
        jobInfoMapper.deleteById(id);
        logger.info("Job deleted, jobId={}", id);
        return ReturnT.success();
    }

    @Override
    public ReturnT<String> start(int id) {
        JobInfoDO exist = jobInfoMapper.selectById(id);
        if (exist == null) {
            return ReturnT.fail("任务不存在");
        }
        try {
            new CronExpression(exist.getJobCron());
        } catch (ParseException e) {
            return ReturnT.fail("Cron表达式格式错误: " + e.getMessage());
        }
        exist.setTriggerStatus(1);
        exist.setUpdateTime(new Date());
        jobInfoMapper.updateById(exist);

        if (jobTriggerService != null) {
            jobTriggerService.registerJob(DoMapper.toDTO(exist));
        }
        logger.info("Job started, jobId={}", id);
        return ReturnT.success();
    }

    @Override
    public ReturnT<String> stop(int id) {
        JobInfoDO exist = jobInfoMapper.selectById(id);
        if (exist == null) {
            return ReturnT.fail("任务不存在");
        }
        exist.setTriggerStatus(0);
        exist.setTriggerLastTime(0L);
        exist.setTriggerNextTime(0L);
        exist.setUpdateTime(new Date());
        jobInfoMapper.updateById(exist);

        if (jobTriggerService != null) {
            jobTriggerService.cancelJob(id);
        }
        logger.info("Job stopped, jobId={}", id);
        return ReturnT.success();
    }

    @Override
    public ReturnT<String> trigger(int id) {
        JobInfoDO exist = jobInfoMapper.selectById(id);
        if (exist == null) {
            return ReturnT.fail("任务不存在");
        }
        if (jobTriggerService != null) {
            jobTriggerService.triggerJob(DoMapper.toDTO(exist));
        } else {
            logger.warn("JobTriggerService not available, manual trigger skipped");
        }
        logger.info("Job triggered manually, jobId={}", id);
        return ReturnT.success("任务触发成功", null);
    }

    @Override
    public ReturnT<List<String>> nextTriggerTime(String cron) {
        List<String> result = new java.util.ArrayList<>();
        try {
            CronExpression cronExpression = new CronExpression(cron);
            Date lastTime = new Date();
            for (int i = 0; i < 5; i++) {
                lastTime = cronExpression.getNextValidTimeAfter(lastTime);
                if (lastTime != null) {
                    result.add(com.zifang.util.core.time.DateUtil.format(lastTime, "yyyy-MM-dd HH:mm:ss"));
                } else {
                    break;
                }
            }
        } catch (ParseException e) {
            return ReturnT.fail("Cron表达式格式错误: " + e.getMessage());
        }
        return ReturnT.success(result);
    }

    /**
     * 列出所有 trigger_status=1（运行中）的任务（供调度线程启动时全量恢复）
     */
    public List<JobInfo> listRunning() {
        return DoMapper.toDTOList(jobInfoMapper.selectList(
                new LambdaQueryWrapper<JobInfoDO>().eq(JobInfoDO::getTriggerStatus, 1)));
    }

    /**
     * 原子更新 trigger_last_time / trigger_next_time（Leader 调度线程调用）
     */
    public void updateTriggerTimes(int jobId, long lastTime, long nextTime) {
        jobInfoMapper.update(null,
                new LambdaUpdateWrapper<JobInfoDO>()
                        .eq(JobInfoDO::getId, jobId)
                        .set(JobInfoDO::getTriggerLastTime, lastTime)
                        .set(JobInfoDO::getTriggerNextTime, nextTime));
    }
}
