# Manual Testing Guide

Start the gateway from IntelliJ, then run these commands from the terminal.

---

## Setup — Generate a Test JWT

```bash
JWT = $(python3 -c "
import hmac, hashlib, base64, json, time
s = ';oiajsd;oirlawelkdjadnfalsdfjawhero23nr23r0hn34trjp4t;nfguq34tbrgu'
h = base64.urlsafe_b64encode(json.dumps({'alg':'HS256','typ':'JWT'}).encode()).rstrip(b'=').decode()
p = base64.urlsafe_b64encode(json.dumps({'sub':'abhisek','role':'ROLE_ADMIN','iat':int(time.time()),'exp':int(time.time())+3600}).encode()).rstrip(b'=').decode()
sig = base64.urlsafe_b64encode(hmac.new(s.encode(), f'{h}.{p}'.encode(), hashlib.sha256).digest()).rstrip(b'=').decode()
print(f'{h}.{p}.{sig}')
")
```

Copy the output as `$JWT`. For ROLE_USER, change `ROLE_ADMIN` → `ROLE_USER`.

---

## Authentication

```bash
# 401 — no auth
curl -v http://localhost:8080/users/hello

# 200 — valid JWT
curl -v -H "Authorization: Bearer $JWT" http://localhost:8080/users/hello

# 200 — valid API key
curl -v -H "X-API-Key: admin-key" http://localhost:8080/users/hello

# 401 — invalid key
curl -v -H "X-API-Key: wrong" http://localhost:8080/users/hello
```

---

## RBAC (Role-Based Access Control)

```bash
# 401 — no auth on /admin
curl -v http://localhost:8080/admin/hello

# 403 — ROLE_USER on /admin
curl -v -H "X-API-Key: user-key" http://localhost:8080/admin/hello

# 200 — ROLE_ADMIN on /admin
curl -v -H "X-API-Key: admin-key" http://localhost:8080/admin/hello
```

---

## Request Validation

```bash
# 415 — wrong Content-Type
curl -v -H "X-API-Key: admin-key" -H "Content-Type: text/plain" \
  -X POST http://localhost:8080/users -d 'hello'

# 400 — malformed JSON
curl -v -H "X-API-Key: admin-key" -H "Content-Type: application/json" \
  -X POST http://localhost:8080/users -d '{bad'

# 413 — payload too large
python3 -c "print('X' * 1048577)" > /tmp/big.txt
curl -v -H "X-API-Key: admin-key" -H "Content-Type: application/json" \
  -X POST http://localhost:8080/users -d @/tmp/big.txt
```

---

## Public Endpoints

```bash
# 200 — always open
curl -v http://localhost:8080/actuator/health
```

---

## Notes

- Use `-v` to see status codes and response headers.
- Backend services (8081/8082) don't need to be running.
- A `502`/`503` after auth means authentication and authorization passed — the backend is just unreachable.
