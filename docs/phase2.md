# Phase 2: Securing the Gateway — JWT, API Keys, RBAC, and Request Validation

## A Deep Dive into Building a Production-Grade Security Layer for a Spring Cloud Gateway

---

### Introduction

In [Phase 1](./phase1.md), we built the skeleton of Aegis Gateway — a reactive API gateway built on Spring Cloud Gateway and WebFlux. We wired up route definitions, proxy predicates, path rewriting, and backend service resolution. The gateway could route traffic. But it couldn't say *no* to anyone.

That's a problem.

An API gateway without security is just a fancy load balancer with extra steps. Every request passes through untouched. A malformed payload, an unauthenticated client, an unauthorized admin call — they all reach your backend services with the same privilege as a legitimate, authenticated request.

**Why does security belong at the gateway?**

Because the gateway is the single entry point. Every request to every microservice flows through it. If you push authentication and authorization to individual services, you end up with duplicated security logic, inconsistent enforcement, and the inevitable service that "forgot" to add the auth check. Centralizing security at the gateway means your backend services can focus on what they do best: business logic.

**Phase 2 overview:**

In this phase, we implement four security capabilities:

1. **JWT Authentication** — Validate JSON Web Tokens from the `Authorization` header
2. **API Key Authentication** — Validate API keys from a custom `X-API-Key` header
3. **Role-Based Access Control (RBAC)** — Enforce route-level authorization rules
4. **Request Validation** — Reject malformed, oversized, or invalid requests before they reach backend services

We'll walk through every class, every configuration line, and every design decision. By the end, you'll have a complete picture of how a production-grade security pipeline works in a reactive Spring Cloud Gateway.

---

### Where Phase 2 Fits

#### Architecture

Aegis Gateway is a three-module Maven project:

```
aegis_gateway/
├── gateway/              # Spring Cloud Gateway (the security layer lives here)
├── user-service/         # Backend microservice (port 8081)
└── order-service/        # Backend microservice (port 8082)
```

The gateway is the only module that faces the outside world. Backend services are internal — they sit behind the gateway and trust it to filter traffic.

#### Request Lifecycle

Every request follows this path:

```
Client
  │
  ▼
┌─────────────────────────────────────────────────┐
│                 Aegis Gateway                    │
│                                                  │
│  1. JwtAuthenticationWebFilter                   │
│  2. ApiKeyAuthenticationWebFilter                │
│  3. Spring Security Authentication               │
│  4. Spring Security Authorization (RBAC)         │
│  5. RequestValidationWebFilter                   │
│  6. Route Resolution                             │
│  7. Proxy to Backend                             │
│                                                  │
└─────────────────────────────────────────────────┘
  │
  ▼
Backend Service (user-service or order-service)
```

#### Security Pipeline

The security pipeline is a chain of Spring WebFlux `WebFilter` instances, orchestrated by Spring Security's `SecurityWebFilterChain`. Each filter has a specific responsibility and a specific position in the chain:

```
┌──────────────────────────────┐
│   JwtAuthenticationWebFilter │  ← Checks Authorization: Bearer <token>
│   (before AUTHENTICATION)    │
├──────────────────────────────┤
│   ApiKeyAuthenticationFilter │  ← Checks X-API-Key header
│   (before AUTHENTICATION)    │
├──────────────────────────────┤
│   Spring Security            │  ← Authentication + Authorization
│   AUTHENTICATION + AUTHZ     │
├──────────────────────────────┤
│   RequestValidationFilter    │  ← Content-Type, payload size, JSON
│   (after AUTHORIZATION)      │
└──────────────────────────────┘
```

Filters are added to the chain in `SecurityConfig.java` using `addFilterBefore` and `addFilterAfter` relative to Spring Security's built-in filter positions. This gives us precise control over execution order without fighting Spring's defaults.

---

### Task 1: JWT Authentication

#### Why JWT

JSON Web Tokens are the de facto standard for stateless authentication in distributed systems. A JWT is self-contained: it carries the user's identity and claims directly in the token. The server doesn't need to look up a session — it validates the signature and trusts the payload.

For an API gateway, this is ideal. The gateway can authenticate a request by validating a single token, without making a round-trip to an authentication service or database. If the token is valid, the gateway trusts the claims and proceeds.

#### JWT Structure

A JWT has three Base64-encoded segments separated by dots:

```
eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJhYmhpc2layIsInJvbGUiOiJST0xFX0FETUlOIn0.signature
```

| Segment | Content | Decoded |
|---------|---------|---------|
| Header | Algorithm + type | `{"alg":"HS256","typ":"JWT"}` |
| Payload | Claims (subject, role, expiry) | `{"sub":"abhisek","role":"ROLE_ADMIN","exp":...}` |
| Signature | HMAC-SHA256 of header.payload | Used to verify the token wasn't tampered with |

The gateway validates three things:
1. **Signature** — Was this token signed with the expected key?
2. **Expiration** — Has the token expired?
3. **Claims** — Does it contain the required subject and role?

#### Request Flow

```
Client                                    Gateway
  │                                         │
  │  GET /users/hello                       │
  │  Authorization: Bearer eyJhbGci...      │
  │────────────────────────────────────────>│
  │                                         │
  │                          JwtAuthenticationWebFilter
  │                          ├─ Extract "Bearer " prefix
  │                          ├─ Call JwtService.parse(token)
  │                          ├─ Validate signature + expiry
  │                          ├─ Extract subject + role claims
  │                          ├─ Build Authentication object
  │                          ├─ Inject X-User-Id header
  │                          └─ Set SecurityContext
  │                                         │
  │                          Spring Security Authorization
  │                          ├─ Check path → /users/**
  │                          ├─ Required role: ROLE_USER or ROLE_ADMIN
  │                          └─ Grant or deny
  │                                         │
  │  200 OK / Hello from User Service       │
  │<────────────────────────────────────────│
```

#### Spring Security WebFlux Architecture

Spring Security's WebFlux variant uses a fundamentally different model from the traditional Servlet-based stack. There are no `FilterChain` proxies or `SecurityContextPersistenceFilter`. Instead, everything is reactive:

- **`SecurityWebFilterChain`** — Defines which paths require which authorization rules
- **`WebFilter`** — A reactive filter that intercepts every request in the pipeline
- **`ReactiveAuthenticationManager`** — Authenticates tokens asynchronously
- **`ReactiveSecurityContextHolder`** — Stores authentication state in the reactive context (not `ThreadLocal`)

This matters because Spring Cloud Gateway is built on WebFlux and runs on Netty. Traditional Spring Security won't work here — it's built for the Servlet API. We use the reactive variant exclusively.

#### Code Walkthrough

We implement JWT authentication using three classes:

**Class 1: `JwtService`**

This is the token parsing engine. It uses the JJWT library to validate and decode tokens.

```java
@Service
public class JwtService {

    private final SecretKey signingKey;

    public JwtService(@Value("${gateway.security.jwt-secret}") String secret) {
        this.signingKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    public Optional<Claims> parse(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(signingKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            return Optional.of(claims);
        } catch (ExpiredJwtException e) {
            return Optional.empty();
        } catch (Exception e) {
            return Optional.empty();
        }
    }
}
```

Key design decisions:

