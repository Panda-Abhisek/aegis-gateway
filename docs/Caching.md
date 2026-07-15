# Response Caching

## Purpose

This document describes how Aegis Gateway improves performance by caching eligible HTTP responses in Redis.

Caching reduces unnecessary requests to backend services, decreases response latency, and improves overall system throughput.

---

# Objectives

- Reduce backend load
- Improve response time
- Serve repeated requests efficiently
- Reduce unnecessary network traffic

---

# Scope

Included in this project:

- Redis response caching
- Gateway-managed cache
- Cache lookup before backend routing

Not included:

- Distributed cache synchronization
- Cache invalidation events
- CDN integration
- Write-through caching
- Cache warming

---

# Architecture

Client

↓

Gateway

↓

Cache Filter

↓

Redis

↓

Cache Hit

↓

Return Cached Response

or

Cache Miss

↓

Backend Service

↓

Store Response

↓

Return Response

---

# Processing Flow

## Step 1

The gateway receives an HTTP request.

↓

## Step 2

The Cache Filter determines whether the request is cacheable.

Only eligible requests continue to cache lookup.

↓

## Step 3

The gateway checks Redis.

If cached data exists:

Return cached response immediately.

↓

If no cached response exists:

Forward request to backend service.

↓

## Step 4

Backend service processes the request.

↓

## Step 5

The gateway receives the response.

↓

## Step 6

Eligible responses are stored in Redis.

↓

## Step 7

The response is returned to the client.

---

# Cache Eligibility

Requests eligible for caching:

- GET requests
- Successful responses
- Public data

Requests not cached:

- POST requests
- PUT requests
- PATCH requests
- DELETE requests
- Authentication endpoints
- Error responses

---

# Redis

Redis stores cached responses using a generated cache key.

Example

Key

cache:/users/1

Value

Serialized HTTP response

Each entry expires automatically after its configured lifetime.

---

# Request Flow

Client

↓

Gateway

↓

Cache Filter

↓

Redis Lookup

↓

Cache Hit

↓

Return Cached Response

or

Cache Miss

↓

Backend Service

↓

Cache Response

↓

Client

---

# Benefits

- Lower response latency
- Reduced backend workload
- Better scalability
- Faster repeated requests

---

# Configuration

Caching behavior will be configurable.

Example settings:

- Cache enabled
- Cache expiration time
- Redis connection

Configuration changes should not require application code changes.

---

# Design Principles

- Cache only safe responses.
- Never cache failed responses.
- Gateway manages caching.
- Backend services remain unaware of caching.
- Redis is the single cache store.

---

# Future Enhancements

These features are intentionally outside the current roadmap.

- Per-route cache configuration
- Cache invalidation API
- Conditional caching
- Distributed cache synchronization
- Cache analytics
- Multi-level caching
