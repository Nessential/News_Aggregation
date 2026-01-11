package com.example.news.aggregation.vector.service.impl;

import com.example.news.aggregation.vector.config.VectorProperties;
import com.example.news.aggregation.vector.exception.VectorErrorCode;
import com.example.news.aggregation.vector.exception.VectorException;
import com.example.news.aggregation.vector.model.SearchResult;
import com.example.news.aggregation.vector.model.VectorPoint;
import com.example.news.aggregation.vector.service.VectorStoreService;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.Collections.Distance;
import io.qdrant.client.grpc.Collections.VectorParams;
import io.qdrant.client.grpc.Points;
import io.qdrant.client.grpc.Points.Condition;
import io.qdrant.client.grpc.Points.FieldCondition;
import io.qdrant.client.grpc.Points.Filter;
import io.qdrant.client.grpc.Points.Match;
import io.qdrant.client.grpc.Points.PointStruct;
import io.qdrant.client.grpc.Points.ScoredPoint;
import io.qdrant.client.grpc.Points.SearchPoints;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

import static io.qdrant.client.PointIdFactory.id;
import static io.qdrant.client.ValueFactory.value;
import static io.qdrant.client.VectorsFactory.vectors;
import static io.qdrant.client.WithPayloadSelectorFactory.enable;