- The signing key is loaded from configuration (`gateway.security.jwt-secret`), not hardcoded. The `Keys.hmacShaKeyFor()` method requires a key of at least 256 bits (32 bytes). Our secret is 64 bytes.
- `parse()` returns `Optional<Claims>`, not the raw claims. This makes the downstream code cleaner — the caller maps over the Optional rather than dealing with null checks.
- Both `ExpiredJwtException` and generic exceptions return `Optional.empty()`. The gateway doesn't distinguish between an expired token and a malformed one — both mean "not authenticated."

**Class 2: `JwtAuthenticationManager`**

This implements `ReactiveAuthenticationManager` and converts raw JWT claims into a Spring Security `Authentication` object.

```java
@Component
@Primary
public class JwtAuthenticationManager implements ReactiveAuthenticationManager {

    private final JwtService jwtService;

    public JwtAuthenticationManager(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    public Mono<Authentication> authenticate(Authentication authentication) {
        Object credentials = authentication.getCredentials();
        if (credentials == null) {
            return Mono.empty();
        }
        String token = credentials.toString();
        return Mono.justOrEmpty(jwtService.parse(token))
                .map(claims -> {
                    String subject = claims.getSubject();
                    String role = claims.get("role", String.class);
                    List<SimpleGrantedAuthority> authorities = role != null
                            ? List.of(new SimpleGrantedAuthority(role))
                            : Collections.emptyList();
                    return (Authentication) new UsernamePasswordAuthenticationToken(
                        subject, null, authorities);
                });
    }
}
```

Key design decisions:

- **`@Primary`** — This is the default authentication manager. When Spring Security needs to authenticate an `Authentication` object, it uses this one unless told otherwise.
- The `role` claim is extracted from the JWT payload and wrapped in a `SimpleGrantedAuthority`. This is what Spring Security uses for RBAC checks later.
- If no role claim exists, the user gets an empty authority list — they'll pass authentication but fail authorization on protected routes.
- The token string is passed as both `principal` and `credentials` in the initial `UsernamePasswordAuthenticationToken`. After authentication, the manager replaces it with the `subject` claim as the principal.

**Class 3: `JwtAuthenticationWebFilter`**

This is the entry point — the `WebFilter` that intercepts incoming requests and triggers the JWT validation flow.

```java
@Component
public class JwtAuthenticationWebFilter implements WebFilter {

    private final JwtAuthenticationManager authenticationManager;

    public JwtAuthenticationWebFilter(JwtAuthenticationManager authenticationManager) {
        this.authenticationManager = authenticationManager;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String authHeader = exchange.getRequest().getHeaders()
            .getFirst(HttpHeaders.AUTHORIZATION);
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return chain.filter(exchange);
        }

        String token = authHeader.substring(7);
        UsernamePasswordAuthenticationToken authRequest =
                new UsernamePasswordAuthenticationToken(token, token);

        return authenticationManager.authenticate(authRequest)
                .flatMap(authentication -> {
                    ServerHttpRequest mutatedRequest = exchange.getRequest().mutate()
                            .header("X-User-Id", authentication.getName())
                            .build();
                    return chain.filter(exchange.mutate().request(mutatedRequest).build())
                            .contextWrite(
                                ReactiveSecurityContextHolder
                                    .withAuthentication(authentication));
                })
                .switchIfEmpty(chain.filter(exchange));
    }
}
```

This is the most important class in the JWT subsystem. Let's trace through it:

1. **Extract the header** — `getFirst("Authorization")` returns the raw header value.
2. **Check prefix** — If there's no `Authorization` header or it doesn't start with `"Bearer "`, the filter passes through. This allows the API Key filter to try next. The filter does **not** reject the request — it just doesn't authenticate it.
3. **Extract the token** — `substring(7)` strips the `"Bearer "` prefix (7 characters).
4. **Create the auth request** — The token string is wrapped in a `UsernamePasswordAuthenticationToken`. Both principal and credentials are set to the token string.
5. **Authenticate** — The `JwtAuthenticationManager` validates the token and returns an `Authentication` object.
6. **On success** — Three things happen:
   - The request is **mutated** to include an `X-User-Id` header (set to the JWT subject). This tells backend services who the caller is without them needing to re-validate the token.
   - The `Authentication` object is placed into the reactive security context using `contextWrite`.
   - The filter chain continues with the mutated request.
7. **On empty (no token / invalid token)** — `switchIfEmpty` passes through to the next filter. The request remains unauthenticated.

The `X-User-Id` header is a critical detail. Backend services can read it to identify the caller:

```java
// In a backend service controller
String userId = request.getHeader("X-User-Id");
```

This means backend services don't need JWT libraries, signing keys, or token parsing logic. They trust the gateway.

#### Libraries Used

| Library | Version | Purpose |
|---------|---------|---------|
| `jjwt-api` | 0.13.0 | JWT parsing and validation API |
| `jjwt-impl` | 0.13.0 | JJWT implementation (runtime dependency) |
| `jjwt-jackson` | 0.13.0 | JSON deserialization for JWT payloads |
| `spring-boot-starter-security` | 4.1.0 | Spring Security WebFlux |

The JJWT 0.13 API is significantly different from older versions. The `Jwts.parserBuilder()` pattern is gone — replaced by `Jwts.parser().verifyWith(key).build()`. The `parseClaimsJws()` method is replaced by `parseSignedClaims()`. If you're following along with older tutorials, expect to hit deprecation warnings.

#### Classes Implemented

```
com.aegis.security/
├── SecurityConfig.java               # Filter chain + RBAC rules
├── JwtService.java                   # Token parsing (JJWT)
├── JwtAuthenticationManager.java     # Claims → Authentication object
└── JwtAuthenticationWebFilter.java   # WebFilter entry point
```

#### Validation Flow

```
Incoming Request
  │
  ▼
JwtAuthenticationWebFilter
  │
  ├─ No Authorization header? ──→ Pass through (let next filter try)
  │
  ├─ Header doesn't start with "Bearer "? ──→ Pass through
  │
  ├─ Extract token
  │
  ▼
JwtService.parse(token)
  │
  ├─ Invalid signature? ──→ Optional.empty() ──→ Pass through
  ├─ Expired token?     ──→ Optional.empty() ──→ Pass through
  ├─ Malformed token?   ──→ Optional.empty() ──→ Pass through
  │
  ▼
Claims extracted successfully
  │
  ├─ Extract subject + role
  ├─ Build Authentication object
  ├─ Inject X-User-Id header
  ├─ Set SecurityContext
  │
  ▼
Continue filter chain
```

#### Testing

Generate a test JWT using Python (the secret must match your `application.yaml`):

```bash
JWT=$(python3 -c "
import hmac, hashlib, base64, json, time
s = ';oiajsd;oirlawelkdjadnfalsdfjawhero23nr23r0hn34trjp4t;nfguq34tbrgu'
h = base64.urlsafe_b64encode(json.dumps({'alg':'HS256','typ':'JWT'}).encode()).rstrip(b'=').decode()
p = base64.urlsafe_b64encode(json.dumps({'sub':'abhisek','role':'ROLE_ADMIN','iat':int(time.time()),'exp':int(time.time())+3600}).encode()).rstrip(b'=').decode()
sig = base64.urlsafe_b64encode(hmac.new(s.encode(), f'{h}.{p}'.encode(), hashlib.sha256).digest()).rstrip(b'=').decode()
print(f'{h}.{p}.{sig}')
")
```

