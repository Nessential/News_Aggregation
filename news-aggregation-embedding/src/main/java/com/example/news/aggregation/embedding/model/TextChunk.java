package com.example.news.aggregation.embedding.model;


import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TextChunk {
    private int index;
    private String text;
    private int startOffset;
    private int endOffset;
}