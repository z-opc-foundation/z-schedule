package com.zifang.z.schedule.web.auth;

import com.zifang.z.schedule.web.domain.entity.UserDO;
import org.junit.Test;

import java.util.Arrays;

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

    @Test
    public void permission列进了身份就参与判定() {
        LoginSessionStore store = new LoginSessionStore();

        // 这一列从 UserDO 到 LoginSession 的那一段：签发时漏带，后面所有分权判据都成了摆设
        assertEquals("1,2", store.issue(user(20, "peon", "NORMAL", "1,2")).getPermission());
        assertNull("库里那一列可以为 NULL，原样带过来，和空串一样算不限",
                store.issue(user(21, "peon", "NORMAL", null)).getPermission());

        LoginSession scoped = store.issue(user(22, "peon", "NORMAL", "1, abc ,2"));
        assertTrue(scoped.permits(1));
        assertTrue("逗号两侧的空格要吃得掉，这一列是人手填的", scoped.permits(2));
        assertFalse(scoped.permits(3));
        assertFalse("非数字那一段只是不匹配，不等于全都算", scoped.permits(99));

        assertTrue("留空=不限：现存 NORMAL 账号这一列全是空串",
                store.issue(user(23, "peon", "NORMAL", "")).permits(7));
        // 管理员这一支由 permits 自己认账。调用方（GroupAccess.restrictable）现在会提前短路，
        // 但 permits 是公开的判据，它的成立不能靠"恰好没人那么调"。
        assertTrue("role 优先于 permission 列",
                store.issue(user(24, "boss", "ADMIN", "1")).permits(2));
    }

    @Test
    public void 展开的组集合与点名的判据是同一份() {
        LoginSessionStore store = new LoginSessionStore();

        LoginSession scoped = store.issue(user(25, "peon", "NORMAL", "2,1,2"));
        assertEquals("去重并保持填写顺序：逐组查询不该对同一组打两次库",
                Arrays.asList(2, 1), scoped.declaredGroups());
        assertFalse(scoped.unrestricted());
        for (Integer group : scoped.declaredGroups()) {
            assertTrue("展开出来的每一组，点名也必须认: " + group, scoped.permits(group));
        }
        assertFalse("没写进那一列的组，两边都不认", scoped.permits(3));

        LoginSession dirty = store.issue(user(26, "peon", "NORMAL", "abc"));
        assertTrue("整列错字 ⇒ 展开为空", dirty.declaredGroups().isEmpty());
        assertFalse("展开为空和 permits 恒假是同一件事的两面，不许一面真一面假", dirty.permits(1));
        assertFalse("留了值就不算不限", dirty.unrestricted());

        LoginSession blank = store.issue(user(27, "peon", "NORMAL", ""));
        assertTrue(blank.unrestricted());
        assertTrue("留空=不限，展开出来的是空列表而不是『所有组』——拿空列表逐组查会把一个"
                + "本该看见全部的人裁成什么都看不见", blank.declaredGroups().isEmpty());
        assertTrue(store.issue(user(28, "boss", "ADMIN", "1")).unrestricted());
    }

    @Test
    public void 带前导零的组号两边给同一个答案() {
        LoginSession scoped = new LoginSessionStore().issue(user(29, "peon", "NORMAL", "01"));

        // job_group 在库里是 int：'01' 写的就是第 1 组。按字符串比会把这个人挡在看得见的那组外面，
        // 按数字展开又会在列表里给他看——两把尺必须同一份解析。
        assertEquals(Arrays.asList(1), scoped.declaredGroups());
        assertTrue(scoped.permits(1));
        assertFalse(scoped.permits(2));
    }

    private static UserDO user(int id, String username, String role) {
        return user(id, username, role, "");
    }

    private static UserDO user(int id, String username, String role, String permission) {
        UserDO d = new UserDO();
        d.setId(id);
        d.setUsername(username);
        d.setRole(role);
        d.setPermission(permission);
        return d;
    }
}
