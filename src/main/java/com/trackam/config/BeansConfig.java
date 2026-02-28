package com.trackam.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

/**
 * Central bean definitions — HTTP clients and other infrastructure beans.
 * Never create RestTemplate (or similar) inline in services; inject from here.
 */
@Configuration
public class BeansConfig {

    @Bean
    public RestTemplate restTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5_000);  // 5 s
        factory.setReadTimeout(30_000);    // 30 s
        return new RestTemplate(factory);
    }
}
