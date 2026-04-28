package com.contextswitcher.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Allows the Chrome extension (origin {@code chrome-extension://…}) to POST GraphQL to the
 * backend from the popup.
 */
@Configuration
public class GraphqlCorsConfig {

    @Bean
    public WebMvcConfigurer graphqlCorsConfigurer() {
        return new WebMvcConfigurer() {
            @Override
            public void addCorsMappings(CorsRegistry registry) {
                registry.addMapping("/graphql")
                        .allowedOriginPatterns("chrome-extension://*")
                        .allowedMethods("GET", "POST", "OPTIONS")
                        .allowedHeaders("*");
            }
        };
    }
}
