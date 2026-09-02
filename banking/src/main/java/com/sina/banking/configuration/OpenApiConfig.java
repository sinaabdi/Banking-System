package com.sina.banking.configuration;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {
    @Bean
    public OpenAPI bankingOpenAPI() {
        return new OpenAPI().info( new Info()
                .title("Banking API")
                .description("A banking system for learning purpose")
                .version("v0.1"));
    }
}
