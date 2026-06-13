# Module 5: Async Programming — @Async, CompletableFuture & Thread Pools

> Everything about asynchronous programming in Spring Boot — why we need it, how @Async works, what CompletableFuture does, how thread pools are configured, and every place we use async in our ride-sharing platform. Written so you can read this 20 years from now and still understand it.

---

## Table of Contents

1. [The Problem: Blocking the User](#1-the-problem-blocking-the-user)
2. [What is Async?](#2-what-is-async)
3. [Threads — The Foundation](#3-threads--the-foundation)
4. [Thread Pool — Why Not Create Threads Manually?](#4-thread-pool--why-not-create-threads-manually)
5. [Our Thread Pool Configuration (AsyncConfig.java)](#5-our-thread-pool-configuration-asyncconfigjava)
6. [@EnableAsync — The On Switch](#6-enableasync--the-on-switch)
7. [@Async — Making a Method Run in Background](#7-async--making-a-method-run-in-background)
8. [How @Async Works Behind the Scenes (Proxy)](#8-how-async-works-behind-the-scenes-proxy)
9. [CompletableFuture — Async Pipeline](#9-completablefuture--async-pipeline)
10. [Our Usage 1: Driver Matching (RideMatchingService)](#10-our-usage-1-driver-matching-ridematchingservice)
11. [Our Usage 2: Notifications (NotificationService)](#11-our-usage-2-notifications-notificationservice)
12. [Common Pitfalls](#12-common-pitfalls)
13. [CallerRunsPolicy — Backpressure](#13-callerrunspolicy--backpressure)
14. [Graceful Shutdown](#14-graceful-shutdown)
15. [Async Exception Handling](#15-async-exception-handling)
16. [Sync vs Async — Visual Comparison](#16-sync-vs-async--visual-comparison)
17. [Key Files in Our Codebase](#17-key-files-in-our-codebase)
18. [Interview One-Liners](#18-interview-one-liners)

---

## 1. The Problem: Blocking the User

When a rider requests a ride, we need to:
1. Save the ride to database (~10ms)
2. Search for nearby drivers (~100ms)
3. Validate each driver (~50ms per driver)
4. Assign driver (~10ms)
5. Send notification (~50ms)

Total: ~300-500ms minimum. Could be 5-15 seconds with retries.

```
WITHOUT ASYNC:
  Rider taps "Book Ride"
       │
       ├─ Save ride            (10ms)
       ├─ Search drivers       (100ms)
       ├─ Validate driver 1    (50ms)
       ├─ Validate driver 2    (50ms)
       ├─ Assign driver        (10ms)
       ├─ Send notification    (50ms)
       │
       └─ HTTP response         ← Rider waited 270ms+ (or 15s with retries)
          "Here's your driver"

WITH ASYNC:
  Rider taps "Book Ride"
       │
       ├─ Save ride            (10ms)
       ├─ Start background task → matchDriverForRide()  ← fires and forgets
       │
       └─ HTTP response         ← Rider waited only 15ms!
          "Searching for drivers..."

       Meanwhile in background thread:
       ├─ Search drivers       (100ms)
       ├─ Validate driver      (50ms)
       ├─ Assign driver        (10ms)
       └─ Send WebSocket: "Driver Found!" to rider's phone
```

---

## 2. What is Async?

**Synchronous** (sync) = do one thing, wait for it to finish, then do the next thing.
**Asynchronous** (async) = start a task, don't wait, continue doing other things. Get notified when the task finishes.

```
SYNC (like standing in a queue at a restaurant):
  You order → wait at counter → get food → eat → order dessert → wait → eat

ASYNC (like a waiter-based restaurant):
  You order food → waiter goes to kitchen → you chat with friends
  Waiter brings food → you order dessert → waiter goes to kitchen
  You keep chatting → waiter brings dessert

  YOU never waited. The WAITER (background thread) did the waiting.
```

---

## 3. Threads — The Foundation

A **thread** is a lightweight unit of execution. Your CPU can run multiple threads simultaneously (one per core, more with hyperthreading).

```
Single-threaded app:
  Thread 1: ─── Request A ───── Request B ───── Request C ─────
            (B waits for A to finish, C waits for B)

Multi-threaded app (Tomcat default = 200 threads):
  Thread 1: ─── Request A ─────────────
  Thread 2: ─── Request B ─────────────
  Thread 3: ─── Request C ─────────────
  (All processed simultaneously!)

Our async setup adds another layer:
  HTTP Thread:  ─── Request A (save ride) ─── Return response ─── Request D ───
  Async Thread: ──────────── Match driver (background) ── Send notification ────
```

Each HTTP request in Spring Boot gets its own thread from Tomcat's pool. When we use `@Async`, we spawn work onto a DIFFERENT thread pool — our custom async pool.

---

## 4. Thread Pool — Why Not Create Threads Manually?

```java
// BAD: Creating a new thread for every task
new Thread(() -> matchDriverForRide(ride)).start();

// WHY BAD?
// 1. Thread creation is EXPENSIVE (~1ms + memory allocation)
// 2. No limit — 10,000 ride requests = 10,000 threads → OutOfMemoryError
// 3. No reuse — thread dies after task, wasted
// 4. No monitoring — you can't track or name these threads
// 5. No graceful shutdown — threads may be killed mid-task

// GOOD: Using a thread pool
asyncExecutor.submit(() -> matchDriverForRide(ride));

// WHY GOOD?
// 1. Threads are PRE-CREATED and REUSED (no creation overhead)
// 2. LIMITED — max 10 threads, won't exhaust memory
// 3. QUEUED — if all threads busy, tasks wait in a queue
// 4. NAMED — "async-pool-1" appears in logs for debugging
// 5. GRACEFUL SHUTDOWN — waits for running tasks to complete
```

### The Pool Analogy

```
Thread Pool = Swimming Pool with limited lanes

  5 lanes always open (core pool size = 5)
  Can open up to 10 lanes for rush hour (max pool size = 10)
  500-person waiting area (queue capacity = 500)

  Person arrives → lane available? → swim immediately
  All lanes busy? → wait in queue
  Queue full too? → CallerRunsPolicy: person swims in the hallway
                    (calling thread does the work itself)
```

---

## 5. Our Thread Pool Configuration (AsyncConfig.java)

```java
@Configuration
public class AsyncConfig implements AsyncConfigurer {

    private static final int CORE_POOL_SIZE = 5;       // Always alive
    private static final int MAX_POOL_SIZE = 10;       // Can grow to this
    private static final int QUEUE_CAPACITY = 500;     // Waiting room
    private static final String THREAD_NAME_PREFIX = "async-pool-";

    @Bean(name = "asyncExecutor")
    @Override
    public Executor getAsyncExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(CORE_POOL_SIZE);
        executor.setMaxPoolSize(MAX_POOL_SIZE);
        executor.setQueueCapacity(QUEUE_CAPACITY);
        executor.setThreadNamePrefix(THREAD_NAME_PREFIX);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        executor.initialize();
        return executor;
    }
}
```

### Each Setting Explained

```
CORE_POOL_SIZE = 5
  5 threads are created when the app starts and stay alive FOREVER.
  Ready to pick up tasks instantly (no thread creation delay).
  Sized for our typical workload: ~5 concurrent async operations.

MAX_POOL_SIZE = 10
  During peak hours (surge), pool can grow to 10 threads.
  Extra threads are created ONLY when the queue is full.
  After idle timeout, extra threads are destroyed back to 5.

  Flow: task arrives → core thread available? use it
        → core threads all busy? → add to queue
        → queue full? → create thread up to max (10)
        → max reached AND queue full? → rejection policy

QUEUE_CAPACITY = 500
  If all 5 core threads are busy, tasks wait here.
  FIFO (first-in, first-out).
  500 is generous — prevents OutOfMemoryError from unbounded queues.

THREAD_NAME_PREFIX = "async-pool-"
  In logs: "async-pool-3 — Starting driver matching for ride 7"
  Without this: "Thread-47" — impossible to debug.

CallerRunsPolicy
  If pool AND queue are both full (extremely rare):
  The calling thread (HTTP thread) runs the task itself.
  Slower for that one request, but NO task is ever lost.

WaitForTasksToCompleteOnShutdown = true
  When app stops, wait for running tasks to finish.
  Don't kill tasks mid-execution (e.g., mid-payment-creation).

AwaitTerminationSeconds = 60
  Wait up to 60 seconds for tasks to finish during shutdown.
  After 60s, force-kill remaining tasks.
```

---

## 6. @EnableAsync — The On Switch

```java
// RideSharingApplication.java
@SpringBootApplication
@EnableAsync          // ← Without this, ALL @Async annotations are IGNORED
@EnableScheduling
public class RideSharingApplication { ... }
```

`@EnableAsync` tells Spring: "Scan for `@Async` annotations and create proxies that run those methods on the async thread pool."

Without `@EnableAsync`, every `@Async` method runs on the **calling thread** — synchronously, as if the annotation didn't exist. No error, no warning — a silent bug.

---

## 7. @Async — Making a Method Run in Background

### Basic Usage

```java
@Async("asyncExecutor")
public void matchDriverForRide(Ride ride) {
    // This method runs on a thread from "asyncExecutor" pool
    // The caller does NOT wait for this to finish
    // The caller's thread is freed immediately
}
```

### The "asyncExecutor" Name

```java
@Async("asyncExecutor")    // Use our custom pool (recommended)
@Async                     // Use Spring's default pool (not recommended)
```

By specifying `"asyncExecutor"`, we explicitly tell Spring to use our custom `ThreadPoolTaskExecutor` bean. If you just write `@Async` without a name, it uses the default (SimpleAsyncTaskExecutor — creates a new thread per task, unbounded, no reuse — bad!).

### Rules for @Async

```
RULE 1: The method must be PUBLIC
  ✓ public void matchDriver(...)     → Spring proxy can intercept
  ✗ private void matchDriver(...)    → Spring proxy CANNOT intercept → runs sync

RULE 2: Must be called from a DIFFERENT class
  ✓ rideMatchingService.matchDriverForRide(ride)  → called from RideService → async works
  ✗ this.matchDriverForRide(ride)                 → self-call → proxy bypassed → runs sync

RULE 3: Return type must be void or Future/CompletableFuture
  ✓ public void doSomething()                     → fire-and-forget
  ✓ public CompletableFuture<Result> doSomething() → caller can wait for result
  ✗ public String doSomething()                   → can't return to caller (already moved on)
```

---

## 8. How @Async Works Behind the Scenes (Proxy)

Spring uses a **proxy pattern**. When you inject `RideMatchingService`, you don't get the actual object — you get a proxy wrapper.

```
What you think happens:
  RideService → calls → RideMatchingService.matchDriverForRide()

What ACTUALLY happens:
  RideService → calls → Proxy(RideMatchingService).matchDriverForRide()
                              │
                              ▼
                         Proxy intercepts the call:
                         "I see @Async annotation."
                         "Submit this task to asyncExecutor thread pool."
                         "Return immediately to caller."
                              │
                              ▼
                         asyncExecutor picks up the task:
                         "async-pool-1 starts running matchDriverForRide()"

  Meanwhile, RideService's thread has already moved on to:
  return toResponseDto(ride);  // Returns response to rider
```

### WHY Self-Calls Don't Work

```java
// Inside RideMatchingService:
@Async("asyncExecutor")
public void matchDriverForRide(Ride ride) { ... }

public void someOtherMethod(Ride ride) {
    this.matchDriverForRide(ride);  // ← SELF-CALL → bypasses proxy → runs SYNC!
}
```

`this` refers to the actual object, not the proxy. The proxy is only involved when the call comes from OUTSIDE the class.

```
External call:   RideService → Proxy → @Async intercepted → background thread ✓
Self call:       RideMatchingService → this → NO proxy → same thread ✗
```

---

## 9. CompletableFuture — Async Pipeline

`CompletableFuture` is Java's API for composing asynchronous operations. Think of it as a **promise**: "I will do this work, and when I'm done, do the next thing."

### Basic Methods

```java
CompletableFuture
    .supplyAsync(() -> doWork(), executor)    // Step 1: run in background, return result
    .thenApply(result -> transform(result))  // Step 2: transform the result
    .thenAccept(result -> consume(result))   // Step 3: consume result (no return)
    .exceptionally(ex -> handleError(ex));   // Error handler (if any step fails)
```

### Method Reference

```
supplyAsync(supplier)     → Run supplier in background, return result
thenApply(function)       → Transform result (like .map() in streams)
thenAccept(consumer)      → Consume result, return nothing
thenRun(runnable)         → Run something after, ignoring result
exceptionally(function)   → Handle errors (like catch block)
thenCompose(function)     → Chain another CompletableFuture
```

### Analogy

```
CompletableFuture is like ordering food delivery:

  supplyAsync: "Start preparing my food" (kitchen starts cooking)
  thenApply:   "When food is ready, add extra sauce" (transform)
  thenAccept:  "When everything is ready, deliver to my door" (consume)
  exceptionally: "If anything goes wrong, refund me" (error handling)

You don't wait at the restaurant. You place the order and go home.
Each step automatically triggers when the previous one completes.
```

---

## 10. Our Usage 1: Driver Matching (RideMatchingService)

```java
@Async("asyncExecutor")
public void matchDriverForRide(Ride ride) {
    // Track as pending request (for surge calculation)
    redisTemplate.opsForSet().add("ride:pending-requests", ride.getId().toString());

    CompletableFuture
            .supplyAsync(() -> attemptMatching(ride), asyncExecutor)  // STEP 1
            .thenAccept(matched -> {                                    // STEP 2
                redisTemplate.opsForSet().remove("ride:pending-requests", ride.getId().toString());
                if (!matched) cancelRideNoDriverFound(ride);
            })
            .exceptionally(ex -> {                                      // ERROR HANDLER
                redisTemplate.opsForSet().remove("ride:pending-requests", ride.getId().toString());
                cancelRideNoDriverFound(ride);
                return null;
            });
}
```

### Traced Step by Step

```
HTTP Thread (http-nio-8080-exec-1):
  requestRide() → save ride → kafkaTemplate.send() → matchDriverForRide()
                                                            │
                                                    @Async intercepts
                                                    submits to asyncExecutor
                                                            │
  return toResponseDto(ride) ← HTTP thread is FREE          │
  ← Response sent to rider: "Searching..."                  │
                                                            │
Async Thread (async-pool-1):                                │
  matchDriverForRide() starts ◄─────────────────────────────┘
       │
       ├── SADD "ride:pending-requests" "1"
       │
       ├── CompletableFuture.supplyAsync:
       │       attemptMatching(ride)
       │       ├── GEOSEARCH drivers within 5 km
       │       ├── Validate each driver in PostgreSQL
       │       └── assignDriver(ride, driver3) → return true
       │
       ├── thenAccept(true):
       │       SREM "ride:pending-requests" "1"
       │       matched=true → do NOT cancel
       │
       └── Done! async-pool-1 is returned to pool, ready for next task.
```

---

## 11. Our Usage 2: Notifications (NotificationService)

```java
@Async("asyncExecutor")
public void notifyRiderDriverAccepted(Long riderId, Long rideId, Long driverId) {
    NotificationMessage notification = NotificationMessage.builder()
            .type("RIDE_ACCEPTED")
            .title("Driver Found!")
            .message("A driver has accepted your ride and is on the way.")
            .build();

    messagingTemplate.convertAndSend("/topic/rider/" + riderId, notification);
}
```

### WHY Async for Notifications?

```
The Kafka consumer (RideEventConsumer) calls this method.
If notification delivery takes 200ms (network delay to WebSocket):

WITHOUT @Async:
  Kafka consumer thread is BLOCKED for 200ms
  → Can't process next Kafka message
  → Kafka messages pile up
  → System slows down

WITH @Async:
  Kafka consumer submits notification to async pool → returns immediately
  → Kafka consumer processes next message instantly
  → Notification is sent in background
  → High throughput maintained
```

---

## 12. Common Pitfalls

### Pitfall 1: Self-Invocation

```java
// BROKEN — @Async is IGNORED because of self-call
public class MyService {
    @Async
    public void asyncMethod() { ... }

    public void callerMethod() {
        this.asyncMethod();  // ← Runs SYNCHRONOUSLY! No proxy interception.
    }
}

// FIX: Call from a different class
public class CallerService {
    @Autowired MyService myService;

    public void callerMethod() {
        myService.asyncMethod();  // ← Runs ASYNCHRONOUSLY ✓
    }
}
```

### Pitfall 2: No Return Type for Results

```java
// BROKEN — caller can't get the result
@Async
public String doWork() { return "result"; }  // String is returned to nowhere

// FIX — use CompletableFuture
@Async
public CompletableFuture<String> doWork() {
    return CompletableFuture.completedFuture("result");
}
```

### Pitfall 3: Silent Exception Swallowing

```java
// DANGEROUS — exception vanishes into the void
@Async
public void doWork() {
    throw new RuntimeException("Something broke!");
    // No one ever knows this happened!
}

// FIX — we configured AsyncUncaughtExceptionHandler in AsyncConfig:
@Override
public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
    return (throwable, method, params) ->
            log.error("Async exception in '{}': {}", method.getName(), throwable.getMessage());
}
```

### Pitfall 4: Missing @EnableAsync

```java
// Without @EnableAsync on the main class:
@Async
public void doWork() { }  // Runs SYNCHRONOUSLY. No error. No warning. Silent bug.
```

---

## 13. CallerRunsPolicy — Backpressure

When the thread pool AND queue are both full:

```
Scenario: 10 threads busy + 500 tasks in queue + new task arrives

REJECTION POLICIES:

  AbortPolicy (default):
    THROWS RejectedExecutionException
    Task is LOST → dangerous for ride matching

  DiscardPolicy:
    Silently drops the task → LOST → dangerous

  DiscardOldestPolicy:
    Drops the OLDEST task in queue → also dangerous

  CallerRunsPolicy (OUR CHOICE):
    The calling thread runs the task ITSELF
    → HTTP thread runs matchDriverForRide() synchronously
    → That one rider waits longer (270ms instead of 15ms)
    → BUT: no task is EVER lost
    → AND: natural backpressure — caller slows down, reducing incoming rate
```

```
Normal operation:
  HTTP Thread → submit to pool → return immediately → handle next request
  Pool Thread → matchDriverForRide() (background)

Pool exhausted (CallerRunsPolicy kicks in):
  HTTP Thread → submit to pool → POOL FULL → HTTP Thread runs task itself
  HTTP Thread → matchDriverForRide() → takes 270ms → then handles next request
  → Automatically slows down incoming requests (backpressure)
  → Pool has time to catch up
  → Self-healing
```

---

## 14. Graceful Shutdown

```java
executor.setWaitForTasksToCompleteOnShutdown(true);
executor.setAwaitTerminationSeconds(60);
```

```
WITHOUT graceful shutdown:
  App stops → all threads KILLED immediately
  → Driver matching mid-way? Ride stuck as REQUESTED forever.
  → Notification mid-send? Rider never gets "Driver Found!"

WITH graceful shutdown:
  App stops
  → Pool stops accepting NEW tasks
  → Waits up to 60 seconds for RUNNING tasks to finish
  → After 60s, force-terminates remaining tasks
  → Clean exit
```

---

## 15. Async Exception Handling

```java
// AsyncConfig.java
@Override
public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
    return (throwable, method, params) ->
            log.error("Async exception in method '{}': {}",
                    method.getName(), throwable.getMessage(), throwable);
}
```

```
For VOID @Async methods:
  Exception occurs → AsyncUncaughtExceptionHandler catches it → logs it
  Without handler: exception is SWALLOWED (lost forever, no log, no trace)

For CompletableFuture @Async methods:
  Exception occurs → .exceptionally() block catches it
  You handle it explicitly in the pipeline:
  
  CompletableFuture
      .supplyAsync(() -> riskyWork())
      .exceptionally(ex -> {
          log.error("Failed: {}", ex.getMessage());
          fallbackAction();
          return null;
      });
```

---

## 16. Sync vs Async — Visual Comparison

```
SYNCHRONOUS (single thread does everything):

  Thread 1: ──[Save Ride]──[Search Drivers]──[Validate]──[Assign]──[Notify]──[Response]──
                                                                                ↑
                                                                          Rider waits
                                                                          for ALL steps


ASYNCHRONOUS (work split across threads):

  HTTP Thread:  ──[Save Ride]──[Submit async task]──[Response]──[Handle next request]──
                                      │                 ↑
                                      │           Rider gets response
                                      │           after just Save (fast!)
                                      ▼
  Async Thread: ──────────[Search Drivers]──[Validate]──[Assign]──[Notify via WebSocket]──
                                                                         │
                                                                    Rider gets
                                                                    "Driver Found!"
                                                                    on their phone
```

---

## 17. Key Files in Our Codebase

| File | Purpose |
|------|---------|
| `RideSharingApplication.java` | `@EnableAsync` — activates async support |
| `AsyncConfig.java` | Thread pool config: 5 core, 10 max, 500 queue, CallerRunsPolicy |
| `RideMatchingService.java` | `@Async` + CompletableFuture for driver matching |
| `NotificationService.java` | `@Async` for WebSocket notification delivery |
| `RideService.java` | Calls `rideMatchingService.matchDriverForRide()` (triggers async) |
| `RideEventConsumer.java` | Kafka consumer that calls async notification methods |

---

## 18. Interview One-Liners

- **@Async**: Spring annotation that makes a method execute on a separate thread from a configured thread pool. The calling thread returns immediately without waiting. Requires `@EnableAsync` on the main class and must be called from a different class (not self-invocation).

- **CompletableFuture**: Java's API for composing asynchronous operations. `supplyAsync()` runs work in background, `thenAccept()` processes the result, `exceptionally()` handles errors. Like a promise chain — each step triggers when the previous completes.

- **Thread Pool**: A pre-created set of reusable threads managed by `ThreadPoolTaskExecutor`. Avoids expensive thread creation per task, limits resource usage, provides named threads for debugging, and supports graceful shutdown. Core threads are always alive; pool grows to max under load.

- **CallerRunsPolicy**: Rejection policy when thread pool and queue are both full. Instead of dropping the task, the calling thread runs it synchronously. Provides natural backpressure — slows down the caller, giving the pool time to catch up. No task is ever lost.

- **Why Async for Driver Matching**: HTTP response should return in <500ms ("Searching for drivers..."). Driver matching with retries can take 5-15 seconds. @Async runs matching on a background thread while the rider gets an instant response. WebSocket notifies the rider when a driver is found.

- **Self-Invocation Pitfall**: Calling an @Async method from within the same class bypasses Spring's proxy, causing the method to run synchronously on the calling thread. Always call @Async methods from a different bean.

- **Graceful Shutdown**: `WaitForTasksToCompleteOnShutdown(true)` ensures running async tasks finish before the application stops. `AwaitTerminationSeconds(60)` sets the maximum wait time. Prevents tasks from being killed mid-execution (e.g., mid-payment creation).

- **AsyncUncaughtExceptionHandler**: Catches exceptions from void @Async methods that would otherwise be silently swallowed. Critical for debugging — logs the method name, exception message, and full stack trace.
