package com.example.news.aggregation.vector.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.Map;

/**
 * 向量搜索结果模型
 * 表示向量相似性搜索的单个结果项
 * 
 * @author system
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SearchResult {
    
    /**
     * 匹配向量点的唯一标识符
     */
    private String id;
    
    /**
     * 相似度得分
     * 值越高表示相似度越高
     */
    private float score;
    
    /**
     * 匹配向量点的元数据载荷
     * 包含与该向量点关联的业务数据
     */
    private Map<String, Object> payload;
}