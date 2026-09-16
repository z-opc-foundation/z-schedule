package io.github.yuku123.z.schedule.web.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.github.yuku123.z.schedule.core.enums.ExecutorRouteStrategyEnum;
import io.github.yuku123.z.schedule.core.model.JobGroup;
import io.github.yuku123.z.schedule.core.model.ReturnT;
import io.github.yuku123.z.schedule.core.route.ExecutorRouter;
import io.github.yuku123.z.schedule.web.domain.entity.JobGroupDO;
import io.github.yuku123.z.schedule.web.domain.entity.JobRegistryDO;
import io.github.yuku123.z.schedule.web.domain.mapper.JobGroupMapper;
import io.github.yuku123.z.schedule.web.domain.mapper.JobRegistryMapper;
import io.github.yuku123.z.schedule.web.service.JobGroupService;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 执行器分组服务 — 持久化实现
 * <p>
 * 分组元数据落地 z_schedule_job_group 表，心跳注册落地 z_schedule_job_registry 表。
 * addressList 字段为冗余缓存（registry 表实时聚合），便于 Dashboard 快速查询。
 */
@Service
public class JobGroupServiceImpl implements JobGroupService {

    private static final Logger logger = LogManager.getLogger(JobGroupServiceImpl.class);

    private static final Map<String, ExecutorRouter> ROUTERS = new ConcurrentHashMap<>();

    static {
        ROUTERS.put(ExecutorRouteStrategyEnum.ROUND.getCode(),
                new io.github.yuku123.z.schedule.core.route.impl.RoundRobinRouter());
        ROUTERS.put(ExecutorRouteStrategyEnum.RANDOM.getCode(),
                new io.github.yuku123.z.schedule.core.route.impl.RandomRouter());
        ROUTERS.put(ExecutorRouteStrategyEnum.CONSISTENT_HASH.getCode(),
                new io.github.yuku123.z.schedule.core.route.impl.ConsistentHashRouter());
        ROUTERS.put(ExecutorRouteStrategyEnum.FAILOVER.getCode(),
                new io.github.yuku123.z.schedule.core.route.impl.FailoverRouter());
        ROUTERS.put(ExecutorRouteStrategyEnum.SHARDING_BROADCAST.getCode(),
                new io.github.yuku123.z.schedule.core.route.impl.ShardingBroadcastRouter());
    }

    @Resource
    private JobGroupMapper jobGroupMapper;

    @Resource
    private JobRegistryMapper jobRegistryMapper;

    public static String route(String strategy, List<String> addresses, int jobId) {
        if (addresses == null || addresses.isEmpty()) {
            return null;
        }
        ExecutorRouter router = ROUTERS.get(strategy);
        if (router == null) {
            return addresses.get(0);
        }
        return router.route(addresses, jobId);
    }

