package com.aegis.security;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

import java.util.Collections;

public class ApiKeyAuthenticationToken extends UsernamePasswordAuthenticationToken {

    public ApiKeyAuthenticationToken(String apiKey) {
        super(apiKey, apiKey, Collections.emptyList());
    }
}
