package com.zifang.z.schedule.web.controller;

import com.zifang.z.schedule.core.model.JobGroup;
import com.zifang.z.schedule.core.model.JobInfo;
import com.zifang.z.schedule.core.model.JobLog;
import com.zifang.z.schedule.core.model.ReturnT;
import com.zifang.z.schedule.web.service.ExecutorRegistryService;
import com.zifang.z.schedule.web.service.JobGroupService;
import com.zifang.z.schedule.web.service.JobInfoService;
import com.zifang.z.schedule.web.service.JobLogService;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Field;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;

/**
 * Dashboard / 日志列表的读接口契约。
 * <p>
 * 重点钉两件事：
 * <ul>
 *   <li>统计只走数据库侧聚合，任何端点都不允许再把整张 job_log 拉进 JVM
 *       （旧实现 {@code query(0,0,-1,0)} 无 LIMIT 全表 SELECT，页签每刷一次扫一次）</li>
 *   <li>{@code status=2}（失败）覆盖所有失败码，而不只是 500</li>
 * </ul>
 */
public class ScheduleDashboardControllerTest {

    private ScheduleDashboardController dashboard;
    private JobLogController jobLogController;
    private FakeJobLogService logs;
    private FakeJobInfoService infos;

    @Before
    public void setUp() throws Exception {
        dashboard = new ScheduleDashboardController();
        jobLogController = new JobLogController();
        logs = new FakeJobLogService();
        infos = new FakeJobInfoService();
        inject(dashboard, "jobInfoService", infos);
        inject(dashboard, "jobLogService", logs);
        inject(dashboard, "registryService", new FakeRegistryService());
        inject(dashboard, "jobGroupService", new FakeJobGroupService());
        inject(jobLogController, "jobLogService", logs);
    }

    // ---- /stats ----

    @Test
    public void 今日统计走聚合查询且不取日志行() {
        logs.stats = new JobLogService.Stats(1000L, 850L);
        infos.store.put(1, job(1, 1, 1));
        infos.store.put(2, job(2, 1, 0));
        infos.store.put(3, job(3, 2, 1));

        Map<String, Object> stats = dashboard.stats().getContent();

        assertEquals("整表拉取一旦被调用就该失败", 0, logs.queryCalls);
        assertEquals(1, logs.statsCalls);
        assertEquals(3, stats.get("jobCount"));
        assertEquals(2L, stats.get("runningJobCount"));
        assertEquals(1000L, stats.get("todayTriggerCount"));
        assertEquals(85, stats.get("successRate"));
        assertEquals(15, stats.get("failRate"));
    }

    @Test
    public void 统计区间正好是本地今天的零点到现在() {
        logs.stats = new JobLogService.Stats(0L, 0L);
        dashboard.stats();

        assertEquals(1, logs.statsCalls);
        Calendar expected = GregorianCalendar.getInstance();
        expected.set(Calendar.HOUR_OF_DAY, 0);
        expected.set(Calendar.MINUTE, 0);
        expected.set(Calendar.SECOND, 0);
        expected.set(Calendar.MILLISECOND, 0);
        assertEquals("区间左端 = 今日 00:00", expected.getTime(), logs.statsStart);
        expected.add(Calendar.DAY_OF_YEAR, 1);
        assertEquals("右端开区间 = 明日 00:00", expected.getTime(), logs.statsEnd);
    }

    @Test
    public void 无调度时成功率为零而不是百分之一百() {
        logs.stats = new JobLogService.Stats(0L, 0L);
        Map<String, Object> stats = dashboard.stats().getContent();
        assertEquals(0, stats.get("successRate"));
        assertEquals(100, stats.get("failRate"));
    }

    // ---- /successRateTrend ----

    @Test
    public void 趋势一次查询覆盖全部天数() {
        logs.daily = daily(todayOffset(0), 10L, 9L, todayOffset(-1), 4L, 2L);

        List<Map<String, Object>> trend = dashboard.successRateTrend(7).getContent();

        assertEquals(7, trend.size());
        assertEquals("天数不得变成 N 次查询", 1, logs.dailyCalls);
        assertEquals(0, logs.queryCalls);
        assertEquals(label(todayOffset(-6)), trend.get(0).get("date"));
        assertEquals(label(todayOffset(0)), trend.get(6).get("date"));
        assertEquals(90, trend.get(6).get("successRate"));
        assertEquals(10L, trend.get(6).get("total"));
        assertEquals(50, trend.get(5).get("successRate"));
        assertEquals("没有调度记录的日子沿用 100% 约定", 100, trend.get(4).get("successRate"));
        assertEquals(0L, trend.get(4).get("total"));
    }

