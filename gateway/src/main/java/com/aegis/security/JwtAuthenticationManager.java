package com.aegis.security;

import io.jsonwebtoken.Claims;
import org.springframework.context.annotation.Primary;
import org.springframework.security.authentication.ReactiveAuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.Collections;

@Component
@Primary
public class JwtAuthenticationManager implements ReactiveAuthenticationManager {

    private final JwtService jwtService;

    public JwtAuthenticationManager(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    public Mono<Authentication> authenticate(Authentication authentication) {
        Object credentials = authentication.getCredentials();
        if (credentials == null) {
            return Mono.empty();
        }
        String token = credentials.toString();
        return Mono.justOrEmpty(jwtService.parse(token))
                .map(Claims::getSubject)
                .map(subject -> new UsernamePasswordAuthenticationToken(subject, null, Collections.emptyList()))
                .cast(Authentication.class);
    }
}
