package com.zifang.z.schedule.web.controller;

import com.zifang.z.schedule.core.model.ReturnT;
import com.zifang.z.schedule.core.model.User;
import com.zifang.z.schedule.web.auth.LoginSession;
import com.zifang.z.schedule.web.filter.TokenAuthFilter;
import com.zifang.z.schedule.web.service.UserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import java.util.List;

/**
 * 用户管理 Controller,提供用户的增删改查以及登录接口.
 * <p>
 * API 基础路径: /user
 * 所属模块: z-schedule-admin
 * 鉴权: 由 {@code TokenAuthFilter} 统一拦；建/改/删账号只接受 ADMIN 会话（见其 ADMIN_ONLY_PATHS），
 * 而"把某个账号变成 ADMIN"这一步另有一道闸：只有 ADMIN 会话或共享 accessToken 放行的请求才做得了
 * （见 {@link #canMintAdmin}）——演示模式对匿名敞开的是管理面，不该把"铸管理员"也一起敞开。
 *
 * <p>主要端点:
 * <ul>
 *   <li>GET /user/list — 获取用户列表</li>
 *   <li>POST /user/add — 新增用户（ADMIN）</li>
 *   <li>POST /user/update — 更新用户（ADMIN）</li>
 *   <li>POST /user/remove?id= — 删除用户（ADMIN）</li>
 *   <li>POST /user/login — 用户登录，换取会话令牌</li>
 *   <li>POST /user/logout — 撤销当前会话令牌</li>
 * </ul>
 */
@RestController("scheduleUserController")
@RequestMapping("/user")
public class UserController {

    @Autowired
    private UserService userService;

    /**
     * "把账号变成管理员"需要哪种凭证。
     * <p>
     * 光靠 {@code ADMIN_ONLY_PATHS} 的角色闸拦不住这件事：未配置 accessToken 的演示模式下
     * 管理面对匿名敞开，而 {@code /user/add} 的 role 是请求体自填的——真机实测（p22 的 A.1）
     * 匿名 POST 一个 {@code role=ADMIN} 就真的铸出了一个管理员。铸管理员要能回答"是谁在铸"，
     * 所以只认两种答得出来的凭证：ADMIN 会话（知道是谁）、共享密钥（知道握着口令）。
     */
    private static final String NEED_ADMIN_CREDENTIAL =
            "创建或提升为 ADMIN 需要 ADMIN 会话或 accessToken；未配置 accessToken 时匿名请求只能建 NORMAL";

    private static boolean wantsAdmin(User user) {
        return user != null && LoginSession.ROLE_ADMIN.equalsIgnoreCase(user.getRole());
    }

    private static boolean canMintAdmin(HttpServletRequest request) {
        if (TokenAuthFilter.presentedSharedSecret(request)) {
            return true;
        }
        LoginSession identity = TokenAuthFilter.currentIdentity(request);
        return identity != null && identity.isAdmin();
    }

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
    public ReturnT<String> add(@RequestBody User user, HttpServletRequest request) {
        if (wantsAdmin(user) && !canMintAdmin(request)) {
            return ReturnT.fail(NEED_ADMIN_CREDENTIAL);
        }
        return userService.add(user);
    }

    /**
     * 更新用户.
     *
     * @param user 待更新的用户实体
     * @return 操作结果(成功/失败以及错误信息)
     */
    @PostMapping("/update")
    public ReturnT<String> update(@RequestBody User user, HttpServletRequest request) {
        if (wantsAdmin(user) && !canMintAdmin(request)) {
            return ReturnT.fail(NEED_ADMIN_CREDENTIAL);
        }
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
     * @return 操作结果（成功时 content 是服务端签发的会话令牌，后续请求用 accessToken 参数或
     *         {@code X-Access-Token} 头出示）
     */
    @PostMapping("/login")
    public ReturnT<String> login(@RequestBody java.util.Map<String, String> param) {
        String username = param.get("username");
        String password = param.get("password");
        return userService.login(username, password);
    }

    /**
     * 注销当前会话.
     * <p>
     * 令牌从请求上下文取（{@code TokenAuthFilter} 已验过它），不从请求体取：
     * 能注销任意字符串的那个口，等于给持有他人令牌的人一个"帮你退出"的入口。
     */
    @PostMapping("/logout")
    public ReturnT<String> logout(HttpServletRequest request) {
        LoginSession identity = TokenAuthFilter.currentIdentity(request);
        if (identity == null) {
            return ReturnT.fail("当前请求没有登录态");
        }
        return userService.logout(identity.getToken());
    }
}
