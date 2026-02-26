package com.example.news.aggregation.llm.springai.intent;

import lombok.Data;

import java.util.Collections;
import java.util.List;

/**
 * 意图规则集合
 */
@Data
public class IntentRuleSet {

    /** 规则版本 */
    private String version;

    /** LLM意图识别提示词模板 */
    private String promptTemplate;

    /** 提示词Key（用于从统一提示词仓库加载） */
    private String promptKey;

    /** 任务族规则 */
    private List<TaskFamilyRule> taskFamilyRules;

    /** 直答关键词（命中后可走 NONE） */
    private List<String> directAnswerKeywords;

    /** 直答正则（命中后可走 NONE） */
    private List<String> directAnswerPatterns;

    /** 新闻关键词（命中后倾向检索） */
    private List<String> newsKeywords;

    public static IntentRuleSet defaultRuleSet() {
        IntentRuleSet ruleSet = new IntentRuleSet();
        ruleSet.setVersion("default");
        ruleSet.setPromptTemplate(defaultPromptTemplate());
        ruleSet.setTaskFamilyRules(Collections.emptyList());
        return ruleSet;
    }

    public static String defaultPromptTemplate() {
        return "";
    }

    @Data
    public static class TaskFamilyRule {
        /** 任务族 */
        private String taskFamily;

        /** 关键词列表 */
        private List<String> keywords;

        /** 匹配原因说明 */
        private String reason;
    }
}
