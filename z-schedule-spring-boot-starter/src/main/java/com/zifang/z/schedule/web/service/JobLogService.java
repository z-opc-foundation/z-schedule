package com.zifang.z.schedule.web.service;

import com.zifang.z.schedule.core.model.JobLog;

import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * 任务调度日志服务接口
 */
public interface JobLogService {

    /**
     * 写入一条调度日志.
     *
     * @param jobLog 日志实体
     * @return 新日志主键 ID
     */
    long save(JobLog jobLog);

    /**
     * 更新调度/执行结果(由调度完成回调).
     *
     * @param jobLog 日志实体(必须包含 id)
     */
    void update(JobLog jobLog);

    /**
     * 根据主键 ID 查询日志.
     *
     * @param id 主键 ID
     * @return 日志实体
     */
    JobLog getById(long id);

    /**
     * 多条件分页查询日志.
     *
     * @param jobGroup   任务分组,0 表示不过滤
     * @param jobId      任务 ID,0 表示不过滤
     * @param handleCode 执行结果码:负数不过滤,{@link #ANY_FAILURE} 表示"所有失败"(非 0 且非成功),
     *                   其它非负值按等值匹配
     * @param limit      返回上限,非正数取默认值,超过 {@link #MAX_PAGE_SIZE} 会被收窄
     * @return 倒序(最新在前)的日志列表
     */
    List<JobLog> query(int jobGroup, int jobId, int handleCode, int limit);

    /**
     * 区间内的执行结果计数,聚合在数据库侧完成.
     * <p>Dashboard 这类"只要几个数字"的场景必须走这里而不是 {@code query}: job_log 每次触发都长一行,
     * 把整表拉进 JVM 会让统计接口随运行时间线性变慢.
     *
     * @param startInclusive 起始调度时间(含)
     * @param endExclusive   结束调度时间(不含)
     * @return 总数与成功数
     */
    Stats statsBetween(Date startInclusive, Date endExclusive);

    /**
     * 自 {@code startInclusive} 起按自然日聚合的执行结果计数.
     *
     * @param startInclusive 最早一天的 00:00
     * @return key 为 {@code yyyy-MM-dd}, 无数据的日期不出现在 map 里
     */
    Map<String, Stats> dailyStatsSince(Date startInclusive);

    /**
     * 按任务 ID 清理日志.
     *
     * @param jobId 任务 ID
     * @return 删除数量
     */
    int clearByJobId(int jobId);

    /**
     * 清空全部日志.
     *
     * @return 删除数量
     */
    int clearAll();

    /**
     * 清理超过指定天数的日志.
     *
     * @param days 保留天数（清理 days 天之前的日志）
     * @return 删除数量
     */
    int clearLogByDays(int days);

    /** {@code query} 的 handleCode 取值:匹配所有失败(既非"未执行完"的 0,也非成功码)。 */
    int ANY_FAILURE = -2;

    /** {@code query} 单次返回的硬上限,防止调用方用 limit 拖垮服务。 */
    int MAX_PAGE_SIZE = 1000;

    /** {@code query} 未显式给上限时的默认返回条数。 */
    int DEFAULT_PAGE_SIZE = 100;

    /** 一段调度时间内的执行结果计数。 */
    class Stats {
        private final long total;
        private final long success;

        public Stats(long total, long success) {
            this.total = total;
            this.success = success;
        }

        public static Stats empty() {
            return new Stats(0L, 0L);
        }

        public long getTotal() {
            return total;
        }

        public long getSuccess() {
            return success;
        }

        /** 成功率(0-100,四舍五入);无数据时按 0 计,与"没有调度"一致。 */
        public int successRate() {
            return total == 0L ? 0 : (int) Math.round(success * 100.0 / total);
        }

        @Override
        public String toString() {
            return "Stats{total=" + total + ", success=" + success + "}";
        }
    }
}