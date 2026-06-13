# Module 6: Scheduler — @Scheduled & Surge Pricing

> Everything about scheduled tasks in Spring Boot — how @Scheduled works, its lifecycle, scheduling strategies, and how we use it for surge pricing in our ride-sharing platform. Also covers how real companies (Uber/Rapido) handle surge pricing differently. Written so you can read this 20 years from now and still understand it.

---

## Table of Contents

1. [What is a Scheduler?](#1-what-is-a-scheduler)
2. [Real-World Examples](#2-real-world-examples)
3. [@EnableScheduling — The On Switch](#3-enablescheduling--the-on-switch)
4. [@Scheduled — Scheduling Strategies](#4-scheduled--scheduling-strategies)
5. [Scheduler Lifecycle — When Does It Start and Stop?](#5-scheduler-lifecycle--when-does-it-start-and-stop)
6. [Our Surge Pricing Scheduler — Complete Walkthrough](#6-our-surge-pricing-scheduler--complete-walkthrough)
7. [Step 1: Count Demand (Pending Ride Requests)](#7-step-1-count-demand-pending-ride-requests)
8. [Step 2: Count Supply (Online Drivers)](#8-step-2-count-supply-online-drivers)
9. [Step 3: Calculate Surge Multiplier](#9-step-3-calculate-surge-multiplier)
10. [Step 4: Store Surge in Redis](#10-step-4-store-surge-in-redis)
11. [Step 5: PricingService Reads the Surge](#11-step-5-pricingservice-reads-the-surge)
12. [Complete Data Flow Diagram](#12-complete-data-flow-diagram)
13. [Safety: What If the Scheduler Crashes?](#13-safety-what-if-the-scheduler-crashes)
14. [How Uber/Rapido Handle Surge in Production](#14-how-uberrapido-handle-surge-in-production)
15. [fixedRate vs fixedDelay vs cron](#15-fixedrate-vs-fixeddelay-vs-cron)
16. [Scheduler Threading](#16-scheduler-threading)
17. [Key Files in Our Codebase](#17-key-files-in-our-codebase)
18. [Interview One-Liners](#18-interview-one-liners)

---

## 1. What is a Scheduler?

A scheduler is a mechanism that **automatically runs a piece of code at regular intervals** — without any HTTP request, without any user action, without any event. It just runs on a timer.

```
HTTP-triggered code:                     Scheduler-triggered code:
  Runs when someone calls an API            Runs automatically every N seconds
  "Do this when I tell you"                 "Do this every 30 seconds, forever"

  POST /api/rides/request                   @Scheduled(fixedRate = 30000)
  → runs requestRide()                      → runs calculateSurge()
  → runs ONCE per request                   → runs FOREVER (until app stops)
```

### Analogy

```
HTTP endpoint = A light switch (you flip it, light turns on)
Scheduler     = An alarm clock (rings every morning at 6 AM, you don't press anything)
```

---

## 2. Real-World Examples

```
┌──────────────────────────────────────────────────────────────────────┐
│ Schedulers in the real world:                                       │
│                                                                      │
│ • Surge pricing calculation      → every 30 seconds                 │
│ • Database backup                → every night at 2 AM              │
│ • Session cleanup (expired)      → every 5 minutes                  │
│ • Email daily digest             → every day at 8 AM                │
│ • Health check / monitoring      → every 10 seconds                 │
│ • Cache warming (pre-load data)  → every hour                       │
│ • Subscription renewal check     → every day at midnight            │
│ • Stale ride cleanup             → every 2 minutes                  │
│   (rides stuck in REQUESTED for too long)                           │
└──────────────────────────────────────────────────────────────────────┘
```

---

## 3. @EnableScheduling — The On Switch

```java
// RideSharingApplication.java
@SpringBootApplication
@EnableAsync
@EnableScheduling     // ← Without this, ALL @Scheduled annotations are IGNORED
public class RideSharingApplication { ... }
```

Just like `@EnableAsync`, this is a **master switch**. Without it, Spring ignores every `@Scheduled` annotation — no error, no warning, the method simply never runs. A silent bug.

---

## 4. @Scheduled — Scheduling Strategies

Spring provides three ways to schedule a method:

### fixedRate (Our Choice)

```java
@Scheduled(fixedRate = 30000)   // Every 30,000 milliseconds = 30 seconds
public void calculateSurge() { ... }
```

```
Timeline with fixedRate = 30000ms:

  0s      30s     60s     90s     120s
  │───────│───────│───────│───────│
  ▼       ▼       ▼       ▼       ▼
  run     run     run     run     run

Key: The clock starts WHEN THE PREVIOUS RUN STARTED.
If task takes 5s, next run is at 30s mark (25s after previous finished).
If task takes 35s (longer than interval), next run starts IMMEDIATELY when previous finishes.
```

### fixedDelay

```java
@Scheduled(fixedDelay = 30000)   // 30 seconds AFTER the previous execution FINISHES
public void doWork() { ... }
```

```
Timeline with fixedDelay = 30000ms and task taking 5s:

  0s    5s         35s   40s        70s   75s
  │─────│──────────│─────│──────────│─────│
  ▼     ▲          ▼     ▲          ▼     ▲
  start end        start end        start end
        ├── 30s ──►│     ├── 30s ──►│

Key: The clock starts AFTER THE PREVIOUS RUN FINISHES.
Guarantees 30s gap between runs.
Use when tasks shouldn't overlap and you need a cool-down period.
```

### cron

```java
@Scheduled(cron = "0 0 2 * * ?")   // At 2:00 AM every day
public void nightlyBackup() { ... }
```

```
Cron format: second minute hour day-of-month month day-of-week

  "0 0 2 * * ?"     → At 2:00:00 AM every day
  "0 */5 * * * ?"   → Every 5 minutes
  "0 0 9 * * MON"   → Every Monday at 9 AM
  "0 0 0 1 * ?"     → First day of every month at midnight

Use for: time-specific tasks (reports, backups, batch jobs)
```

### WHY We Chose fixedRate for Surge

```
fixedRate:  Surge is recalculated at consistent intervals.
            Even if one calculation takes 5 seconds, the next
            starts at the 30-second mark — predictable, consistent.

fixedDelay: Would add 5s execution time + 30s delay = 35s intervals.
            During surge (high demand), we WANT frequent updates,
            not longer gaps.

cron:       Surge isn't time-of-day specific. It's demand-based.
            A cron schedule doesn't make sense here.
```

---

## 5. Scheduler Lifecycle — When Does It Start and Stop?

```
Application lifecycle:

  ┌─────────────────────────────────────────────────────────────────────────┐
  │  java -jar app.jar                                                     │
  │       │                                                                 │
  │       ▼                                                                 │
  │  Spring Boot starts                                                     │
  │       │                                                                 │
  │       ├── Load configuration (application-dev.yml)                     │
  │       ├── Create beans (services, controllers, repositories)           │
  │       ├── Run Flyway migrations                                        │
  │       ├── Connect to PostgreSQL, Redis, Kafka                          │
  │       ├── Start Tomcat (HTTP server)                                    │
  │       ├── Start Kafka consumers (@KafkaListener threads)              │
  │       │                                                                 │
  │       ├── @EnableScheduling: Start scheduler thread                    │
  │       │     └── Scan for @Scheduled methods                            │
  │       │     └── Register SurgePricingScheduler.calculateSurge()       │
  │       │     └── Start timer: run every 30 seconds                      │
  │       │                                                                 │
  │       └── Application READY                                            │
  │                                                                         │
  │  ───── App is running ──── Scheduler runs every 30s ─────             │
  │                                                                         │
  │  CTRL+C or kill signal received                                        │
  │       │                                                                 │
  │       ├── Stop accepting new HTTP requests                             │
  │       ├── Stop scheduler (no more runs after current one finishes)    │
  │       ├── Shut down Kafka consumers                                    │
  │       ├── Wait for async tasks to complete (60s max)                   │
  │       ├── Close database connections                                    │
  │       ├── Close Redis connection                                        │
  │       └── Application STOPPED                                          │
  └─────────────────────────────────────────────────────────────────────────┘
```

### Key Points

```
✓ Scheduler starts AUTOMATICALLY when the app starts
  (no manual trigger, no API call needed)

✓ Scheduler runs for the ENTIRE lifetime of the app
  (from startup to shutdown, every 30 seconds)

✓ Scheduler stops AUTOMATICALLY when the app stops
  (current run completes, no more new runs)

✓ Scheduler runs on a SINGLE thread by default
  (if you have 5 @Scheduled methods, they take turns on 1 thread)
  
✓ If the app crashes, scheduler dies with it
  (that's why we have TTL on surge values — they expire and self-heal)
```

---

## 6. Our Surge Pricing Scheduler — Complete Walkthrough

### WHAT is Surge Pricing?

When more riders want rides than drivers are available, prices go up. This:
1. **Discourages** low-priority rides (rider thinks "I'll wait for the price to drop")
2. **Encourages** drivers to come online (driver thinks "I can earn 2x right now!")
3. **Balances** supply and demand naturally

```
Normal times:  10 riders, 20 drivers → ratio 0.5 → surge 1.0x → ₹174 fare
Rush hour:     30 riders, 10 drivers → ratio 3.0 → surge 2.0x → ₹348 fare
                                       ↑                ↑
                                  demand > supply  price doubles
```

### The Full Code

```java
@Component
public class SurgePricingScheduler {

    private static final String SURGE_KEY_PREFIX = "pricing:surge:";
    private static final String RIDE_REQUESTS_KEY = "ride:pending-requests";
    private static final String DRIVER_LOCATIONS_KEY = "driver:locations";
    private static final long SURGE_TTL_SECONDS = 120;

    private final RedisTemplate<String, Object> redisTemplate;

    @Scheduled(fixedRate = 30000)   // Every 30 seconds
    public void calculateSurge() {
        try {
            long pendingRequests = countPendingRequests();   // Demand
            long onlineDrivers = countOnlineDrivers();       // Supply

            if (onlineDrivers == 0) {
                updateSurgeForAllTypes(2.5);   // No drivers → max surge
                return;
            }

            double ratio = (double) pendingRequests / onlineDrivers;
            double surge = calculateSurgeMultiplier(ratio);

            updateSurgeForAllTypes(surge);

        } catch (Exception e) {
            log.error("Failed to calculate surge pricing", e);
        }
    }
}
```

---

## 7. Step 1: Count Demand (Pending Ride Requests)

```java
private long countPendingRequests() {
    Long size = redisTemplate.opsForSet().size("ride:pending-requests");
    return size != null ? size : 0;
}
// Redis: SCARD "ride:pending-requests" → 15
```

### Where Does This Data Come From?

```
RideMatchingService.matchDriverForRide():
  → SADD "ride:pending-requests" "rideId"    (when ride starts matching)
  → SREM "ride:pending-requests" "rideId"    (when driver found or ride cancelled)

At any moment, this set contains IDs of rides actively searching for a driver.
SCARD counts them in O(1) time.

Example:
  Time 10:00 — 3 rides searching: {"5", "8", "12"}
  Time 10:01 — ride 5 matched, ride 15 added: {"8", "12", "15"}
  Scheduler reads at 10:01 → SCARD = 3
```

### Important: This is a Country-Wide Count

In our project, we count ALL pending requests across the entire system. This is a simplification.

---

## 8. Step 2: Count Supply (Online Drivers)

```java
private long countOnlineDrivers() {
    Long size = redisTemplate.opsForZSet().zCard("driver:locations");
    return size != null ? size : 0;
}
// Redis: ZCARD "driver:locations" → 8
```

### Why opsForZSet and Not opsForGeo?

```
"driver:locations" is a Redis GEO key.
GEO is built ON TOP of Sorted Sets.
ZCARD is a Sorted Set command that counts ALL members.
There's no GEOCARD command — we use ZCARD directly.

This works because every entry in the GEO set = one online driver.
When a driver goes offline → ZREM removes them.
When a driver gets busy → ZREM removes them.
ZCARD gives us the count of AVAILABLE drivers.
```

---

## 9. Step 3: Calculate Surge Multiplier

```java
double calculateSurgeMultiplier(double ratio) {
    if (ratio <= 1.0) return 1.0;   // More drivers than riders → no surge
    if (ratio <= 1.5) return 1.2;   // Slightly more demand → mild surge
    if (ratio <= 2.0) return 1.5;   // 2x demand → moderate surge
    if (ratio <= 3.0) return 2.0;   // 3x demand → high surge
    return 2.5;                      // Extreme demand → capped at 2.5x
}
```

### Surge Bracket Table

```
┌───────────────┬───────────────┬──────────────────────────┬──────────────┐
│ Demand/Supply │ Surge         │ Example                  │ Fare Impact  │
│ Ratio         │ Multiplier    │                          │              │
├───────────────┼───────────────┼──────────────────────────┼──────────────┤
│ 5/10 = 0.5    │ 1.0x          │ Quiet Sunday morning     │ ₹174 → ₹174 │
│ 10/10 = 1.0   │ 1.0x          │ Normal traffic           │ ₹174 → ₹174 │
│ 12/10 = 1.2   │ 1.2x          │ Light rain               │ ₹174 → ₹209 │
│ 15/10 = 1.5   │ 1.2x          │ Evening rush starting    │ ₹174 → ₹209 │
│ 20/10 = 2.0   │ 1.5x          │ Heavy rain + office time │ ₹174 → ₹261 │
│ 25/10 = 2.5   │ 2.0x          │ New Year's Eve           │ ₹174 → ₹348 │
│ 40/10 = 4.0   │ 2.5x (capped) │ Extreme demand           │ ₹174 → ₹435 │
└───────────────┴───────────────┴──────────────────────────┴──────────────┘
```

### WHY a 2.5x Cap?

Without a cap, surge could be 10x during a concert or match ending — riders would be furious. The cap balances business incentive with user trust. Real companies like Uber also cap surge (typically 5x-8x).

### Special Case: Zero Drivers

```java
if (onlineDrivers == 0) {
    updateSurgeForAllTypes(2.5);   // Max surge
    return;
}
```

If no drivers are online, we set max surge to **attract drivers**. "Hey drivers, prices are 2.5x right now — come online and earn more!" Division by zero is also avoided.

---

## 10. Step 4: Store Surge in Redis

```java
private void updateSurgeForAllTypes(double surge) {
    String[] vehicleTypes = {"AUTO", "BIKE", "SEDAN", "SUV"};
    for (String type : vehicleTypes) {
        String key = "pricing:surge:" + type;
        redisTemplate.opsForValue().set(key, surge, SURGE_TTL_SECONDS, TimeUnit.SECONDS);
        // Redis: SET "pricing:surge:SEDAN" 1.5 EX 120
    }
}
```

### What Happens in Redis

```
After scheduler runs with surge = 1.5:

  KEY: "pricing:surge:AUTO"    VALUE: 1.5    TTL: 120s
  KEY: "pricing:surge:BIKE"    VALUE: 1.5    TTL: 120s
  KEY: "pricing:surge:SEDAN"   VALUE: 1.5    TTL: 120s
  KEY: "pricing:surge:SUV"     VALUE: 1.5    TTL: 120s

After 30 seconds, scheduler runs again with surge = 1.2:
  All 4 keys updated to 1.2, TTL reset to 120s.

Each run OVERWRITES the previous value and RESETS the TTL.
```

### WHY Same Surge for All Vehicle Types?

This is a **simplification** in our project. In reality:
- SEDAN might have surge 2.0x (lots of sedan demand)
- AUTO might have surge 1.0x (plenty of autos available)

To do per-vehicle-type surge, we'd need separate demand/supply counts per vehicle type, which would require filtering the Redis Set by vehicle type.

---

## 11. Step 5: PricingService Reads the Surge

```java
// PricingService.java — called during every ride request
BigDecimal getCurrentSurge(String vehicleType) {
    String surgeKey = "pricing:surge:" + vehicleType;
    Object surgeValue = redisTemplate.opsForValue().get(surgeKey);
    // Redis: GET "pricing:surge:SEDAN" → 1.5

    if (surgeValue == null) {
        return BigDecimal.ONE;   // No surge data → default 1.0x
    }

    return BigDecimal.valueOf(((Number) surgeValue).doubleValue())
            .setScale(1, RoundingMode.HALF_UP);   // Round to 1 decimal
}
```

The PricingService doesn't calculate surge itself — it just READS whatever the scheduler wrote to Redis. Complete **separation of concerns**: scheduler calculates, pricing service consumes.

---

## 12. Complete Data Flow Diagram

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    SURGE PRICING — DATA FLOW                               │
│                                                                             │
│  WRITES TO REDIS                        READS FROM REDIS                   │
│  ──────────────                         ────────────────                   │
│                                                                             │
│  Driver goes online:                    SurgePricingScheduler:              │
│    DriverLocationService                  @Scheduled(fixedRate = 30000)     │
│    GEOADD "driver:locations"               │                               │
│    lng lat "driverId"                      ├─ SCARD "ride:pending-requests" │
│         │                                  │  → demand = 15               │
│         └──── count ──────────────────►    ├─ ZCARD "driver:locations"     │
│                                            │  → supply = 8                │
│  Ride starts matching:                     ├─ ratio = 15/8 = 1.875       │
│    RideMatchingService                     ├─ surge = 1.5x               │
│    SADD "ride:pending-requests"            │                               │
│    "rideId"                                └─ SET "pricing:surge:SEDAN"    │
│         │                                     1.5 EX 120                  │
│         └──── count ──────────────────►           │                       │
│                                                    │                       │
│  Ride matched/cancelled:                           │ reads                 │
│    RideMatchingService                             ▼                       │
│    SREM "ride:pending-requests"            PricingService:                 │
│    "rideId"                                  GET "pricing:surge:SEDAN"     │
│                                              → 1.5                        │
│  Driver assigned (busy):                     → multiply into fare          │
│    DriverLocationService                     → ₹174 × 1.5 = ₹261         │
│    ZREM "driver:locations"                                                 │
│    "driverId"                                                              │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘

                    ┌──────────────────────────┐
                    │        TIMELINE          │
                    ├──────────────────────────┤
                    │ 0s   Scheduler runs      │
                    │      surge = 1.5x        │
                    │                          │
                    │ 5s   Rider requests ride  │
                    │      PricingService reads │
                    │      surge = 1.5x        │
                    │      fare = ₹261         │
                    │                          │
                    │ 30s  Scheduler runs again │
                    │      3 more drivers online│
                    │      surge = 1.2x        │
                    │                          │
                    │ 35s  Another rider        │
                    │      fare = ₹209 (lower!) │
                    │                          │
                    │ 60s  Scheduler runs again │
                    │      demand dropped       │
                    │      surge = 1.0x        │
                    └──────────────────────────┘
```

---

## 13. Safety: What If the Scheduler Crashes?

```
SCENARIO: App crashes or scheduler throws an exception repeatedly.

Without TTL:
  "pricing:surge:SEDAN" = 2.5    ← stays FOREVER
  Riders pay 2.5x surge on a quiet Sunday morning
  Nobody notices until customers complain → terrible UX

With TTL (120 seconds):
  "pricing:surge:SEDAN" = 2.5 EX 120
  After 2 minutes, Redis auto-deletes the key
  PricingService reads: null → returns 1.0x (no surge)
  System SELF-HEALS

  Timeline:
    0s    Scheduler writes surge 2.5x (TTL 120s)
    30s   Scheduler CRASHES
    60s   TTL counting down... surge still 2.5x (90s remaining)
    90s   TTL counting down... surge still 2.5x (30s remaining)
    120s  TTL expires → Redis deletes key → surge defaults to 1.0x
    
  Worst case: riders overpay for 2 minutes. Then prices normalize.
```

### The try-catch Safety Net

```java
@Scheduled(fixedRate = 30000)
public void calculateSurge() {
    try {
        // ... all surge logic
    } catch (Exception e) {
        log.error("Failed to calculate surge pricing", e);
        // Exception is caught → scheduler doesn't crash
        // Next run in 30 seconds will try again
    }
}
```

Without the try-catch, an unhandled exception would **stop the scheduler entirely** — no more surge updates ever (until app restart).

---

## 14. How Uber/Rapido Handle Surge in Production

Our scheduler is a simplified version. Here's how real companies do it:

```
OUR APPROACH (Simple):
  ✓ Single scheduler, global demand/supply count
  ✓ Same surge for all vehicle types
  ✓ Same surge for all locations (country-wide)
  ✓ Fixed brackets (1.0, 1.2, 1.5, 2.0, 2.5)

UBER'S APPROACH (Production):
  ✓ Geo-specific surge zones (city divided into hexagonal cells)
  ✓ Per-vehicle-type surge (SEDAN may have different surge than AUTO)
  ✓ ML-based pricing (not fixed brackets — dynamic models)
  ✓ Demand prediction (forecasts surge BEFORE it happens using historical data)
  ✓ Multiple data sources (weather API, events calendar, traffic data)
  ✓ A/B testing different surge algorithms
  ✓ Surge smoothing (gradual increase, not sudden jumps)
  ✓ Max surge caps per city/region (regulatory compliance)
```

### Geo-Specific Surge (How Uber Does It)

```
Our approach (country-wide):
  ┌──────────────────────────────┐
  │ INDIA                        │
  │ 15 pending / 8 drivers       │
  │ Surge: 1.5x EVERYWHERE      │
  └──────────────────────────────┘

Uber's approach (zone-specific):
  ┌──────────┬──────────┬──────────┐
  │ Zone A   │ Zone B   │ Zone C   │
  │ (Airport)│ (City)   │ (Suburb) │
  │ 20/5     │ 8/15     │ 2/10     │
  │ Surge:   │ Surge:   │ Surge:   │
  │ 2.5x     │ 1.0x     │ 1.0x    │
  └──────────┴──────────┴──────────┘

  Uber divides the city into H3 hexagonal cells (about 1-2 km each).
  Each cell has its own demand/supply count and its own surge.
  A rider at the airport sees 2.5x, rider in the suburbs sees 1.0x.
```

### How Would We Make Ours Geo-Specific?

```
1. Instead of one global Set "ride:pending-requests",
   use zone-specific sets: "ride:pending:zone-hyderabad-central"

2. Instead of counting ALL drivers with ZCARD,
   use GEOSEARCH to count drivers within each zone

3. Calculate surge per zone, store: "pricing:surge:SEDAN:zone-hyderabad-central"

4. When rider requests a ride, look up their zone's surge value

This is a good follow-up project to enhance the platform.
```

---

## 15. fixedRate vs fixedDelay vs cron

### Quick Reference

```
┌──────────────┬──────────────────────────────┬──────────────────────────────┐
│ Strategy     │ When                         │ Use Case                     │
├──────────────┼──────────────────────────────┼──────────────────────────────┤
│ fixedRate    │ Every N ms from START        │ Regular polling, metrics,    │
│              │ of previous run              │ surge pricing                │
│              │                              │ (consistent intervals)       │
├──────────────┼──────────────────────────────┼──────────────────────────────┤
│ fixedDelay   │ N ms after previous run      │ API polling, cleanup tasks   │
│              │ FINISHES                     │ (guaranteed gap between runs)│
├──────────────┼──────────────────────────────┼──────────────────────────────┤
│ cron         │ At specific times            │ Nightly backups, daily       │
│              │ (like Linux crontab)         │ reports, monthly billing     │
└──────────────┴──────────────────────────────┴──────────────────────────────┘
```

### Visual Comparison (task takes 5 seconds)

```
fixedRate(30000):
  0s────5s                   30s───35s                  60s───65s
  [RUN 1]                    [RUN 2]                    [RUN 3]
  │←──── 30s interval ──────→│←──── 30s interval ──────→│

fixedDelay(30000):
  0s────5s                        35s───40s                        70s───75s
  [RUN 1]                         [RUN 2]                          [RUN 3]
         │←──── 30s delay ────────→│         │←──── 30s delay ────→│

fixedRate: Next run at 30s mark (25s after end)
fixedDelay: Next run 30s after end (35s from start)
```

---

## 16. Scheduler Threading

```
By default, Spring uses a SINGLE thread for ALL @Scheduled methods.

If you have:
  @Scheduled(fixedRate = 30000) calculateSurge()       → takes 2s
  @Scheduled(fixedRate = 60000) cleanupExpiredRides()   → takes 10s

Both share ONE thread:
  Thread: ──[calculateSurge]──[cleanupExpiredRides──────────]──[calculateSurge]──

If cleanupExpiredRides takes 10s, calculateSurge is DELAYED.
Not a problem with just 1 scheduled task (our project), but
could be an issue with many scheduled tasks.

FIX (if needed):
  @Configuration
  public class SchedulerConfig implements SchedulingConfigurer {
      @Override
      public void configureTasks(ScheduledTaskRegistrar registrar) {
          registrar.setScheduler(Executors.newScheduledThreadPool(5));
      }
  }
  Now 5 threads handle scheduled tasks — no blocking.
```

---

## 17. Key Files in Our Codebase

| File | Purpose |
|------|---------|
| `RideSharingApplication.java` | `@EnableScheduling` — activates scheduled tasks |
| `SurgePricingScheduler.java` | `@Scheduled(fixedRate=30000)` — surge calculation |
| `PricingService.java` | Reads surge from Redis during fare estimation |
| `RideMatchingService.java` | Writes to `ride:pending-requests` Set (demand data) |
| `DriverLocationService.java` | Manages `driver:locations` GEO set (supply data) |
| `RedisConfig.java` | RedisTemplate used by all Redis operations |

---

## 18. Interview One-Liners

- **@Scheduled**: Spring annotation that runs a method automatically at regular intervals. Requires `@EnableScheduling` on the main class. Supports `fixedRate` (from start), `fixedDelay` (from end), and `cron` (specific times).

- **Scheduler Lifecycle**: Starts automatically when Spring Boot starts (no manual trigger). Runs for the entire lifetime of the application. Stops when the application shuts down. Uses a single thread by default.

- **fixedRate vs fixedDelay**: `fixedRate` measures intervals from the START of the previous run (consistent timing). `fixedDelay` measures from the END (guaranteed gap). Use `fixedRate` for polling/metrics, `fixedDelay` when tasks shouldn't overlap.

- **Surge Pricing**: Dynamically adjusts fares based on demand/supply ratio. Our scheduler counts pending ride requests (demand from Redis Set) and online drivers (supply from Redis GEO) every 30 seconds. Ratio maps to surge brackets (1.0x to 2.5x cap). Stored in Redis with TTL for self-healing if the scheduler crashes.

- **TTL Safety Net**: Surge values are stored with 120-second TTL. If the scheduler crashes, stale surge values expire automatically, and the system defaults to 1.0x (no surge). Prevents riders from being overcharged indefinitely.

- **Our Approach vs Uber**: We use global demand/supply counts (whole system). Uber uses geo-specific zones (H3 hexagonal cells, ~1-2km each) with per-zone, per-vehicle-type surge. Uber also uses ML-based dynamic pricing, weather/event data, and demand prediction. Our approach is a solid foundation that could be enhanced with zone-specific calculations.

- **try-catch in Scheduler**: Without it, an unhandled exception stops the scheduler permanently (no more runs until app restart). The try-catch ensures the scheduler recovers on the next 30-second cycle.

- **Single Thread Default**: All `@Scheduled` methods share one thread by default. If one task blocks, others are delayed. For multiple scheduled tasks, configure a `ScheduledThreadPool` with more threads.
