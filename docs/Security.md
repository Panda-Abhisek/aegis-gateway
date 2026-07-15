# Security

## Purpose

This document describes the security architecture of Aegis Gateway.

The gateway is responsible for authenticating clients, authorizing access to protected resources, and preventing unauthorized requests from reaching backend services.

Backend services trust the gateway and focus only on business logic.

---

# Security Objectives

- Authenticate every protected request
- Authorize access based on roles
- Prevent unauthorized access
- Reject invalid requests early
- Keep backend services isolated from public traffic

---

# Security Layers

Every incoming request passes through the following layers.

Client

↓

Authentication

↓

Authorization

↓

Route Request

↓

Backend Service

If any security check fails, request processing stops immediately.

---

# Authentication

Authentication verifies the identity of the client.

The gateway supports two authentication methods.

## JWT Authentication

Clients send a JWT using the Authorization header.

Example

Authorization: Bearer <jwt-token>

The gateway validates:

- Token format
- Signature
- Expiration
- Issuer
- Subject

If validation succeeds:

The authenticated user information is attached to the request context.

Otherwise:

HTTP 401 Unauthorized

---

## API Key Authentication

Clients may authenticate using an API Key.

Example

X-API-Key: abc123xyz

The gateway verifies that:

- The key exists
- The key is active
- The key has not expired (if applicable)

If validation fails:

HTTP 401 Unauthorized

---

# Authorization

After authentication, the gateway checks whether the authenticated client has permission to access the requested resource.

Authorization is role-based.

Supported roles:

- ADMIN
- USER

Example rules

ADMIN

Allowed

- /admin/**
- /users/**
- /orders/**

USER

Allowed

- /users/**
- /orders/**

Denied

- /admin/**

Unauthorized access returns

HTTP 403 Forbidden

---

# Public Endpoints

Some endpoints do not require authentication.

Examples

- /auth/login
- /health
- /actuator/health

These endpoints bypass authentication filters.

---

# Protected Endpoints

Every other endpoint requires successful authentication before routing.

Examples

- /users/**
- /orders/**
- /admin/**

---

# Security Filter Order

Incoming Request

↓

Correlation ID Filter

↓

Request Logging Filter

↓

Authentication Filter

↓

Authorization Filter

↓

Route Resolution

↓

Backend Service

Security checks always occur before routing.

---

# Error Responses

## Missing Token

HTTP 401 Unauthorized

Reason

Authentication credentials were not provided.

---

## Invalid Token

HTTP 401 Unauthorized

Reason

JWT validation failed.

---

## Invalid API Key

HTTP 401 Unauthorized

Reason

API Key validation failed.

---

## Access Denied

HTTP 403 Forbidden

Reason

Authenticated client lacks permission.

---

# Security Principles

- Authenticate before authorization.
- Authorize before routing.
- Reject invalid requests as early as possible.
- Never expose backend services directly.
- Never trust client-provided identity without validation.
- Keep security logic centralized in the gateway.

---

# Current Scope

Included in this project:

- JWT Authentication
- API Key Authentication
- RBAC Authorization
- Security Filters

---

# Future Enhancements

The following are intentionally outside the current roadmap.

- OAuth 2.0
- OpenID Connect (OIDC)
- Mutual TLS (mTLS)
- Single Sign-On (SSO)
- External Identity Providers
- Fine-grained permission management
- Multi-tenant authorization
- Secret management integration
