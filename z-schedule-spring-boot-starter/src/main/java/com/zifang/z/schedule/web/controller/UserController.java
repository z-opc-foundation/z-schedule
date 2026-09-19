package com.zifang.z.schedule.web.controller;

import com.zifang.z.schedule.core.model.ReturnT;
import com.zifang.z.schedule.core.model.User;
import com.zifang.z.schedule.web.service.UserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 用户管理 Controller,提供用户的增删改查以及登录接口.
 * <p>
 * API 基础路径: /user
 * 所属模块: z-schedule-admin
 * 鉴权: 由调度管理端统一拦截,需登录态校验
 *
 * <p>主要端点:
 * <ul>
 *   <li>GET /user/list — 获取用户列表</li>
 *   <li>POST /user/add — 新增用户</li>
 *   <li>POST /user/update — 更新用户</li>
 *   <li>POST /user/remove?id= — 删除用户</li>
 *   <li>POST /user/login — 用户登录</li>
 * </ul>
 */
@RestController("scheduleUserController")
@RequestMapping("/user")
public class UserController {

    @Autowired
    private UserService userService;

    /**
     * 获取用户列表.
     *
     * @return 用户列表的封装结果
     */
    @GetMapping("/list")
    public ReturnT<List<User>> list() {
        List<User> list = userService.getAll();
        return ReturnT.success(list);
    }

    /**
     * 新增用户.
     *
     * @param user 待新增的用户实体
     * @return 操作结果(成功/失败以及错误信息)
     */
    @PostMapping("/add")
    public ReturnT<String> add(@RequestBody User user) {
        return userService.add(user);
    }

    /**
     * 更新用户.
     *
     * @param user 待更新的用户实体
     * @return 操作结果(成功/失败以及错误信息)
     */
    @PostMapping("/update")
    public ReturnT<String> update(@RequestBody User user) {
        return userService.update(user);
    }

    /**
     * 根据主键 ID 删除用户.
     *
     * @param id 用户主键 ID
     * @return 操作结果(成功/失败以及错误信息)
     */
    @PostMapping("/remove")
    public ReturnT<String> remove(@RequestParam int id) {
        return userService.delete(id);
    }

    /**
     * 用户登录.
     *
     * @param param 登录参数，包含 username 和 password
     * @return 操作结果（成功返回 token=用户名）
     */
    @PostMapping("/login")
    public ReturnT<String> login(@RequestBody java.util.Map<String, String> param) {
        String username = param.get("username");
        String password = param.get("password");
        return userService.login(username, password);
    }
}