    /**
     * 分组 + 实时心跳 → 前端列表。
     */
    public static List<Map<String, Object>> toFrontendList(List<JobGroup> groups) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (JobGroup g : groups) {
            Map<String, Object> m = new HashMap<>();
            m.put("id", g.getId());
            m.put("appName", g.getAppName());
            m.put("title", g.getTitle());
            m.put("order", g.getOrder());
            m.put("addressType", g.getAddressType());
            m.put("addressList", g.getAddressList());
            m.put("updateTime", g.getUpdateTime());
            if (g.getAddressList() != null && !g.getAddressList().isEmpty()) {
                List<String> nodes = new ArrayList<>();
                for (String a : g.getAddressList().split(",")) {
                    String t = a.trim();
                    if (!t.isEmpty()) nodes.add(t);
                }
                m.put("registryList", nodes);
            } else {
                m.put("registryList", new ArrayList<String>());
            }
            list.add(m);
        }
        return list;
    }

    @Override
    public List<JobGroup> getAll() {
        List<JobGroup> list = DoMapper.toGroupDTOList(jobGroupMapper.selectList(null));
        list.sort((a, b) -> Integer.compare(a.getOrder(), b.getOrder()));
        return list;
    }

    @Override
    public JobGroup getById(int id) {
        return DoMapper.toDTO(jobGroupMapper.selectById(id));
    }

    @Override
    public JobGroup getByAppName(String appName) {
        if (appName == null || appName.isEmpty()) {
            return null;
        }
        return DoMapper.toDTO(jobGroupMapper.selectOne(
                new LambdaQueryWrapper<JobGroupDO>().eq(JobGroupDO::getAppName, appName)));
    }

    @Override
    public ReturnT<String> add(JobGroup jobGroup) {
        if (jobGroup == null) return ReturnT.fail("参数不能为空");
        if (jobGroup.getAppName() == null || jobGroup.getAppName().trim().isEmpty()) {
            return ReturnT.fail("AppName 不能为空");
        }
        if (jobGroup.getTitle() == null || jobGroup.getTitle().trim().isEmpty()) {
            return ReturnT.fail("执行器名称不能为空");
        }
        if (getByAppName(jobGroup.getAppName()) != null) {
            return ReturnT.fail("AppName 已存在: " + jobGroup.getAppName());
        }
        if (jobGroup.getAddressType() != 0 && jobGroup.getAddressType() != 1) {
            jobGroup.setAddressType(0);
        }
        jobGroup.setUpdateTime(new Date());

        JobGroupDO d = DoMapper.toDO(jobGroup);
        jobGroupMapper.insert(d);
        logger.info("JobGroup added, id={}, appName={}", d.getId(), jobGroup.getAppName());
        return new ReturnT<>(ReturnT.SUCCESS_CODE, "success", String.valueOf(d.getId()));
    }

    @Override
    public ReturnT<String> update(JobGroup jobGroup) {
        if (jobGroup == null || jobGroup.getId() <= 0) {
            return ReturnT.fail("ID 不能为空");
        }
        JobGroupDO exist = jobGroupMapper.selectById(jobGroup.getId());
        if (exist == null) {
            return ReturnT.fail("执行器分组不存在");
        }
        if (jobGroup.getTitle() != null && !jobGroup.getTitle().isEmpty()) exist.setTitle(jobGroup.getTitle());
        if (jobGroup.getOrder() > 0) exist.setOrderNum(jobGroup.getOrder());
        if (jobGroup.getAddressType() == 0 || jobGroup.getAddressType() == 1) {
            exist.setAddressType(jobGroup.getAddressType());
        }
        if (jobGroup.getAddressList() != null) exist.setAddressList(jobGroup.getAddressList());
        exist.setUpdateTime(new Date());
        jobGroupMapper.updateById(exist);
        return ReturnT.success();
    }

    @Override
    public ReturnT<String> delete(int id) {
        int rows = jobGroupMapper.deleteById(id);
        if (rows == 0) return ReturnT.fail("执行器分组不存在");
        return ReturnT.success();
    }

    @Override
    public synchronized ReturnT<String> register(String appName, String address) {
        if (appName == null || appName.isEmpty() || address == null || address.isEmpty()) {
            return ReturnT.fail("appName/address 不能为空");
        }
        // 1. 更新/创建分组
        JobGroup group = getByAppName(appName);
        if (group == null) {
            JobGroup auto = new JobGroup();
            auto.setAppName(appName);
            auto.setTitle(appName);
            auto.setOrder(99);
            auto.setAddressType(0);
            ReturnT<String> created = add(auto);
            if (created.getCode() != ReturnT.SUCCESS_CODE) return created;
            group = getByAppName(appName);
        }

        // 2. 写入/更新注册表 (upsert by registry_group+registry_key+registry_value)
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

        // 3. 重新聚合 addressList
        List<String> addresses = jobRegistryMapper.selectList(
                        new LambdaQueryWrapper<JobRegistryDO>()
                                .eq(JobRegistryDO::getRegistryKey, appName))
                .stream().map(JobRegistryDO::getRegistryValue).collect(java.util.stream.Collectors.toList());
        group.setAddressList(addresses.isEmpty() ? null : String.join(",", addresses));
        group.setUpdateTime(new Date());
        jobGroupMapper.updateById(DoMapper.toDO(group));

        logger.info("Executor registered, appName={}, address={}", appName, address);
        return ReturnT.success();
    }

    @Override
    public List<String> getRegistryNodes(String appName) {
        if (appName == null || appName.isEmpty()) return new ArrayList<>();
        JobGroup group = getByAppName(appName);
        if (group == null || group.getAddressList() == null || group.getAddressList().isEmpty()) {
            return new ArrayList<>();
        }
        List<String> nodes = new ArrayList<>();
        for (String a : group.getAddressList().split(",")) {
            String t = a.trim();
            if (!t.isEmpty() && !nodes.contains(t)) nodes.add(t);
        }
        return nodes;
    }
}
