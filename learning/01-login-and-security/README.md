# Module 1: Login & Security — Complete Guide

> Everything you need to understand about how a user registers, logs in, and stays authenticated in a Spring Boot application. Written so you can read this 10 years from now and still understand it.

---

## Table of Contents

1. [The Big Picture](#1-the-big-picture)
2. [What Happens When a User Registers](#2-what-happens-when-a-user-registers)
3. [Password Security — BCrypt, Salt & Hashing](#3-password-security--bcrypt-salt--hashing)
4. [What Happens When a User Logs In](#4-what-happens-when-a-user-logs-in)
5. [JWT Tokens — Access Token & Refresh Token](#5-jwt-tokens--access-token--refresh-token)
6. [How Every API Call is Authenticated](#6-how-every-api-call-is-authenticated)
7. [Role-Based Access Control](#7-role-based-access-control)
8. [Why No Admin Registration API](#8-why-no-admin-registration-api)
9. [The Complete Request Lifecycle](#9-the-complete-request-lifecycle)
10. [Key Files in Our Codebase](#10-key-files-in-our-codebase)

---

## 1. The Big Picture

Our app has 3 types of users: **RIDER**, **DRIVER**, and **ADMIN**.

```
┌─────────────────────────────────────────────────────────────┐
│                                                             │
│  RIDER   → Can request rides, rate drivers, view payments   │
│  DRIVER  → Can go online, accept rides, update location     │
│  ADMIN   → Can approve drivers, manage the platform         │
│                                                             │
│  All 3 use the SAME login API.                              │
│  The server knows who you are from the DATABASE.            │
│  The TOKEN carries your identity to every future request.   │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

### The Core Problem

HTTP is **stateless** — the server forgets you after every request. It's like talking to someone with amnesia. You say "I'm Mohith" and they say "OK". Next request — "Who are you again?"

### The Solution: JWT Tokens

When you login, the server gives you a **token** (like an ID card). You show this ID card with every future request. The server reads it and knows who you are — without checking the database again.

---

## 2. What Happens When a User Registers

### The API

```
POST /api/auth/register
{
  "name": "Mohith Rider",
  "email": "mohith.rider@test.com",
  "password": "password123",
  "phone": "9876543210",
  "role": "RIDER"
}
```

### The Complete Journey (every layer)

```
INSOMNIA / Browser
    │
    ▼
TOMCAT (embedded web server, port 8080)
    │  Receives HTTP request, parses JSON body
    ▼
SPRING SECURITY FILTER CHAIN (13 filters)
    │  /api/auth/register is WHITELISTED → no token needed → passes through
    ▼
DISPATCHER SERVLET
    │  Looks at URL + HTTP method → routes to AuthController.register()
    ▼
CONTROLLER (AuthController)
    │  @Valid validates input (checks @NotBlank, @Email, etc.)
    │  If validation fails → 400 Bad Request (never reaches service)
    │  Calls authService.register(request)
    ▼
SERVICE (AuthService)
    │  1. Check duplicate email → SELECT COUNT(*) FROM users WHERE email = ?
    │  2. Block ADMIN role → if role == ADMIN, throw UnauthorizedException
    │  3. Hash password → BCrypt.encode("password123") → "$2a$10$..."
    │  4. Build User entity
    │  5. Save to database
    │  6. Generate JWT tokens
    ▼
REPOSITORY (UserRepository) → HIBERNATE (ORM) → HIKARICP (Connection Pool)
    │  Generates: INSERT INTO users (...) VALUES (...)
    │  Sends SQL to PostgreSQL
    ▼
POSTGRESQL (Database)
    │  Stores the user, returns generated ID
    ▼
JWT SERVICE
    │  Creates accessToken (15 min) and refreshToken (7 days)
    ▼
RESPONSE back through all layers
    │
    ▼
{
  "success": true,
  "data": {
    "accessToken": "eyJhbG...",
    "refreshToken": "eyJhbG...",
    "role": "RIDER"
  }
}
```

### What Gets Stored in the Database

```
┌────┬──────────────┬───────────────────────┬──────────────────────┬───────┐
│ id │ name         │ email                 │ password             │ role  │
├────┼──────────────┼───────────────────────┼──────────────────────┼───────┤
│ 2  │ Mohith Rider │ mohith.rider@test.com │ $2a$10$Abc123...     │ RIDER │
│    │              │                       │ (NOT "password123")  │       │
│    │              │                       │ (BCrypt HASH of it)  │       │
└────┴──────────────┴───────────────────────┴──────────────────────┴───────┘
```

The actual password "password123" is **NEVER stored**. Only its BCrypt hash.

---

## 3. Password Security — BCrypt, Salt & Hashing

### Why Can't We Store Plain Text Passwords?

If a hacker gets access to the database, they see every user's password. Millions of accounts compromised instantly.

### Why Simple Hashing (like SHA-256) Isn't Enough

```
"password123" → SHA256 → "ef92b778bafb..."

Problem: Same password ALWAYS produces the same hash.
Hackers have pre-computed tables (Rainbow Tables) of common passwords.
They look up "ef92b778..." and find "password123" instantly.

Also: If two users have the same password, their hashes are identical.
Hacker knows: "These users share the same password."
```

### BCrypt Solves This With SALT

**Salt** = a random string generated fresh every time you hash a password.

```
WHAT BCrypt DOES WHEN YOU REGISTER:

  Input: "password123"

  Step 1: Generate random salt
          salt = "Abc12Def34Ghi56Jkl78Mn" (22 characters, unique)

  Step 2: Pick cost factor
          cost = 10 (means 2^10 = 1024 hashing rounds)
          Higher cost = slower = harder to brute force

  Step 3: Hash password + salt through 1024 rounds
          BCrypt("password123" + salt, 1024 rounds)
          → produces hash result

  Step 4: Combine into ONE string (stored in database)

          $2a$10$Abc12Def34Ghi56Jkl78MnOpQrStUvWxYz012345678901
          ─┬── ─┬─ ──────────┬──────────── ────────────┬────────
           │    │             │                         │
           │    │             │                         └─ HASH (31 chars)
           │    │             └─ SALT (22 chars)
           │    └─ COST FACTOR (10)
           └─ ALGORITHM ($2a = BCrypt)
```

### The Key Insight

The **salt is stored INSIDE the hash string**. No separate column needed. BCrypt extracts it during verification.

### Same Password → Different Hashes (Because Different Salts)

```
Mohith registers with "password123":
  Random salt1 → "$2a$10$xK9mP2qR7sT4uV6wX8yZ0e..." 

Ravi also registers with "password123":
  Random salt2 → "$2a$10$bQ4nR7tU1vW3xY5zA8bC0d..."

COMPLETELY DIFFERENT hashes!
A hacker looking at the database CANNOT tell they used the same password.
```

### How BCrypt Verifies During Login

```
User types: "password123"
Stored hash: "$2a$10$Abc12Def34Ghi56Jkl78MnOpQrStUvWxYz012345678901"

BCrypt.matches("password123", stored_hash):

  Step 1: Extract salt from stored hash → "Abc12Def34Ghi56Jkl78Mn"
  Step 2: Extract cost from stored hash → 10
  Step 3: Hash input with SAME salt → BCrypt("password123" + salt)
  Step 4: Compare generated hash with stored hash
          Match? → TRUE → login success
          No match? → FALSE → "wrong password"
```

### Real Code in Our Project

```java
// During REGISTRATION (AuthService.java, line 86):
.password(passwordEncoder.encode(request.getPassword()))
// "password123" → "$2a$10$Abc..." (hashed, stored in DB)

// During LOGIN (AuthService.java, line 134):
if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
    throw new UnauthorizedException("Invalid email or password");
}
// matches("password123", "$2a$10$Abc...") → extracts salt, re-hashes, compares
```

---

## 4. What Happens When a User Logs In

### The API

```
POST /api/auth/login
{
  "email": "mohith.rider@test.com",
  "password": "password123"
}
```

### Step-by-Step

```
1. FIND USER BY EMAIL
   SELECT * FROM users WHERE email = 'mohith.rider@test.com'
   → Found: User{id=2, password="$2a$10$Abc...", role=RIDER}
   → Not found? → 404: "User not found"

2. CHECK IF ACCOUNT IS ACTIVE
   user.isActive() → true
   → If false → 401: "Account is suspended"

3. VERIFY PASSWORD (BCrypt)
   passwordEncoder.matches("password123", "$2a$10$Abc...")
   → Extract salt from stored hash
   → Re-hash "password123" with same salt
   → Compare: MATCH! → continue
   → No match → 401: "Invalid email or password"

4. GENERATE JWT TOKENS
   accessToken  → { sub: "2", role: "RIDER", exp: 15min }
   refreshToken → { sub: "2", role: "RIDER", exp: 7days }

5. RETURN RESPONSE
   { accessToken: "eyJ...", refreshToken: "eyJ...", role: "RIDER" }
```

### Same API for All Roles

One login API serves Rider, Driver, and Admin. The difference is what's in the database:

```
mohith.rider@test.com  → DB says role=RIDER  → Token has role=RIDER
ravi.driver@test.com   → DB says role=DRIVER → Token has role=DRIVER
admin@ridesharing.com  → DB says role=ADMIN  → Token has role=ADMIN

Same code path. Role comes from the DATABASE, goes into the TOKEN.
```

---

## 5. JWT Tokens — Access Token & Refresh Token

### What is a JWT Token?

A JSON Web Token is a self-contained string that carries information about the user. The server doesn't need to store anything — the token itself contains the user's identity.

### Structure of a JWT

```
eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIyIiwicm9sZSI6IlJJREVSIn0.Xt7s9kP2mN...
──────────┬────────── ─────────────────┬─────────────────────── ─────┬──────
       HEADER                      PAYLOAD                      SIGNATURE

HEADER (Base64 decoded):
{
  "alg": "HS256",        ← signing algorithm
  "typ": "JWT"
}

PAYLOAD (Base64 decoded):
{
  "sub": "2",            ← userId
  "role": "RIDER",       ← user's role
  "iat": 1781096400,     ← issued at (timestamp)
  "exp": 1781097300      ← expires at (15 min later)
}

SIGNATURE:
HMAC-SHA256(header + "." + payload, SECRET_KEY)
← Proves no one tampered with the token
← If someone changes "RIDER" to "ADMIN", signature won't match → rejected
```

### Why TWO Tokens?

```
ACCESS TOKEN                           REFRESH TOKEN
─────────────                          ──────────────
Expires: 15 MINUTES                    Expires: 7 DAYS
Sent with: EVERY API call             Sent to: ONLY /auth/refresh
Exposure: HIGH (used everywhere)       Exposure: LOW (used rarely)

WHY?
If hacker steals access token  → can use it for max 15 min → limited damage
If we had one 7-day token      → hacker has 7 days → massive damage

The refresh token quietly gets you a new access token every 15 min.
User logs in ONCE, stays authenticated for 7 DAYS.
```

### The Refresh Flow (Timeline)

```
6:30 PM  LOGIN → get accessToken (expires 6:45) + refreshToken (expires Jun 17)
6:31 PM  GET /rides + accessToken → works ✓
6:40 PM  GET /rides + accessToken → works ✓
6:46 PM  GET /rides + accessToken → FAILS! Token expired.
6:46 PM  POST /auth/refresh + refreshToken → get NEW accessToken (expires 7:01)
6:46 PM  GET /rides + NEW accessToken → works ✓
...repeats for 7 days without logging in again...
Jun 17   Refresh token expires → user must login with email + password again
```

---

## 6. How Every API Call is Authenticated

When you call any protected API (like adding a vehicle), here's what happens:

```
POST /api/drivers/vehicles
Header: Authorization: Bearer eyJhbGciOiJIUzI1NiJ9...
Body: { "make": "Toyota", ... }

STEP 1: JwtAuthenticationFilter intercepts the request
        │
        ├─ Is URL whitelisted? (/api/auth/register, /api/auth/login)
        │  YES → skip filter, let request through without token
        │  NO  → token required, continue checking
        │
        ▼
STEP 2: Extract token from "Authorization: Bearer eyJ..."
        Remove "Bearer " prefix → token = "eyJhbG..."

STEP 3: Validate token (JwtService)
        │
        ├─ Decode using SECRET KEY
        │  If fake token (wrong secret) → 401 Unauthorized
        │
        ├─ Check expiry
        │  If expired → 401 Unauthorized
        │
        └─ Extract: userId = "3", role = "DRIVER"

STEP 4: Set in Spring Security Context
        "This request is from user 3, role DRIVER"

STEP 5: Controller receives the request
        Authentication auth → auth.getName() → "3"
        driverService.addVehicle(3, request)
        → Vehicle saved, linked to user 3

The server NEVER asked for email or password again.
The token carried the identity.
```

---

## 7. Role-Based Access Control

The token's role decides what APIs you can access:

```
RIDER token  + POST /api/rides           → 200 OK (riders can request rides)
RIDER token  + PUT /api/admin/drivers/3  → 403 FORBIDDEN (not an admin)

DRIVER token + PUT /api/drivers/location → 200 OK (drivers can update location)
DRIVER token + PUT /api/admin/drivers/3  → 403 FORBIDDEN (not an admin)

ADMIN token  + PUT /api/admin/drivers/3  → 200 OK (admin can approve drivers)
ADMIN token  + POST /api/rides           → depends on controller logic
```

The controller or security config checks the role:

```java
// In SecurityConfig — URL-level protection
.requestMatchers("/api/admin/**").hasRole("ADMIN")

// In Controller — method-level check
if (!auth.getAuthorities().contains("ROLE_ADMIN")) {
    throw new UnauthorizedException("Admin access required");
}
```

---

## 8. Why No Admin Registration API

Admins CANNOT self-register through the API. This is a deliberate security decision.

```
POST /api/auth/register { "role": "ADMIN" }
→ 401: "Cannot self-register as ADMIN"
```

**Why?** If anyone could register as admin, they could control the entire platform — approve drivers, see all data, manipulate rides. That's a complete security breach.

**How is the admin created?**

1. **Database Migration** (our approach): `V7__seed_admin_user.sql` inserts the first admin when the app starts for the first time.
2. **Direct DB access**: A DBA runs an INSERT query in pgAdmin.
3. **Admin creates admin** (could be built): An existing admin promotes a user through a protected API.

This is how real companies work — Uber's first admin was seeded during deployment. That admin created other admins through internal tools. Regular users never have a path to become admin.

---

## 9. The Complete Request Lifecycle

Every request to our app passes through these layers:

```
CLIENT (Insomnia / Browser / Mobile App)
    │
    ▼
TOMCAT (Embedded Web Server — port 8080)
    │
    ▼
SECURITY FILTER CHAIN (13 filters)
    │  Filter 6: JwtAuthenticationFilter
    │  → Whitelisted URL? Skip.
    │  → Extract token, validate, set userId + role in context
    │
    ▼
DISPATCHER SERVLET (URL Router)
    │  POST /api/auth/register → AuthController.register()
    │  POST /api/auth/login    → AuthController.login()
    │  POST /api/drivers/...   → DriverController
    │  POST /api/rides/...     → RideController
    │
    ▼
CONTROLLER (@RestController)
    │  Receives HTTP, validates input (@Valid)
    │  Calls service layer
    │  Returns HTTP response
    │
    ▼
SERVICE (@Service)
    │  Business logic (validation, calculations, orchestration)
    │  Calls repository for DB operations
    │  Calls other services (JWT, BCrypt, Kafka, Redis)
    │
    ▼
REPOSITORY (Spring Data JPA Interface)
    │
    ▼
HIBERNATE (ORM — Java objects ↔ SQL)
    │
    ▼
HIKARICP (Connection Pool — manages DB connections efficiently)
    │
    ▼
POSTGRESQL (Database — data stored on disk)
```

---

## 10. Key Files in Our Codebase

| File | Purpose |
|------|---------|
| `AuthController.java` | HTTP endpoints: /register, /login, /refresh |
| `AuthService.java` | Business logic: validate, hash password, generate tokens |
| `JwtService.java` | Generate and validate JWT tokens |
| `JwtAuthenticationFilter.java` | Intercepts every request, validates token |
| `SecurityConfig.java` | Configures which URLs need auth, which are public |
| `User.java` | JPA entity mapped to `users` table |
| `UserRepository.java` | Database operations (findByEmail, existsByEmail) |
| `RegisterRequest.java` | DTO with validation annotations |
| `LoginRequest.java` | DTO for login input |
| `AuthResponse.java` | DTO returned after register/login |
| `V1__create_users_table.sql` | Flyway migration that creates the users table |
| `V7__seed_admin_user.sql` | Seeds the first admin user |
| `application-dev.yml` | JWT secret key, token expiry configuration |

---

## Quick Reference — Interview One-Liners

- **BCrypt**: A password hashing algorithm that adds a random salt to each password before hashing, so the same password produces different hashes. The salt is embedded in the output string. Cost factor controls how slow (and secure) the hash is.

- **JWT**: A self-contained token with 3 parts (header.payload.signature) that carries user identity (userId, role, expiry). The server doesn't store sessions — the token IS the session. Signature prevents tampering.

- **Access Token vs Refresh Token**: Access token is short-lived (15 min) and sent with every API call — if stolen, damage is limited. Refresh token is long-lived (7 days) and used only to get new access tokens — less exposure. Together they provide security + good UX.

- **Stateless Authentication**: Server stores nothing about the user session. All info is in the JWT. Any server in a cluster can validate the token — perfect for horizontal scaling.

- **Why no admin self-registration**: Security measure. Admin accounts are created via database migrations or by existing admins. Prevents privilege escalation attacks.
