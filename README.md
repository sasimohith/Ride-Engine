# Ride-Engine

A production-grade ride-sharing backend (like Uber/Rapido) built as a **modular monolith** using modern Java technologies. Features real-time driver matching, event-driven payments, surge pricing, live notifications, and comprehensive monitoring.

## Architecture

```
┌─────────────────────────────────────────────────────────┐
│                 Spring Boot Application                  │
│                                                         │
│  ┌────────┐  ┌────────┐  ┌────────┐  ┌──────────────┐  │
│  │  Auth  │  │  Ride  │  │ Driver │  │   Admin      │  │
│  │ Module │  │ Module │  │ Module │  │   Module     │  │
│  └────────┘  └────────┘  └────────┘  └──────────────┘  │
│                                                         │
│  ┌────────┐  ┌────────────────┐  ┌───────────────────┐  │
│  │Pricing │  │  Notification  │  │    Payment        │  │
│  │ Module │  │(WebSocket+Kafka│  │  Module (Kafka)   │  │
│  └────────┘  └────────────────┘  └───────────────────┘  │
│                                                         │
│  ┌────────┐  ┌────────────────────────────────────────┐  │
│  │ Rating │  │       Shared Infrastructure            │  │
│  │ Module │  │   Kafka | Redis | Async | Exceptions   │  │
│  └────────┘  └────────────────────────────────────────┘  │
└────────────────────┬────────────┬───────────────────────┘
                     │            │
         ┌───────────┼────────────┼───────────┐
         ▼           ▼            ▼           ▼
    PostgreSQL     Redis        Kafka    Prometheus
    (Data Store)   (GEO +      (Event    + Grafana
                    Cache)     Stream)   (Monitoring)
```

## Tech Stack

| Technology | Purpose |
|------------|---------|
| **Java 17** | Core language |
| **Spring Boot 3** | Application framework |
| **Spring Security + JWT** | Stateless authentication & role-based authorization |
| **Spring Data JPA + Hibernate** | ORM for database operations |
| **PostgreSQL** | Primary relational database |
| **Redis** | Driver location tracking (GEO), fare rule caching, surge pricing |
| **Apache Kafka** | Event-driven communication between modules |
| **WebSocket (STOMP)** | Real-time push notifications to riders/drivers |
| **CompletableFuture + @Async** | Non-blocking asynchronous driver matching |
| **Flyway** | Versioned database migrations (V1–V9) |
| **Prometheus + Grafana** | Metrics collection and monitoring dashboards |
| **Docker + Docker Compose** | Containerized infrastructure |
| **Maven** | Build and dependency management |
| **JUnit 5 + Mockito** | Testing (164 tests) |

## Modules

### 1. Auth Module
- User registration (Rider / Driver) with BCrypt password hashing
- JWT-based stateless authentication (access + refresh tokens)
- Admin self-registration blocked (seeded via migration)
- Spring Security filter chain with role-based endpoint protection

### 2. Driver Module
- Vehicle registration with duplicate plate number prevention
- Document upload (driving license, insurance, etc.)
- Real-time location tracking using **Redis GEO** (`GEOADD`, `GEOSEARCH`)
- Online/Offline/Busy availability management

### 3. Admin Module
- View pending driver registrations
- Approve/Reject drivers (publishes Kafka event)
- Suspend/Reactivate user accounts
- Protected by `ROLE_ADMIN` authorization

### 4. Pricing Module
- Fare estimation using **Haversine formula** (distance calculation)
- Per-vehicle-type fare rules (AUTO, BIKE, SEDAN, SUV)
- **Redis cache-aside pattern** for fare rules
- Dynamic **surge pricing** (recalculated every 30 seconds via `@Scheduled`)
- Surge based on pending ride requests vs online drivers ratio

### 5. Ride Module
- Complete ride lifecycle: `REQUESTED → ACCEPTED → IN_PROGRESS → COMPLETED / CANCELLED`
- **Asynchronous driver matching** using `CompletableFuture` on custom thread pool
- Nearest driver discovery via Redis GEO radius search (5km)
- Retry logic with backoff for matching (3 attempts)
- Duplicate active ride prevention
- Kafka events published at each state transition

