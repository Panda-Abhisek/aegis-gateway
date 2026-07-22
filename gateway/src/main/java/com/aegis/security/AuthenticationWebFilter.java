package com.aegis.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.security.authentication.ReactiveAuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

@Component
@Order(-101)
public class AuthenticationWebFilter implements WebFilter {

    private static final Logger log = LoggerFactory.getLogger(AuthenticationWebFilter.class);

    private final ReactiveAuthenticationManager jwtManager;
    private final ReactiveAuthenticationManager apiKeyManager;

    public AuthenticationWebFilter(
            @Qualifier("jwtAuthenticationManager") ReactiveAuthenticationManager jwtManager,
            @Qualifier("apiKeyAuthenticationManager") ReactiveAuthenticationManager apiKeyManager) {
        this.jwtManager = jwtManager;
        this.apiKeyManager = apiKeyManager;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getURI().getPath();
        if (path.startsWith("/actuator/")) {
            return chain.filter(exchange);
        }
        return authenticate(exchange, chain);
    }

    private Mono<Void> authenticate(ServerWebExchange exchange, WebFilterChain chain) {
        String authHeader = exchange.getRequest().getHeaders().getFirst("Authorization");

        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7);
            UsernamePasswordAuthenticationToken authRequest = new UsernamePasswordAuthenticationToken(token, token);
            return tryJwt(exchange, chain, authRequest)
                    .flatMap(authenticated -> authenticated ? Mono.empty() : tryApiKey(exchange, chain));
        }

        return tryApiKey(exchange, chain);
    }

    private Mono<Boolean> tryJwt(ServerWebExchange exchange, WebFilterChain chain, Authentication authRequest) {
        return jwtManager.authenticate(authRequest)
                .flatMap(auth -> onSuccess(exchange, chain, auth).then(Mono.just(true)))
                .defaultIfEmpty(false)
                .doOnNext(authenticated -> {
                    if (!authenticated) {
                        log.warn("JWT authentication failed, falling back to API key for {} {}",
                                exchange.getRequest().getMethod(), exchange.getRequest().getURI().getPath());
                    }
                });
    }

    private Mono<Void> tryApiKey(ServerWebExchange exchange, WebFilterChain chain) {
        String apiKeyHeader = exchange.getRequest().getHeaders().getFirst("X-API-Key");
        if (apiKeyHeader != null) {
            ApiKeyAuthenticationToken authRequest = new ApiKeyAuthenticationToken(apiKeyHeader);
            return tryApiKeyAuth(exchange, chain, authRequest)
                    .flatMap(authenticated -> authenticated ? Mono.empty() : unauthorized(exchange));
        }
        log.warn("No authentication credentials provided for {} {}",
                exchange.getRequest().getMethod(), exchange.getRequest().getURI().getPath());
        return unauthorized(exchange);
    }

    private Mono<Boolean> tryApiKeyAuth(ServerWebExchange exchange, WebFilterChain chain, Authentication authRequest) {
        return apiKeyManager.authenticate(authRequest)
                .flatMap(auth -> onSuccess(exchange, chain, auth).then(Mono.just(true)))
                .defaultIfEmpty(false)
                .doOnNext(authenticated -> {
                    if (!authenticated) {
                        log.warn("API key authentication failed for {} {}",
                                exchange.getRequest().getMethod(), exchange.getRequest().getURI().getPath());
                    }
                });
    }

    private Mono<Void> onSuccess(ServerWebExchange exchange, WebFilterChain chain, Authentication auth) {
        SecurityContext context = new SecurityContextImpl(auth);
        ServerHttpRequest mutated = exchange.getRequest().mutate()
                .header("X-User-Id", auth.getName())
                .build();
        return chain.filter(exchange.mutate().request(mutated).build())
                .contextWrite(ReactiveSecurityContextHolder.withSecurityContext(Mono.just(context)));
    }

    private Mono<Void> unauthorized(ServerWebExchange exchange) {
        exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
        return exchange.getResponse().setComplete();
    }
}
