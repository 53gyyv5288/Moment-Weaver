package com.momentweaver.account.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.momentweaver.account.entity.User;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface UserMapper extends BaseMapper<User> {
}
