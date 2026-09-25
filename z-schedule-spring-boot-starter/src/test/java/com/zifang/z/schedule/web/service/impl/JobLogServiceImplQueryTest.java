package com.zifang.z.schedule.web.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.zifang.z.schedule.core.model.ReturnT;
import com.zifang.z.schedule.web.domain.entity.JobLogDO;
import com.zifang.z.schedule.web.domain.mapper.JobLogMapper;
import com.zifang.z.schedule.web.service.JobLogService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * {@link JobLogServiceImpl} 生成给数据库的查询形状。
 * <p>
 * 这里钉的是两条会直接拖垮服务的约定：
 * <ul>
 *   <li>列表查询永远带 LIMIT——limit 缺失或非正数时收默认值，过大时收窄到硬上限。
 *       旧实现在 {@code limit <= 0} 时干脆不拼 LIMIT，Dashboard 每次刷新都全表扫 job_log</li>
 *   <li>统计走聚合行——聚合结果的键名要能容忍驱动差异（H2 会把 {@code total} 变成 {@code TOTAL}），
 *       日期键要能接受 DATE()/LocalDate/字符串三种回值</li>
 * </ul>
 */
public class JobLogServiceImplQueryTest {

    private CapturingMapper capture;
    private JobLogServiceImpl service;

