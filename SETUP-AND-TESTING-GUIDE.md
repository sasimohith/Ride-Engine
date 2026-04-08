# Ride-Sharing Platform — Setup & Manual Testing Guide

## STEP 1: Install Docker Desktop

Docker is the ONLY thing you need to install. It runs PostgreSQL, Redis, Kafka, Prometheus, and Grafana as containers on your machine.

### What is Docker? (Quick Explanation)
Think of Docker as a "mini virtual machine" manager. Instead of installing PostgreSQL directly on Windows (dealing with installers, PATH, services), you just say "docker, run PostgreSQL" and it downloads a pre-configured image and runs it in an isolated container.

### Install Docker Desktop for Windows

1. Go to: https://www.docker.com/products/docker-desktop/
2. Click "Download for Windows"
3. Run the installer (accept defaults)
4. **IMPORTANT**: During installation, ensure "Use WSL 2" is checked
5. Restart your computer when prompted
6. After restart, open Docker Desktop — wait for the whale icon in taskbar to say "Docker Desktop is running"

### Verify Installation
Open PowerShell and run:
```powershell
docker --version
docker compose version
```
You should see version numbers. If you see errors, make sure Docker Desktop is running (check the whale icon in your system tray).

---

## STEP 2: Understand What Each Service Does

Before starting, here's what each container in our docker-compose.yml does:

```
┌──────────────────────────────────────────────────────────────┐
│  docker-compose.yml → Starts 6 containers                   │
├──────────────────────────────────────────────────────────────┤
│                                                              │
│  1. PostgreSQL (port 5432)                                   │
│     └── Our main database. Stores: users, rides, payments,   │
│         ratings, vehicles, documents, fare_rules             │
│     └── Think of it as a big Excel file with relationships   │
│                                                              │
│  2. Redis (port 6379)                                        │
│     └── In-memory speed store. Used for:                     │
│         • Driver locations (GEO — find within 5km)           │
│         • Fare rule cache (avoid hitting DB every time)      │
│         • Surge multipliers (calculated every 30 sec)        │
│         • Pending ride request counts                        │
│     └── Think of it as a super-fast sticky notes board       │
│                                                              │
│  3. Zookeeper (port 2181)                                    │
│     └── Kafka's coordinator. Manages Kafka broker metadata.  │
│     └── You never interact with it directly — Kafka needs it │
│                                                              │
│  4. Kafka (port 9092)                                        │
│     └── Event streaming. When a ride completes:              │
│         → Ride module publishes "ride-completed" event       │
│         → Payment module consumes it → creates payment       │
│         → Notification module consumes it → sends WebSocket  │
│     └── Think of it as a shared noticeboard between modules  │
│                                                              │
│  5. Prometheus (port 9090)                                   │
│     └── Metrics collector. Every 15 seconds, it asks our     │
│         app "how many requests? how much memory? any errors?"│
│     └── Visit http://localhost:9090 to query metrics         │
│                                                              │
│  6. Grafana (port 3000)                                      │
│     └── Beautiful dashboards built on Prometheus data.       │
│     └── Visit http://localhost:3000 (admin/admin)            │
│     └── Shows: request rate, response times, JVM memory      │
│                                                              │
└──────────────────────────────────────────────────────────────┘
```

---

## STEP 3: Start Everything

### 3.1 Start Infrastructure (one command!)

Open PowerShell in the project root:
```powershell
cd C:\Users\dornadum\Upskill\ride-sharing-platform
docker compose up -d
```

This downloads images (first time takes 5-10 minutes) and starts all 6 containers.

### 3.2 Verify Containers Are Running
```powershell
docker compose ps
```
You should see all 6 services with status "running" or "healthy".

### 3.3 Check Individual Services

**PostgreSQL:**
```powershell
docker exec -it ride-sharing-postgres psql -U ridesharing -d ridesharing_db -c "\dt"
```
→ Should show empty tables (Flyway creates them when our app starts)

**Redis:**
```powershell
docker exec -it ride-sharing-redis redis-cli ping
```
→ Should print `PONG`

**Kafka:**
```powershell
docker exec -it ride-sharing-kafka kafka-topics --bootstrap-server localhost:9092 --list
```
→ May be empty initially (topics are auto-created when the app starts)

### 3.4 Start the Spring Boot Application
```powershell
cd C:\Users\dornadum\Upskill\ride-sharing-platform
mvn spring-boot:run
```
Wait until you see: `Started RideSharingApplication in X seconds`

Flyway will automatically create all 9 database tables (V1 through V9).

### 3.5 Verify App is Running
Open browser: http://localhost:8080/actuator/health
→ Should show `{"status":"UP"}`

