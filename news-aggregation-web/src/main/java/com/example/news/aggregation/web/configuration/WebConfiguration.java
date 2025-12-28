package com.example.news.aggregation.web.configuration;

import com.example.news.aggregation.web.filter.TokenFilter;
import com.example.news.aggregation.web.handler.GlobalWebExceptionHandler;
import org.redisson.api.RedissonClient;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * @author Hollis
 */
@AutoConfiguration
@ConditionalOnWebApplication
public class WebConfiguration implements WebMvcConfigurer {

    @Bean
    @ConditionalOnMissingBean
    GlobalWebExceptionHandler globalWebExceptionHandler() {
        return new GlobalWebExceptionHandler();
    }

    /**
     * 注册token过滤器
     *
     * @param redissonClient
     * @return
     */
    @Bean
    public FilterRegistrationBean<TokenFilter> tokenFilter(RedissonClient redissonClient) {
        FilterRegistrationBean<TokenFilter> registrationBean = new FilterRegistrationBean<>();

        registrationBean.setFilter(new TokenFilter(redissonClient));
        registrationBean.addUrlPatterns("/trade/buy","/trade/newBuy","/trade/normalBuy","/trade/newBuyPlus");
        registrationBean.setOrder(10);

        return registrationBean;
    }

}
