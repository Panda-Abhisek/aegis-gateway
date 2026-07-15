# Request Flow

## Purpose

This document describes how an HTTP request travels through Aegis Gateway before reaching a backend service and how the response is returned to the client.

This flow represents the complete request lifecycle for the current project roadmap.

---

# High-Level Flow

Client

↓

Aegis Gateway

↓

Global Filters

↓

Authentication

↓

Authorization

↓

Rate Limiting

↓

Cache Lookup

↓

Route Resolution

↓

Backend Service

↓

Gateway Response Filters

↓

Client

---

# Step 1 - Client Request

The client sends an HTTP request.

Example

GET /users/1

POST /orders

The gateway is the only public entry point.

Backend services are never accessed directly.

---

# Step 2 - Global Filters

Global filters execute for every request.

Responsibilities include:

- Generate Correlation ID
- Request logging
- Response logging
- Common preprocessing

These filters execute before any business-specific logic.

---

# Step 3 - Authentication

The gateway verifies the identity of the client.

Supported authentication methods:

- JWT
- API Key

If authentication fails:

- Request processing stops.
- HTTP 401 Unauthorized is returned.

---

# Step 4 - Authorization

Once authenticated, the gateway checks whether the client has permission to access the requested resource.

Examples:

ADMIN

↓

Allowed to access

/admin/**

USER

↓

Denied for

/admin/**

If authorization fails:

HTTP 403 Forbidden

---

# Step 5 - Rate Limiting

The gateway checks whether the client has exceeded the allowed request limit.

Redis stores request counters.

If the limit is exceeded:

HTTP 429 Too Many Requests

is returned immediately.

The backend service is never contacted.

---

# Step 6 - Cache Lookup

For cacheable requests:

The gateway checks Redis.

Cache Hit

↓

Return cached response

Cache Miss

↓

Forward request to backend service

Only successful responses are eligible for caching.

---

# Step 7 - Route Resolution

The gateway determines the target backend service using static route definitions from application.yml.

Example

/users/**

↓

User Service

/orders/**

↓

Order Service

---

# Step 8 - Forward Request

The gateway forwards the request to the selected backend service.

The original HTTP method, headers, query parameters, and request body are preserved unless modified by gateway filters.

---

# Step 9 - Backend Processing

The backend service processes the request.

Possible outcomes:

- Success
- Validation error
- Server error

The gateway does not contain business logic.

Business logic belongs to backend services.

---

# Step 10 - Receive Response

The backend service returns an HTTP response.

The gateway receives it before sending it back to the client.

---

# Step 11 - Response Filters

Response filters execute.

Examples:

- Response logging
- Response caching
- Header manipulation
- Metrics collection

---

# Step 12 - Response Sent

The processed response is returned to the client.

The request lifecycle is complete.

---

# Error Flow

Authentication Failure

↓

401 Unauthorized

Authorization Failure

↓

403 Forbidden

Rate Limit Exceeded

↓

429 Too Many Requests

Backend Timeout

↓

Gateway Timeout

Circuit Breaker Open

↓

Fallback Response

Unhandled Exception

↓

500 Internal Server Error

---

# Sequence Diagram

Client

↓

Gateway

↓

Global Filter

↓

Authentication

↓

Authorization

↓

Rate Limiter

↓

Cache

↓

Router

↓

Backend Service

↓

Gateway

↓

Client

---

# Design Principles

- Every request passes through the same pipeline.
- Security checks occur before routing.
- Backend services remain unaware of gateway middleware.
- Business logic is never implemented in the gateway.
- Requests should fail as early as possible.
- Responses always pass back through the gateway before reaching the client.
