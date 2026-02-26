package com.example.news.aggregation.llm.springai.controller;

import com.example.news.aggregation.llm.springai.contract.GenerateRequest;
import com.example.news.aggregation.llm.springai.contract.GenerateResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * LLM 生成控制器
 */
@Slf4j
@RestController
@RequestMapping("/api/llm")
@RequiredArgsConstructor
public class GenerationController {

    private final ChatClient chatClient;

    // 统一的生成接口，返回模型输出文本
    @PostMapping("/generate")
    public ResponseEntity<GenerateResponse> generate(@RequestBody GenerateRequest request) {
        String prompt = request != null ? request.getPrompt() : null;
        if (prompt == null || prompt.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(GenerateResponse.builder().content("").build());
        }
        String content = chatClient.prompt()
                .user(prompt)
                .call()
                .content();
        log.info("通用生成-模型原始输出(截断)={}", truncate(content, 500));
        return ResponseEntity.ok(GenerateResponse.builder().content(content).build());
    }

    private String truncate(String text, int maxLen) {
        if (text == null) {
            return "";
        }
        if (text.length() <= maxLen) {
            return text;
        }
        return text.substring(0, maxLen) + "...";
    }
}