Test scenarios:

```bash
# 401 — No authentication at all
curl -v http://localhost:8080/users/hello

# 200 — Valid JWT with ROLE_ADMIN
curl -v -H "Authorization: Bearer $JWT" http://localhost:8080/users/hello

# 401 — Invalid token
curl -v -H "Authorization: Bearer invalid.token.here" http://localhost:8080/users/hello

# 200 — Expired token (generate one with exp in the past)
# The gateway returns 401, same as an invalid token
```

A `502` or `503` after a `200` from the security layer means authentication and authorization passed — the backend service is just unreachable. This is expected if backend services aren't running.

---

### Task 2: API Key Authentication

#### Why API Keys

JWTs are great for user-facing applications — mobile apps, SPAs, and interactive sessions. But not every client is interactive. Machine-to-machine communication, internal service calls, legacy integrations, and third-party webhooks often need a simpler authentication mechanism.

API keys fill this gap. They're static, predictable, and easy to implement on both sides. A client sends a key in a custom header, the server validates it against a known list, and the request is authenticated.

JWTs carry identity and expiry in the token itself. API keys are opaque strings that map to a role or permissions on the server side. The tradeoff is that API keys don't expire automatically (you have to rotate them manually) and they don't carry claims — but for machine clients, that's often acceptable.

#### API Key Flow

```
Client                                    Gateway
  │                                         │
  │  GET /users/hello                       │
  │  X-API-Key: admin-key                   │
  │────────────────────────────────────────>│
  │                                         │
  │                          JwtAuthenticationWebFilter
  │                          ├─ No Authorization header
  │                          └─ Pass through (no JWT auth)
  │                                         │
  │                          ApiKeyAuthenticationWebFilter
  │                          ├─ Check SecurityContext (already authed?)
  │                          ├─ No → Check X-API-Key header
  │                          ├─ Validate key against config
  │                          ├─ Resolve role from key
  │                          ├─ Inject X-User-Id header
  │                          └─ Set SecurityContext
  │                                         │
  │                          Spring Security Authorization
  │                          └─ Check role against path rules
  │                                         │
  │  200 OK / Hello from User Service       │
  │<────────────────────────────────────────│
```

#### Authentication Chaining (JWT OR API Key)

The gateway supports both JWT and API Key authentication, but they are **mutually exclusive per request**. A request authenticates via one method or the other, not both.

This is implemented through filter ordering and the security context check:

```
Request arrives
  │
  ▼
JwtAuthenticationWebFilter
  ├─ Has Authorization: Bearer header? ──→ Yes: Authenticate with JWT
  │                                         No:  Pass through
  ▼
ApiKeyAuthenticationWebFilter
  ├─ Already authenticated? ──→ Yes: Pass through (JWT won)
  │                              No:  Check X-API-Key header
  ├─ Has X-API-Key header? ──→ Yes: Authenticate with API Key
  │                              No:  Pass through (unauthenticated)
```

The key insight is in `ApiKeyAuthenticationWebFilter`: it first checks the reactive security context. If the JWT filter already set an authentication, it skips API key processing entirely. This prevents double-authentication and ensures clear precedence.

#### Code Walkthrough

**Class 1: `ApiKeyProperties`**

This class reads API key configuration from `application.yaml` and provides lookup methods.

```java
@Component
@ConfigurationProperties(prefix = "gateway.security")
public class ApiKeyProperties {

    private List<KeyEntry> apiKeys = new ArrayList<>();

    public List<KeyEntry> getApiKeys() {
        return apiKeys;
    }

    public void setApiKeys(List<KeyEntry> apiKeys) {
        this.apiKeys = apiKeys;
    }

    public boolean isValid(String key) {
        return key != null && apiKeys != null
            && apiKeys.stream().anyMatch(e -> key.equals(e.getKey()));
    }

    public String getRoleForKey(String key) {
        if (key == null || apiKeys == null) {
            return null;
        }
        return apiKeys.stream()
                .filter(e -> key.equals(e.getKey()))
                .map(KeyEntry::getRole)
                .findFirst()
                .orElse(null);
    }

    public static class KeyEntry {
        private String key;
        private String role;

        // getters and setters
    }
}
```

Bound to this configuration in `application.yaml`:

```yaml
gateway:
  security:
    api-keys:
      - key: admin-key
        role: ROLE_ADMIN
      - key: user-key
        role: ROLE_USER
```

Each key is mapped to a role. The `isValid()` method checks if the key exists in the list. The `getRoleForKey()` method resolves the role. This is a simple in-memory lookup — suitable for a small number of keys. For production, you'd move this to a database (the R2DBC dependency is already wired for this).

**Class 2: `ApiKeyAuthenticationToken`**

A thin wrapper around `UsernamePasswordAuthenticationToken` that represents an API key authentication attempt.

```java
public class ApiKeyAuthenticationToken extends UsernamePasswordAuthenticationToken {

    public ApiKeyAuthenticationToken(String apiKey) {
        super(apiKey, apiKey, Collections.emptyList());
    }
}
```

This exists for type safety and readability. When `ApiKeyAuthenticationManager` receives an `Authentication` object, it can check if it's an `ApiKeyAuthenticationToken` and handle it accordingly. In practice, the current implementation uses `@Primary` on `JwtAuthenticationManager` and relies on the filter to route the right token to the right manager.

**Class 3: `ApiKeyAuthenticationManager`**

```java
@Component
public class ApiKeyAuthenticationManager implements ReactiveAuthenticationManager {

    private final ApiKeyProperties apiKeyProperties;

    public ApiKeyAuthenticationManager(ApiKeyProperties apiKeyProperties) {
        this.apiKeyProperties = apiKeyProperties;
    }

    @Override
    public Mono<Authentication> authenticate(Authentication authentication) {
        Object credentials = authentication.getCredentials();
        if (credentials == null) {
            return Mono.empty();
        }
        String apiKey = credentials.toString();
        if (apiKeyProperties.isValid(apiKey)) {
            String role = apiKeyProperties.getRoleForKey(apiKey);
            List<SimpleGrantedAuthority> authorities = role != null
                    ? List.of(new SimpleGrantedAuthority(role))
                    : Collections.emptyList();
            return Mono.just(new UsernamePasswordAuthenticationToken(
                apiKey, null, authorities));
        }
        return Mono.empty();
    }
}
```

If the key is valid, the manager returns an `Authentication` object with the resolved role. If not, `Mono.empty()` signals authentication failure.

**Class 4: `ApiKeyAuthenticationWebFilter`**

This is the most complex filter in the gateway. It has to handle three scenarios:

