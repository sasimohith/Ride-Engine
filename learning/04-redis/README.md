# Module 4: Redis — Complete Guide

> Everything about Redis — what it is, why we need it alongside PostgreSQL, data types (String, Set, Sorted Set/GEO), RedisTemplate, serialization, Cache-Aside pattern, TTL, and every place we use Redis in our ride-sharing platform. Written so you can read this 20 years from now and still understand it.

---

## Table of Contents

1. [What is Redis?](#1-what-is-redis)
2. [Redis vs PostgreSQL — Why We Need Both](#2-redis-vs-postgresql--why-we-need-both)
3. [How Redis Stores Data — Key-Value Pairs](#3-how-redis-stores-data--key-value-pairs)
4. [Redis Data Types We Use](#4-redis-data-types-we-use)
5. [String — Simple Key-Value (Cache + Surge)](#5-string--simple-key-value-cache--surge)
6. [Set — Unordered Unique Collection (Pending Rides)](#6-set--unordered-unique-collection-pending-rides)
7. [Sorted Set / GEO — Geospatial (Driver Locations)](#7-sorted-set--geo--geospatial-driver-locations)
8. [TTL — Time to Live (Auto-Delete)](#8-ttl--time-to-live-auto-delete)
9. [Cache-Aside Pattern (How We Cache Fare Rules)](#9-cache-aside-pattern-how-we-cache-fare-rules)
10. [RedisTemplate — Spring's Redis Client](#10-redistemplate--springs-redis-client)
11. [RedisConfig — Serialization and Connection](#11-redisconfig--serialization-and-connection)
12. [Complete Map: Every Redis Key in Our Project](#12-complete-map-every-redis-key-in-our-project)
13. [Redis on Windows — Memurai](#13-redis-on-windows--memurai)
14. [Key Files in Our Codebase](#14-key-files-in-our-codebase)
15. [Interview One-Liners](#15-interview-one-liners)

---

## 1. What is Redis?

Redis = **RE**mote **DI**ctionary **S**erver

It's an **in-memory data store** — like a giant HashMap that lives as a separate process, accessible over the network.

```
PostgreSQL:                        Redis:
  Data stored on DISK (SSD/HDD)     Data stored in RAM (Memory)
  Survives restarts ✓                Lost on restart* (unless persistence enabled)
  Complex queries (JOIN, GROUP BY)   Simple operations (GET, SET, GEOSEARCH)
  ~5-50ms per query                  ~0.1-1ms per operation (50x faster!)
  
  * Redis supports disk persistence (RDB/AOF), but speed comes from being in-memory.
```

### Analogy

```
PostgreSQL = Your filing cabinet (organized, safe, but slow to search)
Redis      = Your desk (fast to grab things, but limited space)

You don't replace the filing cabinet with the desk.
You keep frequently-needed documents ON your desk for quick access,
and everything else stays in the cabinet.
```

---

## 2. Redis vs PostgreSQL — Why We Need Both

```
┌────────────────────────┬─────────────────────────┬──────────────────────────┐
│ Task                   │ PostgreSQL               │ Redis                    │
├────────────────────────┼─────────────────────────┼──────────────────────────┤
│ Store user accounts    │ ✓ (permanent, ACID)     │ ✗ (would lose on restart)│
│ Store ride history     │ ✓ (complex queries)     │ ✗ (not designed for this)│
│ Store fare rules       │ ✓ (source of truth)     │ ✓ (CACHE for speed)     │
│ Find nearby drivers    │ Slow (~50ms with GIS)   │ ✓ GEO (~0.1ms)         │
│ Track pending requests │ Overkill                │ ✓ Set (~0.1ms)          │
│ Store surge multiplier │ Overkill                │ ✓ String with TTL       │
│ Session storage        │ Slow                    │ ✓ (fast, TTL support)   │
└────────────────────────┴─────────────────────────┴──────────────────────────┘

PostgreSQL = Source of Truth (permanent, reliable, complex queries)
Redis      = Speed Layer (fast reads, temporary data, real-time features)
```

### Our Usage

```
In our ride-sharing platform, Redis serves 4 purposes:

1. CACHING (fare rules)
   → Avoid hitting PostgreSQL for data that rarely changes
   → 1000 ride requests = 1 DB query + 999 Redis reads

2. GEO OPERATIONS (driver locations)
   → "Find all drivers within 5 km of this point"
   → Sub-millisecond, critical for real-time matching

3. TEMPORARY DATA (pending ride requests)
   → Track which rides are waiting for a driver
   → Used by SurgePricingScheduler to count demand

4. CONFIGURATION (surge multiplier)
   → Calculated every 30 seconds, stored in Redis with TTL
   → Expires automatically, always fresh
```

---

## 3. How Redis Stores Data — Key-Value Pairs

Everything in Redis is a **key-value pair**. The key is always a string. The value can be different data types.

```
Redis is like a HashMap<String, Various>:

  KEY (always String)              VALUE (depends on data type)
  ─────────────────                ─────────────────────────────
  "fare:rule:SEDAN"          →     {baseFare:50, perKmRate:15, ...}       (String/JSON)
  "surge:SEDAN"              →     1.5                                     (String/Number)
  "ride:pending-requests"    →     {"1", "3", "7", "12"}                   (Set)
  "driver:locations"         →     {driver3→(lat,lng), driver9→(lat,lng)}  (Sorted Set/GEO)
```

### Key Naming Convention

We follow the `module:entity:identifier` pattern:

```
fare:rule:SEDAN         → Pricing module, fare rule for SEDAN
surge:SEDAN             → Pricing module, surge for SEDAN
ride:pending-requests   → Ride module, set of pending ride IDs
driver:locations        → Driver module, GEO set of all driver positions
```

WHY colons? Convention in the Redis community. Makes keys organized and greppable. Like package names in Java.

---

## 4. Redis Data Types We Use

Redis supports many data types. We use three:

```
┌────────────────┬─────────────────────────────────┬──────────────────────────┐
│ Data Type      │ What It Is                      │ Our Use Case             │
├────────────────┼─────────────────────────────────┼──────────────────────────┤
│ String         │ Simple key → value              │ Cache fare rules         │
│                │ Can store text, numbers, JSON   │ Store surge multiplier   │
│                │ Supports TTL (auto-delete)      │                          │
├────────────────┼─────────────────────────────────┼──────────────────────────┤
│ Set            │ Unordered collection of unique  │ Track pending ride IDs   │
│                │ values. No duplicates.           │ {"1", "3", "7"}          │
│                │ O(1) add/remove/check            │                          │
├────────────────┼─────────────────────────────────┼──────────────────────────┤
│ Sorted Set /   │ Sorted set with scores.         │ Driver locations         │
│ GEO            │ GEO is built ON TOP of sorted   │ "Find drivers within     │
│                │ set. Stores lat/lng as score.    │  5 km of this point"     │
└────────────────┴─────────────────────────────────┴──────────────────────────┘
```

---

## 5. String — Simple Key-Value (Cache + Surge)

The simplest Redis data type. One key holds one value.

### Redis Commands

```
SET key value              → Store a value
GET key                    → Retrieve a value
SET key value EX seconds   → Store with auto-expiry (TTL)
DEL key                    → Delete a key
```

### Our Usage 1: Caching Fare Rules

```java
// PricingService.java
// WRITE (cache miss → fetch from DB → store in Redis)
redisTemplate.opsForValue().set(cacheKey, fareRule, 1, TimeUnit.HOURS);
// Redis: SET "fare:rule:SEDAN" '{"baseFare":50,"perKmRate":15,...}' EX 3600

// READ (cache hit)
FareRule cached = (FareRule) redisTemplate.opsForValue().get(cacheKey);
// Redis: GET "fare:rule:SEDAN" → '{"baseFare":50,"perKmRate":15,...}'
```

What Redis stores:

```
KEY:   "fare:rule:SEDAN"
VALUE: '["com.ridesharing.pricing.model.FareRule",
         {"id":3,"vehicleType":"SEDAN","baseFare":50.00,
          "perKmRate":15.00,"perMinuteRate":2.00,
          "minimumFare":60.00,"active":true,
          "createdAt":"2024-01-15T10:00:00","updatedAt":"2024-01-15T10:00:00"}]'
TTL:   3600 seconds (1 hour)
```

### Our Usage 2: Surge Multiplier

```java
// SurgePricingScheduler.java — WRITES surge every 30 seconds
redisTemplate.opsForValue().set("surge:SEDAN", surge, SURGE_TTL_SECONDS, TimeUnit.SECONDS);
// Redis: SET "surge:SEDAN" 1.5 EX 120

// PricingService.java — READS surge when estimating fare
Object surgeValue = redisTemplate.opsForValue().get("surge:SEDAN");
// Redis: GET "surge:SEDAN" → 1.5
```

---

## 6. Set — Unordered Unique Collection (Pending Rides)

A Redis Set is like Java's `HashSet<String>` — unordered, no duplicates, O(1) operations.

### Redis Commands

```
SADD key member            → Add a member to the set
SREM key member            → Remove a member from the set
SCARD key                  → Count of members in the set
SMEMBERS key               → Get all members
SISMEMBER key member       → Check if member exists (returns true/false)
```

### Our Usage: Tracking Pending Ride Requests

```java
// RideMatchingService.java — when a ride request starts matching
redisTemplate.opsForSet().add("ride:pending-requests", ride.getId().toString());
// Redis: SADD "ride:pending-requests" "1"

// RideMatchingService.java — when matching completes (driver found or cancelled)
redisTemplate.opsForSet().remove("ride:pending-requests", ride.getId().toString());
// Redis: SREM "ride:pending-requests" "1"

// SurgePricingScheduler.java — count pending requests for surge calculation
Long size = redisTemplate.opsForSet().size("ride:pending-requests");
// Redis: SCARD "ride:pending-requests" → 5
```

### What Redis Stores

```
KEY:   "ride:pending-requests"
VALUE: { "1", "3", "7", "12", "15" }    (Set of ride IDs)

Timeline:
  Ride 1 requested → SADD "1" → { "1" }
  Ride 3 requested → SADD "3" → { "1", "3" }
  Ride 7 requested → SADD "7" → { "1", "3", "7" }
  Ride 1 matched   → SREM "1" → { "3", "7" }
  Ride 3 cancelled → SREM "3" → { "7" }
  
  Surge scheduler runs → SCARD → 1 (only 1 pending request)
```

### WHY a Set and Not a List?

```
Set:
  ✓ O(1) add, remove, check membership
  ✓ No duplicates (same ride can't be added twice)
  ✓ No ordering needed (we just need the count)

List:
  ✗ O(N) to remove from middle
  ✗ Can have duplicates
  ✗ Ordering not needed here
```

---

## 7. Sorted Set / GEO — Geospatial (Driver Locations)

This is the most powerful Redis data type we use. Redis GEO is built **on top of** Sorted Sets.

### How GEO Works Internally

```
A Sorted Set stores members with a SCORE:
  ZADD key score member

Redis GEO uses the Sorted Set but encodes lat/lng into the score using
a Geohash algorithm:
  GEOADD key longitude latitude member
  → Internally: ZADD key geohash(lat,lng) member

This means:
  - GEO commands (GEOADD, GEOSEARCH) are wrappers around Sorted Set operations
  - You can use Sorted Set commands (ZCARD, ZREM) on GEO keys
  - That's why removeDriverLocation uses ZREM internally
```

### Redis GEO Commands

```
GEOADD key longitude latitude member      → Add/update a member's location
GEOSEARCH key FROMLONLAT lng lat          → Find members within radius
  BYRADIUS radius km ASC COUNT limit
GEODIST key member1 member2               → Distance between two members
GEOPOS key member                         → Get member's coordinates
ZREM key member                           → Remove a member (Sorted Set command)
ZCARD key                                 → Count all members (Sorted Set command)
```

### Our Usage: Driver Locations

```java
// DriverLocationService.java

// ADD/UPDATE driver location (called every 5 seconds by driver app)
public void updateDriverLocation(Long driverId, double latitude, double longitude) {
    redisTemplate.opsForGeo().add(
            "driver:locations",
            new Point(longitude, latitude),   // IMPORTANT: longitude FIRST in Redis!
            driverId.toString()
    );
}
// Redis: GEOADD "driver:locations" 78.486 17.385 "3"

// SEARCH for nearby drivers (called when rider requests a ride)
public List<DriverLocationResult> findNearbyDrivers(
        double latitude, double longitude, double radiusInKm) {
    Circle searchArea = new Circle(
            new Point(longitude, latitude),
            new Distance(radiusInKm, DistanceUnit.KILOMETERS)
    );
    // ... execute search with distance, sorted ascending, limit 10
}
// Redis: GEOSEARCH "driver:locations" FROMLONLAT 78.486 17.385 BYRADIUS 5 km ASC COUNT 10

// REMOVE driver (when driver goes offline or becomes busy)
public void removeDriverLocation(Long driverId) {
    redisTemplate.opsForGeo().remove("driver:locations", driverId.toString());
}
// Redis: ZREM "driver:locations" "3"
```

### What Redis Stores

```
KEY:   "driver:locations"  (ONE key for ALL drivers)
TYPE:  Sorted Set / GEO

Members and their positions:
  "3"  → (17.385044, 78.486671)   — Driver 3 near Hyderabad Central
  "7"  → (12.971600, 77.594600)   — Driver 7 in Bangalore
  "9"  → (17.390000, 78.490000)   — Driver 9 near KPHB

When rider at (17.385, 78.486) requests a ride (5 km radius):
  GEOSEARCH → [Driver 3: 0.0 km, Driver 9: 0.6 km]
  Driver 7 excluded (Bangalore is ~550 km away!)
```

### The Longitude-First Gotcha

```
COMMON MISTAKE (will give wrong results):
  redisTemplate.opsForGeo().add(key, new Point(latitude, longitude), member);
  // You accidentally put latitude first → all locations are WRONG

CORRECT:
  redisTemplate.opsForGeo().add(key, new Point(longitude, latitude), member);
  // Redis convention: longitude (X) comes before latitude (Y)

Why? Redis follows the mathematical convention: Point(X, Y) where X=longitude, Y=latitude.
But humans naturally say "lat, lng" (like 17.385, 78.486).
```

### Performance

```
Redis GEO with 100,000 drivers:
  GEOSEARCH within 5 km → ~0.2ms

PostgreSQL with PostGIS and 100,000 drivers:
  SELECT with ST_Distance → ~50ms

250x faster. For a feature called with every ride request,
this difference is critical.
```

---

## 8. TTL — Time to Live (Auto-Delete)

TTL is a timer you set on a key. When the timer expires, Redis **automatically deletes** the key.

```
SET "fare:rule:SEDAN" '...' EX 3600
│                            │     │
│                            │     └─ 3600 seconds = 1 hour
│                            └─ EX = "expire after"
│
└─ After 1 hour, Redis AUTOMATICALLY deletes this key.
   No cron job. No cleanup code. Redis handles it.
```

### Our TTL Usage

```
┌──────────────────────────┬──────────┬─────────────────────────────────┐
│ Key                      │ TTL      │ Why This TTL?                   │
├──────────────────────────┼──────────┼─────────────────────────────────┤
│ fare:rule:SEDAN          │ 1 hour   │ Fare rules change rarely.       │
│                          │          │ 1 hour = good balance between   │
│                          │          │ freshness and performance.      │
├──────────────────────────┼──────────┼─────────────────────────────────┤
│ surge:SEDAN              │ 120 sec  │ Surge changes rapidly. If       │
│                          │          │ scheduler dies, stale surge     │
│                          │          │ should expire quickly. Default  │
│                          │          │ to 1.0x (no surge) is safer     │
│                          │          │ than wrong surge.               │
├──────────────────────────┼──────────┼─────────────────────────────────┤
│ driver:locations         │ No TTL   │ Drivers are added/removed       │
│                          │          │ explicitly. No auto-expiry.     │
├──────────────────────────┼──────────┼─────────────────────────────────┤
│ ride:pending-requests    │ No TTL   │ Members added/removed by code.  │
│                          │          │ Set persists, members change.   │
└──────────────────────────┴──────────┴─────────────────────────────────┘
```

### WHY TTL Instead of Manual Deletion?

```
Without TTL:
  You cache fare rules. Admin updates the base fare from ₹50 to ₹60 in PostgreSQL.
  Redis still has the OLD fare rule. Riders get wrong prices.
  You need a mechanism to invalidate the cache.

With TTL:
  Cache expires after 1 hour. Next request hits DB, gets the updated fare rule.
  Worst case: prices are wrong for up to 1 hour. Trade-off worth it for performance.

Without TTL on surge:
  Scheduler crashes. Surge value "2.5" stays in Redis FOREVER.
  Riders pay 2.5x surge on a quiet Sunday morning.

With TTL (120 seconds):
  Scheduler crashes. Surge value expires in 2 minutes.
  PricingService gets null → defaults to 1.0x. System self-heals.
```

---

## 9. Cache-Aside Pattern (How We Cache Fare Rules)

Cache-Aside is the most common caching strategy. The application manages the cache manually.

### The Pattern

```
READ REQUEST arrives:

  Step 1: Check Redis (cache) first
            │
            ├── Cache HIT (key exists)
            │     → Return cached value
            │     → PostgreSQL is NEVER called
            │     → Response time: ~1ms
            │
            └── Cache MISS (key doesn't exist)
                  → Query PostgreSQL
                  → Store result in Redis WITH TTL
                  → Return the value
                  → Response time: ~20ms (but next call will be ~1ms)
```

### Our Code (PricingService.java)

```java
FareRule getFareRuleWithCache(String vehicleType) {
    String cacheKey = "fare:rule:" + vehicleType;  // "fare:rule:SEDAN"

    // Step 1: Check cache
    FareRule cached = (FareRule) redisTemplate.opsForValue().get(cacheKey);
    if (cached != null) {
        log.debug("Cache HIT for fare rule: {}", vehicleType);
        return cached;    // Fast path — return immediately
    }

    // Step 2: Cache miss — go to database
    log.debug("Cache MISS for fare rule: {} — querying database", vehicleType);
    FareRule fareRule = fareRuleRepository.findByVehicleTypeAndActiveTrue(vehicleType)
            .orElseThrow(...);

    // Step 3: Store in cache with 1-hour TTL
    redisTemplate.opsForValue().set(cacheKey, fareRule, 1, TimeUnit.HOURS);

    return fareRule;
}
```

### Timeline Example

```
6:00 PM  — Ride request for SEDAN:
  Redis: GET "fare:rule:SEDAN" → NULL (miss)
  PostgreSQL: SELECT * FROM fare_rules WHERE vehicle_type='SEDAN' → Found!
  Redis: SET "fare:rule:SEDAN" {...} EX 3600
  Response time: 20ms

6:01 PM  — Another SEDAN ride request:
  Redis: GET "fare:rule:SEDAN" → Found! (hit)
  Response time: 1ms

6:30 PM  — 500 more SEDAN ride requests:
  ALL cache hits. PostgreSQL was called ZERO times.
  Response time: 1ms each.

7:00 PM  — TTL expires. Redis auto-deletes "fare:rule:SEDAN".

7:01 PM  — Next SEDAN ride request:
  Redis: GET "fare:rule:SEDAN" → NULL (miss — TTL expired)
  PostgreSQL: SELECT ... → Found! (maybe admin updated fare in between)
  Redis: SET "fare:rule:SEDAN" {...} EX 3600 (fresh data cached)

Summary: 502 ride requests = 2 DB calls + 500 Redis calls.
Without cache: 502 DB calls. Redis saved 99.6% of database load.
```

### Cache-Aside vs Other Caching Strategies (Interview Knowledge)

```
CACHE-ASIDE (our choice):
  App checks cache first, then DB on miss. App writes to cache manually.
  ✓ Simple to implement
  ✓ Cache only what's actually requested (no pre-loading)
  ✗ First request is always slow (cache miss)
  ✗ Cache can be stale for up to TTL duration

WRITE-THROUGH:
  App writes to cache AND DB simultaneously.
  ✓ Cache always has latest data
  ✗ Every write hits both cache and DB (slower writes)

WRITE-BEHIND:
  App writes to cache first, DB is updated asynchronously later.
  ✓ Very fast writes
  ✗ Risk of data loss if cache crashes before DB sync

READ-THROUGH:
  App always reads from cache. Cache auto-fetches from DB on miss.
  ✓ App code is simpler
  ✗ Cache needs to know how to query the database
```

---

## 10. RedisTemplate — Spring's Redis Client

`RedisTemplate` is Spring's client for talking to Redis. Like `JdbcTemplate` is for SQL, `RedisTemplate` is for Redis.

### Method Mapping

```java
redisTemplate.opsForValue()    → String operations (GET, SET)
redisTemplate.opsForSet()      → Set operations (SADD, SREM, SCARD)
redisTemplate.opsForZSet()     → Sorted Set operations (ZADD, ZREM, ZCARD)
redisTemplate.opsForGeo()      → GEO operations (GEOADD, GEOSEARCH)
redisTemplate.opsForHash()     → Hash operations (HSET, HGET)
redisTemplate.opsForList()     → List operations (LPUSH, RPUSH)
```

### How Each Method Maps to Redis Commands

```java
// STRING operations
redisTemplate.opsForValue().set("key", value);              // SET key value
redisTemplate.opsForValue().set("key", value, 60, SECONDS); // SET key value EX 60
redisTemplate.opsForValue().get("key");                     // GET key

// SET operations
redisTemplate.opsForSet().add("key", "member1");           // SADD key member1
redisTemplate.opsForSet().remove("key", "member1");        // SREM key member1
redisTemplate.opsForSet().size("key");                     // SCARD key

// GEO operations
redisTemplate.opsForGeo().add("key", point, "member");    // GEOADD key lng lat member
redisTemplate.opsForGeo().radius("key", circle, args);    // GEOSEARCH key ...
redisTemplate.opsForGeo().remove("key", "member");        // ZREM key member

// SORTED SET operations (used by scheduler to count drivers)
redisTemplate.opsForZSet().zCard("key");                   // ZCARD key (count members)
```

### How Spring Connects to Redis

```
application-dev.yml:
  spring:
    data:
      redis:
        host: ${REDIS_HOST:localhost}
        port: ${REDIS_PORT:6379}

Spring Boot auto-creates a RedisConnectionFactory from these properties.
Our RedisConfig.java uses this factory to build a customized RedisTemplate.
```

---

## 11. RedisConfig — Serialization and Connection

```java
// RedisConfig.java
@Configuration
public class RedisConfig {

    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);

        // KEYS are always readable strings: "fare:rule:SEDAN"
        StringRedisSerializer stringSerializer = new StringRedisSerializer();
        template.setKeySerializer(stringSerializer);
        template.setHashKeySerializer(stringSerializer);

        // VALUES are Java objects converted to JSON
        ObjectMapper mapper = JsonMapper.builder()
                .addModule(new JavaTimeModule())                    // Handle LocalDateTime
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS) // "2024-01-15T10:00:00" not 1705298400
                .build();
        mapper.activateDefaultTyping(
                mapper.getPolymorphicTypeValidator(),
                ObjectMapper.DefaultTyping.NON_FINAL);             // Include class name in JSON

        GenericJackson2JsonRedisSerializer jsonSerializer =
                new GenericJackson2JsonRedisSerializer(mapper);
        template.setValueSerializer(jsonSerializer);
        template.setHashValueSerializer(jsonSerializer);

        template.afterPropertiesSet();
        return template;
    }
}
```

### Why Custom Serialization?

```
DEFAULT (without our config):
  Key: bytes [not human-readable in Redis CLI]
  Value: Java serialization bytes [unreadable, fragile, non-portable]

OUR CONFIG:
  Key: "fare:rule:SEDAN" [human-readable, debug-friendly]
  Value: '{"baseFare":50.00,"perKmRate":15.00,...}' [JSON, readable]
```

### The JavaTimeModule Fix

```
WITHOUT JavaTimeModule:
  FareRule has createdAt: LocalDateTime.now()
  → Serialization FAILS: "Java 8 date/time type LocalDateTime not supported"

WITH JavaTimeModule:
  → LocalDateTime serialized as: "2024-01-15T10:00:00" (ISO format)
  → Works perfectly

This was a bug we encountered and fixed during testing.
```

### Why activateDefaultTyping?

```
WITHOUT defaultTyping:
  Redis stores: {"baseFare": 50, "perKmRate": 15}
  When reading back: Jackson doesn't know which Java class to create
  → Returns a LinkedHashMap, NOT a FareRule object
  → ClassCastException: LinkedHashMap cannot be cast to FareRule

WITH defaultTyping:
  Redis stores: ["com.ridesharing.pricing.model.FareRule", {"baseFare": 50, "perKmRate": 15}]
  When reading back: Jackson sees the class name → creates a FareRule object
  → Correct type returned
```

---

## 12. Complete Map: Every Redis Key in Our Project

```
┌───────────────────────────────┬──────────┬──────────┬────────────────────────────────┐
│ Key Pattern                   │ Type     │ TTL      │ Purpose                        │
├───────────────────────────────┼──────────┼──────────┼────────────────────────────────┤
│ fare:rule:SEDAN               │ String   │ 1 hour   │ Cached fare rule for SEDAN     │
│ fare:rule:AUTO                │ String   │ 1 hour   │ Cached fare rule for AUTO      │
│ fare:rule:BIKE                │ String   │ 1 hour   │ Cached fare rule for BIKE      │
│ fare:rule:SUV                 │ String   │ 1 hour   │ Cached fare rule for SUV       │
├───────────────────────────────┼──────────┼──────────┼────────────────────────────────┤
│ surge:SEDAN                   │ String   │ 120 sec  │ Current surge multiplier       │
│ surge:AUTO                    │ String   │ 120 sec  │ Current surge multiplier       │
│ surge:BIKE                    │ String   │ 120 sec  │ Current surge multiplier       │
│ surge:SUV                     │ String   │ 120 sec  │ Current surge multiplier       │
├───────────────────────────────┼──────────┼──────────┼────────────────────────────────┤
│ ride:pending-requests         │ Set      │ None     │ Ride IDs waiting for drivers   │
│                               │          │          │ (members added/removed by code)│
├───────────────────────────────┼──────────┼──────────┼────────────────────────────────┤
│ driver:locations              │ GEO      │ None     │ All online driver positions    │
│                               │          │          │ (members added/removed by code)│
└───────────────────────────────┴──────────┴──────────┴────────────────────────────────┘

WHO WRITES                              WHO READS
──────────                              ─────────
PricingService (cache miss)         →   PricingService (cache hit)
SurgePricingScheduler (every 30s)   →   PricingService (every ride request)
RideMatchingService (ride start)    →   SurgePricingScheduler (demand count)
RideMatchingService (ride end)      →   SurgePricingScheduler (supply count)
Driver app (every 5 seconds)        →   RideMatchingService (find nearby)
RideMatchingService (assign driver) →   (removal, not read)
```

---

## 13. Redis on Windows — Memurai

Redis is officially Linux-only. On Windows, we use **Memurai** — a Redis-compatible server.

```
Installation:
  Downloaded from memurai.com
  Installed as a Windows Service
  Runs on localhost:6379 (same default port as Redis)

Our app doesn't know it's Memurai — it connects to localhost:6379
and speaks the Redis protocol. Identical behavior.

In production: Use actual Redis on Linux, or AWS ElastiCache (managed Redis).
```

---

## 14. Key Files in Our Codebase

| File | Purpose |
|------|---------|
| `RedisConfig.java` | RedisTemplate setup, serialization (keys=String, values=JSON) |
| `DriverLocationService.java` | Redis GEO: add, remove, search driver locations |
| `PricingService.java` | Redis String: cache fare rules, read surge values |
| `SurgePricingScheduler.java` | Redis String: write surge values. Redis Set: count pending. Redis ZSet: count drivers. |
| `RideMatchingService.java` | Redis Set: add/remove pending ride requests |
| `application-dev.yml` | Redis host/port configuration (localhost:6379) |

---

## 15. Interview One-Liners

- **Redis**: An in-memory key-value data store used for caching, real-time geospatial queries, and temporary data storage. 10-100x faster than disk-based databases for simple operations because everything is in RAM.

- **Why Redis + PostgreSQL**: PostgreSQL is the source of truth (permanent, ACID, complex queries). Redis is the speed layer (caching, GEO, temporary data). PostgreSQL for reliability, Redis for performance.

- **Redis Data Types**: String (simple key-value, caching, counters), Set (unordered unique collections, membership checks), Sorted Set (ordered by score), GEO (built on Sorted Set, geospatial queries), Hash (field-value maps), List (ordered, allows duplicates).

- **Cache-Aside Pattern**: Application checks cache first; on miss, queries database and stores result in cache with TTL. Simple, only caches what's actually requested, but first request is always slow and cache can be stale up to TTL duration.

- **TTL**: Time to Live — Redis automatically deletes a key after the specified duration. Ensures cached data is refreshed periodically (fare rules: 1 hour), and temporary data doesn't persist forever (surge: 120 seconds). Enables self-healing — if the surge scheduler crashes, stale surge values expire automatically.

- **Redis GEO**: Geospatial data structure built on Sorted Sets. Stores lat/lng as geohashes. GEOADD to store, GEOSEARCH to find within radius, ZREM to remove. Sub-millisecond radius queries even with 100K+ entries. We use it for real-time driver location tracking and matching.

- **RedisTemplate**: Spring's client for Redis operations. `opsForValue()` for Strings, `opsForSet()` for Sets, `opsForGeo()` for GEO. Configured with serializers to convert Java objects to/from Redis format.

- **Serialization**: Keys use StringRedisSerializer (human-readable). Values use GenericJackson2JsonRedisSerializer with custom ObjectMapper (JavaTimeModule for LocalDateTime, defaultTyping for proper deserialization back to the original Java class).

- **Memurai**: Redis-compatible server for Windows (Redis officially supports Linux only). Identical protocol, same port (6379). Production uses actual Redis on Linux or AWS ElastiCache.
