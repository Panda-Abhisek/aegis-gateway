package com.aegis.security;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.authentication.ReactiveAuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.Collections;
import java.util.List;

@Component
@Qualifier("apiKeyAuthenticationManager")
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
            String role = apiKeyProperties.getRoleForKey(apiKey);
            List<SimpleGrantedAuthority> authorities = role != null
                    ? List.of(new SimpleGrantedAuthority(role))
                    : Collections.emptyList();
            return Mono.just(new UsernamePasswordAuthenticationToken(apiKey, null, authorities));
        }
        return Mono.empty();
    }
}
