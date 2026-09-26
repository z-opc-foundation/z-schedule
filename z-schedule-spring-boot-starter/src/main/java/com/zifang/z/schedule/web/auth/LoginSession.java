package com.zifang.z.schedule.web.auth;

/**
 * 一次登录换来的身份：把"出示的令牌"绑定回"哪个用户、什么角色、能碰哪些 jobGroup"。
 * <p>
 * 不可变，且刻意不带密码或其散列——令牌泄露时不该连带泄露口令材料。
 * <p>
 * {@code permission} 就是 {@code z_schedule_user.permission} 那一列（逗号分隔的 jobGroup id）。
 * 它以前不进身份，因为当时没有任何读者——一个没人读的字段就是装饰；现在 {@code /jobinfo/*}
 * 按 {@link #permits(int)} 收口，这一列才第一次参与判定。
 */
public class LoginSession {

    /** 与 {@code z_schedule_user.role} 的取值一致（见 {@code _doc/004_sql/z-schedule.sql} 第 6 张表）。 */
    public static final String ROLE_ADMIN = "ADMIN";

    private final String token;
    private final int userId;
    private final String username;
    private final String role;
    private final String permission;
    private final long expireAtMillis;

    LoginSession(String token, int userId, String username, String role, String permission,
                 long expireAtMillis) {
        this.token = token;
        this.userId = userId;
        this.username = username;
        this.role = role;
        this.permission = permission;
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

    public String getPermission() {
        return permission;
    }

    /**
     * 这个身份能不能碰某个 jobGroup。
     * <p>
     * 判据只有一份，就是 {@link #declaredGroups()}：点名某一组（{@code /jobinfo/list?jobGroup=}）
     * 和展开列表（"不限组"那种查询要按每个允许的组各查一遍）走的是同一个集合，
     * 两边各写一套解析就会出现"点名被拦、列表却能看见"这种自相矛盾的脸。
     * <p>
     * 三种"不限制"：管理员；{@code permission} 留空（向后兼容的那一半——现存 NORMAL 账号这一列
     * 全是空串，把空读成"一组都不能碰"会一夜之间把所有普通账号锁在管理面之外，那是另一个决定）；
     * 其余情况必须命中列表里的某一组。非数字的 token（{@code "1,abc,2"}）按"不匹配"处理而不是抛异常：
     * 这一列是人手填的，判据不能因为一个错字就整个接口 500。
     */
    public boolean permits(int jobGroup) {
        return unrestricted() || declaredGroups().contains(jobGroup);
    }

    /**
     * 这个身份是不是"一组都不受限制"（管理员，或 {@code permission} 留空）。
     * <p>
     * {@link #permits(int)} 对这两者恒真，所以想知道"到底能碰哪几组"必须先问这一句：留空的账号
     * 展开 {@link #declaredGroups()} 得到的是空列表，拿它去逐组查询会把一个本该看见全部的人裁成
     * 什么都看不见。
     */
    public boolean unrestricted() {
        return isAdmin() || permission == null || permission.trim().isEmpty();
    }

    /**
     * {@code permission} 里那几组，去掉非数字的 token 并按填写顺序去重
     * （{@code "2,1,2"} 是两组，不是三次同样的查询）。
     * <p>
     * 不受限的身份这里返回<b>空列表</b>——"不限"不等于"全部组"，调用方要自己先判
     * {@link #unrestricted()}。
     */
    public List<Integer> declaredGroups() {
        List<Integer> groups = new ArrayList<Integer>();
        if (permission == null) {
            return groups;
        }
        for (String token : permission.split(",")) {
            Integer parsed = parseGroup(token);
            if (parsed != null && !groups.contains(parsed)) {
                groups.add(parsed);
            }
        }
        return groups;
    }

    /** 一个 token 是不是某一组；错字返回 {@code null}（不匹配），不抛。 */
    static Integer parseGroup(String token) {
        if (token == null) {
            return null;
        }
        try {
            return Integer.valueOf(token.trim());
        } catch (NumberFormatException e) {
            return null;
        }
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
