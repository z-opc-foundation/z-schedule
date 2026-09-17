package io.github.yuku123.z.schedule.web.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.yuku123.z.schedule.web.domain.entity.UserDO;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface UserMapper extends BaseMapper<UserDO> {
}
