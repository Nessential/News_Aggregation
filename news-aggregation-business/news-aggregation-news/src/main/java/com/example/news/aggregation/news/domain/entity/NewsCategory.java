package com.example.news.aggregation.news.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.example.news.aggregation.datasource.domain.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

/**
 * 新闻分类实体。
 */
@Getter
@Setter
@TableName("news_category")
public class NewsCategory extends BaseEntity {

    /** 分类名称 */
    private String name;
}

