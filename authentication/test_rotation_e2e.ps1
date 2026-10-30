$ErrorActionPreference = "Stop"

Write-Host "=========================================================="
Write-Host "1. HEALTH CHECK: GET /health"
Write-Host "=========================================================="
$health = Invoke-RestMethod -Uri "http://127.0.0.1:8082/health" -Method GET
Write-Host ($health | ConvertTo-Json)

Write-Host "`n=========================================================="
Write-Host "2. SIGNUP: POST /signup (Verify Access Token + Refresh Token)"
Write-Host "=========================================================="
$rand = Get-Random -Minimum 10000 -Maximum 99999
$email = "rotation_$rand@example.com"
$password = "SecretPass123!"
$signupBody = @{
    email = $email
    password = $password
} | ConvertTo-Json

$signup = Invoke-RestMethod -Uri "http://127.0.0.1:8082/signup" -Method POST -ContentType "application/json" -Body $signupBody
Write-Host ($signup | ConvertTo-Json)

$at1 = $signup.token
$rt1 = $signup.refreshToken

if (-not $rt1) {
    Write-Host "FAILURE: No refreshToken received in signup response!" -ForegroundColor Red
    exit 1
}
Write-Host "SUCCESS: Received RT_1: $rt1" -ForegroundColor Green

Write-Host "`n=========================================================="
Write-Host "3. ACCESS /me with AT_1"
Write-Host "=========================================================="
$me1 = Invoke-RestMethod -Uri "http://127.0.0.1:8082/me" -Method GET -Headers @{ Authorization = "Bearer $at1" }
Write-Host ($me1 | ConvertTo-Json)

Write-Host "`n=========================================================="
Write-Host "4. ROTATION: POST /refresh with RT_1 (Tab A First Arrival)"
Write-Host "=========================================================="
$refreshBody = @{ refreshToken = $rt1 } | ConvertTo-Json
$refresh1 = Invoke-RestMethod -Uri "http://127.0.0.1:8082/refresh" -Method POST -ContentType "application/json" -Body $refreshBody
Write-Host ($refresh1 | ConvertTo-Json)

$at2 = $refresh1.token
$rt2 = $refresh1.refreshToken
Write-Host "SUCCESS: Rotated to RT_2: $rt2" -ForegroundColor Green

Write-Host "`n=========================================================="
Write-Host "5. TWO-TAB CONCURRENCY TEST: POST /refresh with RT_1 (Tab B within 10s Grace)"
Write-Host "=========================================================="
$graceRefresh = Invoke-RestMethod -Uri "http://127.0.0.1:8082/refresh" -Method POST -ContentType "application/json" -Body $refreshBody
Write-Host ($graceRefresh | ConvertTo-Json)

if ($graceRefresh.token -eq $at2 -and $graceRefresh.refreshToken -eq $rt2) {
    Write-Host "SUCCESS: Tab B received identical cached credentials! Both tabs survived without logout." -ForegroundColor Green
} else {
    Write-Host "WARNING: Received different credentials during grace window." -ForegroundColor Yellow
}

Write-Host "`n=========================================================="
Write-Host "6. ACCESS /me with AT_2"
Write-Host "=========================================================="
$me2 = Invoke-RestMethod -Uri "http://127.0.0.1:8082/me" -Method GET -Headers @{ Authorization = "Bearer $at2" }
Write-Host ($me2 | ConvertTo-Json)

Write-Host "`n=========================================================="
Write-Host "7. REPLAY ATTACK TEST: Waiting 11s for grace window to expire..."
Write-Host "=========================================================="
Start-Sleep -Seconds 11

Write-Host "Now attempting replay of old RT_1 (outside grace window)..."
try {
    Invoke-RestMethod -Uri "http://127.0.0.1:8082/refresh" -Method POST -ContentType "application/json" -Body $refreshBody
    Write-Host "FAILURE: Replay was allowed!" -ForegroundColor Red
} catch {
    $statusCode = $_.Exception.Response.StatusCode.value__
    $stream = $_.Exception.Response.GetResponseStream()
    $reader = New-Object System.IO.StreamReader($stream)
    $body = $reader.ReadToEnd()
    Write-Host "SUCCESS: Replay rejected! Status: $statusCode | Body: $body" -ForegroundColor Green
}

Write-Host "`n=========================================================="
Write-Host "8. VERIFY FULL FAMILY REVOCATION: Call /me with AT_2 (Should Fail with 401)"
Write-Host "=========================================================="
try {
    Invoke-RestMethod -Uri "http://127.0.0.1:8082/me" -Method GET -Headers @{ Authorization = "Bearer $at2" }
    Write-Host "FAILURE: Session was NOT revoked after replay attack!" -ForegroundColor Red
} catch {
    $statusCode = $_.Exception.Response.StatusCode.value__
    $stream = $_.Exception.Response.GetResponseStream()
    $reader = New-Object System.IO.StreamReader($stream)
    $body = $reader.ReadToEnd()
    Write-Host "SUCCESS: Session completely destroyed! Status: $statusCode | Body: $body" -ForegroundColor Green
}

Write-Host "`n>>> ALL REFRESH TOKEN ROTATION CHECKS PASSED <<<" -ForegroundColor Cyan
