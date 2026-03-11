package com.example.news.aggregation.news.infrastructure.rss;

import com.example.news.aggregation.news.domain.entity.News;
import com.example.news.aggregation.news.exception.NewsErrorCode;
import com.example.news.aggregation.news.exception.NewsException;
import com.rometools.modules.mediarss.MediaEntryModule;
import com.rometools.modules.mediarss.MediaModule;
import com.rometools.modules.mediarss.types.MediaContent;
import com.rometools.modules.mediarss.types.Metadata;
import com.rometools.modules.mediarss.types.Thumbnail;
import com.rometools.rome.feed.synd.SyndContent;
import com.rometools.rome.feed.synd.SyndEntry;
import com.rometools.rome.feed.synd.SyndFeed;
import com.rometools.rome.io.FeedException;
import com.rometools.rome.io.SyndFeedInput;
import com.rometools.rome.io.XmlReader;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * RSS 解析器。
 */
@Slf4j
@Component
public class RssParser {

    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";
    private static final int CONNECT_TIMEOUT = 15000;
    private static final int READ_TIMEOUT = 30000;
    private static final String DEFAULT_IMAGE_URL = "https://www.bbc.com/news/business-67176677";
    private static final Pattern IMG_SRC_PATTERN = Pattern.compile("(?i)<img[^>]+src\\s*=\\s*['\"]([^'\"]+)['\"]");

    /**
     * 解析 RSS。
     */
    public List<News> parse(String rssUrl, String sourceName, String category, String defaultImageUrl) {
        List<News> newsList = new ArrayList<>();

        try {
            URL url = new URL(rssUrl);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestProperty("User-Agent", USER_AGENT);
            connection.setRequestProperty("Accept", "application/rss+xml, application/xml, text/xml, */*");
            connection.setConnectTimeout(CONNECT_TIMEOUT);
            connection.setReadTimeout(READ_TIMEOUT);

            SyndFeedInput input = new SyndFeedInput();
            try (InputStream inputStream = connection.getInputStream()) {
                SyndFeed feed = input.build(new XmlReader(inputStream));

                for (SyndEntry entry : feed.getEntries()) {
                    News news = new News();
                    news.setTitle(entry.getTitle());
                    if (entry.getDescription() != null) {
                        news.setSummary(entry.getDescription().getValue());
                    }
                    news.setSource(sourceName);
                    news.setLink(entry.getLink());
                    news.setCategory(category);

                    if (entry.getPublishedDate() != null) {
                        news.setPublication_time(entry.getPublishedDate().getTime());
                    } else {
                        news.setPublication_time(System.currentTimeMillis());
                    }

                    String imageUrl = extractImageUrl(entry);
                    if (imageUrl == null || imageUrl.isBlank()) {
                        imageUrl = (defaultImageUrl == null || defaultImageUrl.isBlank()) ? DEFAULT_IMAGE_URL : defaultImageUrl;
                    }
                    news.setImage_url(imageUrl);

                    newsList.add(news);
                }

                log.info("从 {} 解析到 {} 条新闻", sourceName, newsList.size());
            }
        } catch (MalformedURLException e) {
            log.error("RSS 源 URL 格式错误: {}", rssUrl, e);
            throw new NewsException("RSS 源 URL 格式错误, url=" + rssUrl, NewsErrorCode.RSS_PARSE_FAILED);
        } catch (FeedException e) {
            log.error("RSS 源解析失败: {}", rssUrl, e);
            throw new RuntimeException(e);
        } catch (IOException e) {
            log.error("RSS 源网络请求失败: {}", rssUrl, e);
            throw new NewsException("RSS 源解析失败, url=" + rssUrl, NewsErrorCode.RSS_PARSE_FAILED);
        }

        return newsList;
    }

    /**
     * 提取图片 URL。
     */
    private String extractImageUrl(SyndEntry entry) {
        String imageUrl = extractFromEnclosures(entry);
        if (imageUrl != null) {
            return imageUrl;
        }

        imageUrl = extractFromMedia(entry);
        if (imageUrl != null) {
            return imageUrl;
        }

        return extractFromContentEncoded(entry);
    }

    /**
     * 从 enclosures 提取图片。
     */
    private String extractFromEnclosures(SyndEntry entry) {
        if (entry.getEnclosures() == null || entry.getEnclosures().isEmpty()) {
            return null;
        }
        return entry.getEnclosures().stream()
                .filter(e -> e.getType() != null && e.getType().startsWith("image/"))
                .map(e -> e.getUrl())
                .findFirst()
                .orElse(null);
    }

    /**
     * 从 media:content / media:thumbnail 提取图片。
     */
    private String extractFromMedia(SyndEntry entry) {
        MediaEntryModule mediaModule = (MediaEntryModule) entry.getModule(MediaModule.URI);
        if (mediaModule == null) {
            return null;
        }

        MediaContent[] mediaContents = mediaModule.getMediaContents();
        if (mediaContents != null && mediaContents.length > 0) {
            for (MediaContent content : mediaContents) {
                if (content.getReference() != null) {
                    String type = content.getType();
                    if (type == null || type.startsWith("image/")) {
                        return content.getReference().toString();
                    }
                }
            }
        }

        Metadata metadata = mediaModule.getMetadata();
        if (metadata != null && metadata.getThumbnail() != null && metadata.getThumbnail().length > 0) {
            Thumbnail[] thumbnails = metadata.getThumbnail();
            if (thumbnails[0] != null && thumbnails[0].getUrl() != null) {
                return thumbnails[0].getUrl().toString();
            }
        }

        return null;
    }

    /**
     * 从 content:encoded HTML 中提取首张图片 URL。
     */
    private String extractFromContentEncoded(SyndEntry entry) {
        if (entry.getContents() == null || entry.getContents().isEmpty()) {
            return null;
        }

        for (SyndContent content : entry.getContents()) {
            if (content == null || content.getValue() == null || content.getValue().isBlank()) {
                continue;
            }
            Matcher matcher = IMG_SRC_PATTERN.matcher(content.getValue());
            if (matcher.find()) {
                return normalizeHtmlUrl(matcher.group(1));
            }
        }
        return null;
    }

    private String normalizeHtmlUrl(String url) {
        if (url == null) {
            return null;
        }
        return url.replace("&#038;", "&").replace("&amp;", "&");
    }
}