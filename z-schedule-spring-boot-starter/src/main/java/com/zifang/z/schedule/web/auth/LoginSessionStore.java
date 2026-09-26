package com.zifang.z.schedule.web.auth;

import com.zifang.z.schedule.web.domain.entity.UserDO;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 登录会话表：签发不透明令牌，并把它解析回用户身份。
 * <p>
 * 存在的理由：此前"登录成功"返回的凭证就是用户名本身，而用户名是公开信息
 * （{@code /user/list} 就能列出来），所以那次登录没有交出任何凭据；
 * 同时 {@code role} 列被读回后没有任何读者。这里补的是载体，不是策略——
 * 谁能进门仍由 {@code TokenAuthFilter} 判，本类只管"这个令牌对应哪个用户、还在不在有效期"。
 * <p>
 * 只存在内存里：进程重启即全员重新登录。这是刻意的——把令牌落库等于把"库读写权限"
 * 升级成"管理面身份"，而库里的行是可以被绕过本类直接改的。多实例部署下每台各自签发，
 * 所以 A 机登录换来的令牌在 B 机用不了；要跨实例得先有共享会话存储，那是另一个决定。
 * <p>
 * 过期靠"被出示时才判"（{@link #resolve}）+ 容量上限逐出，没有清扫线程：
 * 一个永不出示的过期条目最多多活到被逐出为止，而它此时已经无法通过 {@link #resolve}。
 * <p>
 * 注册方式：由 {@code ZScheduleAutoConfiguration} 以 {@code @ConditionalOnMissingBean} 给出，
 * 所以宿主想换成共享存储（Redis 之类）时注册一个同类型 bean 即可，不必改本仓源码。
 */
public class LoginSessionStore {

    private static final Logger logger = LogManager.getLogger(LoginSessionStore.class);

    static final int DEFAULT_MAX_SESSIONS = 1000;
    static final long DEFAULT_TTL_MILLIS = 30 * 60 * 1000L;

    /** 256 bit：够让"猜中一个在用的令牌"这件事没有可行的尝试次数，也就不需要定长比较。 */
    private static final int TOKEN_BYTES = 32;

    private final int maxSessions;
    private final long ttlMillis;
    private final SecureRandom random = new SecureRandom();

    /**
     * accessOrder=true：每次 {@code get} 都算"用过"，满了逐出最久没被出示的那条。
     * 按插入序逐出会让先登录的管理员在有人批量建会话时被顶下线，那是个 DoS 面。
     */
    private final Map<String, LoginSession> sessions =
            new LinkedHashMap<String, LoginSession>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, LoginSession> eldest) {
                    return size() > maxSessions;
                }
            };

    public LoginSessionStore() {
        this(DEFAULT_MAX_SESSIONS, DEFAULT_TTL_MILLIS);
    }

    /**
     * 自带容量与有效期的构造。
     * <p>
     * 本版本<b>不</b>从 {@code z.schedule.*} 读这两个值——想改就别改这里：注册一个同类型 bean
     * （{@code ZScheduleAutoConfiguration} 那个 {@code @ConditionalOnMissingBean} 会自动退让）。
     * 一个没人能从配置改到的公开旋钮，只会变成第二个装饰。
     */
    public LoginSessionStore(int maxSessions, long ttlMillis) {
        this.maxSessions = maxSessions;
        this.ttlMillis = ttlMillis;
    }

    /**
     * 为库里的那一行签发会话。
     * <p>
     * 身份一律取自 {@link UserDO}（数据库读回的），不从请求体取：能从请求里自填 role 的
     * 签发，等于把 {@code z_schedule_user.role} 这一列变成第二个装饰。
     */
    public LoginSession issue(UserDO user) {
        String token = nextToken();
        long now = System.currentTimeMillis();
        LoginSession session = new LoginSession(token,
                user.getId() == null ? 0 : user.getId(),
                user.getUsername(),
                user.getRole(),
                user.getPermission(),
                now + ttlMillis);
        synchronized (sessions) {
            sessions.put(token, session);
        }
        return session;
    }

    /**
     * 令牌 → 身份。未知或已过期都返回 {@code null}，不区分（区分就是"哪些令牌在用"的探针）。
     */
    public LoginSession resolve(String token) {
        if (token == null || token.isEmpty()) {
            return null;
        }
        long now = System.currentTimeMillis();
        synchronized (sessions) {
            LoginSession session = sessions.get(token);
            if (session == null) {
                return null;
            }
            if (session.isExpired(now)) {
                sessions.remove(token);
                return null;
            }
            return session;
        }
    }

    /** @return 是否真的撤销了一条在用的会话 */
    public boolean invalidate(String token) {
        if (token == null) {
            return false;
        }
        synchronized (sessions) {
            return sessions.remove(token) != null;
        }
    }

    /**
     * 撤销某个用户的全部会话。
     * <p>
     * 改角色、删账号之后不撤销的话，那个会话还会带着旧身份活到 TTL 结束——
     * "已经删掉的用户"照样能继续调管理面。
     */
    public int invalidateUser(int userId) {
        int removed = 0;
        synchronized (sessions) {
            for (Iterator<Map.Entry<String, LoginSession>> it = sessions.entrySet().iterator(); it.hasNext(); ) {
                if (it.next().getValue().getUserId() == userId) {
                    it.remove();
                    removed++;
                }
            }
        }
        if (removed > 0) {
            logger.info("[z-schedule] 已撤销 userId={} 的 {} 个登录会话", userId, removed);
        }
        return removed;
    }

    public int size() {
        synchronized (sessions) {
            return sessions.size();
        }
    }

    private String nextToken() {
        byte[] raw = new byte[TOKEN_BYTES];
        random.nextBytes(raw);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }
}
