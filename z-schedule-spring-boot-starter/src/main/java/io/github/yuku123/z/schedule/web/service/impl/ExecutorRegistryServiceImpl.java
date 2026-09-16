package io.github.yuku123.z.schedule.web.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.github.yuku123.z.schedule.core.model.ReturnT;
import io.github.yuku123.z.schedule.web.domain.entity.JobRegistryDO;
import io.github.yuku123.z.schedule.web.domain.mapper.JobRegistryMapper;
import io.github.yuku123.z.schedule.web.service.ExecutorRegistryService;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import org.springframework.context.annotation.Lazy;
import javax.annotation.Resource;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 执行器心跳注册服务 — 持久化实现
 * <p>
 * 心跳数据落地 z_schedule_job_registry 表，{@link Scheduled} 每 30 秒清理超期注册（默认 90 秒无心跳视为下线）。
 */
@Service
public class ExecutorRegistryServiceImpl implements ExecutorRegistryService {

    private static final Logger logger = LogManager.getLogger(ExecutorRegistryServiceImpl.class);

    /**
     * 注册过期时间（秒）：超过该时间未收到心跳的节点视为下线
     */
    private static final long REGISTRY_EXPIRE_SECONDS = 90;

    @Resource
    private JobRegistryMapper jobRegistryMapper;

    @Resource
    @Lazy
    private io.github.yuku123.z.schedule.web.service.JobGroupService jobGroupService;

    @Override
    public ReturnT<String> beat(String appName, String address) {
        if (appName == null || appName.isEmpty() || address == null || address.isEmpty()) {
            return ReturnT.fail("appName/address 不能为空");
        }
        // upsert 心跳
        JobRegistryDO reg = jobRegistryMapper.selectOne(
                new LambdaQueryWrapper<JobRegistryDO>()
                        .eq(JobRegistryDO::getRegistryGroup, "EXECUTOR")
                        .eq(JobRegistryDO::getRegistryKey, appName)
                        .eq(JobRegistryDO::getRegistryValue, address));
        if (reg == null) {
            reg = new JobRegistryDO();
            reg.setRegistryGroup("EXECUTOR");
            reg.setRegistryKey(appName);
            reg.setRegistryValue(address);
            reg.setUpdateTime(new Date());
            jobRegistryMapper.insert(reg);
        } else {
            reg.setUpdateTime(new Date());
            jobRegistryMapper.updateById(reg);
        }
        // 同步触发分组注册/addressList 刷新
        jobGroupService.register(appName, address);
        int active = countActive(appName);
        logger.debug("Executor beat, appName={}, address={}, active={}", appName, address, active);
        return ReturnT.success("active=" + active, null);
    }

    @Override
    public ReturnT<String> remove(String appName, String address) {
        if (appName == null || address == null) {
            return ReturnT.fail("appName/address 不能为空");
        }
        jobRegistryMapper.delete(
                new LambdaQueryWrapper<JobRegistryDO>()
                        .eq(JobRegistryDO::getRegistryKey, appName)
                        .eq(JobRegistryDO::getRegistryValue, address));
        // 同步触发分组 addressList 刷新
        if (jobGroupService.register(appName, "").getCode() != ReturnT.SUCCESS_CODE) {
            // 简化处理: 即使 register 内部校验失败, 这里仍移除成功
        }
        return ReturnT.success();
    }

    @Override
    public int onlineGroupCount() {
        cleanupExpired();
        return jobRegistryMapper.selectCount(null) > 0
                ? jobRegistryMapper.selectList(
                        new LambdaQueryWrapper<JobRegistryDO>()
                                .select(JobRegistryDO::getRegistryKey)
                                .groupBy(JobRegistryDO::getRegistryKey))
                .size() : 0;
    }

    /**
     * 当前 appName 下的活跃实例数（心跳未过期）
     */
    public int countActive(String appName) {
        Date threshold = new Date(System.currentTimeMillis() - REGISTRY_EXPIRE_SECONDS * 1000L);
        return Math.toIntExact(jobRegistryMapper.selectCount(
                new LambdaQueryWrapper<JobRegistryDO>()
                        .eq(JobRegistryDO::getRegistryKey, appName)
                        .ge(JobRegistryDO::getUpdateTime, threshold)));
    }

    /**
     * 加载所有分组 + 实时心跳，用于 Dashboard。
     */
    public List<Map<String, Object>> loadAll() {
        cleanupExpired();
        List<Map<String, Object>> result = new java.util.ArrayList<>();
        List<JobRegistryDO> all = jobRegistryMapper.selectList(null);
        Map<String, List<String>> byApp = new HashMap<>();
        for (JobRegistryDO r : all) {
            byApp.computeIfAbsent(r.getRegistryKey(), k -> new java.util.ArrayList<>()).add(r.getRegistryValue());
        }
        for (Map.Entry<String, List<String>> e : byApp.entrySet()) {
            Map<String, Object> m = new HashMap<>();
            m.put("appName", e.getKey());
            m.put("instanceCount", e.getValue().size());
            m.put("runningTasks", 0);
            m.put("addresses", e.getValue());
            result.add(m);
        }
        return result;
    }

    @Scheduled(fixedDelay = 30_000L)
    public void cleanupExpired() {
        Date threshold = new Date(System.currentTimeMillis() - REGISTRY_EXPIRE_SECONDS * 1000L);
        int rows = jobRegistryMapper.delete(
                new LambdaQueryWrapper<JobRegistryDO>().lt(JobRegistryDO::getUpdateTime, threshold));
        if (rows > 0) {
            logger.info("Cleaned {} expired executor registries (older than {}s)", rows, REGISTRY_EXPIRE_SECONDS);
        }
    }
}
