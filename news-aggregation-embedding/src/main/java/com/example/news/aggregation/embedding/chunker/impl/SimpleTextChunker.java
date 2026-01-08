package com.example.news.aggregation.embedding.chunker.impl;

import com.example.news.aggregation.embedding.chunker.TextChunker;
import com.example.news.aggregation.embedding.config.EmbeddingProperties;
import com.example.news.aggregation.embedding.model.TextChunk;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class SimpleTextChunker implements TextChunker {

    private final EmbeddingProperties properties;
    private static final String SENTENCE_DELIMITERS = "。！？.!?\n";

    @Override
    public List<TextChunk> chunk(String text) {
        return chunk(text, properties.getChunkSize(), properties.getChunkOverlap());
    }

    @Override
    public List<TextChunk> chunk(String text, int chunkSize, int overlap) {
        List<TextChunk> chunks = new ArrayList<>();

        if (text == null || text.trim().isEmpty()) {
            return chunks;
        }

        text = text.trim().replaceAll("\\s+", " ");

        if (text.length() <= chunkSize) {
            chunks.add(TextChunk.builder()
                    .index(0)
                    .text(text)
                    .startOffset(0)
                    .endOffset(text.length())
                    .build());
            return chunks;
        }

        int start = 0;
        int index = 0;

        while (start < text.length()) {
            int end = Math.min(start + chunkSize, text.length());

            if (end < text.length()) {
                int sentenceEnd = findSentenceEnd(text, start, end);
                if (sentenceEnd > start + properties.getMinChunkSize()) {
                    end = sentenceEnd;
                }
            }

            String chunkText = text.substring(start, end).trim();

            if (end >= text.length() && chunkText.length() < properties.getMinChunkSize() && !chunks.isEmpty()) {
                TextChunk lastChunk = chunks.get(chunks.size() - 1);
                lastChunk.setText(lastChunk.getText() + " " + chunkText);
                lastChunk.setEndOffset(end);
            } else if (!chunkText.isEmpty()) {
                chunks.add(TextChunk.builder()
                        .index(index++)
                        .text(chunkText)
                        .startOffset(start)
                        .endOffset(end)
                        .build());
            }

            start = end - overlap;
            if (start <= chunks.get(chunks.size() - 1).getStartOffset()) {
                start = end;
            }
        }

        log.debug("文本分段完成: 原文长度={}, chunk数量={}", text.length(), chunks.size());
        return chunks;
    }

    private int findSentenceEnd(String text, int start, int maxEnd) {
        int lastDelimiter = -1;

        for (int i = maxEnd - 1; i >= start + properties.getMinChunkSize(); i--) {
            char c = text.charAt(i);
            if (SENTENCE_DELIMITERS.indexOf(c) >= 0) {
                lastDelimiter = i + 1;
                break;
            }
        }

        return lastDelimiter > 0 ? lastDelimiter : maxEnd;
    }
}