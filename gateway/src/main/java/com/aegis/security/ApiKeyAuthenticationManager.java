package com.aegis.security;

import org.springframework.security.authentication.ReactiveAuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.Collections;

@Component
public class ApiKeyAuthenticationManager implements ReactiveAuthenticationManager {

    private final ApiKeyProperties apiKeyProperties;

    public ApiKeyAuthenticationManager(ApiKeyProperties apiKeyProperties) {
        this.apiKeyProperties = apiKeyProperties;
    }

    @Override
    public Mono<Authentication> authenticate(Authentication authentication) {
        Object credentials = authentication.getCredentials();
        if (credentials == null) {
            return Mono.empty();
        }
        String apiKey = credentials.toString();
        if (apiKeyProperties.isValid(apiKey)) {
            return Mono.just(new UsernamePasswordAuthenticationToken(apiKey, null, Collections.emptyList()));
        }
        return Mono.empty();
    }
}
