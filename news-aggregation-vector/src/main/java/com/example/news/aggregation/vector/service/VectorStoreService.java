package com.example.news.aggregation.vector.service;

import com.example.news.aggregation.vector.model.SearchResult;
import com.example.news.aggregation.vector.model.VectorPoint;
import java.util.List;
import java.util.Map;

/**
 * 向量存储服务接口
 * 定义向量数据库的核心操作方法
 * 
 * @author system
 */

public interface VectorStoreService {
    
    /**
     * 确保集合存在，如果不存在则创建
     * 
     * @param collectionName 集合名称
     * @param vectorSize 向量维度大小
     */
    void ensureCollection(String collectionName, int vectorSize);
    
    /**
     * 插入或更新向量点
     * 如果向量点已存在则更新，否则插入新的向量点
     * 
     * @param collectionName 集合名称
     * @param points 要插入/更新的向量点列表
     */
    void upsert(String collectionName, List<VectorPoint> points);
    
    /**
     * 向量相似性搜索
     * 根据查询向量找到最相似的向量点
     * 
     * @param collectionName 集合名称
     * @param queryVector 查询向量
     * @param topK 返回结果数量
     * @param filter 过滤条件（可选）
     * @return 搜索结果列表，按相似度降序排列
     */
    List<SearchResult> search(String collectionName, float[] queryVector, int topK, Map<String, Object> filter);
    
    /**
     * 删除向量点
     * 
     * @param collectionName 集合名称
     * @param ids 要删除的向量点ID列表
     */
    void delete(String collectionName, List<String> ids);
    
    /**
     * 检查向量点是否存在
     * 
     * @param collectionName 集合名称
     * @param id 向量点ID
     * @return 如果存在返回true，否则返回false
     */
    boolean exists(String collectionName, String id);
    
    /**
     * 获取集合中向量点的总数量
     * 
     * @param collectionName 集合名称
     * @return 向量点数量
     */
    long count(String collectionName);
}