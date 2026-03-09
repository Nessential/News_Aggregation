package com.example.news.aggregation.news.domain.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.example.news.aggregation.datasource.domain.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

/**
 * 新闻实体。
 */
@Getter
@Setter
@TableName("news")
public class News extends BaseEntity {

    /** 新闻标题 */
    private String title;

    /** 中文标题 */
    private String title_cn;

    /** 新闻摘要 */
    private String summary;

    /** 中文摘要 */
    private String summary_cn;

    /** 新闻图片 URL */
    private String image_url;

    /** 新闻链接 */
    private String link;

    /** 新闻来源 */
    private String source;

    /** 发布时间（毫秒时间戳） */
    private Long publication_time;

    /** 新闻正文 */
    private String context;

    /** 中文正文 */
    private String context_cn;

    /** 分类ID（关联 news_category.id） */
    private Long category_id;

    /**
     * 分类名称（非持久化字段，仅用于运行时透传）。
     * 兼容历史逻辑，数据库不存该字段。
     */
    @TableField(exist = false)
    private String category;

    /** 正文状态：0-待抓取，1-成功，2-失败 */
    private Integer content_status;

    /** 翻译状态：0-待翻译，1-成功，2-失败 */
    private Integer translation_status;

    /** 向量化状态：0-待处理，1-成功，2-失败 */
    private Integer vector_status;

    /** Story ID（同题聚合） */
    private String canonical_id;

    /** 归档状态：0-待归档，1-已归档，2-失败 */
    private Integer canonical_status;

    /** ES 索引状态：0-未索引，1-已索引 */
    private Integer es_indexed;
}