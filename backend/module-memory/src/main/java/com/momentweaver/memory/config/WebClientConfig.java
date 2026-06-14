package com.momentweaver.memory.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;

@Configuration
@EnableConfigurationProperties(AiProperties.class)
public class WebClientConfig {

    @Bean
    public WebClient aiWebClient(AiProperties props) {
        HttpClient http = HttpClient.create()
            .responseTimeout(Duration.ofMillis(props.getReadTimeoutMs()));
        return WebClient.builder()
            .baseUrl(props.getBaseUrl())
            .clientConnector(new ReactorClientHttpConnector(http))
            .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
            .defaultHeader("Accept", MediaType.APPLICATION_JSON_VALUE)
            .build();
    }
}