```java
@Component
public class ApiKeyAuthenticationWebFilter implements WebFilter {

    private final ApiKeyAuthenticationManager authenticationManager;

    public ApiKeyAuthenticationWebFilter(
            ApiKeyAuthenticationManager authenticationManager) {
        this.authenticationManager = authenticationManager;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        return ReactiveSecurityContextHolder.getContext()
                .map(ctx -> ctx.getAuthentication())
                .filter(auth -> auth != null && auth.isAuthenticated())
                .flatMap(auth -> chain.filter(exchange))
                .switchIfEmpty(Mono.defer(() -> {
                    String apiKey = exchange.getRequest().getHeaders()
                        .getFirst("X-API-Key");
                    if (apiKey == null) {
                        return chain.filter(exchange);
                    }

                    ApiKeyAuthenticationToken authRequest =
                        new ApiKeyAuthenticationToken(apiKey);
                    return authenticationManager.authenticate(authRequest)
                            .flatMap(authentication -> {
                                ServerHttpRequest mutatedRequest =
                                    exchange.getRequest().mutate()
                                        .header("X-User-Id",
                                            authentication.getName())
                                        .build();
                                return chain.filter(
                                    exchange.mutate()
                                        .request(mutatedRequest).build())
                                    .contextWrite(
                                        ReactiveSecurityContextHolder
                                            .withAuthentication(authentication));
                            })
                            .switchIfEmpty(unauthorized(exchange));
                }));
    }

    private Mono<Void> unauthorized(ServerWebExchange exchange) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        return response.setComplete();
    }
}
```

Let's trace through the three scenarios:

**Scenario 1: JWT already authenticated the request**

```java
return ReactiveSecurityContextHolder.getContext()
    .map(ctx -> ctx.getAuthentication())
    .filter(auth -> auth != null && auth.isAuthenticated())
    .flatMap(auth -> chain.filter(exchange))
```

The filter checks the reactive security context. If the JWT filter already set an authentication, this filter short-circuits and passes through. No API key processing happens.

**Scenario 2: No API Key header**

```java
.switchIfEmpty(Mono.defer(() -> {
    String apiKey = exchange.getRequest().getHeaders().getFirst("X-API-Key");
    if (apiKey == null) {
        return chain.filter(exchange);
    }
```

If there's no `X-API-Key` header, the filter passes through. The request remains unauthenticated, and Spring Security's authorization rules will reject it if it hits a protected path.

**Scenario 3: Valid or invalid API key**

```java
    ApiKeyAuthenticationToken authRequest = new ApiKeyAuthenticationToken(apiKey);
    return authenticationManager.authenticate(authRequest)
            .flatMap(authentication -> {
                // ... mutate request, set context
            })
            .switchIfEmpty(unauthorized(exchange));
```

If an API key is present, it's validated. On success, the request is mutated with `X-User-Id` and the security context is set. On failure (`Mono.empty()` from the manager), `switchIfEmpty` triggers the `unauthorized()` helper, which returns a `401` immediately.

This is an important difference from the JWT filter. The JWT filter passes through on failure (letting Spring Security handle the 401). The API key filter returns 401 immediately because an explicit API key was provided but rejected — there's no ambiguity.

#### Configuration

```yaml
gateway:
  security:
    api-keys:
      - key: admin-key
        role: ROLE_ADMIN
      - key: user-key
        role: ROLE_USER
```

Each entry maps a key string to a Spring Security authority. The gateway currently supports two roles: `ROLE_ADMIN` and `ROLE_USER`.

For production, these keys should be:
- Stored in a database, not in YAML
- Encrypted at rest
- Rotatable without restarting the gateway
- Scoped to specific routes or services

The R2DBC dependency is already wired into the gateway's POM — it's scaffolding for exactly this use case.

#### Classes Implemented

```
com.aegis.security/
├── ApiKeyProperties.java              # Configuration binding + lookup
├── ApiKeyAuthenticationToken.java     # Token wrapper
├── ApiKeyAuthenticationManager.java   # Key validation + role resolution
└── ApiKeyAuthenticationWebFilter.java # WebFilter entry point
```

#### Testing

```bash
# 200 — Valid API key (admin)
curl -v -H "X-API-Key: admin-key" http://localhost:8080/users/hello

# 200 — Valid API key (user)
curl -v -H "X-API-Key: user-key" http://localhost:8080/users/hello

# 401 — Invalid API key
curl -v -H "X-API-Key: wrong-key" http://localhost:8080/users/hello

# 403 — Valid API key but wrong role (USER on admin route)
curl -v -H "X-API-Key: user-key" http://localhost:8080/admin/hello

# 200 — Valid API key with correct role (ADMIN on admin route)
curl -v -H "X-API-Key: admin-key" http://localhost:8080/admin/hello
```

