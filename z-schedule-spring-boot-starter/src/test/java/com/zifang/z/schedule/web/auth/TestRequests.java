package com.zifang.z.schedule.web.auth;

import com.zifang.z.schedule.web.domain.entity.UserDO;
import com.zifang.z.schedule.web.filter.TokenAuthFilter;

import javax.servlet.http.HttpServletRequest;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 测试用的 {@link HttpServletRequest} 替身：只把 {@code TokenAuthFilter} 真正会挂的那两个属性
 * 挂上去，其它方法一律回零值。
 * <p>
 * 身份一律经 {@link LoginSessionStore#issue(UserDO)} 签发，不在这里手搓 {@link LoginSession}：
 * {@code permission} 从 {@code UserDO} 到身份里的那一段也得有测试盖着，否则签发时漏带这一列，
 * 下游所有分权断言一条都不会红。
 */
public final class TestRequests {

    private TestRequests() {
    }

    /** 演示模式：过滤器放行匿名请求，两个属性都不挂。 */
    public static HttpServletRequest anonymous() {
        return withAttributes(new LinkedHashMap<String, Object>());
    }

    /** 共享密钥：全权，但没有人——按组收口的那一层对它没有牙。 */
    public static HttpServletRequest sharedSecret() {
        Map<String, Object> attributes = new LinkedHashMap<String, Object>();
        attributes.put(TokenAuthFilter.FULL_AUTHORITY_ATTRIBUTE, Boolean.TRUE);
        return withAttributes(attributes);
    }

    /** 一个只有 {@code permission} 列里那几组权限的普通会话。 */
    public static HttpServletRequest scoped(String permission) {
        return session(7, "peon", "NORMAL", permission);
    }

    public static HttpServletRequest session(int userId, String username, String role, String permission) {
        UserDO row = new UserDO();
        row.setId(userId);
        row.setUsername(username);
        row.setRole(role);
        row.setPermission(permission);
        Map<String, Object> attributes = new LinkedHashMap<String, Object>();
        attributes.put(TokenAuthFilter.IDENTITY_ATTRIBUTE, new LoginSessionStore().issue(row));
        return withAttributes(attributes);
    }

    /** 给失败消息用：把这次请求带的凭证种类写出来，红了能直接看出是哪一支脸。 */
    public static String describe(HttpServletRequest request) {
        if (Boolean.TRUE.equals(request.getAttribute(TokenAuthFilter.FULL_AUTHORITY_ATTRIBUTE))) {
            return "sharedSecret";
        }
        Object identity = request.getAttribute(TokenAuthFilter.IDENTITY_ATTRIBUTE);
        if (identity == null) {
            return "anonymous";
        }
        LoginSession session = (LoginSession) identity;
        return session.getUsername() + "/" + session.getRole() + "/" + session.getPermission();
    }

    private static HttpServletRequest withAttributes(final Map<String, Object> attributes) {
        return (HttpServletRequest) Proxy.newProxyInstance(
                TestRequests.class.getClassLoader(),
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
}
