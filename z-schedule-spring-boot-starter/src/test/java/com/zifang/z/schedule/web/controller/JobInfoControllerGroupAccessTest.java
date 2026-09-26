package com.zifang.z.schedule.web.controller;

import com.zifang.z.schedule.core.model.JobInfo;
import com.zifang.z.schedule.core.model.ReturnT;
import com.zifang.z.schedule.web.service.JobInfoService;
import org.junit.Test;

import javax.servlet.http.HttpServletRequest;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static com.zifang.z.schedule.web.auth.TestRequests.anonymous;
import static com.zifang.z.schedule.web.auth.TestRequests.describe;
import static com.zifang.z.schedule.web.auth.TestRequests.scoped;
import static com.zifang.z.schedule.web.auth.TestRequests.session;
import static com.zifang.z.schedule.web.auth.TestRequests.sharedSecret;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * {@link JobInfoController} 上按 jobGroup 收口的那道闸：让 {@code z_schedule_user.permission}
 * 第一次真的有读者。
 * <p>
 * 它和 {@code TokenAuthFilter} 的 {@code ADMIN_ONLY_PATHS} 是两层，判据也不同：那一层问
 * "这个口能不能进"（真 403），这一层问"进来了能不能碰这一组"（HTTP 200 + {@code code:500}，
 * 见 README 坑 23）。演示模式下匿名请求没有身份可问，所以这一层对匿名一律放行 ——
 * 那是已知且写在 README 里的非交付，不是这里的漏洞。
 * <p>
 * 断言有两处是刻意的：<b>拒绝时 service 一次都没被叫</b>（闸必须落在读写之前），
 * 以及<b>不受约束的身份不许为判组多打一次库</b>（否则这道闸的代价摊在每次管理员操作上）。
 */
public class JobInfoControllerGroupAccessTest {

    private final RecordingJobInfoService service = new RecordingJobInfoService();
    private final JobInfoController controller = controllerWith(service);

    /** 库里三行任务：组 1 两行（自己的），组 2 一行（别人的）。 */
    private void seedRows() {
        service.rows.add(job(11, 1));
        service.rows.add(job(12, 1));
        service.rows.add(job(21, 2));
    }

    @Test
    public void 受限会话的列表只剩自己那一组() {
        seedRows();

        ReturnT<List<JobInfo>> result = controller.list(0, scoped("1"));

        assertTrue(result.isSuccess());
        assertEquals("组 2 那行不该出现在这一页上", Arrays.asList(11, 12), ids(result.getContent()));
        assertTrue("裁剪发生在返回前，不是替数据库少查", service.calls.contains("getAll"));
    }

    @Test
    public void 点名要别的组是拒绝而不是空表() {
        seedRows();

        ReturnT<List<JobInfo>> denied = controller.list(2, scoped("1"));

        assertFalse(denied.isSuccess());
        assertTrue("理由要指名缺的是哪一列的哪个值: " + denied.getMsg(), denied.getMsg().contains("jobGroup=2"));
        assertTrue(denied.getMsg().contains("permission"));
        assertFalse("给空表会让人以为组里没任务，所以闸必须落在查询之前",
                service.calls.contains("getByJobGroup:2"));

        // 阳性对照：自己的组照常走 service —— 否则上面那条红只是"list 全拦"
        ReturnT<List<JobInfo>> mine = controller.list(1, scoped("1"));
        assertTrue(mine.isSuccess());
        assertTrue(service.calls.contains("getByJobGroup:1"));
        assertEquals(Arrays.asList(11, 12), ids(mine.getContent()));
    }

    @Test
    public void 建任务也建不到别人的组里() {
        seedRows();
        HttpServletRequest peon = scoped("1");

        ReturnT<String> denied = controller.add(job(0, 2), peon);
        assertFalse(denied.isSuccess());
        assertTrue(denied.getMsg().contains("jobGroup=2"));
        assertTrue("拒绝必须落在写之前: " + service.calls, mutations().isEmpty());

        // 阳性对照：同一个人往自己的组里建，就得落到 service
        assertTrue(controller.add(job(0, 1), peon).isSuccess());
        assertEquals(Arrays.asList("add:0"), mutations());
    }

