package com.zifang.z.schedule.web.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.zifang.z.schedule.core.enums.MisfireStrategyEnum;
import com.zifang.z.schedule.core.enums.TriggerTypeEnum;
import com.zifang.z.schedule.core.model.JobInfo;
import com.zifang.z.schedule.core.model.ReturnT;
import com.zifang.z.schedule.core.util.CronExpression;
import com.zifang.z.schedule.web.domain.entity.JobInfoDO;
import com.zifang.z.schedule.web.domain.mapper.JobInfoMapper;
import com.zifang.z.schedule.web.service.JobInfoService;
import com.zifang.z.schedule.web.service.JobTriggerService;
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
        if (jobInfo.getTriggerType() == null || jobInfo.getTriggerType().isEmpty()) {
            jobInfo.setTriggerType(TriggerTypeEnum.CRON.getCode());
        } else if (TriggerTypeEnum.match(jobInfo.getTriggerType()) == null) {
            return ReturnT.fail("触发类型不合法: " + jobInfo.getTriggerType()
                    + ", 可选 " + codesOf(TriggerTypeEnum.values()));
        }
        if (jobInfo.getMisfireStrategy() == null || jobInfo.getMisfireStrategy().isEmpty()) {
            jobInfo.setMisfireStrategy(MisfireStrategyEnum.DO_NOTHING.getCode());
        } else if (MisfireStrategyEnum.match(jobInfo.getMisfireStrategy()) == null) {
            return ReturnT.fail("调度过期策略不合法: " + jobInfo.getMisfireStrategy()
                    + ", 可选 " + codesOf(MisfireStrategyEnum.values()));
        }
        ReturnT<String> intervalCheck = checkFixedInterval(jobInfo.getTriggerType(), jobInfo.getFixInterval());
        if (intervalCheck != null) {
            return intervalCheck;
        }

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
        // 先判空再判"要不要改运行中的 cron"：空串是格式问题，跟任务在不在跑无关
        if (jobInfo.getJobCron() != null && jobInfo.getJobCron().trim().isEmpty()) {
            return ReturnT.fail("Cron表达式不能为空");
        }
        if (exist.getTriggerStatus() != null && exist.getTriggerStatus() == 1) {
            if (jobInfo.getJobCron() != null && !jobInfo.getJobCron().equals(exist.getJobCron())) {
                return ReturnT.fail("请先停止任务再修改Cron表达式");
            }
        }
        if (jobInfo.getJobCron() != null) {
            try {
                new CronExpression(jobInfo.getJobCron());
            } catch (ParseException e) {
                return ReturnT.fail("Cron表达式格式错误: " + e.getMessage());
            }
        }
        if (jobInfo.getTriggerType() != null && !jobInfo.getTriggerType().isEmpty()
                && TriggerTypeEnum.match(jobInfo.getTriggerType()) == null) {
            // 引擎按 code 精确匹配 FIX_RATE/FIX_DELAY，大小写写错会静默退化成 cron 任务
            return ReturnT.fail("触发类型不合法: " + jobInfo.getTriggerType()
                    + ", 可选 " + codesOf(TriggerTypeEnum.values()));
        }
        if (jobInfo.getMisfireStrategy() != null && !jobInfo.getMisfireStrategy().isEmpty()
                && MisfireStrategyEnum.match(jobInfo.getMisfireStrategy()) == null) {
            return ReturnT.fail("调度过期策略不合法: " + jobInfo.getMisfireStrategy()
                    + ", 可选 " + codesOf(MisfireStrategyEnum.values()));
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
        if (jobInfo.getTriggerType() != null && !jobInfo.getTriggerType().isEmpty())
            exist.setTriggerType(jobInfo.getTriggerType());
        // DTO 的 fixInterval 是 primitive long：不带这个字段时反序列化成 0，而 0 对 FIX_* 任务是非法值，
        // 所以只认正数为"调用方真的给了间隔"，其余保持列值不变。
        if (jobInfo.getFixInterval() > 0) exist.setFixInterval(jobInfo.getFixInterval());
        if (jobInfo.getMisfireStrategy() != null && !jobInfo.getMisfireStrategy().isEmpty())
            exist.setMisfireStrategy(jobInfo.getMisfireStrategy());
        if (jobInfo.getChildJobId() != null)
            exist.setChildJobId(jobInfo.getChildJobId());

        // 合并后的行必须自己成立：把 CRON 任务改成 FIX_* 却不带间隔，等于交出一个永不触发的任务
        long mergedInterval = exist.getFixInterval() == null ? 0L : exist.getFixInterval();
        ReturnT<String> intervalCheck = checkFixedInterval(effectiveTriggerType(exist.getTriggerType()),
                mergedInterval);
        if (intervalCheck != null) {
            return intervalCheck;
        }

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
        // 按引擎真正会走的分支校验：FIX_* 读 fix_interval，其它类型读 cron。
        // 校验不过就改状态，只会得到"启动成功但永远不触发"的假象。
        String triggerType = effectiveTriggerType(exist.getTriggerType());
        if (isFixedIntervalType(triggerType)) {
            ReturnT<String> intervalCheck = checkFixedInterval(triggerType,
                    exist.getFixInterval() == null ? 0L : exist.getFixInterval());
            if (intervalCheck != null) {
                return intervalCheck;
            }
        } else {
            if (exist.getJobCron() == null || exist.getJobCron().trim().isEmpty()) {
                return ReturnT.fail("Cron表达式不能为空");
            }
            try {
                new CronExpression(exist.getJobCron());
            } catch (ParseException e) {
                return ReturnT.fail("Cron表达式格式错误: " + e.getMessage());
            }
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

    /** 把可选值拼进报错消息。两个枚举的常量名与 code 一致，取 name() 即可。 */
    private static String codesOf(Enum<?>... values) {
        StringBuilder sb = new StringBuilder();
        for (Enum<?> value : values) {
            if (sb.length() > 0) {
                sb.append(" / ");
            }
            sb.append(value.name());
        }
        return sb.toString();
    }

    /**
     * FIX_RATE / FIX_DELAY 任务的间隔必须有正数意义，否则引擎 {@code toScheduledJob} 直接返回 null：
     * 任务不落时间轮，但 {@code trigger_status} 仍是 1——调用方看到"启动成功"，任务却永远不跑。
     *
     * @return 需要拒绝时返回失败结果，合法时返回 null
     */
    private static ReturnT<String> checkFixedInterval(String triggerType, long fixInterval) {
        if (isFixedIntervalType(triggerType) && fixInterval <= 0) {
            return ReturnT.fail(triggerType + " 任务必须配置正数间隔(fix_interval, 毫秒)");
        }
        return null;
    }

    private static boolean isFixedIntervalType(String triggerType) {
        return TriggerTypeEnum.FIX_RATE.getCode().equals(triggerType)
                || TriggerTypeEnum.FIX_DELAY.getCode().equals(triggerType);
    }

    /** 与 {@code JobScheduleEngine.triggerTypeOf} 同规则：空值按 CRON 处理。 */
    private static String effectiveTriggerType(String triggerType) {
        return triggerType == null || triggerType.isEmpty() ? TriggerTypeEnum.CRON.getCode() : triggerType;
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
