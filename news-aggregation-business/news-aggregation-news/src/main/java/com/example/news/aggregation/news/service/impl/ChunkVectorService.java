package com.example.news.aggregation.news.service.impl;


import com.example.news.aggregation.embedding.chunker.TextChunker;
import com.example.news.aggregation.embedding.model.TextChunk;
import com.example.news.aggregation.embedding.service.EmbeddingService;
import com.example.news.aggregation.news.domain.entity.News;
import com.example.news.aggregation.vector.model.VectorPoint;
import com.example.news.aggregation.vector.service.VectorStoreService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChunkVectorService {

    private final EmbeddingService embeddingService;
    private final VectorStoreService vectorStoreService;
    private final TextChunker textChunker;

    private static final String COLLECTION_CHUNKS_EN = "news_chunks_en";
    private static final String COLLECTION_CHUNKS_ZH = "news_chunks_zh";

    public void vectorizeChunksEn(News news) {
        String fullText = buildFullText(news, "en");
        if (fullText == null || fullText.isEmpty()) {
            return;
        }

        List<TextChunk> chunks = textChunker.chunk(fullText);
        List<VectorPoint> points = new ArrayList<>();

        for (TextChunk chunk : chunks) {
            float[] embedding = embeddingService.embed(chunk.getText());

            Map<String, Object> payload = new HashMap<>();
            payload.put("news_id", news.getId());
            payload.put("chunk_id", news.getId() + "_en_chunk_" + chunk.getIndex());
            payload.put("chunk_index", chunk.getIndex());
            payload.put("canonical_id", news.getCanonical_id());
            payload.put("published_at", news.getPublication_time());
            payload.put("category", news.getCategory());
            payload.put("source", news.getSource());

            VectorPoint point = VectorPoint.builder()
                    .id((String) payload.get("chunk_id"))
                    .vector(embedding)
                    .payload(payload)
                    .build();

            points.add(point);
        }

        if (!points.isEmpty()) {
            vectorStoreService.upsert(COLLECTION_CHUNKS_EN, points);
            log.debug("Chunk向量生成完成: newsId={}, lang=en, chunks={}",
                    news.getId(), points.size());
        }
    }

    public void vectorizeChunksZh(News news) {
        String fullText = buildFullText(news, "zh");
        if (fullText == null || fullText.isEmpty()) {
            return;
        }

        List<TextChunk> chunks = textChunker.chunk(fullText);
        List<VectorPoint> points = new ArrayList<>();

        for (TextChunk chunk : chunks) {
            float[] embedding = embeddingService.embed(chunk.getText());

            Map<String, Object> payload = new HashMap<>();
            payload.put("news_id", news.getId());
            payload.put("chunk_id", news.getId() + "_zh_chunk_" + chunk.getIndex());
            payload.put("chunk_index", chunk.getIndex());
            payload.put("canonical_id", news.getCanonical_id());
            payload.put("published_at", news.getPublication_time());
            payload.put("category", news.getCategory());
            payload.put("source", news.getSource());

            VectorPoint point = VectorPoint.builder()
                    .id((String) payload.get("chunk_id"))
                    .vector(embedding)
                    .payload(payload)
                    .build();

            points.add(point);
        }

        if (!points.isEmpty()) {
            vectorStoreService.upsert(COLLECTION_CHUNKS_ZH, points);
            log.debug("Chunk向量生成完成: newsId={}, lang=zh, chunks={}",
                    news.getId(), points.size());
        }
    }

    private String buildFullText(News news, String language) {
        StringBuilder sb = new StringBuilder();

        if ("zh".equals(language)) {
            appendIfNotEmpty(sb, news.getTitle_cn());
            appendIfNotEmpty(sb, news.getSummary_cn());
            appendIfNotEmpty(sb, news.getContext_cn());
        } else {
            appendIfNotEmpty(sb, news.getTitle());
            appendIfNotEmpty(sb, news.getSummary());
            appendIfNotEmpty(sb, news.getContext());
        }

        return sb.toString().trim();
    }

    private void appendIfNotEmpty(StringBuilder sb, String text) {
        if (text != null && !text.isEmpty()) {
            if (sb.length() > 0) {
                sb.append("\n\n");
            }
            sb.append(text);
        }
    }
}
