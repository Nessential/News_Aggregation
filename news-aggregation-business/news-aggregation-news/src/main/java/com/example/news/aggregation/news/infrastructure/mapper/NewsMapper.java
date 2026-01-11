package com.example.news.aggregation.news.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.news.aggregation.news.domain.entity.News;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface NewsMapper extends BaseMapper<News> {

    /**
     * 根据链接查询新闻
     *
     * @param link 新闻链接
     * @return 新闻实体
     */

    News selectByLink(@Param("link") String link);

    List<News> selectForTranslation(int batchSize);

    List<News> selectForVectorization(@Param("batchSize") int batchSize);
}