### 6. Notification Module
- Real-time WebSocket (STOMP) push notifications
- Kafka consumers for ride events (`ride-accepted`, `ride-completed`, `ride-cancelled`)
- Kafka consumer for driver events (`driver-approved`)
- Async notification delivery via `@Async`

### 7. Payment Module
- **Event-driven** payment creation (Kafka consumer on `ride-completed`)
- Idempotent design — duplicate events safely skipped
- Payment methods: CASH, UPI, WALLET
- Payment lifecycle: `PENDING → COMPLETED → REFUNDED`
- Rider payment history and driver earnings tracking
- Admin refund capability

### 8. Rating Module
- Bidirectional ratings (rider rates driver, driver rates rider)
- 1–5 star scale with optional comments
- One rating per rater per ride (unique constraint)
- Only COMPLETED rides can be rated
- **Denormalized average rating** on User entity for fast reads
- `@Transactional` rating + average update

### 9. Shared Infrastructure
- Kafka producer/consumer configuration with error handling
- Redis configuration with JSON serialization
- Custom `ThreadPoolTaskExecutor` for async operations
- Global exception handler (`@ControllerAdvice`)
- Standardized API response DTOs (`ApiResponse<T>`, `ErrorResponse`)
- Custom exceptions (`ResourceNotFoundException`, `BadRequestException`, etc.)

### 10. Monitoring + Deployment
- Multi-stage Dockerfile (build + run, non-root user)
- Docker Compose with PostgreSQL, Redis, Kafka, Zookeeper, Prometheus, Grafana
- Prometheus scraping Spring Boot Actuator metrics
- Pre-configured Grafana dashboard (HTTP rate, JVM memory, response time, error rate)
- Environment-based config (`application-dev.yml`, `application-prod.yml`)

## Project Structure

```
ride-sharing-platform/
├── src/main/java/com/ridesharing/
│   ├── RideSharingApplication.java          # Entry point (@EnableAsync, @EnableScheduling)
│   ├── auth/
│   │   ├── controller/AuthController.java
│   │   ├── service/AuthService.java, JwtService.java
│   │   ├── model/User.java
│   │   ├── repository/UserRepository.java
│   │   ├── filter/JwtAuthenticationFilter.java
│   │   ├── config/SecurityConfig.java
│   │   └── dto/RegisterRequest, LoginRequest, AuthResponse
│   ├── driver/
│   │   ├── controller/DriverController.java
│   │   ├── service/DriverService.java, DriverLocationService.java
│   │   ├── model/Vehicle.java, DriverDocument.java
│   │   ├── repository/VehicleRepository, DriverDocumentRepository
│   │   └── dto/VehicleRequest, DriverDocumentRequest, DriverProfileResponse
│   ├── admin/
│   │   ├── controller/AdminController.java
│   │   ├── service/AdminService.java
│   │   └── dto/AdminDriverResponse.java
│   ├── pricing/
│   │   ├── controller/PricingController.java
│   │   ├── service/PricingService.java
│   │   ├── scheduler/SurgePricingScheduler.java
│   │   ├── model/FareRule.java
│   │   ├── repository/FareRuleRepository.java
│   │   └── dto/FareEstimateRequest, FareEstimateResponse, FareRuleResponse
│   ├── ride/
│   │   ├── controller/RideController.java
│   │   ├── service/RideService.java, RideMatchingService.java
│   │   ├── model/Ride.java
│   │   ├── repository/RideRepository.java
│   │   ├── events/RideEvent.java
│   │   └── dto/RideRequestDto, RideResponseDto
│   ├── notification/
│   │   ├── service/NotificationService.java
│   │   ├── consumer/RideEventConsumer.java, DriverEventConsumer.java
│   │   ├── websocket/WebSocketConfig.java
│   │   └── dto/NotificationMessage.java
│   ├── payment/
│   │   ├── controller/PaymentController.java
│   │   ├── service/PaymentService.java
│   │   ├── consumer/PaymentEventConsumer.java
│   │   ├── model/Payment.java
│   │   ├── repository/PaymentRepository.java
│   │   └── dto/PayRequest, PaymentResponse
│   ├── rating/
│   │   ├── controller/RatingController.java
│   │   ├── service/RatingService.java
│   │   ├── model/Rating.java
│   │   ├── repository/RatingRepository.java
│   │   └── dto/RatingRequest, RatingResponse
│   └── shared/
│       ├── kafka/KafkaTopics, KafkaProducerConfig, KafkaConsumerConfig
│       ├── redis/RedisConfig.java
│       ├── async/AsyncConfig.java
│       ├── exceptions/GlobalExceptionHandler + custom exceptions
│       └── dto/ApiResponse, ErrorResponse, LocationDto
├── src/main/resources/
│   ├── application.yml
│   ├── application-dev.yml
│   ├── application-prod.yml
│   └── db/migration/
│       ├── V1__create_users_table.sql
│       ├── V2__create_vehicles_table.sql
│       ├── V3__create_driver_documents_table.sql
│       ├── V4__add_driver_fields_to_users.sql
│       ├── V5__create_fare_rules_table.sql
│       ├── V6__create_rides_table.sql
│       ├── V7__seed_admin_user.sql
│       ├── V8__create_payments_table.sql
│       └── V9__create_ratings_table.sql
├── src/test/java/com/ridesharing/       # 164 tests
│   ├── e2e/RideSharingE2ETest.java      # 35 E2E integration tests
│   ├── auth/, driver/, pricing/, admin/
│   ├── ride/, notification/
│   ├── payment/, rating/
│   └── shared/
├── docker-compose.yml                    # PostgreSQL, Redis, Kafka, Prometheus, Grafana
├── Dockerfile                            # Multi-stage build
├── monitoring/
│   ├── prometheus.yml
│   └── grafana/provisioning/
│       ├── datasources/prometheus.yml
│       └── dashboards/ride-sharing-dashboard.json
├── scripts/e2e-test.ps1                  # PowerShell E2E test script
└── pom.xml
```

