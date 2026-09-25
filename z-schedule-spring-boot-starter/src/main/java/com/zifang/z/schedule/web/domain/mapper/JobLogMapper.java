package com.zifang.z.schedule.web.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zifang.z.schedule.web.domain.entity.JobLogDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Date;
import java.util.List;
import java.util.Map;

@Mapper
public interface JobLogMapper extends BaseMapper<JobLogDO> {

    /**
     * 区间内的总数/成功数(走 idx_trigger_time,不把日志行拉出数据库).
     *
     * @return 单行 {@code {total, success}}
     */
    @Select("SELECT COUNT(*) AS total, "
            + "COALESCE(SUM(CASE WHEN handle_code = #{successCode} THEN 1 ELSE 0 END), 0) AS success "
            + "FROM z_schedule_job_log "
            + "WHERE trigger_time >= #{start} AND trigger_time < #{end}")
    Map<String, Object> statsBetween(@Param("start") Date start,
                                     @Param("end") Date end,
                                     @Param("successCode") int successCode);

    /**
     * 自 {@code start} 起按自然日聚合的总数/成功数.
     *
     * @return 每行 {@code {stat_day, total, success}},无数据的日期不出现
     */
    @Select("SELECT DATE(trigger_time) AS stat_day, COUNT(*) AS total, "
            + "COALESCE(SUM(CASE WHEN handle_code = #{successCode} THEN 1 ELSE 0 END), 0) AS success "
            + "FROM z_schedule_job_log "
            + "WHERE trigger_time >= #{start} "
            + "GROUP BY DATE(trigger_time)")
    List<Map<String, Object>> dailyStats(@Param("start") Date start,
                                        @Param("successCode") int successCode);
}
