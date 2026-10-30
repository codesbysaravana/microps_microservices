# Microps Authentication Service

A production-grade, reactive authentication microservice built from scratch in Java using **Netty**, **PostgreSQL (Neon)**, and **Redis (Upstash)** with **Lettuce**.

No Spring Boot. No frameworks. Raw Netty HTTP, hand-wired async pipelines, and atomic Redis Lua scripts.

---

## Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│  Netty NIO EventLoop (2 × CPU cores)                           │
│  ┌───────────────────────────────────────────────────────────┐  │
│  │  AuthHandler (channelRead0)                               │  │
│  │  Fast path: /health, JWT validation (nanoseconds)         │  │
│  │  Async path: .whenComplete() callbacks from futures       │  │
│  └───────────────┬───────────────────────────────────────────┘  │
│                  │ CompletableFuture.supplyAsync(...)            │
│                  ▼                                              │
│  ┌───────────────────────────────────────────────────────────┐  │
│  │  BLOCKING_EXECUTOR (8–32 threads, 1000-item queue)        │  │
│  │  JDBC queries (PostgreSQL) + Argon2id hashing (50–100ms)  │  │
│  │  AbortPolicy: rejects with 503 if queue saturates         │  │
│  └───────────────┬───────────────────────────────────────────┘  │
│                  │ .thenCompose(...)                             │
│                  ▼                                              │
│  ┌───────────────────────────────────────────────────────────┐  │
│  │  Lettuce Async (RedisAsyncCommands)                       │  │
│  │  Session CRUD via atomic Lua scripts (1 roundtrip each)   │  │
│  │  Refresh token rotation via Lua state machine             │  │
│  └───────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────┘
```

---

## Tech Stack

| Layer | Technology | Why |
|---|---|---|
| **HTTP Server** | Netty 4.1 (NIO) | Non-blocking event-driven I/O, handles thousands of connections on few threads |
| **Database** | PostgreSQL via Neon (serverless) | User storage with HikariCP connection pooling |
| **Session Store** | Redis via Upstash (TLS) | Async session management with Lettuce client, atomic Lua scripts |
| **Password Hashing** | Argon2id | Memory-hard, GPU-resistant (3 iterations, 64MB memory, parallelism 1) |
| **JWT** | JJWT (HS512) | Short-lived access tokens with embedded `sid` and `userId` claims |
| **JSON** | Gson | Serialization/deserialization with custom `Instant` type adapter |
| **Build** | Gradle | Single `gradle run` to compile and start |

---

## API Endpoints

| Method | Route | Auth | Description |
|---|---|---|---|
| `GET` | `/` | No | Service identifier |
| `GET` | `/health` | No | Health check (`{"status": "UP"}`) |
| `POST` | `/signup` | No | Register new user → returns `accessToken` + `refreshToken` |
| `POST` | `/login` | No | Authenticate → returns `accessToken` + `refreshToken` |
| `GET` | `/me` | Bearer Token | Protected: returns user profile (no password hash) |
| `POST` | `/logout` | Bearer Token | Destroys Redis session atomically via Lua |
| `POST` | `/refresh` | Body: `refreshToken` | Rotates refresh token with replay detection |

---

## Security Model

### Dual-Layer Request Verification
Every protected request (`/me`) passes through two independent verification layers:

1. **Layer 1 — In-Memory JWT Verification** (nanoseconds, zero I/O)
   - Validates cryptographic signature (HS512) and expiration.
   - Extracts `sid` and `userId` from claims.

2. **Layer 2 — Redis Active Session Check** (async, non-blocking)
   - Looks up `auth:sess:<sid>` in Redis.
   - If the session was deleted (logout), the request is **immediately rejected** even if the JWT hasn't expired.
   - Slides the 30-minute idle TTL forward on each valid request.

### Refresh Token Rotation with Replay Detection

The refresh token system implements a **grace-period state machine** to handle the classic two-tab concurrency problem:

```
              Tab A arrives (T=0)              Tab B arrives (T<10s)
ACTIVE ──────────────────────────► CONSUMED ──────────────────────────► CONSUMED
  │              Rotates to            │         Returns same cached        │
  │              (AT_2, RT_2)          │         (AT_2, RT_2) ← idempotent  │
  │                                    │                                    │
  │                                    │         Attacker arrives (T>10s)   │
  │                                    └──────────────────────────────► REVOKED
  │                                                 Full session family
  │                                                 destroyed immediately
