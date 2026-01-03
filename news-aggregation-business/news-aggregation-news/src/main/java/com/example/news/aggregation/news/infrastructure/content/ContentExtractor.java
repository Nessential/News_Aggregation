package com.example.news.aggregation.news.infrastructure.content;

import lombok.extern.slf4j.Slf4j;
import net.dankito.readability4j.Article;
import net.dankito.readability4j.Readability4J;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.stereotype.Component;

/**
 * 网页正文提取器
 */
@Slf4j
@Component
public class ContentExtractor {

    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";
    private static final int TIMEOUT = 15000;

    /**
     * 从URL提取正文内容
     *
     * @param url 网页URL
     * @return 正文内容，失败返回null
     */
    public String extractContent(String url) {
        try {
            // 获取网页HTML
            Document document = Jsoup.connect(url)
                    .userAgent(USER_AGENT)
                    .timeout(TIMEOUT)
                    .get();

            String html = document.html();

            // 使用Readability4J提取正文
            Readability4J readability = new Readability4J(url, html);
            Article article = readability.parse();

            String content = article.getTextContent();

            if (content != null && !content.trim().isEmpty()) {
                // 清理多余空白
                content = content.replaceAll("\\s+", " ").trim();
                log.debug("成功提取正文，长度: {}", content.length());
                return content;
            }

            log.warn("提取正文为空: {}", url);
            return null;

        } catch (Exception e) {
            log.error("提取正文失败: {}", url, e);
            return null;
        }
    }
}