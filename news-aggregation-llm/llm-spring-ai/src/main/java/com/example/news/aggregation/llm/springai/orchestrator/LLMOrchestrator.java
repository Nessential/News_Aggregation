package com.example.news.aggregation.llm.springai.orchestrator;

import com.example.news.aggregation.llm.springai.contract.*;
import com.example.news.aggregation.llm.springai.tool.RetrieveTool;
import com.example.news.aggregation.llm.springai.tool.RerankTool;
import com.example.news.aggregation.llm.springai.tool.dto.RetrievalResult;
import com.example.news.aggregation.llm.springai.validator.OutputValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;
import org.springframework.util.StopWatch;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * LLM编排器 - MVP实现
 * 
 * 使用ChatClient实现的简化编排器（而非完整的Graph API）
 * Phase 6 MVP: 专注于端到端功能实现
 * 
 * 核心6步流程：
 * 1. Router: 意图识别
 * 2. Retrieve: 检索召回
 * 3. Rerank: 重排序
 * 4. Generate: 生成答案
 * 5. Validate: 验证输出
 * 6. Build Response: 构建响应
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LLMOrchestrator {
    
    /** ChatClient构建器，用于调用LLM */
    private final ChatClient.Builder chatClientBuilder;
    
    /** 检索工具，负责召回相关文档 */
    private final RetrieveTool retrieveTool;
    
    /** 重排工具，负责精排文档 */
    private final RerankTool rerankTool;
    
    /** 输出验证器，检查答案质量 */
    private final OutputValidator validator;
    
    /**
     * 处理用户查询（端到端）
     * 执行完整的RAG流程
     * 
     * @param sessionId 会话 ID
     * @param userMessage 用户消息
     * @param userId 用户ID
     * @return 聊天响应
     */
    public ChatResponse processQuery(String sessionId, String userMessage, String userId) {
        log.info("[Orchestrator] Processing query: sessionId={}, userId={}, message={}", 
                sessionId, userId, userMessage);
        
        try {
            // 步顤1: Router - 分析意图
            RouterResult routerResult = route(userMessage);
            log.info("[Orchestrator] Router result: taskFamily={}, retrievalMode={}", 
                    routerResult.getTaskFamily(), routerResult.getRetrievalMode());
            
            // 步顤2: 检查是否需要澄清
            if (Boolean.TRUE.equals(routerResult.getNeedsClarification())) {
                return ChatResponse.builder()
                        .sessionId(sessionId)
                        .answer(routerResult.getClarificationQuestion())
                        .taskFamily(routerResult.getTaskFamily())
                        .timestamp(System.currentTimeMillis())
                        .build();
            }
            
            // 步顤3: 检索相关文档
            List<RetrievalResult> retrievalResults = retrieve(userMessage, routerResult);
            log.info("[Orchestrator] Retrieved {} documents", retrievalResults.size());
            
            // 步顤4: 重排序
            List<RetrievalResult> rerankedResults = rerank(userMessage, retrievalResults);
            log.info("[Orchestrator] Reranked to {} documents", rerankedResults.size());
            
            // 步顤5: 生成答案
            GeneratorDraft draft = generate(userMessage, rerankedResults, routerResult);
            log.info("[Orchestrator] Generated answer: qualityScore={}", draft.getQualityScore());
            
            // 步顤6: 验证输出
            if (!validator.validate(draft)) {
                log.warn("[Orchestrator] Draft validation failed, using conservative answer");
                draft = GeneratorDraft.conservative("Unable to generate high-quality answer");
            }
            
            // 步顤7: 构建响应
            return buildResponse(sessionId, draft, rerankedResults, routerResult);
            
        } catch (Exception e) {
            log.error("[Orchestrator] Error processing query", e);
            return ChatResponse.builder()
                    .sessionId(sessionId)
                    .answer("Sorry, I encountered an error processing your request. Please try again.")
                    .taskFamily("ERROR")
                    .timestamp(System.currentTimeMillis())
                    .build();
        }
    }
    
    /**
     * Router: 分析用户意图
     * 识别任务类型(QA/SUMMARY/COMPARE/TIMELINE/DEEP_DIVE)
     * 和检索模式(SEMANTIC/KEYWORD/HYBRID)
     * 
     * @param userMessage 用户消息
     * @return Router结果，包含任务类型和检索模式
     */
    private RouterResult route(String userMessage) {
        try {
            ChatClient client = chatClientBuilder.build();
            
            String routerPrompt = String.format(
                "Analyze the user query and respond with JSON:\n" +
                "{\"taskFamily\": \"QA|SUMMARY|COMPARE|TIMELINE|DEEP_DIVE\", \"retrievalMode\": \"SEMANTIC|KEYWORD|HYBRID\", \"riskLevel\": \"LOW|MEDIUM|HIGH\"}\n\n" +
                "Task definitions:\n" +
                "- QA: Simple question answering\n" +
                "- SUMMARY: Summarize multiple articles\n" +
                "- COMPARE: Compare different viewpoints\n" +
                "- TIMELINE: Chronological analysis\n" +
                "- DEEP_DIVE: In-depth investigation\n\n" +
                "User query: %s\n\n" +
                "Respond with ONLY valid JSON.",
                userMessage
            );


            String response = client.prompt()
                    .user(routerPrompt)
                    .call()
                    .content();
            
            // 使用鲁棒解析，避免JSON格式错误
            String taskFamily = extractTaskFamily(response);
            String retrievalMode = extractRetrievalMode(response);
            String riskLevel = extractRiskLevel(response);
            
            return RouterResult.builder()
                    .taskFamily(taskFamily)
                    .retrievalMode(retrievalMode)
                    .riskLevel(riskLevel)
                    .needsClarification(false)
                    .build();
                    
        } catch (Exception e) {
            log.warn("[Orchestrator] Router failed, using default", e);
            return RouterResult.defaultQA();
        }
    }
    
    /**
     * 从LLM响应中提取任务类型
     * 使用鲁棒的模式匹配，避免JSON解析失败
     * 
     * @param response LLM的JSON响应
     * @return 任务类型 (QA/SUMMARY/COMPARE/TIMELINE/DEEP_DIVE)
     */
    private String extractTaskFamily(String response) {
        if (response == null) return "QA";
        
        String cleaned = response.toUpperCase().replaceAll("[^A-Z_]", "");
        
        if (cleaned.contains("SUMMARY")) return "SUMMARY";
        if (cleaned.contains("COMPARE")) return "COMPARE";
        if (cleaned.contains("TIMELINE")) return "TIMELINE";
        if (cleaned.contains("DEEPDIVE") || cleaned.contains("DEEP_DIVE")) return "DEEP_DIVE";
        if (cleaned.contains("QA")) return "QA";
        
        return "QA"; // 默认充底
    }
    
    /**
     * 从LLM响应中提取检索模式
     * 
     * @param response LLM的JSON响应
     * @return 检索模式 (SEMANTIC/KEYWORD/HYBRID)
     */
    private String extractRetrievalMode(String response) {
        if (response == null) return "HYBRID";
        
        String cleaned = response.toUpperCase();
        
        if (cleaned.contains("SEMANTIC")) return "SEMANTIC";
        if (cleaned.contains("KEYWORD")) return "KEYWORD";
        if (cleaned.contains("HYBRID")) return "HYBRID";
        
        return "HYBRID"; // 默认使用最全面的模式
    }
    
    /**
     * 从LLM响应中提取风险级别
     * 
     * @param response LLM的JSON响应
     * @return 风险级别 (LOW/MEDIUM/HIGH)
     */
    private String extractRiskLevel(String response) {
        if (response == null) return "LOW";
        
        String cleaned = response.toUpperCase();
        
        if (cleaned.contains("HIGH")) return "HIGH";
        if (cleaned.contains("MEDIUM")) return "MEDIUM";
        if (cleaned.contains("LOW")) return "LOW";
        
        return "LOW"; // 默认使用最安全的选项
    }
    
    /**
     * 检索相关文档
     * 根据检索模式选择不同的检索策略
     * 
     * @param query 用户查询
     * @param routerResult Router结果
     * @return 检索结果列表
     */
    private List<RetrievalResult> retrieve(String query, RouterResult routerResult) {
        String retrievalMode = routerResult.getRetrievalMode();
        
        if ("HYBRID".equals(retrievalMode)) {
            return retrieveTool.hybridRetrieve(query, 10);
        } else {
            return retrieveTool.retrieveNews(query, 10);
        }
    }
    
    /**
     * 重排文档
     * 使用MMR算法平衡相关性和多样性
     * 
     * @param query 用户查询
     * @param candidates 候选文档列表
     * @return 重排后的Top-N文档
     */
    private List<RetrievalResult> rerank(String query, List<RetrievalResult> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return new ArrayList<>();
        }
        
        // 使用MMR算法，lambda=0.7 (70%相关性，30%多样性)
        return rerankTool.mmrRerank(candidates, 5, 0.7);
    }
    
    /**
     * 生成答案
     * 基于检索到的证据，调用LLM生成答案
     * 
     * @param query 用户查询
     * @param evidence 证据列表
     * @param routerResult Router结果
     * @return 生成器草稿，包含答案、引用和质量评分
     */
    private GeneratorDraft generate(String query, List<RetrievalResult> evidence, RouterResult routerResult) {
        try {
            ChatClient client = chatClientBuilder.build();
            
            // 构建证据上下文
            String context = evidence.stream()
                    .map(r -> String.format("[%s] %s: %s", r.getId(), r.getTitle(), r.getContent()))
                    .collect(Collectors.joining("\n\n"));
            
            // 生成任务专用Prompt
            String generatePrompt = buildTaskSpecificPrompt(query, context, routerResult.getTaskFamily());
            
            // 调用LLM生成答案
            String answer = client.prompt()
                    .user(generatePrompt)
                    .call()
                    .content();
            
            if (answer == null || answer.isEmpty()) {
                answer = "Unable to generate answer";
            }
            
            // 提取引用
            List<GeneratorDraft.Citation> citations = extractCitations(answer, evidence);
            
            // 计算质量评分
            double qualityScore = calculateQualityScore(answer, citations, evidence);
            
            return GeneratorDraft.builder()
                    .answer(answer)
                    .citations(citations)
                    .qualityScore(qualityScore)
                    .build();
                    
        } catch (Exception e) {
            log.error("[Orchestrator] Generation failed", e);
            return GeneratorDraft.conservative("Error generating answer");
        }
    }
    
    /**
     * 构建任务专用Prompt
     * 根据taskFamily生成不同风格的Prompt
     * 
     * 支持5种任务类型：
     * - QA: 问答
     * - SUMMARY: 摘要
     * - COMPARE: 对比
     * - TIMELINE: 时间线
     * - DEEP_DIVE: 深度分析
     * 
     * @param query 用户查询
     * @param context 证据上下文
     * @param taskFamily 任务类型
     * @return 格式化的Prompt字符串
     */
    private String buildTaskSpecificPrompt(String query, String context, String taskFamily) {
        String baseContext = context.isEmpty() ? "No relevant information found." : context;
        
        switch (taskFamily) {
            case "SUMMARY": // 摘要任务
                return String.format(
                    "You are a news summarization assistant. Summarize the following news articles.\n\n" +
                    "Articles:\n%s\n\n" +
                    "User request: %s\n\n" +
                    "Provide a comprehensive summary that:\n" +
                    "1. Highlights the key points from all articles\n" +
                    "2. Organizes information logically\n" +
                    "3. Avoids redundancy\n" +
                    "4. Cites sources using [source_id] notation\n\n" +
                    "Summary:",
                    baseContext, query
                );
            
            case "COMPARE": // 对比任务
                return String.format(
                    "You are a news comparison analyst. Compare and contrast the following articles.\n\n" +
                    "Articles:\n%s\n\n" +
                    "User request: %s\n\n" +
                    "Provide a comparison that:\n" +
                    "1. Identifies similarities and differences\n" +
                    "2. Highlights conflicting viewpoints if any\n" +
                    "3. Presents both sides objectively\n" +
                    "4. Cites sources using [source_id] notation\n\n" +
                    "Comparison:",
                    baseContext, query
                );
            
            case "TIMELINE": // 时间线任务
                return String.format(
                    "You are a news timeline creator. Create a chronological timeline from the articles.\n\n" +
                    "Articles:\n%s\n\n" +
                    "User request: %s\n\n" +
                    "Provide a timeline that:\n" +
                    "1. Orders events chronologically\n" +
                    "2. Includes key dates and milestones\n" +
                    "3. Shows progression of events\n" +
                    "4. Cites sources using [source_id] notation\n\n" +
                    "Timeline:",
                    baseContext, query
                );
            
            case "DEEP_DIVE": // 深度分析任务
                return String.format(
                    "You are an investigative news analyst. Provide an in-depth analysis.\n\n" +
                    "Articles:\n%s\n\n" +
                    "User request: %s\n\n" +
                    "Provide an analysis that:\n" +
                    "1. Explores the topic comprehensively\n" +
                    "2. Examines causes and implications\n" +
                    "3. Provides context and background\n" +
                    "4. Discusses potential impacts\n" +
                    "5. Cites sources using [source_id] notation\n\n" +
                    "Analysis:",
                    baseContext, query
                );
            
            case "QA": // 问答任务（默认）
            default:
                return String.format(
                    "You are a helpful news assistant. Answer the user's question based on the provided context.\n\n" +
                    "Context:\n%s\n\n" +
                    "User question: %s\n\n" +
                    "Please provide a clear, accurate answer that:\n" +
                    "1. Directly answers the question\n" +
                    "2. Is based on the provided context\n" +
                    "3. Is concise but complete\n" +
                    "4. Cites sources using [source_id] notation\n\n" +
                    "Answer:",
                    baseContext, query
                );
        }
    }
    
    /**
     * 从LLM答案中提取引用
     * 解析[source_id]标记并创建Citation对象
     * 
     * 工作流程：
     * 1. 使用正则表达式匹配[source_id]
     * 2. 验证source_id是否存在于证据中
     * 3. 提取引用上下文（前后50字符）
     * 4. 创建Citation对象
     * 
     * @param answer LLM生成的答案
     * @param evidence 证据列表
     * @return 引用列表
     */
    private List<GeneratorDraft.Citation> extractCitations(String answer, List<RetrievalResult> evidence) {
        List<GeneratorDraft.Citation> citations = new ArrayList<>();
        
        if (answer == null || evidence == null || evidence.isEmpty()) {
            return citations;
        }
        
        // 创建sourceId映射表
        Map<String, RetrievalResult> evidenceMap = evidence.stream()
                .collect(Collectors.toMap(RetrievalResult::getId, r -> r, (a, b) -> a));
        
        // 正则匹配[source_id]
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("\\[([^\\]]+)\\]");
        java.util.regex.Matcher matcher = pattern.matcher(answer);
        
        int position = 0;
        while (matcher.find()) {
            String sourceId = matcher.group(1);
            
            // 验证source_id合法性
            if (evidenceMap.containsKey(sourceId)) {
                RetrievalResult source = evidenceMap.get(sourceId);
                
                // 提取引用上下文
                int start = Math.max(0, matcher.start() - 50);
                int end = Math.min(answer.length(), matcher.end() + 50);
                String citationText = answer.substring(start, end).trim();
                
                citations.add(GeneratorDraft.Citation.builder()
                        .sourceId(sourceId)
                        .text(citationText)
                        .position(position++)
                        .build());
            }
        }
        
        log.debug("[Orchestrator] Extracted {} citations from answer", citations.size());
        return citations;
    }
    
    /**
     * 计算答案质量评分
     * 基于三个维度：
     * 1. 答案长度 (40%权重): 50-2000字符最佳
     * 2. 引用数量 (30%权重): 2-5个引用最佳
     * 3. 证据覆盖 (30%权重): 答案提到了多少证据
     * 
     * @param answer 答案文本
     * @param citations 引用列表
     * @param evidence 证据列表
     * @return 质量评分 (0.0-1.0)
     */
    private double calculateQualityScore(String answer, List<GeneratorDraft.Citation> citations, 
                                         List<RetrievalResult> evidence) {
        double score = 0.0;
        
        // 因子1: 答案长度 (40%权重)
        int length = answer.length();
        if (length >= 50 && length <= 2000) {
            score += 0.4; // 良好长度
        } else if (length > 2000 && length <= 5000) {
            score += 0.3; // 略长但可接受
        } else if (length >= 20 && length < 50) {
            score += 0.2; // 过短
        } else if (length > 5000) {
            score += 0.1; // 过长
        }
        // length < 20: score += 0 (非常差)
        
        // 因子2: 引用数量 (30%权重)
        if (citations != null && !citations.isEmpty()) {
            int citationCount = citations.size();
            if (citationCount >= 2 && citationCount <= 5) {
                score += 0.3; // 理想引用数
            } else if (citationCount == 1) {
                score += 0.2; // 至少有一个
            } else if (citationCount > 5) {
                score += 0.15; // 引用过多
            }
        }
        // 无引用: score += 0
        
        // 因子3: 证据覆盖 (30%权重)
        if (evidence != null && !evidence.isEmpty()) {
            // 检查答案是否提到证据内容
            int evidenceMentioned = 0;
            for (RetrievalResult ev : evidence) {
                // 检查是否提到证据ID
                if (answer.contains(ev.getId())) {
                    evidenceMentioned++;
                    continue;
                }
                
                // 检查是否提到证据标题（包含null检查）
                String title = ev.getTitle();
                if (title != null && title.length() > 0) {
                    int substringLength = Math.min(20, title.length());
                    if (answer.toLowerCase().contains(title.toLowerCase().substring(0, substringLength))) {
                        evidenceMentioned++;
                    }
                }
            }
            
            double coverageRatio = (double) evidenceMentioned / evidence.size();
            if (coverageRatio >= 0.5) {
                score += 0.3; // 良好覆盖
            } else if (coverageRatio >= 0.3) {
                score += 0.2; // 可接受覆盖
            } else if (coverageRatio > 0) {
                score += 0.1; // 有一些覆盖
            }
        } else {
            // 无证据，无法评估覆盖
            score += 0.15; // 中性评分
        }
        
        log.debug("[Orchestrator] Calculated quality score: {}", score);
        return Math.min(1.0, score);
    }
    
    /**
     * 构建最终响应
     * 将GeneratorDraft转换为ChatResponse
     * 
     * @param sessionId 会话 ID
     * @param draft 生成器草稿
     * @param sources 来源列表
     * @param routerResult Router结果
     * @return 聊天响应
     */
    private ChatResponse buildResponse(String sessionId, GeneratorDraft draft, 
                                       List<RetrievalResult> sources, RouterResult routerResult) {
        
        List<ChatResponse.Source> sourcesDto = sources.stream()
                .limit(3)
                .map(r -> ChatResponse.Source.builder()
                        .id(r.getId())
                        .title(r.getTitle())
                        .url(r.getUrl() != null ? r.getUrl() : "")
                        .relevance(r.getScore())
                        .build())
                .collect(Collectors.toList());
        
        return ChatResponse.builder()
                .sessionId(sessionId)
                .answer(draft.getAnswer())
                .taskFamily(routerResult.getTaskFamily())
                .sources(sourcesDto)
                .timestamp(System.currentTimeMillis())
                .build();
    }
}
