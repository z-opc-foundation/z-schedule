package com.zifang.z.schedule.web.controller;

import com.zifang.z.schedule.core.model.ReturnT;
import com.zifang.z.schedule.core.model.User;
import com.zifang.z.schedule.web.auth.LoginSessionStore;
import com.zifang.z.schedule.web.domain.entity.UserDO;
import com.zifang.z.schedule.web.filter.TokenAuthFilter;
import com.zifang.z.schedule.web.service.UserService;
import org.junit.Test;

import javax.servlet.http.HttpServletRequest;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * {@link UserController} 上"铸出管理员"那道闸。
 * <p>
 * 它和 {@code TokenAuthFilter} 的 {@code ADMIN_ONLY_PATHS} 不是一回事，也不该合并：
 * 角色闸问的是"这个口能不能进"，而演示模式（没配 accessToken）下这三个口对匿名是敞开的
 * ——真机实测（p22 的 A.1）匿名 POST 一个 {@code role=ADMIN} 就真建出了一个管理员。
 * 所以这里问的是第二个问题：<b>能不能把"管理员"这个身份造出来</b>。答案只认两种凭证：
 * ADMIN 会话（知道是谁）、共享密钥（知道握着口令）。
 * <p>
 * 断言的重点是"service 一次都没被调"：闸必须落在写库<b>之前</b>，
 * 先写再退回去的那种"拒绝"，账号已经躺在表里了。
 */
public class UserControllerAdminGuardTest {

    private final RecordingUserService service = new RecordingUserService();
    private final UserController controller = controllerWith(service);

    @Test
    public void 匿名铸管理员被拒而库一个字都没动() throws Exception {
        ReturnT<String> result = controller.add(user("root", "ADMIN"), anonymous());

        assertFalse(result.isSuccess());
        assertTrue("拒绝理由要说清缺的是哪种凭证: " + result.getMsg(), result.getMsg().contains("accessToken"));
        assertEquals("闸必须落在写库之前", 0, service.calls.size());

        // 阳性对照：同一次调用只把 role 换成 NORMAL，就必须放行到 service —
        // 否则上面那条红只是"全拦"，看不出闸咬的是 ADMIN 这一支。
        controller.add(user("guest", "NORMAL"), anonymous());
        assertEquals("普通账号不该被这道闸挡住", 1, service.calls.size());
        assertEquals("add", service.calls.get(0));
    }

    @Test
    public void 普通会话也铸不出管理员() throws Exception {
        // 真过滤器早就把这条请求拦在 403 了（ADMIN_ONLY_PATHS），这里钉的是 controller
        // 自己的判断不依赖那一层：把属性替它填好、只给一个 NORMAL 身份，它照样不能铸。
        ReturnT<String> result = controller.add(user("root", "ADMIN"), sessionOnly("7", "peon", "NORMAL"));

        assertFalse(result.isSuccess());
        assertEquals(0, service.calls.size());
    }

    @Test
    public void 管理员会话与共享密钥都铸得出来() throws Exception {
        controller.add(user("root", "ADMIN"), sessionOnly("8", "boss", "ADMIN"));
        assertEquals("ADMIN 会话是答案之一", 1, service.calls.size());

        controller.add(user("root2", "ADMIN"), sharedSecret());
        assertEquals("共享密钥也是（它本来就是全权）", 2, service.calls.size());
        assertEquals("add", service.calls.get(1));
    }

    @Test
    public void 把已有账号提成管理员走同一道闸() throws Exception {
        ReturnT<String> result = controller.update(user("root", "ADMIN"), anonymous());
        assertFalse(result.isSuccess());
        assertEquals("改角色也不能绕过：否则先建 NORMAL 再 update 成 ADMIN 就是同一扇门", 0, service.calls.size());

        controller.update(user("root", "ADMIN"), sharedSecret());
        assertEquals(1, service.calls.size());
        assertEquals("update", service.calls.get(0));

        // 降权不在这道闸的范围里：它不制造特权
        controller.update(user("root", "NORMAL"), anonymous());
        assertEquals(2, service.calls.size());
    }

    @Test
    public void 只有真的ADMIN才算提权() throws Exception {
        // 大小写：库里比对用的是 equalsIgnoreCase，闸必须同样宽，否则 "admin" 绕过去
        assertFalse(controller.add(user("root", "admin"), anonymous()).isSuccess());
        assertEquals(0, service.calls.size());

        // role 为空 / 非 ADMIN：service 会把它落成 NORMAL（见 UserServiceImpl.add），
        // 那不算铸管理员，闸不该拦 —— 拦了就把演示模式下建普通账号这条路也堵死了。
        controller.add(user("guest", null), anonymous());
        controller.add(user("guest2", ""), anonymous());
        controller.add(user("guest3", "VIEWER"), anonymous());
        assertEquals(3, service.calls.size());
    }