    @Test
    public void 趋势只回看请求窗口内的天() {
        logs.daily = daily(todayOffset(-9), 8L, 8L);
        List<Map<String, Object>> trend = dashboard.successRateTrend(3).getContent();

        assertEquals(3, trend.size());
        assertEquals("窗口外的计数不参与", 0L, trend.get(2).get("total"));
        assertEquals(offsetDay(-2), logs.dailyStart);
    }

    @Test
    public void 趋势天数被夹在合法区间() {
        logs.daily = new LinkedHashMap<String, JobLogService.Stats>();
        assertEquals(1, dashboard.successRateTrend(0).getContent().size());
        assertEquals(1, dashboard.successRateTrend(-5).getContent().size());
        assertEquals(366, dashboard.successRateTrend(100000).getContent().size());
    }

    // ---- /scheduleRecords ----

    @Test
    public void 调度记录透传上限且带上任务描述() {
        logs.rows = new ArrayList<>();
        logs.rows.add(log(11, 200, hourAgo(1)));
        logs.rows.add(log(99, 502, hourAgo(2)));
        infos.store.put(11, job(11, 1, 1));

        List<Map<String, Object>> records = dashboard.scheduleRecords(20).getContent();

        assertEquals(20, logs.lastLimit);
        assertEquals(2, records.size());
        assertEquals("demo job", records.get(0).get("jobDesc"));
        assertEquals("#99", records.get(1).get("jobDesc"));
        assertEquals("success", records.get(0).get("status"));
        assertEquals("fail", records.get(1).get("status"));
    }

    @Test
    public void 正在执行中的记录不算失败() {
        logs.rows = new ArrayList<>();
        logs.rows.add(log(11, 0, hourAgo(1)));
        assertEquals("running", dashboard.scheduleRecords(5).getContent().get(0).get("status"));
    }

    // ---- /dashboard 总览 ----

    @Test
    public void 总览给出分组数与最近五个任务() {
        for (int i = 1; i <= 7; i++) {
            JobInfo job = job(i, i % 2, 1);
            job.setUpdateTime(new Date(1_700_000_000_000L + i * 1000L));
            infos.store.put(i, job);
        }

        Map<String, Object> result = dashboard.dashboard();

        assertEquals(true, result.get("success"));
        Map<String, Object> data = castMap(result.get("data"));
        assertEquals(7, data.get("totalJobs"));
        assertEquals(7L, data.get("runningJobs"));
        assertEquals(2, data.get("groupCount"));
        List<?> recent = (List<?>) result.get("recentJobs");
        assertEquals(5, recent.size());
        assertEquals(7, ((JobInfo) recent.get(0)).getId());
        assertEquals(3, ((JobInfo) recent.get(4)).getId());
    }

    // ---- /joblog/list ----

    @Test
    public void 失败筛选覆盖所有失败码而不只是500() {
        logs.rows = new ArrayList<>();
        jobLogController.list(0, 0, 2, 50);
        assertEquals("status=2 必须是 ANY_FAILURE", JobLogService.ANY_FAILURE, logs.lastHandleCode);

        jobLogController.list(0, 0, 1, 50);
        assertEquals("status=1 仍是精确成功码", ReturnT.SUCCESS_CODE, logs.lastHandleCode);

        jobLogController.list(0, 0, 0, 50);
        assertEquals("status=0 不过滤", -1, logs.lastHandleCode);
    }

    // ---- 辅助 ----

    private static Map<String, JobLogService.Stats> daily(Object... dayOffsetAndCounts) {
        Map<String, JobLogService.Stats> map = new LinkedHashMap<String, JobLogService.Stats>();
        for (int i = 0; i < dayOffsetAndCounts.length; i += 3) {
            int offset = (Integer) dayOffsetAndCounts[i];
            long total = (Long) dayOffsetAndCounts[i + 1];
            long success = (Long) dayOffsetAndCounts[i + 2];
            map.put(new SimpleDateFormat("yyyy-MM-dd").format(offsetDay(offset)),
                    new JobLogService.Stats(total, success));
        }
        return map;
    }

    private static int todayOffset(int days) {
        return days;
    }

    private static Date offsetDay(int days) {
        Calendar cal = GregorianCalendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        cal.add(Calendar.DAY_OF_YEAR, days);
        return cal.getTime();
    }

    private static String label(int dayOffset) {
        return new SimpleDateFormat("MM-dd").format(offsetDay(dayOffset));
    }

    private static Date hourAgo(int hours) {
        Calendar cal = GregorianCalendar.getInstance();
        cal.add(Calendar.HOUR_OF_DAY, -hours);
        return cal.getTime();
    }

