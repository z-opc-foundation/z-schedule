package com.zifang.z.schedule.web.controller;

import com.zifang.z.meta.api.annotation.BizApi;
import com.zifang.z.meta.api.annotation.BizField;
import com.zifang.z.meta.api.annotation.BizParam;
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
 * API 基础路径: /glue 与 /api/schedule/glue 两条等价。
 * <p>
 * {@code /api/schedule/**} 是嵌进宿主时的正式入口（宿主登录闸门按路径前缀匹配，只挡 api 那一段的路径）；
 * 裸路径 {@code /glue} 只留给独立部署的 z-schedule-admin，且受 {@code z.schedule.expose-bare-paths} 控制（默认关闭）。
 */
@RestController
@RequestMapping({"/glue", "/api/schedule/glue"})
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
    @BizApi(summary = "保存 GLUE 源码", tags = { "z-schedule" })
    public ReturnT<String> save(@RequestParam @BizParam(name = "jobId", type = BizField.FieldType.INTEGER, description = "任务ID", position = BizParam.Position.QUERY) int jobId,
                                @RequestParam @BizParam(name = "glueSource", type = BizField.FieldType.STRING, description = "GLUE 源码", position = BizParam.Position.QUERY) String glueSource,
                                @RequestParam @BizParam(name = "glueType", type = BizField.FieldType.STRING, description = "GLUE 类型", position = BizParam.Position.QUERY) String glueType) {
        return glueService.save(jobId, glueSource, glueType);
    }

    /**
     * 获取最新 GLUE 源码
     *
     * @param jobId 任务ID
     * @return GLUE 源码
     */
    @GetMapping("/get")
    @BizApi(summary = "获取最新 GLUE 源码", tags = { "z-schedule" })
    public ReturnT<String> get(@RequestParam @BizParam(name = "jobId", type = BizField.FieldType.INTEGER, description = "任务ID", position = BizParam.Position.QUERY) int jobId) {
        return glueService.get(jobId);
    }

    /**
     * 获取 GLUE 源码历史版本列表
     *
     * @param jobId 任务ID
     * @return 版本列表
     */
    @GetMapping("/versions")
    @BizApi(summary = "获取 GLUE 源码历史版本列表", tags = { "z-schedule" })
    public ReturnT<List<String>> getVersions(@RequestParam @BizParam(name = "jobId", type = BizField.FieldType.INTEGER, description = "任务ID", position = BizParam.Position.QUERY) int jobId) {
        return glueService.getVersions(jobId);
    }
}