---

## STEP 4: Manual Testing — Complete Ride Lifecycle

Now test EVERY feature through real API calls. Open a NEW PowerShell window (keep the app running in the other one).

### Helper: Save this function in your PowerShell session
```powershell
function API {
    param([string]$Method, [string]$Url, [string]$Body = $null, [string]$Token = $null)
    $headers = @{ "Content-Type" = "application/json" }
    if ($Token) { $headers["Authorization"] = "Bearer $Token"; $headers["X-User-Id"] = "0" }
    $params = @{ Uri = $Url; Method = $Method; Headers = $headers; ContentType = "application/json" }
    if ($Body) { $params["Body"] = $Body }
    try {
        $r = Invoke-RestMethod @params
        $r | ConvertTo-Json -Depth 5
    } catch {
        Write-Host "ERROR: $($_.Exception.Response.StatusCode) - $($_.Exception.Message)" -ForegroundColor Red
        $reader = [System.IO.StreamReader]::new($_.Exception.Response.GetResponseStream())
        $reader.ReadToEnd()
    }
}
```

---

### TEST 1: AUTHENTICATION

#### 1a. Register a Rider
```powershell
API POST "http://localhost:8080/api/auth/register" '{"name":"Arun Kumar","email":"arun@test.com","password":"password123","phone":"9876543210","role":"RIDER"}'
```
**What happens behind the scenes:**
- Spring Security allows `/api/auth/**` without a token
- Password is BCrypt-hashed before storing
- JWT access + refresh tokens are generated
- User is saved to PostgreSQL `users` table

**Save the accessToken!** Copy it:
```powershell
$RIDER_TOKEN = "paste-the-accessToken-here"
```

#### 1b. Register a Driver
```powershell
API POST "http://localhost:8080/api/auth/register" '{"name":"Ravi Driver","email":"ravi@test.com","password":"driver123","phone":"8765432109","role":"DRIVER"}'
```
Save the token:
```powershell
$DRIVER_TOKEN = "paste-the-accessToken-here"
```

#### 1c. Try Admin Self-Registration (should FAIL)
```powershell
API POST "http://localhost:8080/api/auth/register" '{"name":"Hacker","email":"hack@test.com","password":"hack123","phone":"0000000000","role":"ADMIN"}'
```
→ Expected: 401 Unauthorized — "Cannot self-register as ADMIN"

#### 1d. Login as Admin (seeded by V7 migration)
```powershell
API POST "http://localhost:8080/api/auth/login" '{"email":"admin@ridesharing.com","password":"admin123"}'
```
Save the token:
```powershell
$ADMIN_TOKEN = "paste-the-accessToken-here"
```

#### 1e. Login with wrong password (should FAIL)
```powershell
API POST "http://localhost:8080/api/auth/login" '{"email":"arun@test.com","password":"WRONG"}'
```
→ Expected: 401 Unauthorized

---

### TEST 2: DRIVER SETUP

#### 2a. Add Vehicle (as driver)
```powershell
API POST "http://localhost:8080/api/driver/vehicle" '{"vehicleType":"AUTO","plateNumber":"TN 01 AB 1234","model":"Bajaj RE","color":"Yellow"}' $DRIVER_TOKEN
```
**Behind the scenes:** Saves to `vehicles` table, linked to driver's user ID via JWT.

#### 2b. Add Document
```powershell
API POST "http://localhost:8080/api/driver/documents" '{"documentType":"DRIVING_LICENSE","documentNumber":"TN-DL-2024-001234","expiryDate":"2030-12-31"}' $DRIVER_TOKEN
```

#### 2c. Get Driver Profile
```powershell
API GET "http://localhost:8080/api/driver/profile" $null $DRIVER_TOKEN
```

---

### TEST 3: ADMIN OPERATIONS

#### 3a. See Pending Drivers
```powershell
API GET "http://localhost:8080/api/admin/drivers/pending" $null $ADMIN_TOKEN
```
→ Should show Ravi Driver with status PENDING

#### 3b. Approve the Driver
First, note the driver's user ID from the response above (likely `2`).
```powershell
API PUT "http://localhost:8080/api/admin/drivers/2/approve" $null $ADMIN_TOKEN
```
**Behind the scenes:**
- User.approvalStatus changes from PENDING → APPROVED
- Kafka event `driver-approved` is published
- Notification consumer picks it up → sends WebSocket notification

#### 3c. Try to approve again (should FAIL)
```powershell
API PUT "http://localhost:8080/api/admin/drivers/2/approve" $null $ADMIN_TOKEN
```
→ Expected: 400 Bad Request — "Driver is already approved"

