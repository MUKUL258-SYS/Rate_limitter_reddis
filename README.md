# Distributed Rate Limiter — Spring Boot + Bucket4j + Redis

A production-grade distributed rate limiting service demonstrating multiple strategies
backed by Redis for shared state across horizontally-scaled instances.

## Architecture

```
Client Request
     │
     ▼
Spring MVC ──────────────────────────────────────────────────────────────────
     │
     ├── RateLimitAspect (@Around)          ← AOP intercepts @RateLimit methods
     │       │                                resolves client key from IP / X-User-Id
     │       ▼
     │   RateLimiterService
     │       │
     │       ├── ProxyManager<String>       ← LettuceBasedProxyManager (Bucket4j)
     │       │       │
     │       │       ▼
     │       │   Redis (Bucket State)       ← atomic CAS operations, shared across instances
     │       │
     │       └── MeterRegistry              ← Micrometer counters per strategy
     │               │
     │               ▼
     │           Prometheus → Grafana
     │
     ▼
HTTP Response
  - 200 OK + X-RateLimit-Remaining header  (allowed)
  - 429 Too Many Requests + Retry-After    (blocked)
```

## Rate Limiting Strategies

| Strategy | Limit | Algorithm | Use Case |
|----------|-------|-----------|----------|
| STANDARD | 60 req/min | Token bucket | General API endpoints |
| BURST | 60/min + 20 burst | Dual-limit token bucket | Public search/browse |
| STRICT | 10 req/min | Fixed window | Auth, OTP, payments |
| SLIDING | 500 req/hr | Greedy refill | Analytics ingestion |
| DAILY | 10,000 req/day | Fixed daily quota | External API keys |

## Key Design Decisions

**Why Redis-backed (not in-memory)?**
In-memory buckets only work for single-instance deployments. With Redis, all pods share
the same bucket state — one user can't bypass limits by hitting different instances.

**Why Bucket4j's `ConsumptionProbe` over `tryConsume()`?**
`ConsumptionProbe` returns remaining tokens AND nanosToWaitForRefill in one atomic call,
enabling proper `Retry-After` header values without a second Redis round-trip.

**Why AOP for annotation-driven limiting?**
Zero boilerplate in business logic. Adding `@RateLimit` to a method is the only change
needed. The aspect handles key resolution, header injection, and 429 short-circuiting.

**Why `X-Forwarded-For` parsing in key resolution?**
Behind nginx or an AWS ALB, `request.getRemoteAddr()` returns the load balancer IP.
The aspect correctly parses the first IP from the forwarded chain — the real client.

## Running Locally

```bash
# Start Redis + app
docker-compose up

# Test standard endpoint
curl -v http://localhost:8080/api/v1/rate-limiter/standard

# Test per-user limiting
curl -H "X-User-Id: user123" http://localhost:8080/api/v1/rate-limiter/user/profile

# Test API key quota
curl -H "X-Api-Key: my-api-key" http://localhost:8080/api/v1/rate-limiter/api-quota

# View Prometheus metrics
curl http://localhost:8080/actuator/prometheus | grep rate_limiter

# Reset a bucket (admin)
curl -X DELETE http://localhost:8080/api/v1/rate-limiter/admin/reset/strict/192.168.1.1
```

## Running Tests

```bash
# Integration tests (Testcontainers spins up Redis automatically)
./mvnw test
```

## Resume Bullet Points

- Built a **distributed rate limiting service** in Spring Boot using **Bucket4j + Redis (Lettuce)**,
  enforcing per-user and per-IP quotas across horizontally-scaled instances via atomic CAS operations

- Implemented **5 rate limiting strategies** (token bucket, burst, strict, sliding window, daily quota)
  with a **custom AOP aspect** that intercepts `@RateLimit`-annotated endpoints — zero boilerplate in business logic

- Integrated **Micrometer + Prometheus** to expose `rate_limiter.requests.allowed/blocked` counters
  per strategy, enabling real-time alerting on traffic anomalies

- Added **Retry-After** response headers using `ConsumptionProbe.getNanosToWaitForRefill()`,
  providing clients with accurate backoff intervals in a single atomic Redis call

- Wrote **Testcontainers-based integration tests** against a real Redis 7.2 container,
  verifying per-user bucket isolation, limit exhaustion, and reset behavior

## Tech Stack

- Java 21, Spring Boot 3.2
- Bucket4j 8.7 (token bucket algorithm)
- Redis 7.2 via Lettuce (reactive, non-blocking client)
- Micrometer + Prometheus (metrics)
- Testcontainers (integration testing)
- Docker + Docker Compose
