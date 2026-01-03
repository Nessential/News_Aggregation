package com.example.news.aggregation.news;

import com.example.news.aggregation.base.util.translate.BaiduTranslateClient;
import com.example.news.aggregation.news.infrastructure.content.ContentExtractor;
import com.example.news.aggregation.news.service.RssFetchService;
import com.example.news.aggregation.news.service.TranslationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertNotNull;

@SpringBootTest(classes = TestApplication.class)
class RssFetchServiceTest {
    @Autowired
    private ContentExtractor contentExtractor;


    @Autowired
    private RssFetchService rssFetchService;

    @Autowired
    private TranslationService translationService;


    @Autowired
    private BaiduTranslateClient translateClient;

    @Test
    void testFetchAndSaveNews() {
        rssFetchService.fetchAndSaveNews();
    }

    @Test
    void testExtractContent() {
        String url = "https://www.cnblogs.com/whaleX/p/19144972";
        String content = contentExtractor.extractContent(url);
        System.out.println("正文内容: " + content);
        assertNotNull(content);
    }

    @Test
    void testTranslate() {
        String result = translateClient.translate("Hello World");
        System.out.println("翻译结果: " + result);
        assertNotNull(result);
    }

    @Test
    void testTranslatePendingNews() {
        translationService.translatePendingNews(5);
    }
}