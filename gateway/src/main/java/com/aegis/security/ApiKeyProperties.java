package com.aegis.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
@ConfigurationProperties(prefix = "gateway.security")
public class ApiKeyProperties {

    private List<String> apiKeys = new ArrayList<>();

    public List<String> getApiKeys() {
        return apiKeys;
    }

    public void setApiKeys(List<String> apiKeys) {
        this.apiKeys = apiKeys;
    }

    public boolean isValid(String key) {
        return key != null && apiKeys != null && apiKeys.contains(key);
    }
}