    private static JobInfo job(int id, int group, int running) {
        JobInfo job = new JobInfo();
        job.setId(id);
        job.setJobGroup(group);
        job.setJobDesc("demo job");
        job.setTriggerStatus(running);
        return job;
    }

    private static JobLog log(int jobId, int handleCode, Date triggerTime) {
        JobLog log = new JobLog();
        log.setJobId(jobId);
        log.setHandleCode(handleCode);
        log.setTriggerTime(triggerTime);
        return log;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object value) {
        return (Map<String, Object>) value;
    }

    private static void inject(Object target, String field, Object value) throws Exception {
        Class<?> type = target.getClass();
        while (type != null) {
            try {
                Field declared = type.getDeclaredField(field);
                declared.setAccessible(true);
                declared.set(target, value);
                return;
            } catch (NoSuchFieldException e) {
                type = type.getSuperclass();
            }
        }
        throw new NoSuchFieldException(field);
    }

    /** 记录统计侧调用；任何"拉日志行"的路径都单独计数，供断言其次数为 0。 */
    private static class FakeJobLogService implements JobLogService {
        int queryCalls;
        int statsCalls;
        int dailyCalls;
        int lastLimit;
        int lastHandleCode = Integer.MIN_VALUE;
        Date statsStart;
        Date statsEnd;
        Date dailyStart;
        Stats stats = Stats.empty();
        Map<String, Stats> daily = new LinkedHashMap<String, Stats>();
        List<JobLog> rows = new ArrayList<JobLog>();

        public long save(JobLog jobLog) {
            return 0L;
        }

        public void update(JobLog jobLog) {
        }

        public JobLog getById(long id) {
            return null;
        }

        public List<JobLog> query(int jobGroup, int jobId, int handleCode, int limit) {
            queryCalls++;
            lastHandleCode = handleCode;
            lastLimit = limit;
            return rows;
        }

        public Stats statsBetween(Date startInclusive, Date endExclusive) {
            statsCalls++;
            statsStart = startInclusive;
            statsEnd = endExclusive;
            return stats;
        }

        public Map<String, Stats> dailyStatsSince(Date startInclusive) {
            dailyCalls++;
            dailyStart = startInclusive;
            return daily;
        }

        public int clearByJobId(int jobId) {
            return 0;
        }

        public int clearAll() {
            return 0;
        }

        public int clearLogByDays(int days) {
            return 0;
        }
    }

    private static class FakeJobInfoService implements JobInfoService {
        final Map<Integer, JobInfo> store = new LinkedHashMap<Integer, JobInfo>();

        public JobInfo getById(int id) {
            return store.get(id);
        }

        public List<JobInfo> getAll() {
            return new ArrayList<JobInfo>(store.values());
        }

        public List<JobInfo> getByJobGroup(int jobGroup) {
            return getAll();
        }

        public List<JobInfo> listRunning() {
            return getAll();
        }

        public void updateTriggerTimes(int jobId, long lastTime, long nextTime) {
        }

        public ReturnT<String> add(JobInfo jobInfo) {
            return ReturnT.success();
        }

        public ReturnT<String> update(JobInfo jobInfo) {
            return ReturnT.success();
        }

        public ReturnT<String> delete(int id) {
            return ReturnT.success();
        }

        public ReturnT<String> stop(int id) {
            return ReturnT.success();
        }

        public ReturnT<String> start(int id) {
            return ReturnT.success();
        }

        public ReturnT<String> trigger(int id) {
            return ReturnT.success();
        }

        public ReturnT<List<String>> nextTriggerTime(String cron) {
            return ReturnT.success(new ArrayList<String>());
        }
    }

    private static class FakeRegistryService implements ExecutorRegistryService {
        public ReturnT<String> beat(String appName, String address) {
            return ReturnT.success();
        }

        public ReturnT<String> remove(String appName, String address) {
            return ReturnT.success();
        }

        public int onlineGroupCount() {
            return 3;
        }

        public List<Map<String, Object>> loadAll() {
            return new ArrayList<Map<String, Object>>();
        }
    }

    private static class FakeJobGroupService implements JobGroupService {
        public List<JobGroup> getAll() {
            return new ArrayList<JobGroup>();
        }

        public JobGroup getById(int id) {
            return null;
        }

        public JobGroup getByAppName(String appName) {
            return null;
        }

        public ReturnT<String> add(JobGroup jobGroup) {
            return ReturnT.success();
        }

        public ReturnT<String> update(JobGroup jobGroup) {
            return ReturnT.success();
        }

        public ReturnT<String> delete(int id) {
            return ReturnT.success();
        }

        public ReturnT<String> register(String appName, String address) {
            return ReturnT.success();
        }

        public List<String> getRegistryNodes(String appName) {
            return new ArrayList<String>();
        }
    }
}