    @Test
    public void 登录口与登出不受这道闸影响() throws Exception {
        // /user/login 在 PUBLIC_PATHS 里，本来就没有身份可问；闸只管"铸管理员"。
        // 这条钉的是"新加的判据没顺手把别的路径也要求属性"——缺属性的请求一律走得到 service。
        controller.login(map("username", "someone", "password", "pw"));
        assertEquals("login", service.calls.get(0));
        assertEquals("someone", service.lastLogin[0]);

        // 登出在没有登录态时由 controller 自己回失败，不经过 service（令牌不从请求体取）
        ReturnT<String> loggedOut = controller.logout(anonymous());
        assertFalse(loggedOut.isSuccess());
        assertTrue(loggedOut.getMsg().contains("没有登录态"));
        assertEquals("不该把别人的令牌当参数注销", 1, service.calls.size());

        controller.logout(sessionOnly("9", "boss", "ADMIN"));
        assertEquals(2, service.calls.size());
        assertEquals("logout", service.calls.get(1));
    }

    // ---- 替身 ----

    private static UserController controllerWith(UserService userService) {
        UserController controller = new UserController();
        try {
            Field field = UserController.class.getDeclaredField("userService");
            field.setAccessible(true);
            field.set(controller, userService);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        return controller;
    }

    private static User user(String username, String role) {
        User user = new User();
        user.setId(1);
        user.setUsername(username);
        user.setPassword("pw");
        user.setRole(role);
        return user;
    }

    private static Map<String, String> map(String k1, String v1, String k2, String v2) {
        Map<String, String> m = new LinkedHashMap<String, String>();
        m.put(k1, v1);
        m.put(k2, v2);
        return m;
    }

    private static HttpServletRequest withAttributes(final Map<String, Object> attributes) {
        return (HttpServletRequest) Proxy.newProxyInstance(
                UserControllerAdminGuardTest.class.getClassLoader(),
                new Class<?>[]{HttpServletRequest.class},
                new InvocationHandler() {
                    public Object invoke(Object proxy, Method method, Object[] args) {
                        if ("getAttribute".equals(method.getName())) {
                            return attributes.get((String) args[0]);
                        }
                        if (method.getReturnType() == boolean.class) {
                            return Boolean.FALSE;
                        }
                        if (method.getReturnType() == int.class) {
                            return Integer.valueOf(0);
                        }
                        return null;
                    }
                });
    }

    /** 演示模式：过滤器放行匿名请求，两个属性都不挂。 */
    private static HttpServletRequest anonymous() {
        return withAttributes(new LinkedHashMap<String, Object>());
    }

    private static HttpServletRequest sessionOnly(String userId, String username, String role) {
        UserDO row = new UserDO();
        row.setId(Integer.parseInt(userId));
        row.setUsername(username);
        row.setRole(role);
        Map<String, Object> attributes = new LinkedHashMap<String, Object>();
        attributes.put(TokenAuthFilter.IDENTITY_ATTRIBUTE, new LoginSessionStore().issue(row));
        return withAttributes(attributes);
    }

    private static HttpServletRequest sharedSecret() {
        Map<String, Object> attributes = new LinkedHashMap<String, Object>();
        attributes.put(TokenAuthFilter.FULL_AUTHORITY_ATTRIBUTE, Boolean.TRUE);
        return withAttributes(attributes);
    }

    private static class RecordingUserService implements UserService {
        final List<String> calls = new ArrayList<String>();
        String[] lastLogin;

        public List<User> getAll() {
            calls.add("getAll");
            return new ArrayList<User>();
        }

        public User getById(int id) {
            calls.add("getById");
            return null;
        }

        public User getByUsername(String username) {
            calls.add("getByUsername");
            return null;
        }

        public ReturnT<String> add(User user) {
            calls.add("add");
            return ReturnT.success();
        }

        public ReturnT<String> update(User user) {
            calls.add("update");
            return ReturnT.success();
        }

        public ReturnT<String> delete(int id) {
            calls.add("delete");
            return ReturnT.success();
        }

        public ReturnT<String> login(String username, String password) {
            calls.add("login");
            lastLogin = new String[]{username, password};
            return ReturnT.success("msg", "token");
        }

        public ReturnT<String> logout(String token) {
            calls.add("logout");
            return ReturnT.success();
        }
    }
}
