package com.zifang.z.schedule.web.auth;

/**
 * 一次登录换来的身份：把"出示的令牌"绑定回"哪个用户、什么角色"。
 * <p>
 * 不可变，且刻意不带密码或其散列——令牌泄露时不该连带泄露口令材料。
 * 也<b>不带</b> {@code permission} 列：那一列是"能操作哪些 jobGroup"的维度，
 * 而本版本还没有任何按 jobGroup 的判据，带进身份只会多一个没人读的字段。
 */
public class LoginSession {

    /** 与 {@code z_schedule_user.role} 的取值一致（见 {@code _doc/004_sql/z-schedule.sql} 第 6 张表）。 */
    public static final String ROLE_ADMIN = "ADMIN";

    private final String token;
    private final int userId;
    private final String username;
    private final String role;
    private final long expireAtMillis;

    LoginSession(String token, int userId, String username, String role, long expireAtMillis) {
        this.token = token;
        this.userId = userId;
        this.username = username;
        this.role = role;
        this.expireAtMillis = expireAtMillis;
    }

    public String getToken() {
        return token;
    }

    public int getUserId() {
        return userId;
    }

    public String getUsername() {
        return username;
    }

    public String getRole() {
        return role;
    }

    public long getExpireAtMillis() {
        return expireAtMillis;
    }

    public boolean isAdmin() {
        return ROLE_ADMIN.equalsIgnoreCase(role);
    }

    boolean isExpired(long nowMillis) {
        return nowMillis >= expireAtMillis;
    }

    /** 不含令牌：这条串会进日志，而日志的读者不需要能顶替谁。 */
    @Override
    public String toString() {
        return "LoginSession{userId=" + userId
                + ", username='" + username + '\''
                + ", role='" + role + '\''
                + ", expireAt=" + expireAtMillis
                + '}';
    }
}
