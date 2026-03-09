package com.example.news.aggregation.news.infrastructure.content;

import lombok.extern.slf4j.Slf4j;
import net.dankito.readability4j.Article;
import net.dankito.readability4j.Readability4J;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 网页正文提取器。
 */
@Slf4j
@Component
public class ContentExtractor {

    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";
    private static final int TIMEOUT = 15000;

    /**
     * 从 URL 提取正文内容。
     * 优先使用 readability 的 content(html) 保留段落结构，失败时回退到 textContent。
     */
    public String extractContent(String url) {
        try {
            Document document = Jsoup.connect(url)
                    .userAgent(USER_AGENT)
                    .timeout(TIMEOUT)
                    .get();

            String html = document.html();
            Readability4J readability = new Readability4J(url, html);
            Article article = readability.parse();

            String content = extractFromArticleHtml(article);
            if (!hasText(content)) {
                content = normalizeParagraphText(article.getTextContent());
            }

            if (hasText(content)) {
                log.debug("成功提取正文，长度={}", content.length());
                return content;
            }

            log.warn("提取正文为空: {}", url);
            return null;
        } catch (Exception e) {
            log.error("提取正文失败: {}", url, e);
            return null;
        }
    }

    /**
     * 从 readability 的 content(html) 中提取段落。
     */
    private String extractFromArticleHtml(Article article) {
        if (article == null || !hasText(article.getContent())) {
            return null;
        }

        Document contentDoc = Jsoup.parseBodyFragment(article.getContent());
        List<String> paragraphs = new ArrayList<>();

        for (Element p : contentDoc.select("p")) {
            String text = normalizeInlineText(p.text());
            if (hasText(text)) {
                paragraphs.add(text);
            }
        }

        if (paragraphs.isEmpty()) {
            return normalizeParagraphText(contentDoc.text());
        }
        return String.join("\n\n", paragraphs);
    }

    /**
     * 保留段落结构：段内空白压缩为单空格，段落间使用双换行。
     */
    private String normalizeParagraphText(String rawText) {
        if (!hasText(rawText)) {
            return null;
        }

        String normalized = rawText.replace("\r\n", "\n").replace('\r', '\n');
        String[] lines = normalized.split("\n");
        List<String> paragraphs = new ArrayList<>();
        StringBuilder current = new StringBuilder();

        for (String line : lines) {
            String trimmed = line == null ? "" : line.trim();
            if (trimmed.isEmpty()) {
                flushParagraph(paragraphs, current);
                continue;
            }
            if (!current.isEmpty()) {
                current.append(' ');
            }
            current.append(normalizeInlineText(trimmed));
        }
        flushParagraph(paragraphs, current);

        if (paragraphs.isEmpty()) {
            return normalizeInlineText(normalized);
        }
        return String.join("\n\n", paragraphs);
    }

    private String normalizeInlineText(String text) {
        if (text == null) {
            return "";
        }
        return text.replaceAll("\\s+", " ").trim();
    }

    private void flushParagraph(List<String> paragraphs, StringBuilder current) {
        if (current.isEmpty()) {
            return;
        }
        paragraphs.add(current.toString());
        current.setLength(0);
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}