    @Test
    public void 读单个任务也认库里那一行() {
        seedRows();
        HttpServletRequest peon = scoped("1");

        ReturnT<JobInfo> denied = controller.getById(21, null, peon);
        assertFalse(denied.isSuccess());
        assertTrue(denied.getMsg().contains("jobGroup=2"));

        ReturnT<JobInfo> mine = controller.getById(11, null, peon);
        assertTrue(mine.isSuccess());
        assertEquals(11, mine.getContent().getId());
    }

    @Test
    public void 删停启触发四支都不越组() {
        seedRows();
        HttpServletRequest peon = scoped("1");

        assertFalse(controller.remove(21, null, peon).isSuccess());
        assertFalse(controller.stop(21, null, peon).isSuccess());
        assertFalse(controller.start(21, null, peon).isSuccess());
        assertFalse(controller.trigger(21, null, peon).isSuccess());
        assertTrue("拒绝必须落在写之前: " + service.calls, mutations().isEmpty());
        assertEquals("每一支都只解析了一次库里那一行", Arrays.asList(
                "getById:21", "getById:21", "getById:21", "getById:21"), service.calls);

        // 阳性对照：同一次调用换成自己那组的 id，四支都得落到 service
        assertTrue(controller.remove(11, null, peon).isSuccess());
        assertTrue(controller.stop(12, null, peon).isSuccess());
        assertTrue(controller.start(11, null, peon).isSuccess());
        assertTrue(controller.trigger(12, null, peon).isSuccess());
        assertEquals(Arrays.asList("delete:11", "stop:12", "start:11", "trigger:12"), mutations());
    }

    @Test
    public void 改组要过两道判据() {
        seedRows();
        HttpServletRequest peon = scoped("1");

        // 第一道：请求体里写着看得见的组，但库里那一行是别人的 ⇒ 不能把人家的任务挪进来
        assertFalse(controller.update(job(21, 1), peon).isSuccess());
        // 第二道：库里那行看得见，但请求体把它改到看不见的组 ⇒ 那是"藏到别人身后"
        assertFalse(controller.update(job(11, 2), peon).isSuccess());
        assertTrue("两道都该落在写之前: " + service.calls, mutations().isEmpty());

        // 阳性对照：不动组就是普通更新
        assertTrue(controller.update(job(11, 1), peon).isSuccess());
        assertEquals(Arrays.asList("update:11"), mutations());
    }

    @Test
    public void 管理员共享密钥与空permission都不收口() {
        seedRows();

        for (HttpServletRequest request : Arrays.asList(
                anonymous(), sharedSecret(), session(8, "boss", "ADMIN", "1"),
                session(9, "peon", "NORMAL", ""))) {
            ReturnT<List<JobInfo>> list = controller.list(0, request);
            assertTrue(list.isSuccess());
            assertEquals("这一支不该被裁剪: " + describe(request), Arrays.asList(11, 12, 21), ids(list.getContent()));
        }
    }

    @Test
    public void 不受约束的身份不为判组多打一次库() {
        seedRows();

        for (HttpServletRequest request : Arrays.asList(
                anonymous(), sharedSecret(), session(8, "boss", "ADMIN", "1"), session(9, "peon", "NORMAL", ""))) {
            service.calls.clear();
            assertTrue(describe(request), controller.remove(21, null, request).isSuccess());
            assertTrue("管理员/密钥/匿名/留空都不需要读那一行来判组: " + describe(request),
                    !service.calls.contains("getById:21"));
            assertEquals(Arrays.asList("delete:21"), mutations());
        }
    }

    @Test
    public void 这一列是人手填的所以解析只容错不抛() {
        seedRows();

        // 空格 + 错字：错字那一段只是不匹配，别把接口 500。命中的一侧照放行。
        assertTrue(controller.list(2, scoped("1, abc ,2")).isSuccess());
        assertTrue(controller.getById(11, null, scoped("1, abc ,2")).isSuccess());
        // 同一份脏列表，不命中的那一组仍然要拦住（否则上面那条绿只是"解析崩了改成放行"）
        ReturnT<JobInfo> dirtyDenial = controller.getById(21, null, scoped("abc, 1"));
        assertFalse(dirtyDenial.isSuccess());
        assertTrue(dirtyDenial.getMsg().contains("jobGroup=2"));

        // 整列都是错字 ⇒ 一组都碰不到，但拒绝理由仍然说清是组的事
        ReturnT<List<JobInfo>> none = controller.list(1, scoped("abc"));
        assertFalse(none.isSuccess());
        assertTrue(none.getMsg().contains("jobGroup=1"));
    }