## API Endpoints

### Authentication
| Method | Endpoint | Access | Description |
|--------|----------|--------|-------------|
| POST | `/api/auth/register` | Public | Register rider/driver |
| POST | `/api/auth/login` | Public | Login, get JWT tokens |

### Driver
| Method | Endpoint | Access | Description |
|--------|----------|--------|-------------|
| POST | `/api/driver/vehicle` | Driver | Add vehicle |
| POST | `/api/driver/documents` | Driver | Upload document |
| PUT | `/api/driver/availability` | Driver | Go online/offline |
| PUT | `/api/driver/location` | Driver | Update GPS location |
| GET | `/api/driver/profile` | Driver | View profile |

### Admin
| Method | Endpoint | Access | Description |
|--------|----------|--------|-------------|
| GET | `/api/admin/drivers/pending` | Admin | List pending drivers |
| GET | `/api/admin/drivers` | Admin | List all drivers |
| GET | `/api/admin/drivers/{id}` | Admin | Driver details |
| PUT | `/api/admin/drivers/{id}/approve` | Admin | Approve driver |
| PUT | `/api/admin/drivers/{id}/reject` | Admin | Reject driver |
| PUT | `/api/admin/users/{id}/suspend` | Admin | Suspend user |
| PUT | `/api/admin/users/{id}/reactivate` | Admin | Reactivate user |

### Pricing
| Method | Endpoint | Access | Description |
|--------|----------|--------|-------------|
| POST | `/api/pricing/estimate` | Authenticated | Get fare estimate |
| GET | `/api/pricing/fare-rules` | Authenticated | List all fare rules |
| DELETE | `/api/pricing/cache/{type}` | Admin | Invalidate fare cache |

### Rides
| Method | Endpoint | Access | Description |
|--------|----------|--------|-------------|
| POST | `/api/rides/request` | Rider | Request a ride |
| PUT | `/api/rides/{id}/start` | Driver | Start ride |
| PUT | `/api/rides/{id}/complete` | Driver | Complete ride |
| PUT | `/api/rides/{id}/cancel` | Rider/Driver | Cancel ride |
| GET | `/api/rides/{id}` | Participant | Get ride details |
| GET | `/api/rides/history/rider` | Rider | Ride history |
| GET | `/api/rides/history/driver` | Driver | Ride history |

### Payments
| Method | Endpoint | Access | Description |
|--------|----------|--------|-------------|
| PUT | `/api/payments/{id}/pay` | Rider | Process payment |
| GET | `/api/payments/ride/{rideId}` | Participant | Payment for ride |
| GET | `/api/payments/history/rider` | Rider | Payment history |
| GET | `/api/payments/history/driver` | Driver | Earnings history |
| PUT | `/api/payments/{id}/refund` | Admin | Refund payment |

