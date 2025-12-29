package com.example.news.aggregation.news.domain.entity;


import com.baomidou.mybatisplus.annotation.TableName;
import com.example.news.aggregation.datasource.domain.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

/**
 * 新闻实体类
 *
 * @author Hollis
 */
@Getter
@Setter
@TableName("news")
public class News extends BaseEntity {

    /**
     * 新闻标题
     */
    private String title;

    /**
     * 新闻摘要
     */
    private String summary;

    /**
     * 新闻图片url
     */
    private String image_url;

    /**
     * 新闻链接
     */
    private String link;

    /**
     * 新闻翻译
     */
    private String translation;

    /**
     * 新闻来源
     */
    private String source;

    /**
     * 新闻报道时间戳，毫秒级别
     */
    private Long publication_time;

    /**
     * 压缩后的正文
     */
    private String context;
}
