package com.example.news.aggregation.vector.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.Map;

/**
 * 向量点数据模型
 * 表示向量数据库中的一个向量点，包含ID、向量数据和元数据
 * 
 * @author system
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VectorPoint {
    
    /**
     * 向量点的唯一标识符
     */
    private String id;
    
    /**
     * 向量数据（浮点数组）
     */
    private float[] vector;
    
    /**
     * 向量点的元数据载荷
     * 可以包含任意键值对信息，如新闻ID、标题、分类等
     */
    private Map<String, Object> payload;
}