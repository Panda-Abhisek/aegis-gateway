# Rate Limiting

## Purpose

This document describes how Aegis Gateway protects backend services from excessive traffic using Redis-based rate limiting.

The objective is to prevent abuse, improve system stability, and ensure fair resource usage.

---

# Objectives

- Prevent request flooding
- Protect backend services
- Ensure fair client usage
- Reduce unnecessary server load
- Return clear error responses when limits are exceeded

---

# Scope

Included in this project:

- Redis-backed rate limiting
- Gateway-level enforcement
- HTTP 429 responses

Not included:

- Distributed quotas
- Tier-based rate limits
- Adaptive rate limiting
- Geographic limits

---

# Architecture

Client

↓

Gateway

↓

Rate Limiting Filter

↓

Redis

↓

Decision

↓

Allow Request

or

Reject Request

---

# Processing Flow

## Step 1

The gateway receives an incoming request.

↓

## Step 2

The Rate Limiting Filter extracts the client identifier.

Possible identifiers:

- API Key
- User ID (JWT)
- Client IP (fallback)

↓

## Step 3

The filter checks Redis for the current request count.

↓

## Step 4

If the request count is below the configured limit:

- Increment counter
- Forward request

↓

## Step 5

If the request count exceeds the configured limit:

- Stop request processing
- Return HTTP 429

The backend service is never contacted.

---

# Redis

Redis stores temporary request counters.

Typical entry

Key

rate_limit:user123

Value

Current request count

Counters automatically expire after the configured time window.

---

# Request Flow

Client

↓

Gateway

↓

Rate Limiting Filter

↓

Redis Check

↓

Allowed

↓

Backend Service

or

Exceeded

↓

HTTP 429 Too Many Requests

---

# Response

When the request is allowed:

HTTP 200 (or backend response)

When the limit is exceeded:

HTTP 429 Too Many Requests

Example response

{
  "status": 429,
  "error": "Too Many Requests",
  "message": "Rate limit exceeded."
}

---

# Benefits

- Prevents API abuse
- Improves application stability
- Reduces backend load
- Provides predictable resource usage

---

# Configuration

Rate limiting values will be configurable through application configuration.

Example settings:

- Request limit
- Time window
- Redis connection

No code should require modification when changing limits.

---

# Design Principles

- Gateway enforces limits before routing.
- Backend services remain unaware of rate limiting.
- Redis stores temporary counters only.
- Requests exceeding the limit fail immediately.
- Rate limiting should remain configurable.

---

# Future Enhancements

These are intentionally outside the current roadmap.

- Tier-based limits
- Per-route limits
- Sliding Window algorithm
- Token Bucket algorithm
- Leaky Bucket algorithm
- Distributed quota management
- Dynamic configuration
- Rate limit dashboards
