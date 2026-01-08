package com.example.news.aggregation.embedding.chunker;

import com.example.news.aggregation.embedding.model.TextChunk;
import java.util.List;

public interface TextChunker {
    List<TextChunk> chunk(String text);

    List<TextChunk> chunk(String text, int chunkSize, int overlap);
}