package com.aegis.userservice.controllers;

import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/users")
public class UserController {

    @GetMapping("/hello")
    public String hello() {
        return "Hello from User Service";
    }

    @PostMapping
    public String createUser(@RequestBody String body) {
        return "User created: " + body;
    }

    @GetMapping("/search")
    public String search(@RequestParam String tier) {
        return "Searching users with tier: " + tier;
    }

    @GetMapping("/v2/hello")
    public String helloV2() {
        return "Hello from User Service v2";
    }

    @PutMapping("/{id}")
    public String updateUser(@PathVariable String id) {
        return "User " + id + " updated";
    }
}
