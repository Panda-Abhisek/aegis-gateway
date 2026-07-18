package com.aegis.security;

import com.aegis.validation.RequestValidationWebFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.SecurityWebFiltersOrder;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;

@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    private final JwtAuthenticationWebFilter jwtFilter;
    private final ApiKeyAuthenticationWebFilter apiKeyFilter;
    private final RequestValidationWebFilter validationFilter;

    public SecurityConfig(JwtAuthenticationWebFilter jwtFilter,
                          ApiKeyAuthenticationWebFilter apiKeyFilter,
                          RequestValidationWebFilter validationFilter) {
        this.jwtFilter = jwtFilter;
        this.apiKeyFilter = apiKeyFilter;
        this.validationFilter = validationFilter;
    }

    @Bean
    public SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http) {
        http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .authorizeExchange(exchange -> exchange
                        .pathMatchers("/actuator/**").permitAll()
                        .pathMatchers("/admin/**").hasAuthority("ROLE_ADMIN")
                        .pathMatchers("/users/**", "/orders/**", "/api/**").hasAnyAuthority("ROLE_USER", "ROLE_ADMIN")
                )
                .addFilterBefore(jwtFilter, SecurityWebFiltersOrder.AUTHENTICATION)
                .addFilterBefore(apiKeyFilter, SecurityWebFiltersOrder.AUTHENTICATION)
                .addFilterAfter(validationFilter, SecurityWebFiltersOrder.AUTHORIZATION)
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((exchange, authException) -> {
                            exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
                            return exchange.getResponse().setComplete();
                        })
                        .accessDeniedHandler((exchange, denied) -> {
                            exchange.getResponse().setStatusCode(HttpStatus.FORBIDDEN);
                            return exchange.getResponse().setComplete();
                        })
                );

        return http.build();
    }
}
