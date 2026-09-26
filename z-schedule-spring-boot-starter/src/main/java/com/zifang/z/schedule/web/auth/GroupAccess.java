package com.zifang.z.schedule.web.auth;

import com.zifang.z.schedule.web.filter.TokenAuthFilter;

import javax.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * 按 jobGroup 收口的那点判断，供各 controller 复用。
 * <p>
 * 只在"出示了会话令牌、且这一列真的写明了组"时才有牙：
 * <ul>
 *   <li>共享密钥 {@code accessToken} 不区分"是谁"（它就是全权，见 {@code TokenAuthFilter}）</li>
 *   <li>未配置 token 的演示模式下匿名请求根本没有身份可问</li>
 *   <li>管理员，以及 {@code permission} 留空的账号——留空在这套代码里的语义就是"不限组"
 *       （理由见 {@link LoginSession#permits(int)}），把它当成"一组都不能碰"会一夜之间把所有
 *       普通账号锁在管理面之外</li>
 * </ul>
 * 这三支都拿 {@code null} 身份，于是走 {@link #restrictable} 直接短路——不为"结构上不可能被限制"
 * 的请求多打一次库。收口范围也只会出现在"这一列写了值"的那批账号身上，所以调用方拿到非空身份时
 * 不需要再问一遍 {@link LoginSession#unrestricted()}。
 */
public final class GroupAccess {

    private GroupAccess() {
    }

    /** 拒绝理由：说清缺的是哪一列的哪个值，让人能自己去补，而不是只回一句"无权"。 */
    public static String denialReason(int jobGroup) {
        return "当前会话没有 jobGroup=" + jobGroup + " 的权限，可访问的组见 z_schedule_user.permission（留空=不限）";
    }

    /**
     * 这个请求需不需要按组收口。
     *
     * @return 需要收口时给出身份；匿名、共享密钥、管理员、{@code permission} 留空都回
     *         {@code null}（表示不受约束）
     */
    public static LoginSession restrictable(HttpServletRequest request) {
        // 没有请求对象 = 不是从 servlet 进来的（测试里直接调 controller），这一层无从可问
        if (request == null) {
            return null;
        }
        LoginSession identity = TokenAuthFilter.currentIdentity(request);
        if (identity == null || identity.unrestricted()) {
            return null;
        }
        return identity;
    }

    /** 裁剪结果集：留下这个身份碰得到的那些组。{@code null} 身份原样返回，不复制一份。 */
    public static <T> List<T> narrow(List<T> rows, LoginSession identity, Function<T, Integer> groupOf) {
        if (identity == null || rows == null || rows.isEmpty()) {
            return rows;
        }
        List<T> kept = new ArrayList<T>(rows.size());
        for (T row : rows) {
            Integer group = row == null ? null : groupOf.apply(row);
            if (identity.permits(group == null ? 0 : group)) {
                kept.add(row);
            }
        }
        return kept;
    }
}
