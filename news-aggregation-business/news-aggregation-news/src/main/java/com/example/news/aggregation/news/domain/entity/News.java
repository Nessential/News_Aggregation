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
     * 中文标题
     */
    private String title_cn;

    /**
     * 新闻摘要
     */
    private String summary;

    /**
     * 中文摘要
     */
    private String summary_cn;

    /**
     * 新闻图片url
     */
    private String image_url;

    /**
     * 新闻链接
     */
    private String link;

    /**
     * 新闻来源
     */
    private String source;

    /**
     * 新闻报道时间戳，毫秒级别
     */
    private Long publication_time;

    /**
     * 新闻正文
     */
    private String context;

    /**
     * 中文正文
     */
    private String context_cn;


    /**
     * 新闻分类
     */
    private String category;


    /**
     * 正文状态：0-待抓取，1-成功，2-失败
     */
    private Integer content_status;

    /**
     * 翻译状态：0-待翻译，1-成功，2-失败
     */
    private Integer translation_status;

    /**
     * 向量化状态：0-待向量化，1-成功，2-失败
     */
    private Integer vector_status;

    /**
     * Story ID，用于同题去重
     */
    private String canonical_id;


    /**
     * 归簇状态：0-待归簇，1-已归簇，2-失败
     */
    private Integer canonical_status;
}