    @Test
    public void 不存在的任务仍由service说话() {
        seedRows();
        HttpServletRequest peon = scoped("1");

        // 闸在这里不能替"存在与否"表态：越组的前提是那一行存在
        assertTrue(controller.remove(999, null, peon).isSuccess());
        assertEquals(Arrays.asList("getById:999", "delete:999"), service.calls);

        ReturnT<JobInfo> missing = controller.getById(999, null, peon);
        assertFalse(missing.isSuccess());
        assertTrue("该回\"任务不存在\"，不是\"没有权限\": " + missing.getMsg(),
                missing.getMsg().contains("任务不存在"));
    }

    // ---- 替身 ----

    private List<Integer> ids(List<JobInfo> rows) {
        List<Integer> out = new ArrayList<Integer>();
        for (JobInfo row : rows) {
            out.add(row.getId());
        }
        return out;
    }

    /** 只留下会改动库的那些调用，用来证明"拒绝落在写之前"。 */
    private List<String> mutations() {
        List<String> out = new ArrayList<String>();
        for (String call : service.calls) {
            if (!call.startsWith("getById") && !call.startsWith("getAll") && !call.startsWith("getByJobGroup")) {
                out.add(call);
            }
        }
        return out;
    }

    private static JobInfo job(int id, int jobGroup) {
        JobInfo jobInfo = new JobInfo();
        jobInfo.setId(id);
        jobInfo.setJobGroup(jobGroup);
        return jobInfo;
    }

    private static JobInfoController controllerWith(JobInfoService jobInfoService) {
        JobInfoController controller = new JobInfoController();
        try {
            Field field = JobInfoController.class.getDeclaredField("jobInfoService");
            field.setAccessible(true);
            field.set(controller, jobInfoService);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        return controller;
    }

    private static class RecordingJobInfoService implements JobInfoService {
        final List<String> calls = new ArrayList<String>();
        final List<JobInfo> rows = new ArrayList<JobInfo>();

        private JobInfo find(int id) {
            for (JobInfo row : rows) {
                if (row.getId() == id) {
                    return row;
                }
            }
            return null;
        }

        public JobInfo getById(int id) {
            calls.add("getById:" + id);
            return find(id);
        }

        public List<JobInfo> getAll() {
            calls.add("getAll");
            return new ArrayList<JobInfo>(rows);
        }

        public List<JobInfo> getByJobGroup(int jobGroup) {
            calls.add("getByJobGroup:" + jobGroup);
            List<JobInfo> out = new ArrayList<JobInfo>();
            for (JobInfo row : rows) {
                if (row.getJobGroup() == jobGroup) {
                    out.add(row);
                }
            }
            return out;
        }

        public ReturnT<String> add(JobInfo jobInfo) {
            calls.add("add:" + jobInfo.getId());
            return ReturnT.success();
        }

        public ReturnT<String> update(JobInfo jobInfo) {
            calls.add("update:" + jobInfo.getId());
            return ReturnT.success();
        }

        public ReturnT<String> delete(int id) {
            calls.add("delete:" + id);
            return ReturnT.success();
        }

        public ReturnT<String> stop(int id) {
            calls.add("stop:" + id);
            return ReturnT.success();
        }

        public ReturnT<String> start(int id) {
            calls.add("start:" + id);
            return ReturnT.success();
        }

        public ReturnT<String> trigger(int id) {
            calls.add("trigger:" + id);
            return ReturnT.success();
        }

        public ReturnT<List<String>> nextTriggerTime(String cron) {
            calls.add("nextTriggerTime");
            return ReturnT.success(new ArrayList<String>());
        }

        public List<JobInfo> listRunning() {
            calls.add("listRunning");
            return new ArrayList<JobInfo>(rows);
        }

        public void updateTriggerTimes(int jobId, long lastTime, long nextTime) {
            calls.add("updateTriggerTimes:" + jobId);
        }
    }
}
