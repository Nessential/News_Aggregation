package com.example.news.aggregation.news.service.impl;

import com.example.news.aggregation.base.util.translate.BaiduTranslateClient;
import com.example.news.aggregation.news.domain.entity.News;
import com.example.news.aggregation.news.infrastructure.mapper.NewsMapper;
import com.example.news.aggregation.news.service.TranslationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 翻译服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TranslationServiceImpl implements TranslationService {

    private final NewsMapper newsMapper;
    private final BaiduTranslateClient translateClient;

    /**
     * 百度翻译免费版一般是 1 QPS，保持保守节流。
     */
    private static final long TRANSLATE_INTERVAL_MS = 1000;

    /**
     * 单次请求字符上限，留有签名和编码冗余。
     */
    private static final int MAX_BATCH_CHARS = 4200;

    /**
     * 段落分隔符（落库使用双换行）。
     */
    private static final String PARAGRAPH_SEPARATOR = "\n\n";

    /**
     * 批量请求时的哨兵分隔符，尽量使用不会被自然文本碰撞的标记。
     */
    private static final String BATCH_DELIMITER = "\n<<<NEWS_PARAGRAPH_DELIMITER_V1>>>\n";

    @Override
    public void translatePendingNews(int batchSize) {
        List<News> newsList = newsMapper.selectForTranslation(batchSize);

        if (newsList.isEmpty()) {
            log.info("没有待翻译新闻");
            return;
        }

        log.info("开始翻译 {} 条新闻", newsList.size());
        int success = 0;
        int failed = 0;

        for (News news : newsList) {
            try {
                boolean result = translateSingleNews(news);
                if (result) {
                    success++;
                } else {
                    failed++;
                }

                Thread.sleep(TRANSLATE_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.error("翻译任务被中断");
                break;
            } catch (Exception e) {
                log.error("翻译新闻失败: id={}", news.getId(), e);
                failed++;
            }
        }

        log.info("翻译任务完成，成功: {}, 失败: {}", success, failed);
    }

    private boolean translateSingleNews(News news) {
        try {
            // 标题翻译
            String titleCn = translateClient.translate(news.getTitle());
            if (titleCn != null) {
                news.setTitle_cn(titleCn);
            }

            // 摘要翻译
            if (hasText(news.getSummary())) {
                Thread.sleep(TRANSLATE_INTERVAL_MS);
                String summaryCn = translateClient.translate(news.getSummary());
                if (summaryCn != null) {
                    news.setSummary_cn(summaryCn);
                }
            }

            // 正文翻译：按段分批请求，保持段落结构
            if (hasText(news.getContext())) {
                Thread.sleep(TRANSLATE_INTERVAL_MS);
                String contextCn = translateContextByParagraphs(news.getContext());
                if (contextCn != null) {
                    news.setContext_cn(contextCn);
                }
            }

            news.setTranslation_status(1);
            newsMapper.updateById(news);
            log.debug("翻译成功: id={}, title={}", news.getId(), news.getTitle());
            return true;
        } catch (Exception e) {
            log.error("翻译单条新闻失败: id={}", news.getId(), e);
            news.setTranslation_status(2);
            newsMapper.updateById(news);
            return false;
        }
    }

    private String translateContextByParagraphs(String context) throws InterruptedException {
        List<String> paragraphs = splitParagraphs(context);
        if (paragraphs.isEmpty()) {
            return null;
        }

        List<String> translatedParagraphs = new ArrayList<>(paragraphs.size());
        int index = 0;
        while (index < paragraphs.size()) {
            List<String> batch = new ArrayList<>();
            int batchChars = 0;
            int cursor = index;

            while (cursor < paragraphs.size()) {
                String paragraph = paragraphs.get(cursor);
                int additional = paragraph.length() + (batch.isEmpty() ? 0 : BATCH_DELIMITER.length());

                if (!batch.isEmpty() && batchChars + additional > MAX_BATCH_CHARS) {
                    break;
                }

                if (batch.isEmpty() && paragraph.length() > MAX_BATCH_CHARS) {
                    // 极长单段，切片后再翻译，避免单请求超限
                    List<String> sliced = sliceLongParagraph(paragraph, MAX_BATCH_CHARS - 200);
                    String translatedSliced = translateSingleParagraphBatch(sliced);
                    if (translatedSliced == null) {
                        return null;
                    }
                    translatedParagraphs.add(translatedSliced);
                    cursor++;
                    break;
                }

                batch.add(paragraph);
                batchChars += additional;
                cursor++;
            }

            if (!batch.isEmpty()) {
                String joined = String.join(BATCH_DELIMITER, batch);
                String translatedJoined = translateClient.translate(joined, "en", "zh");
                if (translatedJoined == null) {
                    return null;
                }

                String[] parts = translatedJoined.split(java.util.regex.Pattern.quote(BATCH_DELIMITER), -1);
                if (parts.length != batch.size()) {
                    // 分隔符被模型破坏时，降级为单段翻译，保证正确性。
                    log.warn("批量翻译段落对齐失败，降级单段翻译: batchSize={}, parts={}", batch.size(), parts.length);
                    for (String paragraph : batch) {
                        Thread.sleep(TRANSLATE_INTERVAL_MS);
                        String translated = translateClient.translate(paragraph, "en", "zh");
                        if (translated == null) {
                            return null;
                        }
                        translatedParagraphs.add(translated.trim());
                    }
                } else {
                    for (String part : parts) {
                        translatedParagraphs.add(part == null ? "" : part.trim());
                    }
                }

                // 批量请求之间节流
                Thread.sleep(TRANSLATE_INTERVAL_MS);
            }

            index = cursor;
        }

        return String.join(PARAGRAPH_SEPARATOR, translatedParagraphs);
    }

    private String translateSingleParagraphBatch(List<String> slices) throws InterruptedException {
        List<String> translatedSlices = new ArrayList<>(slices.size());
        for (String slice : slices) {
            Thread.sleep(TRANSLATE_INTERVAL_MS);
            String translated = translateClient.translate(slice, "en", "zh");
            if (translated == null) {
                return null;
            }
            translatedSlices.add(translated.trim());
        }
        return String.join("", translatedSlices);
    }

    private List<String> splitParagraphs(String text) {
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n').trim();
        if (normalized.isEmpty()) {
            return List.of();
        }

        String[] blocks = normalized.split("\\n\\s*\\n+");
        List<String> paragraphs = new ArrayList<>(blocks.length);
        for (String block : blocks) {
            if (!hasText(block)) {
                continue;
            }
            paragraphs.add(block.trim().replaceAll("\\s+", " "));
        }

        if (!paragraphs.isEmpty()) {
            return paragraphs;
        }

        // 兜底：按单行切分
        String[] lines = normalized.split("\\n+");
        for (String line : lines) {
            if (hasText(line)) {
                paragraphs.add(line.trim().replaceAll("\\s+", " "));
            }
        }
        return paragraphs;
    }

    private List<String> sliceLongParagraph(String paragraph, int maxLen) {
        List<String> slices = new ArrayList<>();
        int len = paragraph.length();
        int start = 0;
        while (start < len) {
            int end = Math.min(start + maxLen, len);
            slices.add(paragraph.substring(start, end));
            start = end;
        }
        return slices;
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
