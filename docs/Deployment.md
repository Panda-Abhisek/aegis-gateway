# Deployment

## Purpose

This document describes how Aegis Gateway is packaged, deployed, and monitored in the project environment.

The objective is to provide a reproducible deployment that is simple enough for local development while demonstrating production-oriented practices.

---

# Deployment Goals

- Consistent local development
- Reproducible environment
- Containerized services
- Simple monitoring
- Easy project setup

---

# Deployment Stack

| Component | Technology |
|----------|------------|
| Gateway | Spring Boot |
| Backend Services | Spring Boot |
| Database | PostgreSQL |
| Cache | Redis |
| Monitoring | Prometheus |
| Dashboard | Grafana |
| Containers | Docker |
| Orchestration | Docker Compose |

---

# Deployment Architecture

                     Client
                        │
                        ▼
               +----------------+
               | Aegis Gateway  |
               +-------+--------+
                       │
         ┌─────────────┴─────────────┐
         ▼                           ▼
 +---------------+          +---------------+
 | User Service  |          | Order Service |
 +---------------+          +---------------+
         │
         │
         ├────────── Redis
         │
         ├────────── PostgreSQL
         │
         ├────────── Prometheus
         │
         └────────── Grafana

---

# Containers

The project will consist of the following containers.

- gateway
- user-service
- order-service
- redis
- postgres
- prometheus
- grafana

All containers are managed using Docker Compose.

---

# Repository Structure

aegis-gateway/

├── gateway/

├── user-service/

├── order-service/

├── docker/

│   └── docker-compose.yml

├── prometheus/

├── grafana/

├── docs/

└── README.md

---

# Local Development Flow

Developer

↓

Clone Repository

↓

Start Docker Compose

↓

Containers Start

↓

Gateway Connects to Dependencies

↓

Application Ready

---

# Configuration

Environment-specific configuration should be externalized.

Examples:

- Database URL
- Redis URL
- JWT Secret
- API Keys
- Logging Level

Application code should not require modification between environments.

---

# Monitoring

Prometheus collects application metrics.

Grafana visualizes those metrics using dashboards.

Health endpoints provide service availability information.

---

# Logging

The gateway writes structured application logs.

Logs should include:

- Timestamp
- Request ID
- HTTP Method
- Request Path
- Response Status
- Processing Time

---

# CI/CD

The project includes a basic CI pipeline.

Responsibilities:

- Build project
- Execute unit tests
- Verify successful compilation

Deployment remains a manual process for this project.

---

# Deployment Checklist

Before deployment:

- Project builds successfully
- Unit tests pass
- Docker images build successfully
- Docker Compose starts all services
- Gateway routes correctly
- Redis connection works
- PostgreSQL connection works
- Prometheus collects metrics
- Grafana dashboard loads

---

# Design Principles

- Containerized deployment
- Environment-based configuration
- Repeatable setup
- Minimal manual steps
- Production-oriented project structure

---

# Future Enhancements

The following are intentionally outside the current roadmap.

- Kubernetes
- Helm Charts
- Service Discovery
- Blue-Green Deployment
- Canary Deployment
- Cloud Load Balancers
- Auto Scaling
- Infrastructure as Code
