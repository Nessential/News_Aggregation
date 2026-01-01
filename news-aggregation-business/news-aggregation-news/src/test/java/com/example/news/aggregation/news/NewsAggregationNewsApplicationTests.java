package com.example.news.aggregation.news;

import com.example.news.aggregation.news.service.RssFetchService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(classes = TestApplication.class)
class RssFetchServiceTest {

    @Autowired
    private RssFetchService rssFetchService;

    @Test
    void testFetchAndSaveNews() {
        rssFetchService.fetchAndSaveNews();
    }
}