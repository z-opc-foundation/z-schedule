package com.zifang.z.schedule.web.controller;

import com.zifang.z.schedule.core.model.JobInfo;
import com.zifang.z.schedule.core.model.JobLog;
import com.zifang.z.schedule.core.model.ReturnT;
import com.zifang.z.schedule.web.service.JobInfoService;
import com.zifang.z.schedule.web.service.JobLogService;
import org.junit.Test;

import javax.servlet.http.HttpServletRequest;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import static com.zifang.z.schedule.web.auth.TestRequests.anonymous;
import static com.zifang.z.schedule.web.auth.TestRequests.describe;
import static com.zifang.z.schedule.web.auth.TestRequests.scoped;
import static com.zifang.z.schedule.web.auth.TestRequests.session;
import static com.zifang.z.schedule.web.auth.TestRequests.sharedSecret;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * {@link JobLogController} 四条口上的按组收口。
 * <p>
 * 判据是日志行上的 {@code job_group}（不是任务那一行的）——派发路径把组写死成 0 的话，
 * 这一层的列表过滤和逐组合并会把那些行变成对所有组隐形的行，而界面上看不出任何异常。
 * <p>
 * 替身刻意复刻了 {@code JobLogServiceImpl.query} 的两件事：按 {@code (trigger_time desc, id desc)}
 * 排序，以及把 limit 收窄到 {@link JobLogService#effectiveLimit(int)}。收窄这条尤其要复刻——
 * "逐组各查一页再合并"的超发缺陷就长在<b>调用方没跟着收窄</b>上，替身不收窄那例就永远是绿的。
 */
public class JobLogControllerGroupAccessTest {

    private final FakeJobLogService logs = new FakeJobLogService();
    private final FakeJobInfoService infos = new FakeJobInfoService();
    private final JobLogController controller = controllerWith(logs, infos);

    /**
     * 组 1 三行（id 11/12/13，调度时间 10/30/50 分），组 2 三行（id 21/22/23，时间 20/40/60 分）。
     * 两组的时间交错，所以"全局最新在前"与"各组内部最新在前"给出不同顺序——合并那几例才有牙。
     */
    private void seedRows() {
        logs.rows.addAll(seed());
        infos.store.put(11, jobInfo(11, 1));
        infos.store.put(21, jobInfo(21, 2));
    }

    // ---- /joblog/list ----

    @Test
    public void 点名别的组是拒绝且一次都不查() {
        seedRows();

        ReturnT<List<JobLog>> denied = controller.list(0, 2, 0, 50, scoped("1"));

        assertFalse(denied.isSuccess());
        assertTrue("理由要指名缺的是哪一列的哪个值: " + denied.getMsg(), denied.getMsg().contains("jobGroup=2"));
        assertTrue(denied.getMsg().contains("permission"));
        assertTrue("闸必须落在查询之前（给空表会让人以为这组没日志）: " + logs.queryCalls,
                logs.queryCalls.isEmpty());

        // 阳性对照：自己的组照常走 service，且带上那个组
        ReturnT<List<JobLog>> mine = controller.list(0, 1, 0, 50, scoped("1"));
        assertTrue(mine.isSuccess());
        assertEquals(Arrays.asList("query:1/0/-1/50"), logs.queryCalls);
        assertEquals(Arrays.asList(13L, 12L, 11L), ids(mine.getContent()));
    }

    @Test
    public void 不限组时逐组各查一页而不是全局查一页再裁() {
        seedRows();

        // 只给组 1 权限、要 2 条：全局最新两条是 23(60分) 和 13(50分)，"先全局查再裁剪"只剩 13 一条，
        // 别的组再密一点就能裁成空表——而库里明明还有这个人碰得到的行。
        ReturnT<List<JobLog>> result = controller.list(0, 0, 0, 2, scoped("1"));

        assertTrue(result.isSuccess());
        assertEquals("必须拿到组 1 自己最新的两条: " + ids(result.getContent()),
                Arrays.asList(13L, 12L), ids(result.getContent()));
        assertEquals("按允许的组各查一次，不许出现不限组的那一次查询: " + logs.queryCalls,
                Arrays.asList("query:1/0/-1/2"), logs.queryCalls);
    }

    @Test
    public void 多组权限的合并结果按调度时间倒序() {
        seedRows();

        ReturnT<List<JobLog>> result = controller.list(0, 0, 0, 4, scoped("1,2"));

        assertEquals(Arrays.asList("query:1/0/-1/4", "query:2/0/-1/4"), logs.queryCalls);
        assertEquals("跨组取最新的四条: " + ids(result.getContent()),
                Arrays.asList(23L, 13L, 22L, 12L), ids(result.getContent()));
    }

    @Test
    public void limit超过硬上限时逐组合并不许超发() {
        // 每组 600 行、请求 limit=5000：实现侧把每组收窄到 1000（两组各回 600），合并得 1200 行。
        // 调用方按原始 limit 截就是超发（1200 > 上限语义），必须按收窄后的 1000 截。
        for (int i = 0; i < 600; i++) {
            logs.rows.add(log(1000 + i, 1, 11, i));
            logs.rows.add(log(2000 + i, 2, 21, i));
        }

        ReturnT<List<JobLog>> result = controller.list(0, 0, 0, 5000, scoped("1,2"));

        assertEquals(Arrays.asList("query:1/0/-1/1000", "query:2/0/-1/1000"), logs.queryCalls);
        assertEquals("返回条数不得超过收窄后的上限: " + result.getContent().size(),
                JobLogService.MAX_PAGE_SIZE, result.getContent().size());
    }

    @Test
    public void 调度时间为空的行排在最后而不是把合并撞死() {
        // 组 2 那两行 trigger_time 为空：库里它们会落在最新一页的末尾（替身按同一规则排），
        // 合并这一侧必须既不被 null 撞出 NPE，也不把它们当成"最新"顶到有名有姓的那两条前面。
        logs.rows.add(log(11, 1, 11, 10));
        logs.rows.add(log(12, 1, 11, 30));
        logs.rows.add(log(21, 2, 21, -1));
        logs.rows.add(log(22, 2, 21, -1));

        ReturnT<List<JobLog>> result = controller.list(0, 0, 0, 3, scoped("1,2"));

        assertEquals(Arrays.asList("query:1/0/-1/3", "query:2/0/-1/3"), logs.queryCalls);
        assertEquals("空时间的行不该顶掉有名有姓的那两条: " + ids(result.getContent()),
                Arrays.asList(12L, 11L, 22L), ids(result.getContent()));
    }

    @Test
    public void 按jobId过滤时判的是任务现在那一行() {
        seedRows();

        ReturnT<List<JobLog>> denied = controller.list(21, 0, 0, 50, scoped("1"));
        assertFalse(denied.isSuccess());
        assertTrue(denied.getMsg().contains("jobGroup=2"));
        assertTrue("组是从任务那一行解析的: " + infos.calls, infos.calls.contains("getById:21"));
        assertTrue("拒绝不许落在查了之后: " + logs.queryCalls, logs.queryCalls.isEmpty());

        ReturnT<List<JobLog>> mine = controller.list(21, 0, 0, 50, scoped("2"));
        assertTrue(mine.isSuccess());
        assertEquals(Arrays.asList("query:0/21/-1/50"), logs.queryCalls);
        assertEquals(Arrays.asList(23L, 22L, 21L), ids(mine.getContent()));
    }

    @Test
    public void 解析不出组的jobId过滤只能拒绝() {
        seedRows();

        // 库里没有这个任务 ⇒ 分权会话无从判组。这里和 /jobinfo 相反：那一条口是读取，存在与否由
        // service 说话；这一条是"把这个 job 的全部日志给你"，判不了组就不能给。
        ReturnT<List<JobLog>> denied = controller.list(999, 0, 0, 50, scoped("1"));
        assertFalse(denied.isSuccess());
        assertTrue(denied.getMsg().contains("999"));
        assertTrue(logs.queryCalls.isEmpty());

        // 阳性对照：不受约束的身份保持原行为（查得到多少算多少，没有行就是空表）
        assertTrue(controller.list(999, 0, 0, 50, anonymous()).isSuccess());
    }

    @Test
    public void 这一列是人手填的所以逐组展开只认数字() {
        seedRows();

        ReturnT<List<JobLog>> mixed = controller.list(0, 0, 0, 50, scoped("1, abc ,2"));
        assertTrue(mixed.isSuccess());
        assertEquals("错字那一段只是不匹配，两组照常各查一次: " + logs.queryCalls,
                Arrays.asList("query:1/0/-1/50", "query:2/0/-1/50"), logs.queryCalls);
        assertEquals(6, mixed.getContent().size());

        logs.queryCalls.clear();
        ReturnT<List<JobLog>> garbage = controller.list(0, 0, 0, 50, scoped("abc"));
        assertTrue("整列错字不是接口 500: " + garbage.getMsg(), garbage.isSuccess());
        assertTrue(garbage.getContent().isEmpty());
        assertTrue("展开为空就不该有任何查询: " + logs.queryCalls, logs.queryCalls.isEmpty());
        assertFalse("同一份脏列表，点名那一组仍然要拦住",
                controller.list(0, 1, 0, 50, scoped("abc")).isSuccess());
    }

    // ---- /joblog/get 与 /joblog/executionLog ----

    @Test
    public void 读单条日志与执行日志都认行上那一组() {
        seedRows();
        HttpServletRequest peon = scoped("1");

        ReturnT<JobLog> denied = controller.getById(21L, null, peon);
        assertFalse(denied.isSuccess());
        assertTrue(denied.getMsg().contains("jobGroup=2"));

        ReturnT<JobLog> mine = controller.getById(11L, null, peon);
        assertTrue(mine.isSuccess());
        assertEquals(11L, mine.getContent().getId());

        ReturnT<Map<String, Object>> deniedLog = controller.executionLog(23L, 0, peon);
        assertFalse(deniedLog.isSuccess());
        assertTrue(deniedLog.getMsg().contains("jobGroup=2"));

        ReturnT<Map<String, Object>> mineLog = controller.executionLog(13L, 0, peon);
        assertTrue(mineLog.isSuccess());
        assertEquals("执行日志不能因为换了个口就漏判组", "body-13", mineLog.getContent().get("content"));
    }

    @Test
    public void 不存在的日志仍说不存在而不是无权() {
        seedRows();

        assertTrue(controller.getById(999L, null, scoped("1")).getMsg().contains("日志不存在"));
        assertTrue(controller.executionLog(999L, 0, scoped("1")).getMsg().contains("日志不存在"));
    }

    // ---- /joblog/clear ----

    @Test
    public void 清空全部对分权会话直接拒绝() {
        seedRows();

        ReturnT<String> denied = controller.clear(body(0, 0), scoped("1"));
        assertFalse(denied.isSuccess());
        assertTrue("理由要说清需要什么凭证: " + denied.getMsg(), denied.getMsg().contains("管理员"));
        assertTrue("拒绝必须落在删之前: " + logs.calls, logs.calls.isEmpty());
        assertEquals("一行都不该少", 6, logs.rows.size());

        // 阳性对照：同一份请求换成全权的四种脸都照旧清得掉
        for (HttpServletRequest request : Arrays.asList(
                anonymous(), sharedSecret(), session(8, "boss", "ADMIN", "1"), session(9, "peon", "NORMAL", ""))) {
            logs.rows.addAll(seed());
            assertTrue(describe(request), controller.clear(body(0, 0), request).isSuccess());
            assertTrue(describe(request) + ": " + logs.calls, logs.calls.contains("clearAll"));
            assertTrue(logs.rows.isEmpty());
        }
    }

    @Test
    public void 按jobId清理判组且解析不出组就不删() {
        seedRows();
        HttpServletRequest peon = scoped("1");

        assertFalse(controller.clear(body(1, 21), peon).isSuccess());
        assertFalse("库里没有的任务：判不了组，也不能顺手把它的行清掉",
                controller.clear(body(1, 999), peon).isSuccess());
        assertFalse(controller.clear(body(1, 0), peon).isSuccess());
        assertTrue("三条拒绝一条删都不该落下去: " + logs.calls, logs.calls.isEmpty());
        assertEquals(6, logs.rows.size());

        // 阳性对照：自己那组的任务照清
        assertTrue(controller.clear(body(1, 11), peon).isSuccess());
        assertEquals(Arrays.asList("clearByJobId:11"), logs.calls);
        assertEquals(Arrays.asList(21L, 22L, 23L), ids(logs.rows));
        assertEquals("判组只读任务那一行，且 jobId<=0 时根本不读: " + infos.calls,
                Arrays.asList("getById:21", "getById:999", "getById:11"), infos.calls);
    }

    @Test
    public void 不受约束的身份不为判组多打一次库() {
        seedRows();

        for (HttpServletRequest request : Arrays.asList(
                anonymous(), sharedSecret(), session(8, "boss", "ADMIN", "1"), session(9, "peon", "NORMAL", ""))) {
            infos.calls.clear();
            logs.queryCalls.clear();

            assertTrue(describe(request), controller.list(0, 0, 0, 50, request).isSuccess());
            assertEquals("一次不限组的全局查询就够: " + logs.queryCalls,
                    Arrays.asList("query:0/0/-1/50"), logs.queryCalls);
            assertTrue("留空/管理员/密钥/匿名都不该去读任务那一行: " + infos.calls, infos.calls.isEmpty());
            assertEquals(6, ids(controller.list(0, 0, 0, 50, request).getContent()).size());

            assertTrue(describe(request), controller.getById(21L, null, request).isSuccess());
            assertEquals(21L, controller.getById(21L, null, request).getContent().getId());
        }
    }

    // ---- 替身 ----

    private static List<JobLog> seed() {
        List<JobLog> rows = new ArrayList<JobLog>();
        rows.add(log(11, 1, 11, 10));
        rows.add(log(12, 1, 11, 30));
        rows.add(log(13, 1, 11, 50));
        rows.add(log(21, 2, 21, 20));
        rows.add(log(22, 2, 21, 40));
        rows.add(log(23, 2, 21, 60));
        return rows;
    }

    private static List<Long> ids(List<JobLog> rows) {
        List<Long> out = new ArrayList<Long>();
        for (JobLog row : rows) {
            out.add(row.getId());
        }
        return out;
    }

    private static Map<String, Object> body(int type, int jobId) {
        Map<String, Object> body = new HashMap<String, Object>();
        body.put("type", type);
        body.put("jobId", jobId);
        return body;
    }

    /** 最后一个参数是分钟的偏移；负数表示 {@code trigger_time} 为空。 */
    private static JobLog log(long id, int jobGroup, int jobId, int minute) {
        JobLog row = new JobLog();
        row.setId(id);
        row.setJobGroup(jobGroup);
        row.setJobId(jobId);
        row.setTriggerCode(ReturnT.SUCCESS_CODE);
        row.setHandleCode(ReturnT.SUCCESS_CODE);
        row.setHandleMsg("body-" + id);
        if (minute >= 0) {
            row.setTriggerTime(new Date(1_700_000_000_000L + minute * 60_000L));
        }
        return row;
    }

    private static JobInfo jobInfo(int id, int jobGroup) {
        JobInfo jobInfo = new JobInfo();
        jobInfo.setId(id);
        jobInfo.setJobGroup(jobGroup);
        return jobInfo;
    }

    private static JobLogController controllerWith(JobLogService jobLogService, JobInfoService jobInfoService) {
        JobLogController controller = new JobLogController();
        try {
            inject(controller, "jobLogService", jobLogService);
            inject(controller, "jobInfoService", jobInfoService);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
        return controller;
    }

    private static void inject(Object target, String field, Object value)
            throws NoSuchFieldException, IllegalAccessException {
        Field declared = target.getClass().getDeclaredField(field);
        declared.setAccessible(true);
        declared.set(target, value);
    }

    /** 复刻 {@code JobLogServiceImpl.query} 的排序与 limit 收窄，只到"上面那几例判得动"为止。 */
    private static class FakeJobLogService implements JobLogService {
        final List<JobLog> rows = new ArrayList<JobLog>();
        final List<String> calls = new ArrayList<String>();
        final List<String> queryCalls = new ArrayList<String>();

        public List<JobLog> query(int jobGroup, int jobId, int handleCode, int limit) {
            queryCalls.add("query:" + jobGroup + "/" + jobId + "/" + handleCode + "/" + limit);
            int cap = JobLogService.effectiveLimit(limit);
            List<JobLog> out = new ArrayList<JobLog>();
            for (JobLog row : rows) {
                if ((jobGroup <= 0 || row.getJobGroup() == jobGroup)
                        && (jobId <= 0 || row.getJobId() == jobId)
                        && matches(row.getHandleCode(), handleCode)) {
                    out.add(row);
                }
            }
            Collections.sort(out, NEWEST_FIRST);
            return out.size() <= cap ? out : new ArrayList<JobLog>(out.subList(0, cap));
        }

        private static final Comparator<JobLog> NEWEST_FIRST = new Comparator<JobLog>() {
            public int compare(JobLog a, JobLog b) {
                Date left = a.getTriggerTime();
                Date right = b.getTriggerTime();
                if (left == null && right != null) {
                    return 1;
                }
                if (left != null && right == null) {
                    return -1;
                }
                if (left != null && !left.equals(right)) {
                    return right.compareTo(left);
                }
                return Long.compare(b.getId(), a.getId());
            }
        };

        private static boolean matches(int handleCode, int wanted) {
            if (wanted < 0) {
                return true;
            }
            if (wanted == JobLogService.ANY_FAILURE) {
                return handleCode != 0 && handleCode != ReturnT.SUCCESS_CODE;
            }
            return handleCode == wanted;
        }

        public JobLog getById(long id) {
            calls.add("getById:" + id);
            for (JobLog row : rows) {
                if (row.getId() == id) {
                    return row;
                }
            }
            return null;
        }

        public int clearByJobId(int jobId) {
            calls.add("clearByJobId:" + jobId);
            int removed = 0;
            for (Iterator<JobLog> it = rows.iterator(); it.hasNext(); ) {
                if (it.next().getJobId() == jobId) {
                    it.remove();
                    removed++;
                }
            }
            return removed;
        }

        public int clearAll() {
            calls.add("clearAll");
            int removed = rows.size();
            rows.clear();
            return removed;
        }

        public long save(JobLog jobLog) {
            throw new UnsupportedOperationException();
        }

        public void update(JobLog jobLog) {
            throw new UnsupportedOperationException();
        }

        public JobLogService.Stats statsBetween(Date startInclusive, Date endExclusive) {
            throw new UnsupportedOperationException();
        }

        public Map<String, JobLogService.Stats> dailyStatsSince(Date startInclusive) {
            throw new UnsupportedOperationException();
        }

        public int clearLogByDays(int days) {
            throw new UnsupportedOperationException();
        }
    }

    private static class FakeJobInfoService implements JobInfoService {
        final Map<Integer, JobInfo> store = new HashMap<Integer, JobInfo>();
        final List<String> calls = new ArrayList<String>();

        public JobInfo getById(int id) {
            calls.add("getById:" + id);
            return store.get(id);
        }

        public List<JobInfo> getAll() {
            calls.add("getAll");
            return new ArrayList<JobInfo>(store.values());
        }

        public List<JobInfo> getByJobGroup(int jobGroup) {
            throw new UnsupportedOperationException();
        }

        public ReturnT<String> add(JobInfo jobInfo) {
            throw new UnsupportedOperationException();
        }

        public ReturnT<String> update(JobInfo jobInfo) {
            throw new UnsupportedOperationException();
        }

        public ReturnT<String> delete(int id) {
            throw new UnsupportedOperationException();
        }

        public ReturnT<String> stop(int id) {
            throw new UnsupportedOperationException();
        }

        public ReturnT<String> start(int id) {
            throw new UnsupportedOperationException();
        }

        public ReturnT<String> trigger(int id) {
            throw new UnsupportedOperationException();
        }

        public ReturnT<List<String>> nextTriggerTime(String cron) {
            throw new UnsupportedOperationException();
        }

        public List<JobInfo> listRunning() {
            throw new UnsupportedOperationException();
        }

        public void updateTriggerTimes(int jobId, long lastTime, long nextTime) {
            throw new UnsupportedOperationException();
        }
    }
}
