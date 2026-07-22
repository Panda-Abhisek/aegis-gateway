package com.aegis.userservice.controllers;

import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/users")
public class UserController {

    @GetMapping("/hello")
    public String hello() {
        return "Hello from User Service";
    }

    @GetMapping
    public List<Map<String, Object>> getAllUsers() {
        return List.of(
            Map.of("id", "1", "name", "John Doe", "email", "john@example.com", "tier", "premium"),
            Map.of("id", "2", "name", "Jane Smith", "email", "jane@example.com", "tier", "standard")
        );
    }

    @GetMapping("/strip-test")
    public String stripTest() {
        return "User endpoint reached via StripPrefix";
    }

    @GetMapping("/{id}")
    public Map<String, Object> getUserById(@PathVariable String id) {
        return Map.of("id", id, "name", "User " + id, "email", "user" + id + "@example.com", "tier", "standard");
    }

    @PostMapping
    public Map<String, Object> createUser(@RequestBody Map<String, Object> body) {
        String id = String.valueOf(System.currentTimeMillis());
        return Map.of("id", id, "name", body.get("name"), "email", body.get("email"), "tier", "standard", "message", "User created");
    }

    @PutMapping("/{id}")
    public Map<String, Object> updateUser(@PathVariable String id, @RequestBody Map<String, Object> body) {
        return Map.of("id", id, "name", body.get("name"), "email", body.get("email"), "tier", body.getOrDefault("tier", "standard"), "message", "User updated");
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> deleteUser(@PathVariable String id) {
        return Map.of("id", id, "message", "User deleted");
    }
}
