package com.example.news.aggregation.es.config;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.ElasticsearchTransport;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.elasticsearch.client.RestClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ElasticsearchConfig {

    @Value("${news.aggregation.elasticsearch.host:localhost}")
    private String host;

    @Value("${news.aggregation.elasticsearch.port:9200}")
    private int port;

    @Value("${news.aggregation.elasticsearch.username:}")
    private String username;

    @Value("${news.aggregation.elasticsearch.password:}")
    private String password;

    @Bean
    public ElasticsearchClient elasticsearchClient() {
        // 创建 RestClient
        RestClient restClient;

        if (username != null && !username.isEmpty()) {
            // 带认证
            CredentialsProvider credentialsProvider = new BasicCredentialsProvider();
            credentialsProvider.setCredentials(
                    AuthScope.ANY,
                    new UsernamePasswordCredentials(username, password)
            );

            restClient = RestClient.builder(
                            new HttpHost(host, port, "http")
                    )
                    .setHttpClientConfigCallback(httpClientBuilder ->
                            httpClientBuilder.setDefaultCredentialsProvider(credentialsProvider)
                    )
                    .build();
        } else {
            // 不带认证
            restClient = RestClient.builder(
                    new HttpHost(host, port, "http")
            ).build();
        }

        // 创建 Transport
        ElasticsearchTransport transport = new RestClientTransport(
                restClient,
                new JacksonJsonpMapper()
        );

        // 创建 Client
        return new ElasticsearchClient(transport);
    }
}