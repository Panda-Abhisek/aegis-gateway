package com.aegis.security;

import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

@Component
public class ApiKeyAuthenticationWebFilter implements WebFilter {

    private final ApiKeyAuthenticationManager authenticationManager;

    public ApiKeyAuthenticationWebFilter(ApiKeyAuthenticationManager authenticationManager) {
        this.authenticationManager = authenticationManager;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        return ReactiveSecurityContextHolder.getContext()
                .map(ctx -> ctx.getAuthentication())
                .filter(auth -> auth != null && auth.isAuthenticated())
                .flatMap(auth -> chain.filter(exchange))
                .switchIfEmpty(Mono.defer(() -> {
                    String apiKey = exchange.getRequest().getHeaders().getFirst("X-API-Key");
                    if (apiKey == null) {
                        return chain.filter(exchange);
                    }

                    ApiKeyAuthenticationToken authRequest = new ApiKeyAuthenticationToken(apiKey);
                    return authenticationManager.authenticate(authRequest)
                            .flatMap(authentication -> {
                                ServerHttpRequest mutatedRequest = exchange.getRequest().mutate()
                                        .header("X-User-Id", authentication.getName())
                                        .build();
                                return chain.filter(exchange.mutate().request(mutatedRequest).build())
                                        .contextWrite(ReactiveSecurityContextHolder.withAuthentication(authentication));
                            })
                            .switchIfEmpty(unauthorized(exchange));
                }));
    }

    private Mono<Void> unauthorized(ServerWebExchange exchange) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        return response.setComplete();
    }
}
