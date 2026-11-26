package com.slotwise.common.config;

import com.slotwise.auth.security.AuthenticatedUser;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springdoc.core.utils.SpringDocUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class OpenApiConfig {

    public static final String BEARER_SCHEME = "bearerAuth";

    static {
        // Resolved from the JWT by an argument resolver, not a request parameter.
        SpringDocUtils.getConfig().addRequestWrapperToIgnore(AuthenticatedUser.class);
    }

    @Bean
    OpenAPI slotwiseOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Slotwise API")
                        .version("v1")
                        .description("""
                                Appointment booking and scheduling API. Authenticate with \
                                `POST /api/v1/auth/login`, then send `Authorization: Bearer <accessToken>`. \
                                Errors use RFC 9457 Problem Details with a stable `code` property."""))
                .components(new Components().addSecuritySchemes(BEARER_SCHEME, new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME));
    }
}
