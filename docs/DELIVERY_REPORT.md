# SeatVault Event Ticketing Platform: Delivery Report
**Prepared for:** Product Delivery & Hand-off  
**Date:** July 2026  
**Status:** Completed & Ready for Integration  

---

## 1. Executive Summary
SeatVault is an enterprise-grade, high-concurrency event ticketing platform built on a modernized Spring Boot 3 / Java 17 backend, paired with an interactive, responsive vanilla HTML/CSS/JS frontend. 

The application is structured to deliver:
- **Zero Overselling Guarantees:** Multi-layered concurrency checks prevent double-booking.
- **Premium User Experience:** B2B light-mode reskin inspired by market leading tools (Luma / District).
- **High Security & Compliance:** JWT-based rotating sessions, dynamic CORS, and brute force defenses.
- **Observability:** Operational telemetry ready for prometheus metrics collectors.

---

## 2. Feature Walkthrough & Enhancements

### 2.1 Premium UX & Luma Reskin
- **Light Theme:** Transitioned the entire visual system to a formal light tech theme using CSS custom properties.
- **Luma Actions & Metadata:** Adds an event-host metadata bar under event banners. Users can download iCalendar files (`.ics` files) directly to add events to Outlook, Google Calendar, or Apple Calendar, and copy deep-linked sharing URLs to their clipboard.
- **Interactive Seat Map:** Interactive seat picking layout featuring responsive SVG seat rendering, color-coded premium seat tiers, and instant selection feedback.

### 2.2 Security Hardening
- **JWT Rotation & Sessions:** Short-lived access tokens (15m) paired with rotating refresh tokens (7 days). Session family invalidation is automatically triggered if a compromised token is reused.
- **Brute Force Protection:** IP rate limiting restricts authentication requests on `/auth/login` to 10 attempts per minute per IP.
- **Transport Security:** Enforced strict HTTPS headers including HTTP Strict Transport Security (HSTS).

### 2.3 Real-Time Sync & Scalability
- **WebSocket Seat Synchronization:** STOMP over SockJS sends updates to client seat maps in real time when seats are held or confirmed, preventing concurrent selection conflicts.
- **Database Connection Optimization:** Tuned HikariCP connection pools to prevent bottlenecks under concurrent checkout transactions.
- **Payment Circuit Breaker:** Integrated Resilience4j circuit breakers around payment gateway interfaces to handle timeouts and outages gracefully.

### 2.4 Data Auditing & Compliance
- **Soft Delete:** Enabled soft deletes on the `Event` entity. Deletes are blocked if the event has active bookings, ensuring database integrity.
- **Administrative Auditing:** Registered JPA EntityListeners to automatically log event creations, updates, and soft deletes to a dedicated `audit_logs` table.
- **Compliance Pages:** Wired up static, custom-tailored Privacy Policy and Terms of Service documents.

---

## 3. Tech Stack & Infrastructure

- **Language/Framework:** Java 17, Spring Boot 3.3
- **Database & Migrations:** PostgreSQL 16, Flyway 10
- **Cache & WebSockets:** Redis 7 (String keys, Sorted Sets), SockJS / STOMP
- **Resilience & Telemetry:** Resilience4j, Micrometer, Prometheus, Logback JSON
- **CI/CD Pipeline:** GitHub Actions, OWASP Dependency Check, Trivy CVE Scanner

---

## 4. Operational & Deployment Guide

### Running Locally (Developer Mode)
1. Ensure JDK 17, PostgreSQL, and Redis are installed.
2. Build and run tests using the Maven Wrapper:
   ```bash
   ./mvnw clean verify
   ```
3. Run the boot application under the `dev` profile:
   ```bash
   ./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
   ```

### Deploying to Production
1. Package the binary:
   ```bash
   ./mvnw clean package -DskipTests
   ```
2. Run the application with the `prod` profile, ensuring that environment variables (`DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `REDIS_HOST`, `JWT_SECRET`, `CORS_ALLOWED_ORIGINS`) are set:
   ```bash
   java -jar target/seatvault-0.1.0.jar --spring.profiles.active=prod
   ```

### Creating Administrators
To register an admin account:
1. Register a normal account via the web UI.
2. Elevate the user's role in the database:
   ```sql
   UPDATE users SET role = 'ADMIN' WHERE email = 'admin@example.com';
   ```
3. Upon logging in, the web client will automatically show the "Create event" portal.
