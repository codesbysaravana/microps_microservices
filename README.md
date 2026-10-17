# MicrOps — Microservices Backend

The Java rebuild of [MicrOps](https://microps.in), my self-hosted deployment
platform. This repo holds the backend as independent services: auth runs on
plain Netty (no framework), everything else runs on Spring Boot.

Status: early development. Auth service skeleton is up, the auth database
schema is finalized, and the Spring side is scaffolded.

## Repository layout

```
authentication/   Netty auth service (identity, login, tokens)
microservices/    Spring Boot side (tenant, projects, deployments, billing)
```

## authentication/ — Netty auth service

Plain Java + Netty 4.1 HTTP server, no Spring. Layered structure:
handler → service → config, with DTOs and a JSON util.

- HTTP server on port 8082 (`/health`, `/login`, `/logout` — login and
  logout are stubs for now)
- Self-registers with Spring Boot Admin on startup (http://localhost:8081)
- `JwtService` — JWT creation, parsing, validation (JJWT). Secret and
  expiration are constructor-injected so they're configurable per environment
- `PasswordService` — Argon2id password hashing (3 iterations, 64 MB memory)
- `DatabaseConfig` — HikariCP connection pool to Postgres, configured through
  `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` environment variables
- Gson for JSON, Lettuce on the classpath for the upcoming Redis session layer
- `auth_schema.sql` — the finalized auth database (see below)

### Auth database (auth_schema.sql)

Auth owns identity only, three tables:

- `users` — one row per human, case-insensitive unique email among live
  users, soft delete, `password_hash` nullable for OAuth-only accounts
- `user_identities` — OAuth links (github/google), one row per
  (provider, provider_user_id)
- `user_tokens` — one-time email verification and password reset tokens,
  hash-only storage

Deliberate boundaries:

- No sessions or refresh tokens in this database. Sessions live in Redis
  (`auth:sess:<sid>` hash, `auth:user:<sub>:sessions` set) so logout and
  rotation are fast and revocable.
- No roles, permissions, or organizations here. Authorization belongs to the
  tenant (Spring) side.
- No foreign keys out of this database. Other services store `user_id` as a
  plain UUID and validate through the auth service.

## microservices/ — Spring Boot side

Spring Boot (Java 17) on port 8081 with Spring Boot Admin Server enabled —
this is where services register and get monitored. Currently scaffolded with
a `/hello` endpoint while the real modules get built.

- `schema.sql` — the full 23-table MicrOps schema (organizations, roles,
  billing, projects, environments, services, builds, deployments, audit log).
  Being split so each service owns its own tables.
- `schema_coolify.sql` — Coolify's schema, kept as reference material.

## Running locally

Requires Java 17+ and Postgres.

Auth service (needs Gradle installed):

```bash
cd authentication
DB_URL=jdbc:postgresql://localhost:5432/microps_auth \
DB_USERNAME=postgres DB_PASSWORD=password \
gradle run
```

Spring side (wrapper included):

```bash
cd microservices
./gradlew bootRun
```

Then: auth health check at http://localhost:8082/health, Boot Admin UI at
http://localhost:8081.