### Ratings
| Method | Endpoint | Access | Description |
|--------|----------|--------|-------------|
| POST | `/api/ratings` | Rider/Driver | Submit rating (1-5) |
| GET | `/api/ratings/received` | Authenticated | Ratings received |
| GET | `/api/ratings/given` | Authenticated | Ratings given |
| GET | `/api/ratings/average/{userId}` | Authenticated | Average rating |

## Data Flow — Complete Ride Lifecycle

```
Rider registers → PostgreSQL
Driver registers → PostgreSQL → Admin approves → Kafka → WebSocket notification

Rider requests ride:
  → PricingService: Redis cache → Haversine → fare = ₹105
  → Save to PostgreSQL (status = REQUESTED)
  → Kafka: publish ride-requested
  → Async: CompletableFuture → Redis GEO (find driver within 5km)
  → Match found → PostgreSQL (status = ACCEPTED, driver = BUSY)
  → Kafka: publish ride-accepted → WebSocket: "Driver Found!"

Driver starts ride → PostgreSQL (IN_PROGRESS)
Driver completes  → PostgreSQL (COMPLETED)
                  → Kafka: ride-completed
                  → PaymentConsumer: creates PENDING payment
                  → NotificationConsumer: WebSocket push to both

Rider pays ₹105 via UPI → PostgreSQL (payment COMPLETED)
Rider rates driver 5★   → PostgreSQL (rating + user.average_rating updated)
```

## Getting Started

### Prerequisites
- Java 17+
- Maven 3.8+
- Docker Desktop

### Quick Start

```bash
# 1. Clone the repository
git clone https://github.com/sasimohith/Ride-Engine.git
cd Ride-Engine

# 2. Start infrastructure (PostgreSQL, Redis, Kafka, Prometheus, Grafana)
docker compose up -d

# 3. Run the application
mvn spring-boot:run

# 4. Verify
# App: http://localhost:8080/actuator/health
# Prometheus: http://localhost:9090
# Grafana: http://localhost:3000 (admin/admin)

# 5. Run tests
mvn test
```

### Test the API

```bash
# Register a rider
curl -X POST http://localhost:8080/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{"name":"Arun","email":"arun@test.com","password":"pass123","phone":"9876543210","role":"RIDER"}'

# Login
curl -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"arun@test.com","password":"pass123"}'
```

See [SETUP-AND-TESTING-GUIDE.md](SETUP-AND-TESTING-GUIDE.md) for the complete 12-step manual testing walkthrough.

## Testing

```
164 tests — ALL PASSING

├── Shared Infrastructure    14 tests
├── Auth Module              15 tests
├── Driver Module             7 tests
├── Pricing Module           22 tests
├── Admin Module             12 tests
├── Ride Module              22 tests
├── Notification Module      12 tests
├── Payment Module           15 tests
├── Rating Module            10 tests
└── E2E Integration          35 tests
```

## Database Schema

9 Flyway migrations creating:
- `users` — riders, drivers, admins with roles and ratings
- `vehicles` — driver vehicles (type, plate, model, color)
- `driver_documents` — license, insurance documents
- `fare_rules` — per-vehicle-type pricing (base, per-km, per-minute)
- `rides` — full ride lifecycle with timestamps and fare data
- `payments` — one payment per ride (CASH/UPI/WALLET)
- `ratings` — bidirectional rider-driver ratings (1-5 stars)

## Event-Driven Architecture (Kafka Topics)

| Topic | Publisher | Consumer(s) |
|-------|-----------|-------------|
| `ride-requested` | RideService | — |
| `ride-accepted` | RideMatchingService | NotificationService |
| `ride-completed` | RideService | PaymentService, NotificationService |
| `ride-cancelled` | RideService | NotificationService |
| `driver-approved` | AdminService | NotificationService |

## Monitoring

- **Prometheus** scrapes `/actuator/prometheus` every 15 seconds
- **Grafana** dashboard includes:
  - HTTP request rate
  - Response time (p95)
  - JVM memory usage
  - Active threads
  - Database connection pool
  - HTTP error rate
  - JVM GC pause time

## License

This project is for educational purposes.
