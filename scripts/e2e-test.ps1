# ============================================================
# E2E Test Script — Ride-Sharing Platform
# ============================================================
# Prerequisites: Docker Desktop running, app started with:
#   docker-compose up -d
#   mvn spring-boot:run
# ============================================================

$BASE_URL = "http://localhost:8080/api"
$PASS = 0
$FAIL = 0

function Test-API {
    param (
        [string]$Name,
        [string]$Method,
        [string]$Url,
        [string]$Body = $null,
        [string]$Token = $null,
        [int]$ExpectedStatus = 200
    )

    $headers = @{ "Content-Type" = "application/json" }
    if ($Token) {
        $headers["Authorization"] = "Bearer $Token"
    }

    try {
        $params = @{
            Uri = $Url
            Method = $Method
            Headers = $headers
            ContentType = "application/json"
        }
        if ($Body) { $params["Body"] = $Body }

        $response = Invoke-WebRequest @params -ErrorAction Stop
        $statusCode = $response.StatusCode
        $content = $response.Content | ConvertFrom-Json

        if ($statusCode -eq $ExpectedStatus) {
            Write-Host "  [PASS] $Name (HTTP $statusCode)" -ForegroundColor Green
            $script:PASS++
            return $content
        } else {
            Write-Host "  [FAIL] $Name — Expected $ExpectedStatus, Got $statusCode" -ForegroundColor Red
            $script:FAIL++
            return $null
        }
    } catch {
        $statusCode = $_.Exception.Response.StatusCode.value__
        if ($statusCode -eq $ExpectedStatus) {
            Write-Host "  [PASS] $Name (HTTP $statusCode — expected error)" -ForegroundColor Green
            $script:PASS++
            return $null
        } else {
            Write-Host "  [FAIL] $Name — $($_.Exception.Message)" -ForegroundColor Red
            $script:FAIL++
            return $null
        }
    }
}

Write-Host ""
Write-Host "============================================" -ForegroundColor Cyan
Write-Host "  RIDE-SHARING PLATFORM — E2E TEST" -ForegroundColor Cyan
Write-Host "============================================" -ForegroundColor Cyan
Write-Host ""

# ── Phase 0: Health Check ──
Write-Host "--- Phase 0: Health Check ---" -ForegroundColor Yellow
Test-API -Name "App is running" -Method GET -Url "http://localhost:8080/actuator/health"

# ── Phase 1: Auth ──
Write-Host ""
Write-Host "--- Phase 1: Authentication ---" -ForegroundColor Yellow

$riderBody = '{"name":"Arun Kumar","email":"arun@test.com","password":"pass123","phone":"9876543210","role":"RIDER"}'
$riderResult = Test-API -Name "Register Rider" -Method POST -Url "$BASE_URL/auth/register" -Body $riderBody
$RIDER_TOKEN = $riderResult.data.accessToken

$driverBody = '{"name":"Ravi Driver","email":"ravi@test.com","password":"pass123","phone":"8765432109","role":"DRIVER"}'
$driverResult = Test-API -Name "Register Driver" -Method POST -Url "$BASE_URL/auth/register" -Body $driverBody
$DRIVER_TOKEN = $driverResult.data.accessToken
$DRIVER_ID = $driverResult.data.userId

$adminLoginBody = '{"email":"admin@ridesharing.com","password":"admin123"}'
$adminResult = Test-API -Name "Login Admin" -Method POST -Url "$BASE_URL/auth/login" -Body $adminLoginBody
$ADMIN_TOKEN = $adminResult.data.accessToken

$adminRegBody = '{"name":"Hacker","email":"hacker@test.com","password":"hack123","phone":"0000000000","role":"ADMIN"}'
Test-API -Name "Block Admin self-registration" -Method POST -Url "$BASE_URL/auth/register" -Body $adminRegBody -ExpectedStatus 401

# ── Phase 2: Driver Setup ──
Write-Host ""
Write-Host "--- Phase 2: Driver Setup ---" -ForegroundColor Yellow

$vehicleBody = '{"vehicleType":"AUTO","plateNumber":"TN 01 AB 1234","model":"Bajaj RE","color":"Yellow"}'
Test-API -Name "Add Vehicle" -Method POST -Url "$BASE_URL/driver/vehicle" -Body $vehicleBody -Token $DRIVER_TOKEN

