package com.example.news.aggregation.news.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.news.aggregation.news.domain.entity.NewsCategory;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface NewsCategoryMapper extends BaseMapper<NewsCategory> {

    @Select("""
            SELECT id, name, deleted, lock_version, gmt_create, gmt_modified
            FROM news_category
            WHERE deleted = 0
            ORDER BY id ASC
            """)
    List<NewsCategory> selectAllActive();

    @Select("""
            SELECT id, name, deleted, lock_version, gmt_create, gmt_modified
            FROM news_category
            WHERE name = #{name}
              AND deleted = 0
            LIMIT 1
            """)
    NewsCategory selectByName(@Param("name") String name);
}

