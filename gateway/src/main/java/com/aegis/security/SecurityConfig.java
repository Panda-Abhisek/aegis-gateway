package com.aegis.security;

import com.aegis.validation.RequestValidationWebFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.SecurityWebFiltersOrder;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;

import java.util.List;

@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    @Bean
    public SecurityWebFilterChain securityWebFilterChain(
            ServerHttpSecurity http,
            @Value("${gateway.validation.max-payload-size:1048576}") long maxPayloadSize,
            @Value("${gateway.validation.required-headers:}") List<String> requiredHeaders,
            @Value("${gateway.validation.allowed-content-types:application/json}") List<String> allowedContentTypes) {

        RequestValidationWebFilter validationFilter = new RequestValidationWebFilter(maxPayloadSize, requiredHeaders, allowedContentTypes);

        http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .authorizeExchange(exchange -> exchange
                        .pathMatchers("/actuator/**").permitAll()
                        .pathMatchers("/admin/**").hasAuthority("ROLE_ADMIN")
                        .pathMatchers("/users/**", "/orders/**", "/api/**").hasAnyAuthority("ROLE_USER", "ROLE_ADMIN")
                )
                .addFilterAfter(validationFilter, SecurityWebFiltersOrder.AUTHENTICATION)
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((exchange, authException) -> {
                            log.error("AUTH ENTRY POINT CALLED: {} on {} {}",
                                    authException.getMessage(),
                                    exchange.getRequest().getMethod(),
                                    exchange.getRequest().getURI().getPath(),
                                    authException);
                            exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
                            return exchange.getResponse().setComplete();
                        })
                        .accessDeniedHandler((exchange, denied) -> {
                            log.error("ACCESS DENIED: {} on {} {}",
                                    denied.getMessage(),
                                    exchange.getRequest().getMethod(),
                                    exchange.getRequest().getURI().getPath(),
                                    denied);
                            exchange.getResponse().setStatusCode(HttpStatus.FORBIDDEN);
                            return exchange.getResponse().setComplete();
                        })
                );

        return http.build();
    }
}
