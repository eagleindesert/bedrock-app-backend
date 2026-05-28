$baseUrl = "http://localhost:8080/api/auth"

# 1. 회원가입
Write-Host "1. Signup Test (test2@example.com)..." -ForegroundColor Cyan
$signupBody = @{
    email = "test2@example.com"
    password = "password123"
    nickname = "tester2"
} | ConvertTo-Json -Depth 5

try {
    $response = Invoke-WebRequest -Uri "$baseUrl/signup" -Method Post -Body $signupBody -ContentType "application/json"
    Write-Host "Signup Success. StatusCode: $($response.StatusCode)`n" -ForegroundColor Green
} catch {
    Write-Host "Signup Failed or Already Exists: $($_.Exception.Message)`n" -ForegroundColor Yellow
}

# 2. 로그인 및 세션 저장
Write-Host "2. Login Test..." -ForegroundColor Cyan
$loginBody = @{
    email = "test2@example.com"
    password = "password123"
} | ConvertTo-Json -Depth 5

$session = New-Object Microsoft.PowerShell.Commands.WebRequestSession

try {
    $response = Invoke-WebRequest -Uri "$baseUrl/login" -Method Post -Body $loginBody -ContentType "application/json" -WebSession $session
    Write-Host "Login Success. StatusCode: $($response.StatusCode)" -ForegroundColor Green
    
    $cookies = $session.Cookies.GetCookies("http://localhost:8080")
    Write-Host "Received Cookies:"
    $cookies | ForEach-Object { Write-Host "$($_.Name) = $($_.Value)" -ForegroundColor Gray }
    Write-Host ""
} catch {
    Write-Host "Login Failed: $($_.Exception.Message)" -ForegroundColor Red
    exit
}

# 3. 로그아웃
Write-Host "3. Logout Test..." -ForegroundColor Cyan
try {
    $response = Invoke-WebRequest -Uri "$baseUrl/logout" -Method Post -WebSession $session
    Write-Host "Logout Success. StatusCode: $($response.StatusCode)`n" -ForegroundColor Green
} catch {
    Write-Host "Logout Failed: $($_.Exception.Message)`n" -ForegroundColor Red
}

# 4. 로그아웃 후 다시 로그인 (회원탈퇴를 위해)
Write-Host "4. Re-Login Test..." -ForegroundColor Cyan
$session = New-Object Microsoft.PowerShell.Commands.WebRequestSession
try {
    $response = Invoke-WebRequest -Uri "$baseUrl/login" -Method Post -Body $loginBody -ContentType "application/json" -WebSession $session
    Write-Host "Re-Login Success. StatusCode: $($response.StatusCode)`n" -ForegroundColor Green
} catch {
    Write-Host "Re-Login Failed: $($_.Exception.Message)`n" -ForegroundColor Red
    exit
}

# 5. 회원탈퇴
Write-Host "5. Withdraw Test..." -ForegroundColor Cyan
try {
    $response = Invoke-WebRequest -Uri "$baseUrl/withdraw" -Method Delete -WebSession $session
    Write-Host "Withdraw Success. StatusCode: $($response.StatusCode)`n" -ForegroundColor Green
} catch {
    Write-Host "Withdraw Failed: $($_.Exception.Message)`n" -ForegroundColor Red
}
