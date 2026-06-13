# Module 2: Ride Request Flow — From "Book Ride" to "Driver Found!"

> The complete journey of a ride request — how the rider's button press travels through pricing, database, Kafka, Redis GEO, async threads, and WebSocket to finally match a driver. Written so you can read this 20 years from now and still understand it.

---

## Table of Contents

1. [The Big Picture](#1-the-big-picture)
2. [Step 1: Rider Hits "Request Ride"](#2-step-1-rider-hits-request-ride)
3. [Step 2: Active Ride Check (Spring Data JPA Magic)](#3-step-2-active-ride-check-spring-data-jpa-magic)
4. [Step 3: Price Estimation (PricingService)](#4-step-3-price-estimation-pricingservice)
5. [Step 4: Fare Rule Cache (Redis Cache-Aside Pattern)](#5-step-4-fare-rule-cache-redis-cache-aside-pattern)
6. [Step 5: Surge Pricing (From Redis)](#6-step-5-surge-pricing-from-redis)
7. [Step 6: Fare Calculation (Distance + Time + Surge)](#7-step-6-fare-calculation-distance--time--surge)
8. [Step 7: Save Ride to Database](#8-step-7-save-ride-to-database)
9. [Step 8: Publish Kafka Event (ride-requested)](#9-step-8-publish-kafka-event-ride-requested)
10. [Step 9: Async Driver Matching (The Background Thread)](#10-step-9-async-driver-matching-the-background-thread)
11. [Step 10: Redis GEO Search (Find Nearby Drivers)](#11-step-10-redis-geo-search-find-nearby-drivers)
12. [Step 11: PostgreSQL Validation (Is Driver Really Available?)](#12-step-11-postgresql-validation-is-driver-really-available)
13. [Step 12: Assign Driver (DB + Redis + Kafka)](#13-step-12-assign-driver-db--redis--kafka)
14. [Step 13: WebSocket Notification (Real-Time Push)](#14-step-13-websocket-notification-real-time-push)
15. [The Complete Timeline](#15-the-complete-timeline)
16. [Where Each Technology Helps](#16-where-each-technology-helps)
17. [Key Files in Our Codebase](#17-key-files-in-our-codebase)
18. [Interview One-Liners](#18-interview-one-liners)

---

## 1. The Big Picture

When a rider taps "Book Ride" on the app, this is the complete journey:

```
┌──────────────────────────────────────────────────────────────────────────┐
│                     RIDE REQUEST — END TO END                           │
│                                                                          │
│  RIDER taps "Book Ride"                                                 │
│       │                                                                  │
│       ▼                                                                  │
│  ① Check: Does rider already have an active ride?         [PostgreSQL]  │
│       │                                                                  │
│       ▼                                                                  │
│  ② Estimate fare: distance + time + surge                 [Redis cache] │
│       │                                                                  │
│       ▼                                                                  │
│  ③ Save ride with status REQUESTED                        [PostgreSQL]  │
│       │                                                                  │
│       ▼                                                                  │
│  ④ Publish "ride-requested" event                         [Kafka]       │
│       │                                                                  │
│       ▼                                                                  │
│  ⑤ Return response to rider immediately                   [HTTP]        │
│     "Searching for drivers..." (rider sees this)                        │
│       │                                                                  │
│       │ (meanwhile, in a BACKGROUND THREAD)                             │
│       ▼                                                                  │
│  ⑥ Add ride to pending requests                           [Redis Set]   │
│       │                                                                  │
│       ▼                                                                  │
│  ⑦ Search for nearby drivers (within 5 km)                [Redis GEO]   │
│       │                                                                  │
│       ▼                                                                  │
│  ⑧ Validate each driver in database                       [PostgreSQL]  │
│       │                                                                  │
│       ▼                                                                  │
│  ⑨ Assign driver → status: ACCEPTED                       [PostgreSQL]  │
│     Remove driver from GEO pool                           [Redis]       │
│     Publish "ride-accepted" event                         [Kafka]       │
│       │                                                                  │
│       ▼                                                                  │
│  ⑩ Send real-time notification                            [WebSocket]   │
│     Rider gets: "Driver Found!"                                         │
│                                                                          │
└──────────────────────────────────────────────────────────────────────────┘
```

### Technologies Used and WHERE

```
PostgreSQL  → Stores rides, users, fare rules. Source of truth.
Redis       → Caches fare rules, stores driver locations (GEO), tracks pending requests, stores surge values.
Kafka       → Publishes ride events (ride-requested, ride-accepted). Decouples modules.
@Async      → Runs driver matching in background thread. HTTP response returns immediately.
WebSocket   → Pushes "Driver Found!" notification to rider's phone in real-time.
@Scheduled  → Calculates surge pricing every 30 seconds (feeds into price estimation).
```

---

## 2. Step 1: Rider Hits "Request Ride"

### The API

```
POST /api/rides/request
Header: Authorization: Bearer eyJhbG... (rider's JWT token)
Body:
{
  "pickupLatitude": 17.385044,
  "pickupLongitude": 78.486671,
  "pickupAddress": "Hyderabad Central",
  "dropoffLatitude": 17.440081,
  "dropoffLongitude": 78.498361,
  "dropoffAddress": "Secunderabad Station",
  "vehicleType": "SEDAN"
}
```

### The Entry Point (RideController.java)

```java
@PostMapping("/request")
public ResponseEntity<ApiResponse<RideResponseDto>> requestRide(
        @Valid @RequestBody RideRequestDto request,
        Authentication auth) {

    Long riderId = Long.parseLong(auth.getName());    // Extract user ID from JWT
    RideResponseDto response = rideService.requestRide(riderId, request);
    return ResponseEntity.ok(ApiResponse.success("Ride requested", response));
}
```

The JWT token is decoded by `JwtAuthenticationFilter` (from Module 1). By the time we reach the controller, `auth.getName()` gives us the rider's userId (e.g., "2").

---

## 3. Step 2: Active Ride Check (Spring Data JPA Magic)

```java
// RideService.java
boolean hasActiveRide = rideRepository.existsByRiderIdAndStatusIn(
        riderId,
        List.of(RideStatus.REQUESTED, RideStatus.ACCEPTED, RideStatus.IN_PROGRESS)
);

if (hasActiveRide) {
    throw new BadRequestException("You already have an active ride");
}
```

### The Magic: You Never Write the SQL

The `RideRepository` interface has this method:

```java
boolean existsByRiderIdAndStatusIn(Long riderId, Collection<RideStatus> statuses);
```

There is **no implementation**. Spring Data JPA reads the method name and auto-generates:

```sql
SELECT CASE WHEN COUNT(*) > 0 THEN true ELSE false END
FROM rides
WHERE rider_id = 2
  AND status IN ('REQUESTED', 'ACCEPTED', 'IN_PROGRESS')
```

### How Spring Parses the Method Name

```
existsBy  RiderId  And  StatusIn
  │          │      │       │
  │          │      │       └─ "status IN (?)" — collection match
  │          │      └─ AND operator
  │          └─ WHERE rider_id = ?
  └─ Return boolean (EXISTS query)
```

### WHY This Check Exists

Prevents a rider from having multiple rides at once. Imagine booking 3 Ubers simultaneously — chaos for drivers.

---

## 4. Step 3: Price Estimation (PricingService)

```java
// RideService.java
FareEstimateResponse fareEstimate = pricingService.estimateFare(
        FareEstimateRequest.builder()
                .pickupLatitude(request.getPickupLatitude())
                .pickupLongitude(request.getPickupLongitude())
                .dropoffLatitude(request.getDropoffLatitude())
                .dropoffLongitude(request.getDropoffLongitude())
                .vehicleType(request.getVehicleType())
                .build()
);
```

The PricingService does 4 things:
1. Get fare rule for vehicle type (from Redis cache or PostgreSQL)
2. Get current surge multiplier (from Redis)
3. Calculate distance using Haversine formula
4. Calculate fare: `(baseFare + distance*perKmRate + time*perMinRate) * surge`

---

## 5. Step 4: Fare Rule Cache (Redis Cache-Aside Pattern)

```java
// PricingService.java
FareRule getFareRuleWithCache(String vehicleType) {
    String cacheKey = FARE_RULE_CACHE_PREFIX + vehicleType;  // "fare:rule:SEDAN"

    FareRule cached = (FareRule) redisTemplate.opsForValue().get(cacheKey);
    if (cached != null) {
        return cached;   // Cache HIT — no database call needed
    }

    // Cache MISS — fetch from PostgreSQL
    FareRule fareRule = fareRuleRepository.findByVehicleTypeAndActiveTrue(vehicleType)
            .orElseThrow(...);

    // Store in Redis with 1-hour TTL
    redisTemplate.opsForValue().set(cacheKey, fareRule, 1, TimeUnit.HOURS);
    return fareRule;
}
```

### The Cache-Aside Pattern Visualized

```
Request 1 (First ride of the day):
  App → Redis: GET "fare:rule:SEDAN"     → NULL (miss)
  App → PostgreSQL: SELECT * FROM fare_rules WHERE vehicle_type='SEDAN'  → Found!
  App → Redis: SET "fare:rule:SEDAN" {baseFare:50, perKmRate:15...} TTL 1 hour
  App uses the fare rule

Request 2 (Second ride, 5 minutes later):
  App → Redis: GET "fare:rule:SEDAN"     → Found! (hit)
  App uses the cached fare rule
  PostgreSQL is NEVER called — faster response

After 1 hour (TTL expires):
  Redis automatically deletes "fare:rule:SEDAN"
  Next request → cache miss → fetch from DB again → re-cache
```

### WHY Cache Fare Rules?

Fare rules rarely change (maybe once a month). But every ride request needs them. Without caching, every ride request hits PostgreSQL for data that changes once a month. With caching, 1000 rides = 1 DB call + 999 Redis calls (Redis is 10-100x faster than PostgreSQL for simple reads).

---

## 6. Step 5: Surge Pricing (From Redis)

```java
// PricingService.java
BigDecimal getCurrentSurge(String vehicleType) {
    String surgeKey = "surge:" + vehicleType.toUpperCase();  // "surge:SEDAN"

    Object surgeValue = redisTemplate.opsForValue().get(surgeKey);
    if (surgeValue != null) {
        return new BigDecimal(surgeValue.toString());  // e.g., 1.5
    }

    return BigDecimal.ONE;  // No surge data → default 1.0x (no surge)
}
```

### Where Does the Surge Value Come From?

The `SurgePricingScheduler` runs every 30 seconds (covered in Module 6). It:
1. Counts pending ride requests from Redis Set
2. Counts online drivers from Redis GEO
3. Calculates demand/supply ratio
4. Writes surge value to Redis: `SET "surge:SEDAN" 1.5 TTL 120s`

The PricingService just READS whatever the scheduler last wrote. If no surge data exists (TTL expired or scheduler hasn't run), default is 1.0x (no surge).

---

## 7. Step 6: Fare Calculation (Distance + Time + Surge)

```java
// PricingService.java — estimateFare() method

// 1. Calculate distance (Haversine formula — Earth is a sphere, not flat)
double distanceKm = calculateDistance(
        request.getPickupLatitude(), request.getPickupLongitude(),
        request.getDropoffLatitude(), request.getDropoffLongitude()
);

// 2. Estimate time (assume average speed of 25 km/h in city traffic)
double estimatedTimeMin = (distanceKm / 25.0) * 60;

// 3. Calculate fare components
BigDecimal distanceCharge = fareRule.getPerKmRate()
        .multiply(BigDecimal.valueOf(distanceKm));           // 15.00 × 6.27 = 94.05

BigDecimal timeCharge = fareRule.getPerMinuteRate()
        .multiply(BigDecimal.valueOf(estimatedTimeMin));     // 2.00 × 15.05 = 30.10

BigDecimal subtotal = fareRule.getBaseFare()
        .add(distanceCharge)
        .add(timeCharge);                                     // 50 + 94.05 + 30.10 = 174.15

// 4. Apply surge multiplier
BigDecimal total = subtotal.multiply(surge);                  // 174.15 × 1.0 = 174.15

// 5. Apply minimum fare
total = total.max(fareRule.getMinimumFare());                 // max(174.15, 60.00) = 174.15
```

### Concrete Example

```
Pickup:  Hyderabad Central (17.385, 78.486)
Dropoff: Secunderabad Station (17.440, 78.498)
Vehicle: SEDAN

Fare Rule (from Redis cache or DB):
  baseFare:       ₹50.00
  perKmRate:      ₹15.00
  perMinuteRate:  ₹2.00
  minimumFare:    ₹60.00

Calculation:
  Distance:       6.27 km (Haversine formula)
  Time:           15.05 min (6.27 km ÷ 25 km/h × 60)
  Surge:          1.0x (no surge right now)

  Base fare:      ₹50.00
  Distance:       ₹15.00 × 6.27 = ₹94.05
  Time:           ₹2.00 × 15.05 = ₹30.10
  ─────────────────────────────────
  Subtotal:       ₹174.15
  × Surge (1.0):  ₹174.15
  vs Minimum (60): ₹174.15 > ₹60.00 ✓

  Final Fare:     ₹174.15
```

### WHY BigDecimal Instead of double?

```java
double result = 0.1 + 0.2;    // = 0.30000000000000004  (WRONG!)
BigDecimal result = new BigDecimal("0.1").add(new BigDecimal("0.2"));  // = 0.3 (CORRECT)
```

Money calculations must be exact. `double` has floating-point precision errors. `BigDecimal` stores numbers as exact decimal values. Every financial system uses `BigDecimal` (or equivalent).

---

## 8. Step 7: Save Ride to Database

```java
// RideService.java
Ride ride = Ride.builder()
        .rider(rider)                                  // WHO requested
        .pickupLatitude(request.getPickupLatitude())   // FROM where
        .pickupLongitude(request.getPickupLongitude())
        .pickupAddress(request.getPickupAddress())
        .dropoffLatitude(request.getDropoffLatitude()) // TO where
        .dropoffLongitude(request.getDropoffLongitude())
        .dropoffAddress(request.getDropoffAddress())
        .vehicleType(request.getVehicleType().toUpperCase()) // WHAT type
        .status(RideStatus.REQUESTED)                  // CURRENT state
        .distanceKm(fareEstimate.getDistanceInKm())    // HOW far
        .estimatedTimeMin(fareEstimate.getEstimatedTimeInMinutes()) // HOW long
        .estimatedFare(fareEstimate.getEstimatedFare())            // HOW much
        .surgeMultiplier(fareEstimate.getSurgeMultiplier())        // SURGE active?
        .build();

ride = rideRepository.save(ride);
```

### What Happens in the Database

```sql
INSERT INTO rides (rider_id, pickup_latitude, pickup_longitude, pickup_address,
                   dropoff_latitude, dropoff_longitude, dropoff_address,
                   vehicle_type, status, distance_km, estimated_time_min,
                   estimated_fare, surge_multiplier, created_at, updated_at)
VALUES (2, 17.385, 78.486, 'Hyderabad Central',
        17.440, 78.498, 'Secunderabad Station',
        'SEDAN', 'REQUESTED', 6.27, 15.1,
        174.15, 1.0, NOW(), NOW());
```

**Key point:** `status = REQUESTED` and `driver_id = NULL`. No driver yet — just a request waiting to be matched.

---

## 9. Step 8: Publish Kafka Event (ride-requested)

```java
// RideService.java
publishRideEvent(ride, KafkaTopics.RIDE_REQUESTED);

// This builds a RideEvent and sends to Kafka:
private void publishRideEvent(Ride ride, String topic) {
    RideEvent event = RideEvent.builder()
            .rideId(ride.getId())
            .riderId(ride.getRider().getId())
            .driverId(ride.getDriver() != null ? ride.getDriver().getId() : null)
            .vehicleType(ride.getVehicleType())
            .status(ride.getStatus().name())
            .pickupLatitude(ride.getPickupLatitude())
            .pickupLongitude(ride.getPickupLongitude())
            .dropoffLatitude(ride.getDropoffLatitude())
            .dropoffLongitude(ride.getDropoffLongitude())
            .estimatedFare(ride.getEstimatedFare())
            .actualFare(ride.getActualFare())
            .surgeMultiplier(ride.getSurgeMultiplier())
            .timestamp(System.currentTimeMillis())
            .build();

    kafkaTemplate.send(topic, ride.getId().toString(), event);
}
```

The `ride-requested` event goes to Kafka. In our project, no active consumer listens to this topic — it serves as an **audit log**. In a real microservices setup, a separate Matching Service would consume it.

---

## 10. Step 9: Async Driver Matching (The Background Thread)

```java
// RideService.java — this is the LAST line before returning response to rider
rideMatchingService.matchDriverForRide(ride);

// Then immediately returns:
return toResponseDto(ride);  // Rider gets response with status: REQUESTED, driverId: null
```

### WHY Async?

```
WITHOUT @Async:
  Rider taps "Book" → Search drivers (5-15 sec) → Response
  Rider sees: ⏳ Loading... Loading... Loading...

WITH @Async:
  Rider taps "Book" → Save ride → Response immediately → "Searching for drivers..."
  Meanwhile in background: Search drivers → Find one → Assign → WebSocket notification
  Rider gets: 🔔 "Driver Found!" (2-3 seconds later)
```

### The matchDriverForRide Method

```java
// RideMatchingService.java
@Async("asyncExecutor")    // Runs on a SEPARATE thread from the HTTP thread
public void matchDriverForRide(Ride ride) {
    // Track as pending (used by SurgePricingScheduler for demand count)
    redisTemplate.opsForSet().add(PENDING_REQUESTS_KEY, ride.getId().toString());
    // Redis: SADD "ride:pending-requests" "1"

    CompletableFuture
            .supplyAsync(() -> attemptMatching(ride), asyncExecutor)  // Try finding driver
            .thenAccept(matched -> {
                redisTemplate.opsForSet().remove(PENDING_REQUESTS_KEY, ride.getId().toString());
                if (!matched) cancelRideNoDriverFound(ride);
            })
            .exceptionally(ex -> {
                redisTemplate.opsForSet().remove(PENDING_REQUESTS_KEY, ride.getId().toString());
                cancelRideNoDriverFound(ride);
                return null;
            });
}
```

### CompletableFuture Pipeline

```
supplyAsync(() → attemptMatching(ride))       // "Do this work in background"
    │
    ├── returns true (driver found)
    │       │
    │       ▼
    │   thenAccept(true):
    │     → Remove ride from pending set
    │     → matched=true → do nothing (driver already assigned)
    │
    ├── returns false (no driver after 3 retries)
    │       │
    │       ▼
    │   thenAccept(false):
    │     → Remove ride from pending set
    │     → cancelRideNoDriverFound() → status=CANCELLED
    │
    └── throws exception
            │
            ▼
        exceptionally():
          → Remove ride from pending set
          → cancelRideNoDriverFound() → status=CANCELLED
          → Safety net — ride never stuck in REQUESTED forever
```

---

## 11. Step 10: Redis GEO Search (Find Nearby Drivers)

```java
// RideMatchingService.java — inside attemptMatching()
for (int attempt = 1; attempt <= MAX_MATCHING_RETRIES; attempt++) {  // Try up to 3 times

    List<DriverLocationResult> nearbyDrivers = driverLocationService.findNearbyDrivers(
            ride.getPickupLatitude(),    // 17.385044
            ride.getPickupLongitude(),   // 78.486671
            SEARCH_RADIUS_KM             // 5.0 km
    );

    // If no drivers within 5 km, wait 5 seconds and retry
    if (nearbyDrivers.isEmpty()) {
        waitBeforeRetry(attempt);
        continue;
    }

    // Try each nearby driver...
}
```

### What Happens Inside Redis

```
Redis GEO data structure: "driver:locations"
  Driver 3 at (17.385, 78.486) — Hyderabad Central
  Driver 7 at (17.450, 78.550) — 8 km away
  Driver 9 at (17.390, 78.490) — 0.6 km away

GEOSEARCH "driver:locations" FROMLONLAT 78.486 17.385 BYRADIUS 5 km ASC

Result (sorted nearest first):
  [Driver 3: 0.0 km, Driver 9: 0.6 km]
  Driver 7 excluded — 8 km is outside 5 km radius
```

### WHY Redis GEO Instead of PostgreSQL?

```
PostgreSQL approach:
  SELECT * FROM users WHERE
    ST_Distance(ST_Point(longitude, latitude), ST_Point(78.486, 17.385)) <= 5000
  → Full table scan, 50-100ms, requires PostGIS extension

Redis GEO approach:
  GEOSEARCH "driver:locations" ... BYRADIUS 5 km
  → In-memory, sub-millisecond, built for this exact use case

For a feature called 100+ times per second (every ride request),
Redis GEO is the right choice.
```

---

## 12. Step 11: PostgreSQL Validation (Is Driver Really Available?)

Redis GEO only stores **location**. It doesn't know if the driver is suspended, offline, or already on a ride. So we validate each driver in PostgreSQL:

```java
// RideMatchingService.java
for (DriverLocationResult nearbyDriver : nearbyDrivers) {
    Optional<User> driverOpt = userRepository.findById(nearbyDriver.driverId());
    if (driverOpt.isEmpty()) continue;

    User driver = driverOpt.get();

    // CHECK 1: Is this actually a driver?
    if (driver.getRole() != UserRole.DRIVER) continue;

    // CHECK 2: Is the driver online (not offline)?
    if (driver.getAvailability() != DriverAvailability.ONLINE) continue;

    // CHECK 3: Is the driver active (not suspended by admin)?
    if (!driver.isActive()) continue;

    // CHECK 4: Is the driver already on another ride?
    boolean onActiveRide = rideRepository.existsByDriverIdAndStatusIn(
            driver.getId(),
            List.of(RideStatus.ACCEPTED, RideStatus.IN_PROGRESS)
    );
    if (onActiveRide) continue;

    // ALL CHECKS PASSED — assign this driver!
    return assignDriver(ride, driver);
}
```

### The Two-Layer Check Strategy

```
Layer 1: Redis GEO (FAST — sub-millisecond)
  "Who is NEAR the pickup point?"
  → Returns: [Driver 3, Driver 9]
  → Filters by LOCATION only

Layer 2: PostgreSQL (ACCURATE — 5-10ms)
  "Is this driver ACTUALLY available?"
  → Driver 3: DRIVER ✓, ONLINE ✓, active ✓, no active ride ✓ → ASSIGN!
  → Driver 9: DRIVER ✓, ONLINE ✗ (went offline 2 sec ago) → SKIP

WHY two layers?
  Redis = fast but limited info (only location)
  PostgreSQL = slower but complete info (role, status, active rides)
  Together = fast AND accurate
```

---

## 13. Step 12: Assign Driver (DB + Redis + Kafka)

When a valid driver is found, 4 things happen atomically:

```java
// RideMatchingService.java
private boolean assignDriver(Ride ride, User driver) {
    // ① Update ride in PostgreSQL
    ride.setDriver(driver);
    ride.setStatus(RideStatus.ACCEPTED);        // REQUESTED → ACCEPTED
    ride.setAcceptedAt(LocalDateTime.now());
    rideRepository.save(ride);
    // SQL: UPDATE rides SET driver_id=3, status='ACCEPTED', accepted_at=NOW() WHERE id=1

    // ② Mark driver as busy in PostgreSQL
    driver.setAvailability(DriverAvailability.BUSY);   // ONLINE → BUSY
    userRepository.save(driver);
    // SQL: UPDATE users SET availability='BUSY' WHERE id=3

    // ③ Remove driver from Redis GEO (no longer available for other rides)
    driverLocationService.removeDriverLocation(driver.getId());
    // Redis: ZREM "driver:locations" "3"

    // ④ Publish Kafka event → triggers notification
    publishRideEvent(ride, KafkaTopics.RIDE_ACCEPTED);
    // Kafka: send to "ride-accepted" topic

    return true;
}
```

### What Each Step Prevents

```
① Ride → ACCEPTED:   Other matching threads won't try to match this ride again
② Driver → BUSY:     PostgreSQL validation rejects this driver for other rides
③ Remove from GEO:   Redis GEO search won't return this driver for other rides
④ Kafka event:       Notification module picks it up → WebSocket to rider
```

All three systems (PostgreSQL, Redis, Kafka) are updated to ensure consistency.

---

## 14. Step 13: WebSocket Notification (Real-Time Push)

The Kafka event `ride-accepted` is consumed by `RideEventConsumer`:

```java
// RideEventConsumer.java
@KafkaListener(topics = KafkaTopics.RIDE_ACCEPTED, groupId = "notification-group")
public void onRideAccepted(RideEvent event) {
    notificationService.notifyRiderDriverAccepted(
            event.getRiderId(), event.getRideId(), event.getDriverId()
    );
}

// NotificationService.java
@Async("asyncExecutor")
public void notifyRiderDriverAccepted(Long riderId, Long rideId, Long driverId) {
    NotificationMessage notification = NotificationMessage.builder()
            .type("RIDE_ACCEPTED")
            .title("Driver Found!")
            .message("A driver has accepted your ride and is on the way.")
            .rideId(rideId)
            .recipientId(riderId)
            .data(Map.of("driverId", driverId))
            .timestamp(LocalDateTime.now())
            .build();

    // Push to rider's WebSocket channel
    messagingTemplate.convertAndSend("/topic/rider/" + riderId, notification);
}
```

### The Notification Flow

```
RideMatchingService                    Kafka                    RideEventConsumer
  assignDriver()                        │                           │
  kafkaTemplate.send(                   │                           │
    "ride-accepted", event)  ────────►  │  "ride-accepted"          │
                                        │  topic stores message     │
                                        │        │                  │
                                        │        └──────────────►   │
                                        │    @KafkaListener         │
                                        │    picks it up            │
                                                                    │
                                                                    ▼
                                                        NotificationService
                                                        notifyRiderDriverAccepted()
                                                                    │
                                                                    ▼
                                                            WebSocket PUSH
                                                        /topic/rider/2
                                                                    │
                                                                    ▼
                                                            Rider's phone:
                                                        🔔 "Driver Found!"
```

---

## 15. The Complete Timeline

```
Time  │  HTTP Thread              │  Background Thread          │  Rider's Screen
──────┼───────────────────────────┼─────────────────────────────┼──────────────────
0s    │ requestRide() called      │                             │ ⏳ Loading...
      │ ├─ hasActiveRide? No ✓    │                             │
      │ ├─ estimateFare()         │                             │
      │ │  ├─ Redis: fare rule    │                             │
      │ │  ├─ Redis: surge value  │                             │
      │ │  └─ calculate fare      │                             │
      │ ├─ save ride (REQUESTED)  │                             │
      │ ├─ Kafka: ride-requested  │                             │
      │ ├─ matchDriverForRide()   │ → spawns background thread  │
      │ └─ return response        │                             │ ✅ "Searching..."
      │                           │                             │    rideId: 1
0.5s  │ (thread is FREE for       │ Redis: SADD pending set    │    fare: ₹174.15
      │  other requests)          │ Redis: GEOSEARCH 5km       │    status: REQUESTED
      │                           │ → Found: Driver 3          │    driverId: null
1s    │                           │ PostgreSQL: validate driver │
      │                           │   ✓ DRIVER, ✓ ONLINE       │
      │                           │   ✓ active, ✓ not busy     │
      │                           │ assignDriver():             │
      │                           │   DB: ride→ACCEPTED         │
      │                           │   DB: driver→BUSY           │
      │                           │   Redis: remove driver GEO  │
      │                           │   Kafka: ride-accepted      │
      │                           │ Redis: SREM pending set    │
1.5s  │                           │ Done!                       │ 🔔 "Driver Found!"
      │                           │                             │    (WebSocket push)
```

---

## 16. Where Each Technology Helps

```
┌──────────────┬────────────────────────────────┬───────────────────────────────┐
│ Technology   │ What It Does Here              │ WHY Not an Alternative?       │
├──────────────┼────────────────────────────────┼───────────────────────────────┤
│ PostgreSQL   │ Stores rides, users, fare      │ Source of truth. ACID         │
│              │ rules. Validates driver status. │ guarantees. Data survives     │
│              │                                │ restarts.                     │
├──────────────┼────────────────────────────────┼───────────────────────────────┤
│ Redis        │ Caches fare rules (speed)      │ 10-100x faster than DB for   │
│              │ Stores driver GEO locations     │ simple reads. GEO search is  │
│              │ Tracks pending requests (Set)   │ sub-millisecond. In-memory.  │
│              │ Stores surge values             │                              │
├──────────────┼────────────────────────────────┼───────────────────────────────┤
│ Kafka        │ Publishes ride events.          │ Decouples Ride from          │
│              │ Notification module listens     │ Notification & Payment.      │
│              │ independently.                  │ Messages persist on disk.    │
│              │                                │ If notification is down,      │
│              │                                │ message waits in Kafka.       │
├──────────────┼────────────────────────────────┼───────────────────────────────┤
│ @Async       │ Driver matching runs in         │ HTTP response returns in     │
│              │ background thread.              │ <500ms instead of 5-15s.     │
│              │                                │ Rider doesn't wait.          │
├──────────────┼────────────────────────────────┼───────────────────────────────┤
│ WebSocket    │ Pushes "Driver Found!" to       │ HTTP = rider must keep       │
│              │ rider in real-time.             │ polling. WebSocket = server  │
│              │                                │ pushes instantly.             │
├──────────────┼────────────────────────────────┼───────────────────────────────┤
│ @Scheduled   │ Calculates surge every 30s.     │ Can't calculate surge        │
│              │ Feeds into price estimation.    │ on-demand (too slow for each │
│              │                                │ ride request).               │
└──────────────┴────────────────────────────────┴───────────────────────────────┘
```

---

## 17. Key Files in Our Codebase

| File | Purpose |
|------|---------|
| `RideController.java` | HTTP endpoint: POST /api/rides/request |
| `RideService.java` | Orchestrates the full ride request flow |
| `RideMatchingService.java` | Async driver matching with retries |
| `PricingService.java` | Fare estimation with Redis cache + surge |
| `SurgePricingScheduler.java` | Calculates surge every 30 seconds |
| `DriverLocationService.java` | Redis GEO operations (update, search, remove) |
| `RideEventConsumer.java` | Kafka consumer → WebSocket notification |
| `NotificationService.java` | Sends WebSocket push to rider/driver |
| `KafkaProducerConfig.java` | Configures how messages are sent to Kafka |
| `KafkaConsumerConfig.java` | Configures how messages are received from Kafka |
| `KafkaTopics.java` | Central registry of all Kafka topic names |
| `RideEvent.java` | The Kafka event payload (shared contract) |
| `RedisConfig.java` | Redis connection + serialization config |
| `RideRepository.java` | JPA queries (existsByRiderIdAndStatusIn) |
| `Ride.java` | JPA entity mapped to `rides` table |
| `FareRule.java` | JPA entity mapped to `fare_rules` table |
| `V5__create_fare_rules_table.sql` | Flyway migration with seed data |

---

## 18. Interview One-Liners

- **Ride Request Flow**: Rider requests a ride → fare is estimated using cached fare rules and surge pricing → ride is saved as REQUESTED → Kafka event is published → async background thread searches Redis GEO for nearby drivers → validates in PostgreSQL → assigns driver → Kafka notification → WebSocket pushes "Driver Found!" to rider. HTTP response returns immediately; matching happens asynchronously.

- **Cache-Aside Pattern**: Check Redis first; if miss, fetch from DB and store in Redis with TTL. Avoids hitting the database for data that rarely changes (like fare rules). Trade-off: cache can be stale for up to TTL duration.

- **Redis GEO for Driver Matching**: We use Redis GEO (GEOADD/GEOSEARCH) to store and query driver locations by radius. Sub-millisecond performance vs PostgreSQL's 50-100ms. Redis is the speed layer, PostgreSQL is the truth layer.

- **Two-Layer Validation**: Redis GEO answers "who is near?" (fast, limited info). PostgreSQL answers "who is actually available?" (slower, complete info). Together they provide speed AND accuracy.

- **Async Driver Matching**: Uses `@Async` + `CompletableFuture` so the HTTP response returns immediately (<500ms) while driver matching happens in a background thread (5-15s with retries). The rider gets a WebSocket notification when a driver is found.

- **BigDecimal for Money**: `double` has floating-point errors (0.1 + 0.2 = 0.30000000000000004). Financial calculations require exact precision. `BigDecimal` stores numbers as exact decimals.
