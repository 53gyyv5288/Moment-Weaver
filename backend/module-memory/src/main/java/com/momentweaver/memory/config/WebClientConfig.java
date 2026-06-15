package com.momentweaver.memory.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.http.codec.json.Jackson2JsonDecoder;
import org.springframework.http.codec.json.Jackson2JsonEncoder;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;

@Configuration
@EnableConfigurationProperties(AiProperties.class)
public class WebClientConfig {

    @Bean
    public WebClient aiWebClient(AiProperties props, ObjectMapper objectMapper) {
        HttpClient http = HttpClient.create()
            .responseTimeout(Duration.ofMillis(props.getReadTimeoutMs()));
        // 强制让 WebClient 用 Spring 管理的 ObjectMapper（默认它会用自己的，忽略 spring.jackson.* 配置）
        ExchangeStrategies strategies = ExchangeStrategies.builder()
            .codecs(c -> {
                c.defaultCodecs().jackson2JsonEncoder(new Jackson2JsonEncoder(objectMapper));
                c.defaultCodecs().jackson2JsonDecoder(new Jackson2JsonDecoder(objectMapper));
            })
            .build();
        return WebClient.builder()
            .baseUrl(props.getBaseUrl())
            .clientConnector(new ReactorClientHttpConnector(http))
            .exchangeStrategies(strategies)
            .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
            .defaultHeader("Accept", MediaType.APPLICATION_JSON_VALUE)
            .build();
    }
}
