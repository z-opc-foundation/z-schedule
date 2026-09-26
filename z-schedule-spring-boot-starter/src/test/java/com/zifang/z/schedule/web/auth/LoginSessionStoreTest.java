package com.zifang.z.schedule.web.auth;

import com.zifang.z.schedule.web.domain.entity.UserDO;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * {@link LoginSessionStore} 的签发/解析/撤销。
 * <p>
 * 这一层唯一要紧的性质是"令牌换不来身份就等于没登录"，所以每条断言都在问：
 * 出示这把串，回来的到底是不是当初那个人、那个人还在不在有效期里。
 */
public class LoginSessionStoreTest {

    @Test
    public void 签发回来的身份就是库里那一行() {
        LoginSessionStore store = new LoginSessionStore();
        LoginSession session = store.issue(user(7, "zoe", "ADMIN"));

        assertEquals(7, session.getUserId());
        assertEquals("zoe", session.getUsername());
        assertEquals("ADMIN", session.getRole());
        assertTrue(session.isAdmin());
        assertTrue("必须落在未来某个时刻，否则一签发就过期",
                session.getExpireAtMillis() > System.currentTimeMillis());

        LoginSession resolved = store.resolve(session.getToken());
        assertNotNull("自己签的令牌必须解析得回来", resolved);
        assertEquals(7, resolved.getUserId());
    }

    @Test
    public void 令牌是不透明的高熵串而不是用户名() {
        LoginSessionStore store = new LoginSessionStore();
        LoginSession first = store.issue(user(1, "zoe", "NORMAL"));

        assertFalse("用户名是公开信息，绝不能再当凭证用", "zoe".equals(first.getToken()));
        assertTrue("令牌长度要够撑住随机性: " + first.getToken().length(), first.getToken().length() >= 40);
        // 阳性对照：同一个用户重复登录也必须换出不同的串，否则"够长"只是固定值
        assertFalse(first.getToken().equals(store.issue(user(1, "zoe", "NORMAL")).getToken()));
    }

    @Test
    public void 未知令牌解析为空() {
        LoginSessionStore store = new LoginSessionStore();
        store.issue(user(2, "amy", "ADMIN"));

        assertNull(store.resolve("not-a-issued-token"));
        assertNull(store.resolve(null));
        assertNull(store.resolve(""));
    }

    @Test
    public void 过期令牌解析为空并且不再占位() throws Exception {
        LoginSessionStore store = new LoginSessionStore(10, 40L);
        String token = store.issue(user(3, "old", "ADMIN")).getToken();
        assertNotNull(store.resolve(token));

        Thread.sleep(80L);
        assertNull("过期后必须验不过", store.resolve(token));
        assertEquals("过期条目要在被出示时就清掉", 0, store.size());
    }

    @Test
    public void 容量满时逐出最久没被出示的那条() {
        LoginSessionStore store = new LoginSessionStore(2, 60_000L);
        String eldest = store.issue(user(4, "a", "NORMAL")).getToken();
        String newer = store.issue(user(5, "b", "NORMAL")).getToken();

        assertNotNull("碰一次就算最近用过", store.resolve(eldest));
        String third = store.issue(user(6, "c", "NORMAL")).getToken();

        assertNotNull("刚签发的也不能被自己挤掉", store.resolve(third));
        assertNull("该逐出的是最久没被出示的那条", store.resolve(newer));
        assertEquals(2, store.size());
    }

    @Test
    public void 撤销只掉那一条会话() {
        LoginSessionStore store = new LoginSessionStore();
        String one = store.issue(user(8, "dup", "ADMIN")).getToken();
        String two = store.issue(user(8, "dup", "ADMIN")).getToken();

        assertTrue(store.invalidate(one));
        assertNull(store.resolve(one));
        assertNotNull("同一个人的另一台设备不该被连带踢掉", store.resolve(two));
        assertFalse("重复注销回 false，但调用方仍按幂等处理", store.invalidate(one));
    }

    @Test
    public void 撤销用户会踢掉他的全部会话但不动别人() {
        LoginSessionStore store = new LoginSessionStore();
        String mine = store.issue(user(9, "leave", "ADMIN")).getToken();
        String alsoMine = store.issue(user(9, "leave", "ADMIN")).getToken();
        String theirs = store.issue(user(10, "stay", "ADMIN")).getToken();

        assertEquals(2, store.invalidateUser(9));
        assertNull(store.resolve(mine));
        assertNull(store.resolve(alsoMine));
        assertNotNull("删账号不能把别人也下线", store.resolve(theirs));
    }

    @Test
    public void 只有ADMIN角色算管理员() {
        LoginSessionStore store = new LoginSessionStore();

        // 库里历史上存过小写（z-opc 那份迁移脚本的种子行就是 'admin'），判角色不该被大小写绊住
        assertTrue(store.issue(user(11, "boss", "admin")).isAdmin());
        assertTrue(store.issue(user(12, "boss", "ADMIN")).isAdmin());
        assertFalse("普通会话不得被当成管理员", store.issue(user(13, "peon", "NORMAL")).isAdmin());
        assertFalse("角色为空也不能算管理员", store.issue(user(14, "blank", "")).isAdmin());
    }

    @Test
    public void 令牌不进toString() {
        LoginSession session = new LoginSessionStore().issue(user(15, "nick", "ADMIN"));

        assertFalse("这条串会进日志，带上令牌就等于把会话抄进了日志文件",
                session.toString().contains(session.getToken()));
        assertTrue(session.toString().contains("nick"));
    }

    private static UserDO user(int id, String username, String role) {
        UserDO d = new UserDO();
        d.setId(id);
        d.setUsername(username);
        d.setRole(role);
        d.setPermission("");
        return d;
    }
}
