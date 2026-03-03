package com.example.news.aggregation.news.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.news.aggregation.news.domain.entity.UserAccount;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface UserAccountMapper extends BaseMapper<UserAccount> {

    @Select("select * from user_account where phone = #{phone} and deleted = 0 limit 1")
    UserAccount selectByPhone(@Param("phone") String phone);
}

