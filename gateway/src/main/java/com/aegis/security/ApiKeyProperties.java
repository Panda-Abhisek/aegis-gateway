package com.aegis.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
@ConfigurationProperties(prefix = "gateway.security")
public class ApiKeyProperties {

    private List<KeyEntry> apiKeys = new ArrayList<>();

    public List<KeyEntry> getApiKeys() {
        return apiKeys;
    }

    public void setApiKeys(List<KeyEntry> apiKeys) {
        this.apiKeys = apiKeys;
    }

    public boolean isValid(String key) {
        return key != null && apiKeys != null && apiKeys.stream().anyMatch(e -> key.equals(e.getKey()));
    }

    public String getRoleForKey(String key) {
        if (key == null || apiKeys == null) {
            return null;
        }
        return apiKeys.stream()
                .filter(e -> key.equals(e.getKey()))
                .map(KeyEntry::getRole)
                .findFirst()
                .orElse(null);
    }

    public static class KeyEntry {
        private String key;
        private String role;

        public String getKey() {
            return key;
        }

        public void setKey(String key) {
            this.key = key;
        }

        public String getRole() {
            return role;
        }

        public void setRole(String role) {
            this.role = role;
        }
    }
}
