package com.zifang.z.schedule.web.controller;

import com.zifang.z.schedule.core.model.ReturnT;
import com.zifang.z.schedule.web.service.GlueService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * GLUE 源码管理 Controller
 * <p>
 * 提供 GLUE 源码的保存、获取、历史版本查询等接口。
 * <p>
 * API 基础路径: /glue
 */
@RestController
@RequestMapping("/glue")
public class GlueController {

    @Autowired
    private GlueService glueService;

    /**
     * 保存 GLUE 源码
     *
     * @param jobId      任务ID
     * @param glueSource GLUE 源码
     * @param glueType   GLUE 类型
     * @return 操作结果
     */
    @PostMapping("/save")
    public ReturnT<String> save(@RequestParam int jobId,
                                @RequestParam String glueSource,
                                @RequestParam String glueType) {
        return glueService.save(jobId, glueSource, glueType);
    }

    /**
     * 获取最新 GLUE 源码
     *
     * @param jobId 任务ID
     * @return GLUE 源码
     */
    @GetMapping("/get")
    public ReturnT<String> get(@RequestParam int jobId) {
        return glueService.get(jobId);
    }

    /**
     * 获取 GLUE 源码历史版本列表
     *
     * @param jobId 任务ID
     * @return 版本列表
     */
    @GetMapping("/versions")
    public ReturnT<List<String>> getVersions(@RequestParam int jobId) {
        return glueService.getVersions(jobId);
    }
}
