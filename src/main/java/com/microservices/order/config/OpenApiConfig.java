package com.microservices.order.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI orderServiceOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("FoodChain — Order Service API")
                        .description("Place and manage customer orders, track status, and handle the order lifecycle.")
                        .version("v1.0.0")
                        .contact(new Contact()
                                .name("FoodchainGroup08")
                                .email("team@foodchain.com")))
                .servers(List.of(
                        new Server()
                                .url("http://localhost:8080/api")
                                .description("API Gateway"),
                        new Server()
                                .url("http://localhost:8083/api")
                                .description("Order Service direct (local dev)")))
                .addSecurityItem(new SecurityRequirement().addList("Bearer Authentication"))
                .components(new Components()
                        .addSecuritySchemes("Bearer Authentication",
                                new SecurityScheme()
                                        .type(SecurityScheme.Type.HTTP)
                                        .scheme("bearer")
                                        .bearerFormat("JWT")
                                        .description("Paste your JWT token. Get it from POST /api/auth/login")));
    }
}
