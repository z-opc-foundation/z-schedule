package com.zifang.z.schedule.web.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.zifang.z.schedule.core.model.JobLog;
import com.zifang.z.schedule.core.model.ReturnT;
import com.zifang.z.schedule.web.domain.entity.JobLogDO;
import com.zifang.z.schedule.web.domain.mapper.JobLogMapper;
import com.zifang.z.schedule.web.service.JobLogService;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 任务日志服务 — 持久化实现
 */
@Service
public class JobLogServiceImpl implements JobLogService {

    /** 清理日志时单批删除的行数上界, 见 {@link #deleteBefore(Date)}. */
    static final int CLEAR_BATCH_SIZE = 1000;

    @Resource
    private JobLogMapper jobLogMapper;

    @Override
    public long save(JobLog jobLog) {
        JobLogDO d = DoMapper.toDO(jobLog);
        jobLogMapper.insert(d);
        return d.getId() == null ? 0L : d.getId();
    }

    @Override
    public void update(JobLog jobLog) {
        if (jobLog == null || jobLog.getId() <= 0) return;
        JobLogDO d = DoMapper.toDO(jobLog);
        jobLogMapper.updateById(d);
    }

    @Override
    public JobLog getById(long id) {
        return DoMapper.toDTO(jobLogMapper.selectById(id));
    }

    @Override
    public List<JobLog> query(int jobGroup, int jobId, int handleCode, int limit) {
        LambdaQueryWrapper<JobLogDO> wrapper = new LambdaQueryWrapper<>();
        if (jobGroup > 0) wrapper.eq(JobLogDO::getJobGroup, jobGroup);
        if (jobId > 0) wrapper.eq(JobLogDO::getJobId, jobId);
        if (handleCode == ANY_FAILURE) {
            wrapper.gt(JobLogDO::getHandleCode, 0).ne(JobLogDO::getHandleCode, ReturnT.SUCCESS_CODE);
        } else if (handleCode >= 0) {
            wrapper.eq(JobLogDO::getHandleCode, handleCode);
        }
        wrapper.orderByDesc(JobLogDO::getTriggerTime);
        wrapper.orderByDesc(JobLogDO::getId);
        wrapper.last("LIMIT " + pageSize(limit));
        return DoMapper.toLogDTOList(jobLogMapper.selectList(wrapper));
    }

    @Override
    public Stats statsBetween(Date startInclusive, Date endExclusive) {
        Map<String, Object> row = jobLogMapper.statsBetween(startInclusive, endExclusive, ReturnT.SUCCESS_CODE);
        return new Stats(number(row, "total"), number(row, "success"));
    }

    @Override
    public Map<String, Stats> dailyStatsSince(Date startInclusive) {
        List<Map<String, Object>> rows = jobLogMapper.dailyStats(startInclusive, ReturnT.SUCCESS_CODE);
        Map<String, Stats> byDay = new LinkedHashMap<String, Stats>();
        if (rows == null) {
            return byDay;
        }
        for (Map<String, Object> row : rows) {
            byDay.put(dayKey(row), new Stats(number(row, "total"), number(row, "success")));
        }
        return byDay;
    }

    private static int pageSize(int limit) {
        if (limit <= 0) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(limit, MAX_PAGE_SIZE);
    }

    /** 别名大小写随驱动而变(H2 会把 total 变成 TOTAL),按不区分大小写取值. */
    private static long number(Map<String, Object> row, String key) {
        if (row == null) {
            return 0L;
        }
        Object value = row.get(key);
        if (value == null) {
            for (Map.Entry<String, Object> entry : row.entrySet()) {
                if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(key)) {
                    value = entry.getValue();
                    break;
                }
            }
        }
        return value instanceof Number ? ((Number) value).longValue() : 0L;
    }

    /**
     * 聚合行的日期键。{@code DATE()} 在不同驱动下可能是 java.sql.Date、LocalDate 或字符串,
     * 统一成 {@code yyyy-MM-dd}。
     */
    private static String dayKey(Map<String, Object> row) {
        Object value = null;
        if (row != null) {
            value = row.get("stat_day");
            if (value == null) {
                for (Map.Entry<String, Object> entry : row.entrySet()) {
                    if (entry.getKey() != null && entry.getKey().equalsIgnoreCase("stat_day")) {
                        value = entry.getValue();
                        break;
                    }
                }
            }
        }
        if (value == null) {
            return "";
        }
        if (value instanceof java.time.LocalDate) {
            return value.toString();
        }
        if (value instanceof Date) {
            return new SimpleDateFormat("yyyy-MM-dd").format((Date) value);
        }
        String text = String.valueOf(value);
        return text.length() >= 10 ? text.substring(0, 10) : text;
    }

    @Override
    public int clearByJobId(int jobId) {
        return jobLogMapper.delete(
                new LambdaQueryWrapper<JobLogDO>().eq(JobLogDO::getJobId, jobId));
    }

    @Override
    public int clearAll() {
        return jobLogMapper.delete(null);
    }

    @Override
    public int clearLogByDays(int days) {
        if (days < 7) days = 7;
        java.util.Calendar cal = java.util.Calendar.getInstance();
        cal.add(java.util.Calendar.DAY_OF_MONTH, -days);
        return deleteBefore(cal.getTime());
    }

    /**
     * 删除触发时间早于 {@code cutoff}（不含）的日志，按主键分批。
     * <p>
     * 一条 {@code DELETE FROM ... WHERE trigger_time < ?} 能删多少行完全由外部条件决定：
     * 保留期配多长、调度中心停了多久，事务就有多大——锁持有时间、undo、binlog 事件
     * 一起跟着涨，而且没有回头路。这里先按主键取一批、再按主键删一批，
     * 把单批大小钉死在 {@link #CLEAR_BATCH_SIZE}。
     *
     * @return 实际删除的行数
     */
    int deleteBefore(Date cutoff) {
        int total = 0;
        while (true) {
            List<Long> ids = idsBefore(cutoff, CLEAR_BATCH_SIZE);
            if (ids.isEmpty()) {
                return total;
            }
            int deleted = jobLogMapper.deleteByIds(ids);
            if (deleted <= 0) {
                // 一批取到的 id 一个都没删掉（被别的连接抢先了），不能再按同一批原地转
                return total;
            }
            total += deleted;
            if (ids.size() < CLEAR_BATCH_SIZE) {
                return total;
            }
        }
    }

    private List<Long> idsBefore(Date cutoff, int batch) {
        List<JobLogDO> rows = jobLogMapper.selectList(new LambdaQueryWrapper<JobLogDO>()
                .select(JobLogDO::getId)
                .lt(JobLogDO::getTriggerTime, cutoff)
                .orderByAsc(JobLogDO::getId)
                .last("LIMIT " + batch));
        if (rows == null || rows.isEmpty()) {
            return java.util.Collections.emptyList();
        }
        List<Long> ids = new ArrayList<Long>(rows.size());
        for (JobLogDO row : rows) {
            if (row.getId() != null) {
                ids.add(row.getId());
            }
        }
        return ids;
    }
}