    @BeforeClass
    public static void initTableInfo() {
        // Lambda 条件里的 handle_code 等列名要靠 TableInfo 解析，脱离 Spring 时得自己注册一次
        MybatisConfiguration configuration = new MybatisConfiguration();
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "");
        assistant.setCurrentNamespace(JobLogMapper.class.getName());
        TableInfoHelper.initTableInfo(assistant, JobLogDO.class);
    }

    @Before
    public void setUp() throws Exception {
        capture = new CapturingMapper();
        service = new JobLogServiceImpl();
        Field field = JobLogServiceImpl.class.getDeclaredField("jobLogMapper");
        field.setAccessible(true);
        field.set(service, capture.proxy());
    }

    // ---- LIMIT ----

    @Test
    public void limit非正数时收默认上限而不是全表扫() {
        service.query(0, 0, -1, 0);
        assertEndsWithLimit(JobLogService.DEFAULT_PAGE_SIZE);

        service.query(0, 0, -1, -7);
        assertEndsWithLimit(JobLogService.DEFAULT_PAGE_SIZE);
    }

    @Test
    public void limit过大时收窄到硬上限() {
        service.query(0, 0, -1, 999_999);
        assertEndsWithLimit(JobLogService.MAX_PAGE_SIZE);
    }

    @Test
    public void 正常limit原样透传() {
        service.query(0, 0, -1, 20);
        assertEndsWithLimit(20);
    }

    private void assertEndsWithLimit(int limit) {
        String sql = capture.wrapper.getSqlSegment();
        assertTrue("LIMIT 必须下推给数据库,实测: " + sql, sql.trim().endsWith("LIMIT " + limit));
    }

    // ---- 条件形状 ----

    @Test
    public void 失败筛选生成非零且非成功两个条件() {
        service.query(0, 0, JobLogService.ANY_FAILURE, 10);
        String sql = capture.wrapper.getTargetSql();
        assertTrue("应排除 handle_code=0(未执行完): " + sql, sql.contains("handle_code > ?"));
        assertTrue("应排除成功码: " + sql, sql.contains("handle_code <> ?"));
        assertEquals(boundValues(0, ReturnT.SUCCESS_CODE), boundValues(capture.wrapper));
    }

    @Test
    public void 精确结果码仍然是等值匹配() {
        service.query(0, 0, ReturnT.SUCCESS_CODE, 10);
        String sql = capture.wrapper.getTargetSql();
        assertTrue(sql, sql.contains("handle_code = ?"));
        assertFalse("等值匹配不得变成区间", sql.contains("handle_code >"));
        assertEquals(boundValues(ReturnT.SUCCESS_CODE), boundValues(capture.wrapper));
    }

    @Test
    public void 分组与任务过滤条件和排序一起下推() {
        service.query(3, 42, -1, 10);
        String sql = capture.wrapper.getTargetSql();
        assertTrue(sql, sql.contains("job_group = ?"));
        assertTrue(sql, sql.contains("job_id = ?"));
        assertFalse(sql, sql.contains("handle_code"));
        assertTrue("按调度时间倒序: " + sql, sql.contains("ORDER BY trigger_time DESC"));
        assertEquals(boundValues(3, 42), boundValues(capture.wrapper));
    }

    /** paramNameValuePairs 是 HashMap,只按集合内容比对绑定值. */
    private static List<String> boundValues(LambdaQueryWrapper<JobLogDO> wrapper) {
        List<String> values = new ArrayList<String>();
        for (Object value : wrapper.getParamNameValuePairs().values()) {
            values.add(String.valueOf(value));
        }
        java.util.Collections.sort(values);
        return values;
    }

    private static List<String> boundValues(Object... expected) {
        List<String> values = new ArrayList<String>();
        for (Object value : expected) {
            values.add(String.valueOf(value));
        }
        java.util.Collections.sort(values);
        return values;
    }

    // ---- 聚合结果解析 ----

    @Test
    public void 聚合行容忍驱动返回的大写列名() {
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("TOTAL", 7L);
        row.put("SUCCESS", new java.math.BigDecimal("5"));
        capture.statsRow = row;

        JobLogService.Stats stats = service.statsBetween(new Date(0L), new Date(1000L));
        assertEquals(7L, stats.getTotal());
        assertEquals(5L, stats.getSuccess());
        assertEquals(71, stats.successRate());
    }

    @Test
    public void 聚合查询把区间与成功码交给数据库() {
        Date start = new Date(1_700_000_000_000L);
        Date end = new Date(1_700_008_640_000L);
        capture.statsRow = new LinkedHashMap<String, Object>();
        service.statsBetween(start, end);

        assertEquals(start, capture.statsArgs[0]);
        assertEquals(end, capture.statsArgs[1]);
        assertEquals(Integer.valueOf(ReturnT.SUCCESS_CODE), capture.statsArgs[2]);
    }

    @Test
    public void 按日聚合接受三种日期回值形态() {
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        rows.add(dayRow(new java.sql.Date(1_700_000_000_000L), 4L, 4L));
        rows.add(dayRow(LocalDate.of(2024, 1, 2), 3L, 1L));
        rows.add(dayRow("2024-01-03 00:00:00", 2L, 2L));
        capture.dailyRows = rows;

        Map<String, JobLogService.Stats> byDay = service.dailyStatsSince(new Date(0L));

        assertEquals(3, byDay.size());
        assertEquals(new Date(0L), capture.dailyArgs[0]);
        String sqlDate = new java.text.SimpleDateFormat("yyyy-MM-dd").format(
                new java.util.Date(1_700_000_000_000L));
        assertEquals("驱动返回 java.sql.Date 时按本地日归一",
                4L, byDay.get(sqlDate).getTotal());
        assertEquals(1L, byDay.get("2024-01-02").getSuccess());
        assertEquals(2L, byDay.get("2024-01-03").getTotal());
    }

    @Test
    public void 数据库无聚合行时给出空结果而不是null() {
        capture.dailyRows = null;
        assertTrue(service.dailyStatsSince(new Date(0L)).isEmpty());

        capture.statsRow = null;
        assertEquals(0L, service.statsBetween(new Date(0L), new Date(1L)).getTotal());
    }

    // ---- 辅助 ----

    private static Map<String, Object> dayRow(Object day, long total, long success) {
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("stat_day", day);
        row.put("total", total);
        row.put("success", success);
        return row;
    }

    /** 只记录条件对象与聚合入参的 Mapper 替身。 */
    private static class CapturingMapper {
        LambdaQueryWrapper<JobLogDO> wrapper;
        Object[] statsArgs;
        Object[] dailyArgs;
        Map<String, Object> statsRow = new LinkedHashMap<String, Object>();
        List<Map<String, Object>> dailyRows = new ArrayList<Map<String, Object>>();

        JobLogMapper proxy() {
            return (JobLogMapper) Proxy.newProxyInstance(JobLogMapper.class.getClassLoader(),
                    new Class<?>[]{JobLogMapper.class}, new InvocationHandler() {
                        public Object invoke(Object proxy, Method method, Object[] args) {
                            String name = method.getName();
                            if ("selectList".equals(name)) {
                                wrapper = (LambdaQueryWrapper<JobLogDO>) args[0];
                                return new ArrayList<JobLogDO>();
                            }
                            if ("statsBetween".equals(name)) {
                                statsArgs = args;
                                return statsRow;
                            }
                            if ("dailyStats".equals(name)) {
                                dailyArgs = args;
                                return dailyRows;
                            }
                            if (method.getDeclaringClass() == Object.class) {
                                if ("toString".equals(name)) {
                                    return "CapturingMapper";
                                }
                                if ("hashCode".equals(name)) {
                                    return System.identityHashCode(proxy);
                                }
                                return proxy == args[0];
                            }
                            return null;
                        }
                    });
        }
    }
}
