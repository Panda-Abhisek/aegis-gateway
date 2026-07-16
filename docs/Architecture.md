# Aegis Gateway Architecture

## Overview

Aegis Gateway is a production-oriented API Gateway built using Spring Cloud Gateway and Spring Boot.

Its responsibility is to act as the single entry point for backend services by routing requests, enforcing security policies, applying middleware, and collecting operational metrics.

The project is designed as a learning-focused implementation of common API Gateway patterns while remaining production-ready.

---

## Goals

- Centralize API traffic
- Route requests to backend services
- Authenticate incoming requests
- Authorize protected resources
- Apply request filtering
- Protect backend services using rate limiting
- Improve performance through caching
- Increase reliability using circuit breakers and retries
- Provide observability through metrics and logging

---

## Non-Goals

The following are intentionally out of scope.

- Service mesh
- Kubernetes Ingress Controller
- GraphQL Gateway
- API monetization
- Developer Portal
- Service Discovery (unless added later)
- Multi-region deployment

---

## High-Level Architecture

                    +----------------------+
                    |      Client          |
                    +----------+-----------+
                               |
                               |
                      HTTP / HTTPS Request
                               |
                               v
                 +----------------------------+
                 |       Aegis Gateway        |
                 |----------------------------|
                 | Routing                    |
                 | Filters                    |
                 | Authentication             |
                 | Authorization              |
                 | Rate Limiting              |
                 | Caching                    |
                 | Circuit Breaker            |
                 | Logging                    |
                 | Metrics                    |
                 +------+-----------+---------+
                        |           |
            ------------            ------------
            |                                   |
            v                                   v
    +---------------+                  +---------------+
    | User Service  |                  | Order Service |
    +---------------+                  +---------------+

---

## Core Components

### Gateway

Responsible for:

- Receiving requests
- Executing filters
- Routing traffic
- Returning responses

---

### Filters

Filters process every request before or after routing.

Examples:

- JWT Authentication
- API Key Validation
- Logging
- Correlation ID
- Rate Limiting

---

### Routing Layer

Responsible for forwarding requests to backend services.

Example:

/users/**

↓

User Service

/orders/**

↓

Order Service

---

### Redis

Redis will be used for:

- Rate limiting
- Response caching

Redis is not used as the primary database.

---

### PostgreSQL

Stores persistent gateway data such as:

- API Keys
- Route configuration (future enhancement)
- Audit information (future enhancement)

---

### Backend Services

Initially the gateway proxies requests to two services.

- User Service
- Order Service

These services exist only to demonstrate gateway functionality.

---

## Request Lifecycle

Client

↓

Gateway receives request

↓

Global Filters execute

↓

Security Filters execute

↓

Rate Limiter executes

↓

Cache Lookup

↓

Route Resolution

↓

Backend Service

↓

Gateway receives response

↓

Response Filters execute

↓

Client receives response

---

## Technology Stack

| Component | Technology           |
|-----------|----------------------|
| Language | Java 25              |
| Framework | Spring Boot 4        |
| Gateway | Spring Cloud Gateway |
| Security | Spring Security      |
| Cache | Redis                |
| Database | PostgreSQL           |
| Build Tool | Maven                |
| Containerization | Docker               |
| Monitoring | Prometheus           |
| Dashboard | Grafana              |
| Testing | JUnit                |

---

## Repository Structure

aegis-gateway/

├── gateway/

├── user-service/

├── order-service/

├── docs/

├── docker/

├── prometheus/

├── grafana/

└── README.md

---

## Architecture Principles

- Single responsibility for every component
- Stateless gateway
- Configuration over hardcoding
- Middleware implemented using filters
- Security before routing
- Observability built into every request
- Production-oriented project structure

---

## Future Enhancements

These are intentionally excluded from the initial roadmap.

- Dynamic route management
- Service discovery
- Canary routing
- Blue-Green deployments
- OpenAPI aggregation
- WebSocket proxy