---

### TEST 4: DRIVER GOES ONLINE

#### 4a. Update Availability to ONLINE
```powershell
API PUT "http://localhost:8080/api/driver/availability" '{"availability":"ONLINE","latitude":12.9716,"longitude":77.5946}' $DRIVER_TOKEN
```
**Behind the scenes:**
- User.availability changes to ONLINE
- Redis GEO: `GEOADD driver:locations 77.5946 12.9716 "2"`
- Driver is now findable by nearby riders!

#### 4b. Verify in Redis (optional)
```powershell
docker exec -it ride-sharing-redis redis-cli GEOPOS driver:locations "2"
```
→ Should show the longitude and latitude

---

### TEST 5: PRICING

#### 5a. Get All Fare Rules
```powershell
API GET "http://localhost:8080/api/pricing/fare-rules" $null $RIDER_TOKEN
```
→ Shows AUTO, BIKE, SEDAN, SUV with their base fares and per-km rates

#### 5b. Get Fare Estimate
```powershell
API POST "http://localhost:8080/api/pricing/estimate" '{"pickupLatitude":12.9716,"pickupLongitude":77.5946,"dropoffLatitude":12.9352,"dropoffLongitude":77.6245,"vehicleType":"AUTO"}' $RIDER_TOKEN
```
→ Shows estimated fare (~₹105), distance (~5.18 km), time (~12 min)

**Behind the scenes:**
- Checks Redis cache for fare rule (miss → loads from PostgreSQL → caches)
- Calculates distance using Haversine formula
- Checks Redis for surge multiplier
- Returns breakdown: baseFare + (distance × perKmRate) + (time × perMinuteRate)

---

### TEST 6: REQUEST A RIDE (The Big One!)

#### 6a. Rider Requests a Ride
```powershell
API POST "http://localhost:8080/api/rides/request" '{"pickupLatitude":12.9716,"pickupLongitude":77.5946,"dropoffLatitude":12.9352,"dropoffLongitude":77.6245,"pickupAddress":"MG Road, Bangalore","dropoffAddress":"Koramangala, Bangalore","vehicleType":"AUTO"}' $RIDER_TOKEN
```

**Behind the scenes (this is the most complex flow):**
1. Validates rider doesn't have an active ride
2. Calls PricingService → fare estimate
3. Saves ride to PostgreSQL (status = REQUESTED)
4. Publishes `ride-requested` Kafka event
5. Triggers async matching on a background thread:
   - `CompletableFuture.supplyAsync()` runs on custom thread pool
   - Calls Redis GEO: `GEOSEARCH driver:locations ... BYRADIUS 5 km`
   - Finds driver #2 (Ravi) at ~0 km distance
   - Checks: is driver ONLINE? Not on another ride? Active?
   - YES → assigns driver, sets driver to BUSY
   - Publishes `ride-accepted` Kafka event
   - Removes driver from Redis GEO (no longer available)
   - Notification module sends WebSocket to rider: "Driver Found!"

**Save the ride ID from the response:**
```powershell
$RIDE_ID = 1  # or whatever the rideId is
```

#### 6b. Check Ride Status (should be ACCEPTED after a few seconds)
```powershell
API GET "http://localhost:8080/api/rides/$RIDE_ID" $null $RIDER_TOKEN
```
→ Status should be ACCEPTED, with driver info filled in

#### 6c. Try Requesting a Second Ride (should FAIL)
```powershell
API POST "http://localhost:8080/api/rides/request" '{"pickupLatitude":12.97,"pickupLongitude":77.59,"dropoffLatitude":12.93,"dropoffLongitude":77.62,"vehicleType":"AUTO"}' $RIDER_TOKEN
```
→ Expected: 400 — "You already have an active ride"

---

### TEST 7: RIDE LIFECYCLE

#### 7a. Driver Starts the Ride
```powershell
API PUT "http://localhost:8080/api/rides/$RIDE_ID/start" $null $DRIVER_TOKEN
```
→ Status: ACCEPTED → IN_PROGRESS

#### 7b. Driver Completes the Ride
```powershell
API PUT "http://localhost:8080/api/rides/$RIDE_ID/complete" $null $DRIVER_TOKEN
```
→ Status: IN_PROGRESS → COMPLETED
→ Actual fare is set
→ Driver becomes ONLINE again

**Behind the scenes:**
- Publishes `ride-completed` Kafka event
- Payment consumer receives it → creates PENDING payment
- Notification consumer receives it → sends WebSocket to both

---

### TEST 8: PAYMENT

#### 8a. Check Payment Was Auto-Created
```powershell
API GET "http://localhost:8080/api/payments/ride/$RIDE_ID" $null $RIDER_TOKEN
```
→ Should show a PENDING payment with the ride's fare amount

