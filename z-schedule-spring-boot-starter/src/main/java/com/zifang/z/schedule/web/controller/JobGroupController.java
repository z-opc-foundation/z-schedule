package com.zifang.z.schedule.web.controller;

import com.zifang.z.schedule.core.model.JobGroup;
import com.zifang.z.schedule.core.model.ReturnT;
import com.zifang.z.schedule.web.service.JobGroupService;
import com.zifang.z.schedule.web.service.impl.JobGroupServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 执行器分组管理 Controller,提供执行器分组的 CRUD 与注册节点查询.
 * <p>
 * API 基础路径: /jobgroup
 * 所属模块: z-schedule-admin
 *
 * <p>主要端点:
 * <ul>
 *   <li>GET  /jobgroup/list               — 全量分组列表(已合并 registryList)</li>
 *   <li>GET  /jobgroup/get?id=             — 查询单个分组</li>
 *   <li>POST /jobgroup/add                — 新增分组</li>
 *   <li>POST /jobgroup/update             — 更新分组</li>
 *   <li>POST /jobgroup/remove?id=         — 删除分组</li>
 *   <li>GET  /jobgroup/registryNodes?appName= — 在线注册节点列表</li>
 * </ul>
 */
@RestController
@RequestMapping("/jobgroup")
public class JobGroupController {

    @Autowired
    private JobGroupService jobGroupService;

    /**
     * 全量分组列表(返回前端期望结构,包含 registryList).
     */
    @GetMapping("/list")
    public ReturnT<List<Map<String, Object>>> list() {
        List<JobGroup> all = jobGroupService.getAll();
        return ReturnT.success(JobGroupServiceImpl.toFrontendList(all));
    }

    /**
     * 根据主键 ID 查询分组.
     * <p>
     * RESTful 别名: GET /jobgroup/{id}
     */
    @GetMapping({"/get", "/{id}"})
    public ReturnT<Map<String, Object>> getById(@RequestParam(required = false) Integer id,
                                                @PathVariable(value = "id", required = false) Integer id2) {
        int realId = id != null ? id : (id2 != null ? id2 : 0);
        JobGroup g = jobGroupService.getById(realId);
        if (g == null) {
            return ReturnT.fail("分组不存在");
        }
        return ReturnT.success(JobGroupServiceImpl.toFrontendList(java.util.Collections.singletonList(g)).get(0));
    }

    /**
     * 新增分组.
     */
    @PostMapping("/add")
    public ReturnT<String> add(@RequestBody JobGroup jobGroup) {
        return jobGroupService.add(jobGroup);
    }

    /**
     * 更新分组.
     */
    @PostMapping("/update")
    public ReturnT<String> update(@RequestBody JobGroup jobGroup) {
        return jobGroupService.update(jobGroup);
    }

    /**
     * 删除分组.
     * <p>
     * RESTful 别名: POST /jobgroup/remove/{id}
     */
    @PostMapping({"/remove", "/remove/{id}"})
    public ReturnT<String> remove(@RequestParam(required = false) Integer id,
                                  @PathVariable(value = "id", required = false) Integer id2) {
        int realId = id != null ? id : (id2 != null ? id2 : 0);
        return jobGroupService.delete(realId);
    }

    /**
     * 在线注册节点列表(执行器 /beat 注册地址).
     */
    @GetMapping("/registryNodes")
    public ReturnT<List<String>> registryNodes(@RequestParam String appName) {
        return ReturnT.success(jobGroupService.getRegistryNodes(appName));
    }
}