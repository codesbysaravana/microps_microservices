# Architecture Plan: Asynchronous Redis Sessions

## 1. Overview & Architecture

```
  [ Client (HTTP) ]
         │  POST /login, POST /logout, POST /revoke-all
         ▼
  [ AuthHandler (Netty) ]  <--- EventLoop stays non-blocking
         │
         ▼
  [ AuthService ]
    ├── PostgresUserRepository (Neon DB: verify password, find user)
    └── RedisSessionRepository (Lettuce: async session storage & TTL)
            │
            ▼
       [ Redis ]
         ├── HASH: auth:sess:<sid>              (TTL = idle expiry)
         └── SET:  auth:user:<userId>:sessions  (active sessions per user)
```

This plan outlines the implementation of Redis-backed session management using the **Lettuce** library (`io.lettuce:lettuce-core`) on top of the existing Netty authentication microservice.

---

## 2. Redis Key & Data Structure Design

Following the architecture defined in `auth_schema.sql`:

### A. Individual Session: `auth:sess:<sid>`
* **Type:** `HASH`
* **Key Format:** `auth:sess:<sid>` (where `sid` is a cryptographically secure UUID string)
* **Fields:**
  * `userId`: UUID string
  * `createdAt`: timestamp (epoch millis)
  * `lastAccessedAt`: timestamp (epoch millis)
  * `absoluteExpiry`: timestamp (epoch millis, e.g., 24h / 7d)
* **TTL Policy (Dual Expiry):**
  * **Idle TTL:** 30 minutes (`1800s`). Reset on every access/request via `EXPIRE`.
  * **Absolute Max TTL:** 24 hours (`86400s`). Capped against `absoluteExpiry` so sessions cannot live indefinitely via continuous sliding renewal.

### B. User Active Sessions Index: `auth:user:<userId>:sessions`
* **Type:** `SET` of active session IDs (`sid`)
* **Key Format:** `auth:user:<userId>:sessions`
* **Purpose:** Enables instant **"Revoke all devices / sessions"** when a user changes their password or requests global logout.
* **TTL:** Set to maximum absolute TTL (`86400s`) so orphaned sets automatically expire.

---

## 3. Asynchronous & Non-Blocking Design (Netty EventLoop Rule)

> [!IMPORTANT]
> **Zero EventLoop Blocking:** Lettuce's synchronous API (`connection.sync()`) blocks Netty's I/O worker threads.
> All Redis operations will use Lettuce's **`RedisAsyncCommands`** which return `RedisFuture<T>` (compatible with Java's `CompletableFuture<T>`).

* Handlers dispatch async commands and register completion listeners (`.whenComplete(...)`).
* Netty's worker threads remain free to serve concurrent requests with near-zero latency overhead.

---

## 4. Components & Implementation Steps

### Step 1: Redis Configuration & Connection (`RedisConnection.java`)
* **Package:** `com.auth.infrastructure.redis`
* Reads `REDIS_URI` from `Config.java` / environment (defaults to `redis://localhost:6379`).
* Manages a thread-safe singleton `RedisClient` and `StatefulRedisConnection<String, String>`.
* Sets a short connection/command timeout (e.g. 2s) to fail fast on network partitions.

### Step 2: Session Domain Model (`Session.java`)
* **Package:** `com.auth.domain`
* Represents a domain session with properties: `sid`, `userId`, `createdAt`, `lastAccessedAt`, `absoluteExpiry`.
* Encapsulates domain logic like `isExpired()` and calculating remaining idle duration.

### Step 3: Async Session Repository (`RedisSessionRepository.java`)
* **Package:** `com.auth.infrastructure.redis`
* Exposes asynchronous methods returning `CompletableFuture<...>`:
  1. `createSession(UUID userId, Duration idleTtl, Duration maxTtl)`:
     * Generates secure `sid`.
     * Atomically executes `HMSET auth:sess:<sid>`, sets TTL via `EXPIRE`, and adds to set `SADD auth:user:<userId>:sessions`.
  2. `getSession(String sid)`:
     * Fetches session hash `HGETALL auth:sess:<sid>`.
     * Updates `lastAccessedAt` and resets idle TTL if still valid.
  3. `deleteSession(String sid)`:
     * Deletes `auth:sess:<sid>`.
     * Removes `sid` from `auth:user:<userId>:sessions`.
  4. `revokeAllUserSessions(UUID userId)`:
     * Reads all `sid`s from `auth:user:<userId>:sessions`.
     * Deletes all corresponding `auth:sess:<sid>` hashes.
     * Deletes `auth:user:<userId>:sessions`.

### Step 4: Integrate with `AuthService` & JWT
* Inject session ID (`sid`) into JWT claims:
  ```json
  {
    "sub": "priyan@example.com",
    "userId": "32bd1e9e-00fb-4f3a-ad91-9e189f9e4fc2",
    "sid": "7b0d1e89-1234-4a56-b789-0123456789ab"
  }
  ```
* **Login flow:**
  1. Authenticate user against PostgreSQL.
  2. Create session in Redis asynchronously.
  3. Issue JWT containing `sid`.
* **Logout flow:**
  1. Extract `sid` from request Bearer token / header.
  2. Delete session from Redis.
* **Revoke All flow:**
  1. Revoke all active sessions for `userId` in Redis on password update or security alert.

### Step 5: Update Netty `AuthHandler` Routes
* `POST /login` $\rightarrow$ Authenticates, creates Redis session, returns JWT with `sid`.
* `POST /logout` $\rightarrow$ Extracts `sid`, destroys Redis session, returns `200 OK`.
* `POST /revoke-all` $\rightarrow$ Invalidates all user sessions, returns `200 OK`.

---

## 5. Fail-Closed Policy (When Redis is Down)

* **Strategy:** **Fail Closed**.
  * Authentication is security-critical. If Redis cannot be reached or times out, **deny the request**.
  * Never issue unmonitored or un-revocable tokens when session tracking is degraded.
* **Error Response:**
  * Catch Lettuce connection errors / timeout exceptions.
  * Return HTTP `503 Service Unavailable`:
    ```json
    { "error": "Session service temporarily unavailable" }
    ```

---

## 6. Verification & Chaos Testing Checklist

1. **Session Creation Verification:**
   * Run `POST /login`.
   * Verify in Redis CLI:
     ```bash
     redis-cli HGETALL auth:sess:<sid>
     redis-cli TTL auth:sess:<sid>
     redis-cli SMEMBERS auth:user:<userId>:sessions
     ```

2. **Logout Verification:**
   * Run `POST /logout` with `Authorization: Bearer <token>`.
   * Confirm `auth:sess:<sid>` is deleted and removed from the user set.

3. **Revoke-All Verification:**
   * Log in multiple times to simulate multiple active devices.
   * Call `POST /revoke-all`.
   * Verify all associated `auth:sess:*` keys and the user's sessions set are wiped.

4. **Chaos / Fail-Closed Test:**
   * Stop Redis (`redis-cli shutdown` or stop container).
   * Send `POST /login`.
   * Confirm the Netty server remains healthy, doesn't block worker threads, and immediately responds with `503 Service Unavailable`.
