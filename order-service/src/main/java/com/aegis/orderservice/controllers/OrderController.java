package com.aegis.orderservice.controllers;

import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/orders")
public class OrderController {

    @GetMapping("/hello")
    public String hello() {
        return "Hello from Order Service";
    }

    @PostMapping
    public String createOrder(@RequestBody String body) {
        return "Order created: " + body;
    }

    @PostMapping("/create")
    public String createOrderExplicit(@RequestBody String body) {
        return "Order created: " + body;
    }

    @PutMapping("/{id}")
    public String updateOrder(@PathVariable String id) {
        return "Order " + id + " updated";
    }
}
