package com.momentweaver.rag.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.http.codec.json.Jackson2JsonDecoder;
import org.springframework.http.codec.json.Jackson2JsonEncoder;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;

/**
 * RAG 模块自己的 WebClient（不复用 module-memory 的 aiWebClient，
 * 保持模块独立 + 单独超时）。
 */
@Configuration
@EnableConfigurationProperties(RagProperties.class)
public class RagWebClientConfig {

    @Bean("ragWebClient")
    public WebClient ragWebClient(RagProperties props, ObjectMapper objectMapper) {
        ExchangeStrategies strategies = ExchangeStrategies.builder()
            .codecs(c -> {
                c.defaultCodecs().jackson2JsonEncoder(new Jackson2JsonEncoder(objectMapper));
                c.defaultCodecs().jackson2JsonDecoder(new Jackson2JsonDecoder(objectMapper));
            })
            .build();
        return WebClient.builder()
            .baseUrl(props.getAiBaseUrl())
            .exchangeStrategies(strategies)
            .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
            .defaultHeader("Accept", MediaType.APPLICATION_JSON_VALUE)
            .defaultHeader("X-Internal-Call", "module-rag")
            .build();
    }

    @Bean
    public Duration ragReadTimeout(RagProperties props) {
        return Duration.ofMillis(props.getReadTimeoutMs());
    }
}