**Save the payment ID:**
```powershell
$PAYMENT_ID = 1  # from the response
```

#### 8b. Rider Pays via UPI
```powershell
API PUT "http://localhost:8080/api/payments/$PAYMENT_ID/pay" '{"paymentMethod":"UPI"}' $RIDER_TOKEN
```
→ Status: PENDING → COMPLETED

#### 8c. View Payment History (Rider)
```powershell
API GET "http://localhost:8080/api/payments/history/rider" $null $RIDER_TOKEN
```

#### 8d. View Earnings (Driver)
```powershell
API GET "http://localhost:8080/api/payments/history/driver" $null $DRIVER_TOKEN
```

---

### TEST 9: RATINGS

#### 9a. Rider Rates Driver (5 stars)
```powershell
API POST "http://localhost:8080/api/ratings" '{"rideId":RIDE_ID_HERE,"score":5,"comment":"Excellent ride! Very smooth."}' $RIDER_TOKEN
```
(Replace RIDE_ID_HERE with actual ride ID)

#### 9b. Driver Rates Rider (4 stars)
```powershell
API POST "http://localhost:8080/api/ratings" '{"rideId":RIDE_ID_HERE,"score":4,"comment":"Polite passenger"}' $DRIVER_TOKEN
```

#### 9c. Try Duplicate Rating (should FAIL)
```powershell
API POST "http://localhost:8080/api/ratings" '{"rideId":RIDE_ID_HERE,"score":3}' $RIDER_TOKEN
```
→ Expected: 409 Conflict — "You have already rated this ride"

#### 9d. Check Average Rating
```powershell
API GET "http://localhost:8080/api/ratings/average/2" $null $RIDER_TOKEN
```
→ Should show 5.0 (one 5-star rating for the driver)

---

### TEST 10: MONITORING

#### 10a. Prometheus Metrics
Open browser: http://localhost:8080/actuator/prometheus
→ Shows raw metrics (http_server_requests, jvm_memory, etc.)

#### 10b. Prometheus UI
Open browser: http://localhost:9090
→ Try query: `http_server_requests_seconds_count`
→ Shows how many HTTP requests each endpoint received

#### 10c. Grafana Dashboard
Open browser: http://localhost:3000
→ Login: admin / admin (skip password change)
→ Go to Dashboards → "Ride Sharing Platform"
→ See: request rate, response time, JVM memory, error rate

---

### TEST 11: VERIFY DATABASE STATE

After all tests, check the actual data in PostgreSQL:
```powershell
docker exec -it ride-sharing-postgres psql -U ridesharing -d ridesharing_db
```

Then run these SQL queries:
```sql
-- See all users
SELECT id, name, email, role, approval_status, availability, average_rating FROM users;

-- See all rides
SELECT id, rider_id, driver_id, status, vehicle_type, estimated_fare, actual_fare FROM rides;

-- See all payments
SELECT id, ride_id, amount, payment_method, status FROM payments;

-- See all ratings
SELECT id, ride_id, rater_id, ratee_id, score, comment FROM ratings;

-- See fare rules
SELECT vehicle_type, base_fare, per_km_rate, per_minute_rate FROM fare_rules;

-- Exit
\q
```

### TEST 12: VERIFY REDIS STATE

```powershell
# See all keys
docker exec -it ride-sharing-redis redis-cli KEYS "*"

# Check driver locations
docker exec -it ride-sharing-redis redis-cli GEOPOS driver:locations "2"

# Check cached fare rules
docker exec -it ride-sharing-redis redis-cli GET "pricing:fare-rule:AUTO"

# Check surge multipliers
docker exec -it ride-sharing-redis redis-cli GET "pricing:surge:AUTO"
```

---

## STEP 5: Shutdown

```powershell
# Stop Spring Boot: Ctrl+C in the terminal running mvn spring-boot:run

# Stop all Docker containers
docker compose down

# Stop AND delete all data (fresh start next time)
docker compose down -v
```

---

## Troubleshooting

| Problem | Fix |
|---------|-----|
| `docker compose up` fails | Make sure Docker Desktop is running (whale icon in taskbar) |
| App can't connect to PostgreSQL | Wait 30 sec for PostgreSQL health check to pass |
| Kafka connection refused | Kafka takes ~30 sec to start. Check: `docker compose logs kafka` |
| Port already in use | Run `docker compose down` first, then `docker compose up -d` |
| Flyway migration fails | Run `docker compose down -v` to clear old data, then restart |
| "JWT expired" errors | Re-login to get a fresh token |
