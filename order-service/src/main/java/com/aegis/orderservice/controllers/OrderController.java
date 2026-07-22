package com.aegis.orderservice.controllers;

import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/orders")
public class OrderController {

    @GetMapping("/hello")
    public String hello() {
        return "Hello from Order Service";
    }

    @GetMapping
    public List<Map<String, Object>> getAllOrders() {
        return List.of(
            Map.of("id", "1", "userId", "1", "product", "Laptop", "amount", 999.99, "status", "delivered"),
            Map.of("id", "2", "userId", "2", "product", "Phone", "amount", 599.99, "status", "shipped")
        );
    }

    @GetMapping("/strip-test")
    public String stripTest() {
        return "Order endpoint reached via StripPrefix";
    }

    @GetMapping("/{id}")
    public Map<String, Object> getOrderById(@PathVariable String id) {
        return Map.of("id", id, "userId", "1", "product", "Product " + id, "amount", 100.0, "status", "pending");
    }

    @PostMapping
    public Map<String, Object> createOrder(@RequestBody Map<String, Object> body) {
        String id = String.valueOf(System.currentTimeMillis());
        return Map.of("id", id, "userId", body.get("userId"), "product", body.get("product"), "amount", body.get("amount"), "status", "pending", "message", "Order created");
    }

    @PutMapping("/{id}")
    public Map<String, Object> updateOrder(@PathVariable String id, @RequestBody Map<String, Object> body) {
        return Map.of("id", id, "userId", body.get("userId"), "product", body.get("product"), "amount", body.get("amount"), "status", body.getOrDefault("status", "pending"), "message", "Order updated");
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> deleteOrder(@PathVariable String id) {
        return Map.of("id", id, "message", "Order deleted");
    }
}
