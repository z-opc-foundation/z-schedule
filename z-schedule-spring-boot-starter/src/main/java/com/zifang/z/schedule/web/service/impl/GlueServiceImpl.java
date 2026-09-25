package com.zifang.z.schedule.web.service.impl;

import com.zifang.z.schedule.core.enums.GlueTypeEnum;
import com.zifang.z.schedule.core.model.ReturnT;
import com.zifang.z.schedule.web.service.GlueService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * GLUE 源码管理服务实现
 * <p>
 * 使用内存存储 GLUE 源码版本，每个 jobId 保留最近 30 个版本。
 * 不依赖数据库。
 */
@Service
public class GlueServiceImpl implements GlueService {

    /**
     * 每个 jobId 最多保留的版本数
     */
    private static final int MAX_VERSION_SIZE = 30;

    /**
     * GLUE 源码版本存储：jobId -> 版本列表（按时间顺序，最新在前）
     */
    private final ConcurrentHashMap<Integer, List<String>> glueVersionMap = new ConcurrentHashMap<>();

    @Override
    public ReturnT<String> save(int jobId, String glueSource, String glueType) {
        if (jobId <= 0) {
            return ReturnT.fail("任务ID无效");
        }
        if (glueSource == null || glueSource.trim().isEmpty()) {
            return ReturnT.fail("GLUE源码不能为空");
        }
        if (glueType == null || glueType.isEmpty()) {
            return ReturnT.fail("GLUE类型不能为空");
        }

        GlueTypeEnum glueTypeEnum = GlueTypeEnum.match(glueType);
        if (glueTypeEnum == null) {
            return ReturnT.fail("GLUE类型不合法: " + glueType);
        }

        // 只发布不可变快照：列表一旦放进 map 就不再原地修改，
        // 否则读侧 new ArrayList<>(versions) 会和在写入头插入的 add(0,..) 抢同一个数组。
        glueVersionMap.compute(jobId, (key, versions) -> {
            List<String> updated = new ArrayList<String>(MAX_VERSION_SIZE + 1);
            updated.add(glueSource);
            if (versions != null) {
                updated.addAll(versions);
            }
            return updated.size() > MAX_VERSION_SIZE
                    ? new ArrayList<String>(updated.subList(0, MAX_VERSION_SIZE))
                    : updated;
        });

        return ReturnT.success("保存成功");
    }

    @Override
    public ReturnT<String> get(int jobId) {
        if (jobId <= 0) {
            return ReturnT.fail("任务ID无效");
        }

        List<String> versions = glueVersionMap.get(jobId);
        if (versions == null || versions.isEmpty()) {
            return ReturnT.fail("GLUE源码不存在");
        }

        // 返回最新版本（列表第一个元素）
        return ReturnT.success(versions.get(0));
    }

    @Override
    public ReturnT<List<String>> getVersions(int jobId) {
        if (jobId <= 0) {
            return ReturnT.fail("任务ID无效");
        }

        List<String> versions = glueVersionMap.get(jobId);
        if (versions == null) {
            return ReturnT.success(Collections.emptyList());
        }

        return ReturnT.success(new ArrayList<>(versions));
    }
}