The `403` case is important to understand: the API key authentication *succeeded* (the key is valid), but the authorization check *failed* (the role doesn't match the path requirements). Authentication and authorization are separate concerns, and the gateway distinguishes between them.

---

### Task 3: Role-Based Access Control (RBAC)

#### Authentication vs Authorization

These two terms are often confused, but they answer different questions:

- **Authentication**: *Who are you?* — Validated by JWT or API Key
- **Authorization**: *What are you allowed to do?* — Validated by RBAC rules

Authentication happens first. Once the gateway knows who the caller is (via JWT claims or API key mapping), authorization checks whether that identity has permission to access the requested resource.

```
Authentication                         Authorization
"Here's my token"                     "Here's my token"
     │                                     │
     ▼                                     ▼
  Valid token?                          Has ROLE_ADMIN?
  Valid key?                            Has ROLE_USER?
     │                                     │
     ▼                                     ▼
  ✅ Authenticated                      ✅ Authorized → Proceed
  ❌ 401 Unauthorized                   ❌ 403 Forbidden
```

The status codes are different: `401` means "who are you?" and `403` means "you're not allowed here."

#### Role Hierarchy

Aegis Gateway implements a flat two-role hierarchy:

| Role | Access |
|------|--------|
| `ROLE_ADMIN` | `/admin/**`, `/users/**`, `/orders/**`, `/api/**` |
| `ROLE_USER` | `/users/**`, `/orders/**`, `/api/**` |

`ROLE_ADMIN` is a superset of `ROLE_USER`. Admins can access everything; regular users cannot access admin routes.

This is enforced in `SecurityConfig.java` using Spring Security's `authorizeExchange` DSL:

```java
.authorizeExchange(exchange -> exchange
    .pathMatchers("/actuator/**").permitAll()
    .pathMatchers("/admin/**").hasAuthority("ROLE_ADMIN")
    .pathMatchers("/users/**", "/orders/**", "/api/**")
        .hasAnyAuthority("ROLE_USER", "ROLE_ADMIN")
)
```

Let's break down each rule:

| Pattern | Rule | Effect |
|---------|------|--------|
| `/actuator/**` | `permitAll()` | No authentication required. Health checks, metrics, and info endpoints are always accessible. |
| `/admin/**` | `hasAuthority("ROLE_ADMIN")` | Only `ROLE_ADMIN` can access. `ROLE_USER` gets a `403`. |
| `/users/**`, `/orders/**`, `/api/**` | `hasAnyAuthority("ROLE_USER", "ROLE_ADMIN")` | Both roles can access. Unauthenticated requests get a `401`. |

#### Spring Security Authorities

Spring Security uses `GrantedAuthority` objects to represent permissions. In Aegis Gateway, each JWT or API key maps to exactly one authority: the `role` claim from the JWT, or the `role` field from the API key configuration.

Both JWT and API key authentication produce the same type of authority:

```java
List<SimpleGrantedAuthority> authorities = role != null
    ? List.of(new SimpleGrantedAuthority(role))
    : Collections.emptyList();
```

This means the authorization layer doesn't care *how* the user authenticated. It only checks whether the `Authentication` object has the right authority for the path. A JWT with `ROLE_ADMIN` and an API key mapped to `ROLE_ADMIN` are treated identically.

#### Route Protection

Here's the complete picture of which routes require which roles:

```
/actuator/health     → permitAll         → Any client
/users/hello         → ROLE_USER or ROLE_ADMIN
/users               → ROLE_USER or ROLE_ADMIN
/orders/**           → ROLE_USER or ROLE_ADMIN
/api/**              → ROLE_USER or ROLE_ADMIN
/admin/**            → ROLE_ADMIN only
```

Note: The `api/**` pattern covers versioned and rewritten routes like `/api/v2/users/**` and `/api/users/**`. This is deliberate — API versioning shouldn't bypass authorization.

#### Code Walkthrough

The RBAC logic lives entirely in `SecurityConfig.java`. There are no separate authorization classes — Spring Security handles it.

```java
@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    private final JwtAuthenticationWebFilter jwtFilter;
    private final ApiKeyAuthenticationWebFilter apiKeyFilter;
    private final RequestValidationWebFilter validationFilter;

    public SecurityConfig(JwtAuthenticationWebFilter jwtFilter,
                          ApiKeyAuthenticationWebFilter apiKeyFilter,
                          RequestValidationWebFilter validationFilter) {
        this.jwtFilter = jwtFilter;
        this.apiKeyFilter = apiKeyFilter;
        this.validationFilter = validationFilter;
    }

    @Bean
    public SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http) {
        http
            .csrf(ServerHttpSecurity.CsrfSpec::disable)
            .authorizeExchange(exchange -> exchange
                .pathMatchers("/actuator/**").permitAll()
                .pathMatchers("/admin/**").hasAuthority("ROLE_ADMIN")
                .pathMatchers("/users/**", "/orders/**", "/api/**")
                    .hasAnyAuthority("ROLE_USER", "ROLE_ADMIN")
            )
            .addFilterBefore(jwtFilter,
                SecurityWebFiltersOrder.AUTHENTICATION)
            .addFilterBefore(apiKeyFilter,
                SecurityWebFiltersOrder.AUTHENTICATION)
            .addFilterAfter(validationFilter,
                SecurityWebFiltersOrder.AUTHORIZATION)
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint((exchange, authException) -> {
                    exchange.getResponse()
                        .setStatusCode(HttpStatus.UNAUTHORIZED);
                    return exchange.getResponse().setComplete();
                })
                .accessDeniedHandler((exchange, denied) -> {
                    exchange.getResponse()
                        .setStatusCode(HttpStatus.FORBIDDEN);
                    return exchange.getResponse().setComplete();
                })
            );

        return http.build();
    }
}
```

Two things to notice:

1. **CSRF is disabled.** This is correct for a stateless API gateway. CSRF protection exists for browser-based applications that rely on cookies. JWT and API key authentication don't use cookies — they use explicit headers. There's nothing to forge.

2. **Exception handlers are custom.** The default Spring Security exception handlers return HTML error pages. We replace them with clean HTTP status codes: `401` for unauthenticated, `403` for unauthorized. API clients expect status codes, not HTML.

#### Testing

```bash
# 401 — No authentication on protected route
curl -v http://localhost:8080/users/hello

# 403 — ROLE_USER on admin route
curl -v -H "X-API-Key: user-key" http://localhost:8080/admin/hello

# 200 — ROLE_ADMIN on admin route
curl -v -H "X-API-Key: admin-key" http://localhost:8080/admin/hello

# 200 — ROLE_USER on user route
curl -v -H "X-API-Key: user-key" http://localhost:8080/users/hello

# 200 — Public endpoint (no auth needed)
curl -v http://localhost:8080/actuator/health
```

The `403` vs `401` distinction is important for debugging:

- `401` — The client didn't provide credentials, or the credentials were invalid. Fix: provide a valid JWT or API key.
- `403` — The client authenticated successfully but lacks the required role. Fix: use a different key or token with the right role.

---

### Task 4: Request Validation

#### Why Validate at the Gateway

Authentication and authorization answer *who* and *what*. Request validation answers *is this request well-formed?*

A gateway that only checks identity but allows any payload through is leaving a gap. A malformed JSON body, an oversized payload, a missing `Content-Type` header — these are all requests that should fail fast, before they consume resources on backend services.

Validation at the gateway means:

- **Backend services don't need to repeat validation logic.** One place to enforce rules.
- **Rejection is faster.** The gateway catches bad requests before they hit the network or the service layer.
- **Backend services are protected from resource exhaustion.** A 10MB JSON body that fails parsing at the gateway never reaches the service.

#### HTTP Validation

The `RequestValidationWebFilter` runs **after** authorization. This is deliberate — we don't want to validate requests from unauthenticated clients. If the request fails authentication, it never reaches the validation filter.

For `POST`, `PUT`, and `PATCH` requests, the filter performs three checks in sequence:

```
Request arrives (after auth + authz)
  │
  ▼
Required headers present?
  ├─ No  → 400 Bad Request
  ├─ Yes → Continue
  │
Content-Type header present?
  ├─ No  → 400 Bad Request
  ├─ Yes → Continue
  │
Content-Type in allowed list?
  ├─ No  → 415 Unsupported Media Type
  ├─ Yes → Continue
  │
Content-Length ≤ maxPayloadSize?
  ├─ No  → 413 Payload Too Large
  ├─ Yes → Continue
  │
Content-Type is JSON?
  ├─ Yes → Read body, parse with ObjectMapper
  │         ├─ Parse fails → 400 Bad Request (Malformed JSON)
  │         └─ Parse succeeds → Re-wrap body, continue
  └─ No  → Continue
```

#### Content-Type Validation

The gateway only allows content types listed in the configuration:

```yaml
gateway:
  validation:
    allowed-content-types:
      - application/json
```

If a client sends `text/plain`, `application/xml`, or anything else, the gateway returns `415 Unsupported Media Type`. This prevents backend services from receiving content they can't parse.

The `isAllowedContentType()` method does a substring match:

```java
private boolean isAllowedContentType(String contentType) {
    for (String allowed : allowedContentTypes) {
        if (contentType.contains(allowed)) {
            return true;
        }
    }
    return false;
}
```

This means `application/json; charset=utf-8` is allowed because it *contains* `application/json`. This is intentional — the `Content-Type` header often includes a charset parameter.

#### Header Validation

If `required-headers` is configured, the gateway checks that each listed header is present:

```yaml
gateway:
  validation:
    required-headers:
      - X-Request-Id
      - X-Correlation-Id
```

Currently, the list is empty:

```yaml
required-headers: []
```

No headers are required beyond what Spring Security enforces. This is extensible — when you add correlation ID support in Phase 3, you can enforce it here.

#### Payload Limits

The gateway enforces a maximum payload size of 1 MB (configurable):

```yaml
gateway:
  validation:
    max-payload-size: 1048576  # 1 MB
```

There are two enforcement points:

1. **Header-level check** — `RequestValidationWebFilter` checks `Content-Length` against `maxPayloadSize` and returns `413` if exceeded.
2. **Codec-level check** — Spring's `ServerCodecConfigurer` has its own limit (`spring.codec.max-in-memory-size: 1MB`). If a body exceeds this, it throws `DataBufferLimitException`.

The `ValidationErrorHandler` catches `DataBufferLimitException` and converts it to a clean `413`:

```java
@Component
public class ValidationErrorHandler implements WebExceptionHandler, Ordered {

    @Override
    public int getOrder() {
        return -1;  // Very high priority
    }

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable ex) {
        ServerHttpResponse response = exchange.getResponse();
        if (response.isCommitted()) {
            return Mono.error(ex);
        }

        if (ex instanceof DataBufferLimitException) {
            response.setStatusCode(HttpStatus.PAYLOAD_TOO_LARGE);
            return response.setComplete();
        }

        return Mono.error(ex);
    }
}
```

The `getOrder()` returning `-1` means this handler runs early in the exception handler chain. Without it, `DataBufferLimitException` would bubble up as a `500 Internal Server Error` — not the clean `413` clients expect.

#### Error Handling

Each validation failure produces a specific HTTP status:

| Check | Status | Message |
|-------|--------|---------|
| Missing required header | `400 Bad Request` | `Missing required header: <name>` |
| Missing Content-Type | `400 Bad Request` | `Missing Content-Type header` |
| Disallowed Content-Type | `415 Unsupported Media Type` | `Content-Type not allowed: <type>` |
| Payload too large (header) | `413 Payload Too Large` | `Payload exceeds limit` |
| Payload too large (codec) | `413 Payload Too Large` | *(no message)* |
| Malformed JSON body | `400 Bad Request` | `Malformed JSON` |

The `error()` helper method sets the status code and completes the response:

```java
private Mono<Void> error(ServerWebExchange exchange,
                          HttpStatus status, String message) {
    ServerHttpResponse response = exchange.getResponse();
    response.setStatusCode(status);
    return response.setComplete();
}
```

Currently, error responses don't include a JSON body with the message. This is something you'd add in a later phase — API clients benefit from structured error responses like `{"error": "Malformed JSON", "status": 400}`.

#### Code Walkthrough

The complete `RequestValidationWebFilter`:

```java
@Component
public class RequestValidationWebFilter implements WebFilter {

    private final long maxPayloadSize;
    private final List<String> requiredHeaders;
    private final List<String> allowedContentTypes;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public RequestValidationWebFilter(
            @Value("${gateway.validation.max-payload-size:1048576}")
                long maxPayloadSize,
            @Value("${gateway.validation.required-headers:}")
                List<String> requiredHeaders,
            @Value("${gateway.validation.allowed-content-types:application/json}")
                List<String> allowedContentTypes) {
        this.maxPayloadSize = maxPayloadSize;
        this.requiredHeaders = requiredHeaders != null
            ? requiredHeaders : List.of();
        this.allowedContentTypes = allowedContentTypes != null
            ? allowedContentTypes : List.of("application/json");
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange,
                              WebFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();

        // Check required headers
        for (String header : requiredHeaders) {
            if (request.getHeaders().getFirst(header) == null) {
                return error(exchange, HttpStatus.BAD_REQUEST,
                    "Missing required header: " + header);
            }
        }

        HttpMethod method = request.getMethod();
        if (method == HttpMethod.POST
                || method == HttpMethod.PUT
                || method == HttpMethod.PATCH) {

            // Check Content-Type
            String contentType = request.getHeaders()
                .getFirst(HttpHeaders.CONTENT_TYPE);
            if (contentType == null) {
                return error(exchange, HttpStatus.BAD_REQUEST,
                    "Missing Content-Type header");
            }
            if (!isAllowedContentType(contentType)) {
                return error(exchange,
                    HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                    "Content-Type not allowed: " + contentType);
            }

            // Check payload size
            long contentLength = request.getHeaders().getContentLength();
            if (contentLength > maxPayloadSize) {
                return error(exchange,
                    HttpStatus.PAYLOAD_TOO_LARGE,
                    "Payload exceeds limit");
            }

            // Validate JSON body
            if (isJsonContentType(contentType)) {
                return validateBody(exchange, chain);
            }
        }

        return chain.filter(exchange);
    }

    private Mono<Void> validateBody(ServerWebExchange exchange,
                                     WebFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        Flux<DataBuffer> body = request.getBody();

        return DataBufferUtils.join(body)
                .flatMap(dataBuffer -> {
                    byte[] bytes =
                        new byte[dataBuffer.readableByteCount()];
                    dataBuffer.read(bytes);
                    DataBufferUtils.release(dataBuffer);

                    try {
                        objectMapper.readTree(bytes);
                    } catch (Exception e) {
                        return error(exchange,
                            HttpStatus.BAD_REQUEST, "Malformed JSON");
                    }

                    DataBuffer buffer =
                        DefaultDataBufferFactory.sharedInstance
                            .wrap(bytes);
                    ServerHttpRequest mutatedRequest =
                        new ServerHttpRequestDecorator(request) {
                            @Override
                            public Flux<DataBuffer> getBody() {
                                return Flux.just(buffer);
                            }
                        };
                    return chain.filter(
                        exchange.mutate()
                            .request(mutatedRequest).build());
                });
    }

    // ... helper methods
}
```

The `validateBody()` method is the most interesting part. It reads the entire request body into memory, attempts to parse it as JSON, and then re-wraps the bytes in a `ServerHttpRequestDecorator`. This is necessary because Spring WebFlux request bodies are one-shot — once you read them, they're consumed. The decorator lets downstream filters and the route handler read the body again.

This approach works for small-to-medium payloads. For very large payloads, you'd want streaming validation instead of buffering the entire body.

#### Testing

```bash
# 415 — Wrong Content-Type
curl -v -H "X-API-Key: admin-key" \
  -H "Content-Type: text/plain" \
  -X POST http://localhost:8080/users \
  -d 'hello'

# 400 — Malformed JSON
curl -v -H "X-API-Key: admin-key" \
  -H "Content-Type: application/json" \
  -X POST http://localhost:8080/users \
  -d '{bad'

# 413 — Payload too large
python3 -c "print('X' * 1048577)" > /tmp/big.txt
curl -v -H "X-API-Key: admin-key" \
  -H "Content-Type: application/json" \
  -X POST http://localhost:8080/users \
  -d @/tmp/big.txt

# 200 — Valid JSON payload
curl -v -H "X-API-Key: admin-key" \
  -H "Content-Type: application/json" \
  -X POST http://localhost:8080/users \
  -d '{"name": "abhisek"}'
```

Note that validation only applies to `POST`, `PUT`, and `PATCH` requests. `GET` requests don't have bodies, so there's nothing to validate.

---

### Complete Request Flow

#### Sequence Diagram

Here's the full lifecycle of a request that authenticates with a JWT and accesses a protected route:

```
┌────────┐          ┌──────────────────────────────────────────────┐          ┌──────────────┐
│ Client │          │               Aegis Gateway                  │          │ Backend Svc  │
└───┬────┘          └──────────────────────────────────────────────┘          └──────┬───────┘
    │                                                                                │
    │  POST /users                                                                   │
    │  Authorization: Bearer eyJhbG...                                               │
    │  Content-Type: application/json                                                │
    │  Body: {"name": "abhisek"}                                                     │
    │───────────────────────────────────────────────────────────────────────────────>│
    │                                                                                │
    │                     ┌─────────────────────────────────┐                        │
    │                     │  JwtAuthenticationWebFilter     │                        │
    │                     │  ├─ Extract "Bearer eyJhbG..."  │                        │
    │                     │  ├─ Strip "Bearer " prefix      │                        │
    │                     │  ├─ JwtService.parse(token)     │                        │
    │                     │  │   ├─ Verify HMAC-SHA256 sig  │                        │
    │                     │  │   ├─ Check expiration        │                        │
    │                     │  │   └─ Extract claims          │                        │
    │                     │  ├─ Build Authentication(sub,   │                        │
    │                     │  │   ROLE_ADMIN)                │                        │
    │                     │  ├─ Mutate request:             │                        │
    │                     │  │   X-User-Id: abhisek         │                        │
    │                     │  └─ Set SecurityContext         │                        │
    │                     └─────────────────────────────────┘                        │
    │                                                                                │
    │                     ┌─────────────────────────────────┐                        │
    │                     │  ApiKeyAuthenticationWebFilter  │                        │
    │                     │  └─ SecurityContext has auth?   │                        │
    │                     │     Yes → Pass through          │                        │
    │                     └─────────────────────────────────┘                        │
    │                                                                                │
    │                     ┌─────────────────────────────────┐                        │
    │                     │  Spring Security AUTHZ          │                        │
    │                     │  ├─ Path: /users/**             │                        │
    │                     │  ├─ Required: ROLE_USER|ADMIN   │                        │
    │                     │  └─ Has ROLE_ADMIN → Allowed    │                        │
    │                     └─────────────────────────────────┘                        │
    │                                                                                │
    │                     ┌─────────────────────────────────┐                        │
    │                     │  RequestValidationWebFilter     │                        │
    │                     │  ├─ Content-Type: allowed       │                        │
    │                     │  ├─ Content-Length: ≤ 1MB        │                        │
    │                     │  ├─ Parse JSON body             │                        │
    │                     │  └─ Valid → Continue            │                        │
    │                     └─────────────────────────────────┘                        │
    │                                                                                │
    │                     ┌─────────────────────────────────┐                        │
    │                     │  Route Resolution               │                        │
    │                     │  ├─ Match: user-post             │                        │
    │                     │  └─ Forward to localhost:8081   │                        │
    │                     └─────────────────────────────────┘                        │
    │                                                                                │
    │                                                                                │
    │  200 OK                                                                         │
    │  "User created: abhisek"                                                        │
    │<───────────────────────────────────────────────────────────────────────────────│
    │                                                                                │
```

#### End-to-End Example

Let's trace a real request through the system:

**Request:**

```bash
curl -v -H "Authorization: Bearer $JWT" \
  -H "Content-Type: application/json" \
  -X POST http://localhost:8080/users \
  -d '{"name": "abhisek"}'
```

**Step-by-step processing:**

1. **JWT Filter** — Finds `Authorization: Bearer eyJhbG...`. Extracts token. Calls `JwtService.parse()`. Token is valid. Extracts `sub=abhisek`, `role=ROLE_ADMIN`. Creates `Authentication` object. Mutates request to add `X-User-Id: abhisek`. Sets security context.

2. **API Key Filter** — Checks security context. Authentication exists. Passes through.

3. **Spring Security Authorization** — Path is `/users/**`. Required authorities: `ROLE_USER` or `ROLE_ADMIN`. User has `ROLE_ADMIN`. Allowed.

4. **Validation Filter** — Method is `POST`. Content-Type is `application/json` (allowed). Content-Length is 20 bytes (under 1MB). Body is valid JSON. Passes.

5. **Route Resolution** — Matches route `user-post` (path `/users`, method `POST`). Forwards to `http://localhost:8081/users`.

6. **Backend Service** — `UserController` receives the request. Reads `X-User-Id` header to identify the caller. Processes the request. Returns `200 OK`.

---

### Libraries Used

| Library | Version | Artifact | Purpose |
|---------|---------|----------|---------|
| **Spring Security WebFlux** | 4.1.0 | `spring-boot-starter-security` | Reactive authentication + authorization |
| **Spring Cloud Gateway** | 2025.1.2 | `spring-cloud-starter-gateway-server-webflux` | Reactive API gateway with route resolution |
| **JJWT** | 0.13.0 | `jjwt-api`, `jjwt-impl`, `jjwt-jackson` | JWT parsing, validation, and claims extraction |
| **Reactor** | (bundled) | Project Reactor | Reactive streams (`Mono`, `Flux`) for async processing |
| **Jackson** | (bundled) | `jackson-databind` | JSON parsing for request body validation |
| **Spring R2DBC** | (bundled) | `spring-boot-starter-data-r2dbc` | Reactive PostgreSQL (scaffolding for future) |
| **Lombok** | (bundled) | `lombok` | Boilerplate reduction (annotation processor) |

The reactive stack is critical. Spring Cloud Gateway runs on Netty, not Tomcat. Every component must be non-blocking and reactive. This is why we use `spring-boot-starter-security` (which provides the WebFlux variant) instead of `spring-security-web` (the Servlet variant). Using the wrong one will produce runtime errors.

---

### HTTP Status Codes

The gateway uses six distinct HTTP status codes. Understanding when each one fires is essential for debugging:

| Status | Meaning | When It Fires | Produced By |
|--------|---------|---------------|-------------|
| `200` | OK | Request authenticated, authorized, validated, and proxied successfully | Backend service |
| `400` | Bad Request | Missing required header, missing Content-Type, malformed JSON body | `RequestValidationWebFilter` |
| `401` | Unauthorized | No credentials provided, invalid JWT, invalid API key | `SecurityConfig` auth entry point, `ApiKeyAuthenticationWebFilter` |
| `403` | Forbidden | Authenticated but wrong role for the requested path | `SecurityConfig` access denied handler |
| `413` | Payload Too Large | Request body exceeds `max-payload-size` | `RequestValidationWebFilter`, `ValidationErrorHandler` |
| `415` | Unsupported Media Type | Content-Type not in the allowed list | `RequestValidationWebFilter` |

#### When to Expect Each Code

**`200 OK`** — Everything worked. The request was authenticated, authorized, validated, and forwarded to the backend. The response is from the backend service.

**`400 Bad Request`** — The request is malformed. Three scenarios:
- `POST /users` without a `Content-Type` header
- `POST /users` with a body that isn't valid JSON
- A required header (if configured in `required-headers`) is missing

**`401 Unauthorized`** — The client isn't authenticated. Three scenarios:
- No `Authorization` or `X-API-Key` header on a protected route
- `Authorization: Bearer <expired-or-invalid-token>`
- `X-API-Key: <unknown-key>`

**`403 Forbidden`** — The client is authenticated but not authorized. Two scenarios:
- `ROLE_USER` trying to access `/admin/**`
- An authenticated user without any role trying to access a protected route

**`413 Payload Too Large`** — The request body exceeds 1 MB. This can fire from two places:
- The validation filter's `Content-Length` check
- Spring's codec buffer limit (`DataBufferLimitException`)

**`415 Unsupported Media Type`** — The `Content-Type` header is present but not in the allowed list. Currently, only `application/json` is allowed.

---

### Lessons Learned

#### Gateway Authentication

**Lesson: Authentication belongs at the gateway, not in every service.**

Before implementing gateway auth, the temptation is to add `spring-security` to every microservice. This creates duplicated logic, inconsistent enforcement, and a maintenance nightmare. Centralizing auth at the gateway means:
- One place to update when auth rules change
- Backend services are simpler (they trust the `X-User-Id` header)
- No risk of a service forgetting to add auth

The tradeoff: the gateway becomes a critical dependency. If it goes down, all traffic is blocked. This is acceptable because the gateway was already a single point of failure for routing.

#### Stateless Security

**Lesson: JWT and API keys are stateless by design. No session store needed.**

The gateway doesn't maintain sessions, cookies, or server-side state for authenticated users. A JWT is self-contained — the token carries everything the gateway needs to know. An API key is a static lookup. Neither requires a database call (though API keys could use one in production).

Stateless auth scales horizontally. You can run 10 gateway instances behind a load balancer, and any instance can validate any token. There's no sticky session problem.

#### Authentication Chaining

**Lesson: "OR" logic is harder than "AND" logic.**

The gateway supports JWT *or* API Key, not JWT *and* API Key. This required careful filter ordering and a security context check in the API key filter. If both filters tried to authenticate independently, you'd get conflicts.

The pattern is:
1. First filter tries JWT → succeeds? Done. Fails? Pass through.
2. Second filter checks if already authenticated → yes? Skip. No? Try API key.
3. Neither succeeded? Request is unauthenticated → Spring Security handles the 401.

This "try one, then try the other" pattern is common in gateways that support multiple auth methods.

#### Authorization

**Lesson: RBAC is simple; RBAC with inheritance is not.**

Aegis Gateway uses flat roles: `ROLE_ADMIN` and `ROLE_USER`. No hierarchy, no permissions, no scopes. This is fine for a two-role system. But as you add roles (`ROLE_MANAGER`, `ROLE_READ_ONLY`, `ROLE_SERVICE`), you'll need a role hierarchy. Spring Security supports this with `RoleHierarchy`, but it adds complexity.

Start simple. Add hierarchy when you need it.

#### Early Request Rejection

**Lesson: Reject bad requests as early as possible.**

The validation filter runs *after* authorization but *before* route resolution. This means:
- Unauthenticated requests are rejected at the auth layer (never reach validation)
- Unauthorized requests are rejected at the authz layer (never reach validation)
- Malformed requests are rejected at the validation layer (never reach the backend)

Each layer rejects what it's responsible for. The backend service only sees requests that are authenticated, authorized, and well-formed.

#### Clean Architecture

**Lesson: Each class has one responsibility.**

```
JwtService              → Parses tokens
JwtAuthenticationManager → Converts claims to Authentication
JwtAuthenticationWebFilter → Intercepts requests and triggers auth

ApiKeyProperties        → Reads config + key lookup
ApiKeyAuthenticationManager → Validates keys
ApiKeyAuthenticationWebFilter → Intercepts requests and triggers auth

RequestValidationWebFilter → Validates request format
ValidationErrorHandler  → Catches codec exceptions

SecurityConfig          → Wires everything together
```

This separation makes the codebase maintainable. If you need to change how JWT tokens are parsed, you modify `JwtService`. If you need to change how API keys are stored, you modify `ApiKeyProperties`. The filters don't care about the implementation details — they just trigger authentication.

---

### What's Next

Phase 2 established the security foundation. Phase 3 will build on it with operational concerns:

#### Phase 3 Preview

| Feature | Description |
|---------|-------------|
| **Redis Rate Limiting** | Throttle requests per client using Redis as the rate limit store. Token bucket or sliding window algorithm. Per-route and per-client limits. |
| **Response Caching** | Cache responses from backend services in Redis. Reduce latency for repeated GET requests. Cache invalidation strategies. |
| **Request Logging** | Log every request with method, path, status, latency, and correlation ID. Structured logging for observability. |
| **Correlation IDs** | Generate a unique ID for every request and propagate it through the gateway and backend services. Essential for distributed tracing. |

These features transform the gateway from a security proxy into a full-featured infrastructure component. Rate limiting protects backend services from traffic spikes. Caching reduces load. Logging and correlation IDs make debugging in production possible.

The security pipeline we built in Phase 2 runs before all of these. The request must be authenticated, authorized, and validated before it's rate-limited, cached, or logged. This ordering is non-negotiable.

---

### Repository Structure (Phase 2)

```
aegis_gateway/
├── gateway/
│   ├── pom.xml
│   └── src/
│       ├── main/
│       │   ├── java/com/aegis/
│       │   │   ├── AegisGatewayApplication.java
│       │   │   ├── security/
│       │   │   │   ├── SecurityConfig.java
│       │   │   │   ├── JwtService.java
│       │   │   │   ├── JwtAuthenticationManager.java
│       │   │   │   ├── JwtAuthenticationWebFilter.java
│       │   │   │   ├── ApiKeyAuthenticationManager.java
│       │   │   │   ├── ApiKeyAuthenticationToken.java
│       │   │   │   ├── ApiKeyProperties.java
│       │   │   │   └── ApiKeyAuthenticationWebFilter.java
│       │   │   └── validation/
│       │   │       ├── RequestValidationWebFilter.java
│       │   │       └── ValidationErrorHandler.java
│       │   └── resources/
│       │       └── application.yaml
│       └── test/java/com/aegis/
│           └── AegisGatewayApplicationTests.java
├── user-service/
│   └── src/main/java/com/aegis/userservice/
│       └── controllers/UserController.java
└── order-service/
    └── src/main/java/com/aegis/orderservice/
        └── controllers/OrderController.java
```

**8 new classes** in Phase 2:
- `SecurityConfig` — Filter chain + RBAC
- `JwtService` — Token parsing
- `JwtAuthenticationManager` — Claims to Authentication
- `JwtAuthenticationWebFilter` — JWT request interception
- `ApiKeyProperties` — Key configuration + lookup
- `ApiKeyAuthenticationToken` — Token wrapper
- `ApiKeyAuthenticationManager` — Key validation
- `ApiKeyAuthenticationWebFilter` — API key request interception
- `RequestValidationWebFilter` — Request format validation
- `ValidationErrorHandler` — Codec exception handling

**25 code snippets** across this article, covering:
- Spring Security WebFlux configuration
- JWT parsing and validation
- Reactive authentication managers
- WebFilter implementations
- API key configuration binding
- Request validation with body re-wrapping
- Exception handling for codec errors

The gateway is now a security-aware, validation-capable, production-ready API gateway. Backend services behind it can focus entirely on business logic, trusting that every incoming request has been authenticated, authorized, and validated.

---

*This article is part of the Aegis Gateway series. Phase 1 covered routing and gateway setup. Phase 3 will cover rate limiting, caching, logging, and correlation IDs.*