/**
 * Qdrant向量存储服务实现类
 * 基于Qdrant数据库实现向量存储和检索功能
 *
 * @author system
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QdrantVectorStoreServiceImpl implements VectorStoreService {

    private final QdrantClient qdrantClient;
    private final VectorProperties properties;

    @PostConstruct
    public void init() {
        ensureCollection(properties.getCollectionName(), properties.getVectorSize());
    }

    @Override
    public void ensureCollection(String collectionName, int vectorSize) {
        try {
            boolean exists = qdrantClient.collectionExistsAsync(collectionName).get();
            if (!exists) {
                qdrantClient.createCollectionAsync(
                        collectionName,
                        VectorParams.newBuilder()
                                .setSize(vectorSize)
                                .setDistance(Distance.Cosine)
                                .build()
                ).get();
                log.info("创建集合成功: {}, vectorSize: {}", collectionName, vectorSize);
            } else {
                log.info("集合已存在: {}", collectionName);
            }
        } catch (InterruptedException | ExecutionException e) {
            log.error("确保集合存在失败: {}", collectionName, e);
            throw new VectorException("创建集合失败: " + collectionName, e, VectorErrorCode.COLLECTION_CREATE_FAILED);
        }
    }

    @Override
    public void upsert(String collectionName, List<VectorPoint> points) {
        if (points == null || points.isEmpty()) {
            return;
        }
        try {
            List<PointStruct> pointStructs = points.stream()
                    .map(this::toPointStruct)
                    .collect(Collectors.toList());
            Points.UpdateResult updateResult = qdrantClient.upsertAsync(collectionName, pointStructs).get();
            log.info("向量插入成功: collection={}, count={},result={}", collectionName, points.size(),updateResult);
        } catch (InterruptedException | ExecutionException e) {
            log.error("向量插入失败: collection={}", collectionName, e);
            throw new VectorException("向量插入失败", e, VectorErrorCode.UPSERT_FAILED);
        }
    }

    @Override
    public List<SearchResult> search(String collectionName, float[] queryVector, int topK, Map<String, Object> filter) {
        try {
            SearchPoints.Builder searchBuilder = SearchPoints.newBuilder()
                    .setCollectionName(collectionName)
                    .addAllVector(toFloatList(queryVector))
                    .setLimit(topK)
                    .setWithPayload(enable(true));

            // 如果有过滤条件，添加到搜索请求中
            if (filter != null && !filter.isEmpty()) {
                searchBuilder.setFilter(buildFilter(filter));
            }

            List<ScoredPoint> scoredPoints = qdrantClient.searchAsync(searchBuilder.build()).get();

            return scoredPoints.stream()
                    .map(this::toSearchResult)
                    .collect(Collectors.toList());
        } catch (InterruptedException | ExecutionException e) {
            log.error("向量搜索失败: collection={}", collectionName, e);
            throw new VectorException("向量搜索失败", e, VectorErrorCode.SEARCH_FAILED);
        }
    }

    @Override
    public void delete(String collectionName, List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        try {
            List<io.qdrant.client.grpc.Points.PointId> pointIds = ids.stream()
                    .map(idStr -> id(UUID.nameUUIDFromBytes(idStr.getBytes())))
                    .collect(Collectors.toList());
            qdrantClient.deleteAsync(collectionName, pointIds).get();
            log.info("向量删除成功: collection={}, count={}", collectionName, ids.size());
        } catch (InterruptedException | ExecutionException e) {
            log.error("向量删除失败: collection={}", collectionName, e);
            throw new VectorException("向量删除失败", e, VectorErrorCode.DELETE_FAILED);
        }
    }

    @Override
    public boolean exists(String collectionName, String pointId) {
        try {
            var pid = id(UUID.nameUUIDFromBytes(pointId.getBytes()));
            var points = qdrantClient.retrieveAsync(
                    collectionName,
                    java.util.Collections.singletonList(pid),
                    false,
                    false,
                    null
            ).get();
            return !points.isEmpty();
        } catch (InterruptedException | ExecutionException e) {
            log.error("检查向量存在失败: collection={}, id={}", collectionName, pointId, e);
            return false;
        }
    }

    @Override
    public long count(String collectionName) {
        try {
            var info = qdrantClient.getCollectionInfoAsync(collectionName).get();
            return info.getPointsCount();
        } catch (InterruptedException | ExecutionException e) {
            log.error("获取集合点数量失败: collection={}", collectionName, e);
            return 0;
        }
    }

    private PointStruct toPointStruct(VectorPoint point) {
        PointStruct.Builder builder = PointStruct.newBuilder()
                .setId(id(UUID.nameUUIDFromBytes(point.getId().getBytes())))
                .setVectors(vectors(toFloatList(point.getVector())));

        if (point.getPayload() != null) {
            for (Map.Entry<String, Object> entry : point.getPayload().entrySet()) {
                Object val = entry.getValue();
                if (val instanceof String) {
                    builder.putPayload(entry.getKey(), value((String) val));
                } else if (val instanceof Integer) {
                    builder.putPayload(entry.getKey(), value((Integer) val));
                } else if (val instanceof Long) {
                    builder.putPayload(entry.getKey(), value((Long) val));
                } else if (val instanceof Double) {
                    builder.putPayload(entry.getKey(), value((Double) val));
                } else if (val instanceof Boolean) {
                    builder.putPayload(entry.getKey(), value((Boolean) val));
                } else {
                    builder.putPayload(entry.getKey(), value(String.valueOf(val)));
                }
            }
        }
        return builder.build();
    }

    private SearchResult toSearchResult(ScoredPoint scoredPoint) {
        Map<String, Object> payload = new HashMap<>();
        scoredPoint.getPayloadMap().forEach((key, val) -> {
            if (val.hasStringValue()) {
                payload.put(key, val.getStringValue());
            } else if (val.hasIntegerValue()) {
                payload.put(key, val.getIntegerValue());
            } else if (val.hasDoubleValue()) {
                payload.put(key, val.getDoubleValue());
            } else if (val.hasBoolValue()) {
                payload.put(key, val.getBoolValue());
            } else {
                payload.put(key, val.toString());
            }
        });

        return SearchResult.builder()
                .id(scoredPoint.getId().getUuid())
                .score(scoredPoint.getScore())
                .payload(payload)
                .build();
    }

    private List<Float> toFloatList(float[] array) {
        List<Float> list = new ArrayList<>(array.length);
        for (float f : array) {
            list.add(f);
        }
        return list;
    }

    /**
     * 构建Qdrant过滤器
     */
    private Filter buildFilter(Map<String, Object> filter) {
        Filter.Builder filterBuilder = Filter.newBuilder();

        for (Map.Entry<String, Object> entry : filter.entrySet()) {
            String key = entry.getKey();
            Object val = entry.getValue();

            Match.Builder matchBuilder = Match.newBuilder();
            if (val instanceof String) {
                matchBuilder.setKeyword((String) val);
            } else if (val instanceof Integer) {
                matchBuilder.setInteger((Integer) val);
            } else if (val instanceof Long) {
                matchBuilder.setInteger((Long) val);
            } else if (val instanceof Boolean) {
                matchBuilder.setBoolean((Boolean) val);
            } else {
                matchBuilder.setKeyword(String.valueOf(val));
            }

            Condition condition = Condition.newBuilder()
                    .setField(FieldCondition.newBuilder()
                            .setKey(key)
                            .setMatch(matchBuilder.build())
                            .build())
                    .build();

            filterBuilder.addMust(condition);
        }

        return filterBuilder.build();
    }
}
