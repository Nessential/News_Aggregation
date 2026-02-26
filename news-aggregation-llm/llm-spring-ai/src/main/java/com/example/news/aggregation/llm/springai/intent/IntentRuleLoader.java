package com.example.news.aggregation.llm.springai.intent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import java.io.InputStream;

/**
 * 意图规则加载器
 * 通过配置中的路径加载规则文件并缓存
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IntentRuleLoader {

    private final ResourceLoader resourceLoader;
    private final IntentRuleProperties properties;

    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory())
            .findAndRegisterModules();

    private volatile IntentRuleSet cachedRuleSet;

    @PostConstruct
    public void init() {
        this.cachedRuleSet = loadRules();
    }

    public IntentRuleSet getRuleSet() {
        if (cachedRuleSet == null) {
            cachedRuleSet = loadRules();
        }
        return cachedRuleSet;
    }

    public synchronized IntentRuleSet reload() {
        cachedRuleSet = loadRules();
        return cachedRuleSet;
    }

    private IntentRuleSet loadRules() {
        String path = properties.getRulesPath();
        try {
            Resource resource = resourceLoader.getResource(path);
            if (!resource.exists()) {
                log.warn("意图规则文件不存在，使用默认规则: path={}", path);
                return IntentRuleSet.defaultRuleSet();
            }
            try (InputStream inputStream = resource.getInputStream()) {
                IntentRuleSet ruleSet = yamlMapper.readValue(inputStream, IntentRuleSet.class);
                if (ruleSet == null) {
                    log.warn("意图规则文件为空，使用默认规则: path={}", path);
                    return IntentRuleSet.defaultRuleSet();
                }
                log.info("意图规则加载完成: path={}, version={}, rules={}",
                        path,
                        ruleSet.getVersion(),
                        ruleSet.getTaskFamilyRules() == null ? 0 : ruleSet.getTaskFamilyRules().size());
                return ruleSet;
            }
        } catch (Exception e) {
            log.warn("意图规则加载失败，使用默认规则: path={}, error={}", path, e.getMessage());
            return IntentRuleSet.defaultRuleSet();
        }
    }
}