```

- **10-second grace window**: If two browser tabs submit the same refresh token within 10 seconds, both receive the identical new token pair. No false-positive logout.
- **Replay attack**: If a stolen token is presented after the grace window expires, the **entire session family is revoked** (all tokens destroyed, user forced to re-authenticate).
- **Atomicity**: The entire rotation is executed in a single Redis Lua script (1 network roundtrip, zero race conditions).

### Overload Protection (Graceful Load Shedding)
- The `BLOCKING_EXECUTOR` thread pool uses `AbortPolicy` instead of `CallerRunsPolicy`.
- Under extreme traffic, rejected tasks return **`503 Service Unavailable`** with `Retry-After: 5` instead of hijacking Netty's I/O threads.
- Netty never freezes, even when the work queue is saturated.

### Fail-Closed Security
- If Redis is unreachable: `503 Service Unavailable` (never silently allows unauthenticated access).
- If PostgreSQL errors: `500 Internal Server Error` with sanitized message.

---

## Project Structure

```
src/main/java/com/auth/
├── Main.java                          # Netty bootstrap, port 8082
├── config/
│   └── Config.java                    # Loads DATABASE_URI from env / .env
├── domain/
│   ├── User.java                      # User entity (id, name, email, passwordHash, timestamps)
│   └── Session.java                   # Session record (sid, userId, createdAt, lastAccessedAt, absoluteExpiry)
├── dto/
│   ├── AuthResponse.java              # Response: accessToken + refreshToken + expiresIn
│   ├── LoginRequest.java              # Request: email + password
│   ├── RefreshRequest.java            # Request: refreshToken
│   └── UserProfileResponse.java       # Safe profile (excludes passwordHash)
├── handler/
│   └── AuthHandler.java               # Netty HTTP router + error handler
├── infrastructure/
│   ├── db/
│   │   ├── NeonDatabaseConnection.java # HikariCP pool with URI parsing for Neon
│   │   ├── repository/
│   │   │   └── PostgresUserRepository.java  # CRUD: createUser, findByEmail, findById
│   │   └── schema/
│   │       └── auth_schema.sql        # PostgreSQL DDL
│   └── redis/
│       ├── RedisConnection.java       # Lettuce client (TLS, .env fallback)
│       └── repository/
│           └── RedisSessionRepository.java  # Atomic Lua scripts for sessions + rotation
├── service/
│   ├── AuthService.java               # Business logic: login, signup, authenticateRequest, refreshTokens
│   ├── JwtService.java                # JWT creation + validation (HS512)
│   └── PasswordService.java           # Argon2id hash + verify
└── util/
    └── JsonUtil.java                  # Gson with Instant serializer
```

---

## Redis Key Schema

| Key Pattern | Type | TTL | Purpose |
|---|---|---|---|
| `auth:sess:<sid>` | Hash | 30 min idle | Session data (userId, createdAt, lastAccessedAt, absoluteExpiry, currentRt) |
| `auth:user:<userId>:sessions` | Set | 7 days | Index of all active session IDs for a user |
| `auth:rt:<refreshToken>` | Hash | 7 days | Active refresh token (sid, userId, status: ACTIVE) |
| `auth:rt_consumed:<oldRt>` | Hash | **10 seconds** | Grace-period storage for two-tab concurrency (newRt, newAt) |

---

## Running Locally

```bash
# Prerequisites: Java 21, Gradle 9.x

# 1. Set up .env with your Neon + Upstash credentials
DATABASE_URI=postgresql://user:pass@host/db?sslmode=require
REDIS_URI=rediss://default:token@host:6379

# 2. Compile and run
gradle run

# Server starts on http://localhost:8082
```

---

## External Services

| Service | Provider | Purpose |
|---|---|---|
| PostgreSQL | [Neon](https://neon.tech) (serverless, ap-southeast-1) | User table storage |
| Redis | [Upstash](https://upstash.com) (TLS, global) | Session store + refresh token rotation |

---

## Design Decisions

1. **Why no Spring Boot?** — Learning exercise to understand raw Netty I/O, thread pool management, and async pipeline composition without framework magic.
2. **Why Lua scripts for Redis?** — Multi-step Redis operations (HSET → EXPIRE → SADD → EXPIRE) are non-atomic when chained as individual commands. If the JVM crashes between steps, keys leak without TTLs. Lua scripts execute atomically in a single Redis roundtrip.
3. **Why `AbortPolicy` over `CallerRunsPolicy`?** — `CallerRunsPolicy` hijacks the calling thread when the queue is full. In Netty, that calling thread is the I/O EventLoop, which freezes TCP I/O for all connections. `AbortPolicy` rejects immediately with a clean 503.
4. **Why `sid.randomSecret` format for refresh tokens?** — On replay detection, we extract the `sid` prefix to identify and destroy the compromised session family without a database lookup.
5. **Why `preferIPv4Stack=true`?** — JioFiber routers return IPv6 addresses that hang during TCP handshake. Forcing IPv4 resolves connection stalls instantly.