$docBody = '{"documentType":"DRIVING_LICENSE","documentNumber":"TN-DL-2024-001234","expiryDate":"2030-12-31"}'
Test-API -Name "Add Document" -Method POST -Url "$BASE_URL/driver/documents" -Body $docBody -Token $DRIVER_TOKEN

Test-API -Name "Admin: Approve Driver" -Method PUT -Url "$BASE_URL/admin/drivers/$DRIVER_ID/approve" -Token $ADMIN_TOKEN

$availBody = '{"availability":"ONLINE","latitude":12.9716,"longitude":77.5946}'
Test-API -Name "Driver Goes Online" -Method PUT -Url "$BASE_URL/driver/availability" -Body $availBody -Token $DRIVER_TOKEN

# ── Phase 3: Pricing ──
Write-Host ""
Write-Host "--- Phase 3: Pricing ---" -ForegroundColor Yellow

Test-API -Name "Get Fare Rules" -Method GET -Url "$BASE_URL/pricing/fare-rules" -Token $RIDER_TOKEN

$estimateBody = '{"pickupLatitude":12.9716,"pickupLongitude":77.5946,"dropoffLatitude":12.9352,"dropoffLongitude":77.6245,"vehicleType":"AUTO"}'
$fareResult = Test-API -Name "Fare Estimate" -Method POST -Url "$BASE_URL/pricing/estimate" -Body $estimateBody -Token $RIDER_TOKEN
if ($fareResult) {
    Write-Host "         Fare: Rs.$($fareResult.data.estimatedFare) | Distance: $($fareResult.data.distanceInKm) km" -ForegroundColor DarkGray
}

# ── Phase 4: Ride Lifecycle ──
Write-Host ""
Write-Host "--- Phase 4: Ride Lifecycle ---" -ForegroundColor Yellow

$rideBody = '{"pickupLatitude":12.9716,"pickupLongitude":77.5946,"dropoffLatitude":12.9352,"dropoffLongitude":77.6245,"pickupAddress":"MG Road, Bangalore","dropoffAddress":"Koramangala, Bangalore","vehicleType":"AUTO"}'
$rideResult = Test-API -Name "Request Ride" -Method POST -Url "$BASE_URL/rides/request" -Body $rideBody -Token $RIDER_TOKEN
$RIDE_ID = $rideResult.data.rideId

Write-Host "         Waiting 5s for driver matching..." -ForegroundColor DarkGray
Start-Sleep -Seconds 5

$rideCheck = Test-API -Name "Check Ride Status" -Method GET -Url "$BASE_URL/rides/$RIDE_ID" -Token $RIDER_TOKEN
Write-Host "         Status: $($rideCheck.data.status) | Driver: $($rideCheck.data.driverName)" -ForegroundColor DarkGray

if ($rideCheck.data.status -eq "ACCEPTED") {
    Test-API -Name "Start Ride" -Method PUT -Url "$BASE_URL/rides/$RIDE_ID/start" -Token $DRIVER_TOKEN
    Test-API -Name "Complete Ride" -Method PUT -Url "$BASE_URL/rides/$RIDE_ID/complete" -Token $DRIVER_TOKEN
}

# ── Phase 5: History ──
Write-Host ""
Write-Host "--- Phase 5: Ride History ---" -ForegroundColor Yellow

Test-API -Name "Rider History" -Method GET -Url "$BASE_URL/rides/history/rider" -Token $RIDER_TOKEN
Test-API -Name "Driver History" -Method GET -Url "$BASE_URL/rides/history/driver" -Token $DRIVER_TOKEN

# ── Phase 6: Admin ──
Write-Host ""
Write-Host "--- Phase 6: Admin Operations ---" -ForegroundColor Yellow

Test-API -Name "Admin: List All Drivers" -Method GET -Url "$BASE_URL/admin/drivers" -Token $ADMIN_TOKEN
Test-API -Name "Admin: Get Driver Details" -Method GET -Url "$BASE_URL/admin/drivers/$DRIVER_ID" -Token $ADMIN_TOKEN

# ── Summary ──
Write-Host ""
Write-Host "============================================" -ForegroundColor Cyan
Write-Host "  RESULTS: $PASS passed, $FAIL failed" -ForegroundColor $(if ($FAIL -eq 0) { "Green" } else { "Red" })
Write-Host "============================================" -ForegroundColor Cyan
Write-Host ""
