package com.planora.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

@Configuration
public class WebConfig {

    @Bean
    public CorsFilter corsFilter(
            @Value("${planora.cors.allowed-origins:http://localhost:5173}") String allowedOrigins
    ) {
        var config = new CorsConfiguration();
        config.setAllowCredentials(true);
        for (String origin : allowedOrigins.split(",")) {
            config.addAllowedOriginPattern(origin.trim());
        }
        config.addAllowedHeader(CorsConfiguration.ALL);
        config.addAllowedMethod(CorsConfiguration.ALL);

        var source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        return new CorsFilter(source);
    }
}
