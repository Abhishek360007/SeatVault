# SeatVault - Enterprise Event Ticket Booking Platform

[![Project Status](https://img.shields.io/badge/Status-Production--Hardened%20Core-brightgreen.svg)](#project-status)
[![Java Version](https://img.shields.io/badge/Java-17-orange.svg)](https://openjdk.org/projects/jdk/17/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.3-green.svg)](https://spring.io/projects/spring-boot)
[![Code Coverage](https://img.shields.io/badge/Coverage-40%25%20Line%20Enforced-blue.svg)](#code-coverage)

SeatVault is a high-performance, concurrency-safe, enterprise event ticketing platform built with Spring Boot 3, PostgreSQL, Redis, and Vanilla JS. It is designed to handle high-demand ticket drops, zero-overselling guarantees, payment idempotency, virtual waiting rooms, PDF e-ticket generation, gate check-in scanning, and production observability.



## 🚀 Project Status

> [!NOTE]
> **Status: Production-hardened core, one integration deliberately deferred**
>
> The booking/concurrency core, security controls, waiting room, e-tickets, and CI
> quality gates below are implemented and covered by tests. The payment gateway is
> still a **simulated** provider (`SimulatedPaymentGateway`) — wiring a real
> Stripe/Razorpay integration behind `PaymentWebhookController` is a tracked,
> explicit `TODO(decision)` pending a provider choice, not a hidden gap.

- **Concurrency & Locking**: Pessimistic/Optimistic row locking with 0% overselling guarantee under extreme concurrent traffic.
- **High-Demand Virtual Waiting Room**: Redis Sorted Set FIFO queue with periodic admission batching and short-lived, re-checked (not consumed) admission tokens.
- **E-Ticket & Gate Check-in**: OpenPDF document rendering, ZXing QR code generation, and HMAC-SHA256 digital signature verification for gate check-in scanners. Generated on demand at download time (not eagerly at payment confirmation).
- **JWT Security & Token Revocation**: 15-min access tokens, 7-day Redis refresh tokens, token revocation blocklist, and a startup guard that halts boot in `prod` if secrets are left at dev defaults.
- **Rate Limiting & Safety**: IP-based rate limiting on registration, login, event browsing, and waiting-room join; per-user rate limiting on seat holds; hard page-size capping on all pageable queries; a trusted-proxy allowlist so `X-Forwarded-For` can't be spoofed to dodge it.
- **Production Observability**: Prometheus metrics (`/actuator/prometheus`), MDC `X-Request-Id` request tracing, and profile-based JSON logging.
- **CI/CD Quality Gates**: a 40% line-coverage floor enforced via JaCoCo in Maven `verify` (set from measured unit-test coverage, meant to be raised as tests grow — see the comment in `pom.xml`), OWASP Dependency-Check failing the build on CVSS ≥ 7.0, and a Trivy image scan failing on HIGH/CRITICAL CVEs.

---

## 📐 Architecture Overview

```text
                      ┌──────────────────────────────────────────────┐
                      │             Single-Page Application          │
                      │         (HTML5 / CSS3 / Vanilla JS)          │
                      └──────────────────────┬───────────────────────┘
                                             │ REST / JSON
                                             ▼
                      ┌──────────────────────────────────────────────┐
                      │          Spring Boot 3.3 Gateway             │
                      │ ┌──────────────────────────────────────────┐ │
                      │ │ RequestIdFilter (MDC X-Request-Id)       │ │
                      │ │ RateLimitInterceptor (Redis IP Limiter)  │ │
                      │ │ JwtAuthenticationFilter (Token & Revoc)  │ │
                      │ └──────────────────────────────────────────┘ │
                      └──────┬───────────────────────────────┬───────┘
                             │                               │
                             ▼                               ▼
              ┌──────────────────────────────┐┌──────────────────────────────┐
              │     PostgreSQL Database      ││         Redis Cache          │
              │ ┌──────────────────────────┐ ││ ┌──────────────────────────┐ │
              │ │ Flyway Schema Migrations │ ││ │ FIFO Waiting Room ZSET   │ │
              │ │ Events, Seats, Bookings  │ ││ │ Refresh Tokens & Blacklist│ │
              │ │ Payments, Users (BCrypt) │ ││ │ IP Rate Limit Counters   │ │
              │ └──────────────────────────┘ ││ └──────────────────────────┘ │
              └──────────────────────────────┘└──────────────────────────────┘
```

---

## 🌟 Key Features

### 1. High-Demand Virtual Waiting Room (FIFO Queue)
- Flag an event `highDemand: true` on create/update (`EventRequest.highDemand`); non-flagged events skip the queue entirely (join/status report immediately admitted).
- `POST /api/v1/events/{id}/queue/join` adds the caller to a Redis Sorted Set FIFO line (`ZADD NX`, so a repeat join doesn't lose their place).
- `GET /api/v1/events/{id}/queue/status` reports position, or the admission token once admitted.
- `QueueAdmissionScheduler` admits the next batch (`app.waiting-room.admission-batch-size`, default 50) every `admission-interval-ms` (default 10s).
- `POST /api/v1/bookings/hold` requires a valid `admissionToken` for high-demand events; the token is TTL-bound (default 120s) and re-checked (not consumed) on every attempt, so a lost seat race doesn't force re-queueing.
- API-only for now — the SPA doesn't yet have a waiting-room UI.

### 2. E-Ticket PDF & Gate Check-in Verification
- `GET /api/v1/bookings/{id}/ticket` (owner or admin) renders a PDF on demand — OpenPDF, one page per seat — via `TicketService`.
- Each page embeds a ZXing QR code encoding an HMAC-SHA256 signed token: `<base64url(bookingId|seatId|eventId|issuedAt)>.<base64url(signature)>`.
- `POST /api/v1/tickets/verify` (accessible by `ADMIN` / `SCANNER` roles) verifies the signature, checks the booking is `CONFIRMED` and the seat is `BOOKED`, and records the check-in. A tampered signature is rejected with 400; a re-scan of an already-checked-in seat returns `alreadyCheckedIn: true` instead of erroring (a scanner can safely re-verify).
- Check-ins are durable (`ticket_checkins` table, unique per seat) so a duplicate scan can't be recorded twice even under concurrent scanners.
- There is currently no API to provision `SCANNER` accounts — promote a user's role directly in the database until an admin user-management endpoint exists.

### 3. Concurrency & Idempotency Guarantees
- **Zero Overselling**: Row locks (`SELECT FOR UPDATE` or optimistic retry) guarantee exactly one user holds a seat.
- **Idempotent Payment**: Unique `(booking_id, idempotency_key)` constraint prevents double-charging.
- **Hold Expiry**: Scheduled `HoldReleaseService` releases abandoned holds after 5 minutes.

### 4. JWT Authentication & Token Management
- Short-lived JWT access tokens (15 mins) and long-lived opaque refresh tokens (7 days stored in Redis).
- `POST /api/v1/auth/refresh` exchanges refresh tokens for new access tokens.
- `POST /api/v1/auth/logout` revokes refresh tokens and blocklists the active access token JTI in Redis.

### 5. Production Hardening & Safety
- **Startup Guard**: `ProductionSecurityValidator` halts boot in the `prod` profile if `ADMIN_PASSWORD` is `admin12345`, or `JWT_SECRET`/`TICKET_HMAC_SECRET` are missing or still contain `change-me`.
- **IP Rate Limiting**: Redis atomic Lua script limits registration, login, event browsing, and waiting-room join; `ClientIpResolver` only trusts `X-Forwarded-For` from an operator-configured `app.security.trusted-proxies` allowlist (empty by default — the header is ignored on a directly-exposed instance).
- **Query Capping**: Enforces a hard limit (`spring.data.web.pageable.max-page-size: 100`) on all Pageable endpoints.
- **Observability**: Prometheus metrics at `/actuator/prometheus`, MDC `X-Request-Id` tracing, custom metrics (`seat.hold.count`, `payment.count`).

---

## 🛠️ Tech Stack

| Layer | Technology |
| --- | --- |
| **Language & Runtime** | Java 17, Spring Boot 3.3.1 |
| **Database** | PostgreSQL 16, Flyway 10 |
| **Caching & Queue** | Redis 7 (Sorted Sets, String TTLs, Atomic Lua) |
| **Security** | Spring Security 6, JJWT 0.12, BCrypt |
| **PDF & QR Code** | OpenPDF 1.3, ZXing 3.5 |
| **Metrics & Tracing** | Micrometer, Prometheus, Logstash Logback JSON |
| **Testing** | JUnit 5, Mockito, Testcontainers, JaCoCo |
| **CI/CD** | GitHub Actions, OWASP Dependency-Check, Trivy Security Scanner |

---

## 📡 Key REST Endpoints

### Authentication & Tokens
| Method | Endpoint | Access | Description |
| --- | --- | --- | --- |
| `POST` | `/api/v1/auth/register` | Public | Register new user account |
| `POST` | `/api/v1/auth/login` | Public | Authenticate & receive access + refresh token |
| `POST` | `/api/v1/auth/refresh` | Public | Refresh expired access token |
| `POST` | `/api/v1/auth/logout` | Authenticated | Revoke refresh token & blocklist access token |

### Events & Waiting Room
| Method | Endpoint | Access | Description |
| --- | --- | --- | --- |
| `GET` | `/api/v1/events` | Public (IP rate limited) | List/search upcoming events |
| `GET` | `/api/v1/events/{id}` | Public (IP rate limited) | Event details |
| `GET` | `/api/v1/events/{id}/seats` | Public (IP rate limited) | Live seat map status |
| `POST` | `/api/v1/events/{id}/queue/join` | Authenticated (IP rate limited) | Join the virtual waiting room (no-op if event isn't high-demand) |
| `GET` | `/api/v1/events/{id}/queue/status` | Authenticated | Poll queue position / admission token |
| `POST` | `/api/v1/events` | Admin | Create event with seat grid / tiers / `highDemand` flag |
| `PUT` | `/api/v1/events/{id}` | Admin | Update event metadata (seating immutable) |
| `DELETE` | `/api/v1/events/{id}` | Admin | Delete an event (fails if it has bookings) |

### Bookings & E-Tickets
| Method | Endpoint | Access | Description |
| --- | --- | --- | --- |
| `POST` | `/api/v1/bookings/hold` | Authenticated (rate limited) | Hold seats (requires `admissionToken` if the event is high-demand) |
| `POST` | `/api/v1/bookings/{id}/pay` | Authenticated | Idempotent payment approval & confirmation (simulated gateway) |
| `POST` | `/api/v1/bookings/{id}/cancel` | Authenticated | Cancel a pending booking, release its held seats |
| `GET` | `/api/v1/bookings/{id}/ticket` | Owner/Admin | Download the PDF e-ticket (confirmed bookings only) |
| `POST` | `/api/v1/tickets/verify` | Admin/Scanner | Verify a scanned QR token and record gate check-in |

### Observability
| Method | Endpoint | Access | Description |
| --- | --- | --- | --- |
| `GET` | `/actuator/prometheus` | Public/Ops | Micrometer Prometheus metrics |
| `GET` | `/actuator/health` | Public | Spring Boot health check |

---

## 🧪 Testing & Code Quality

### Running Unit Tests
```bash
./mvnw clean test
```

### Running Full Integration Tests (Testcontainers Postgres + Redis)
```bash
./mvnw clean verify
```
*Note: JaCoCo enforces a minimum **40% line coverage** floor on the build during `mvn verify` (measured from unit tests alone; integration tests add more in CI). This is a floor, not a target — raise it as coverage grows.*

---

## 👤 Author
**Abhishek Raj** — [GitHub @Abhishek360007](https://github.com/Abhishek360007) · abhiraj360007@gmail.com

---

## 📄 License
This project is licensed under the MIT License — see [LICENSE](LICENSE).
