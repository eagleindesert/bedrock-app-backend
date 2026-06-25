<#
docs/api-files/auth.md 의 엔드포인트를 순서대로 호출하는 테스트 스크립트.
실행: powershell -File scripts\auth.ps1
#>

$BaseUrl = "http://localhost:8080"
$CookieFile = Join-Path $PSScriptRoot "cookies-auth.txt"
if (Test-Path $CookieFile) { Remove-Item $CookieFile }

function Invoke-Api {
    param(
        [string]$Method,
        [string]$Url,
        [string]$Body,
        [switch]$UseCookies,
        [switch]$SaveCookies,
        [string]$CookieFile
    )
    $tmpOut = New-TemporaryFile
    $curlArgs = @('-s', '-o', $tmpOut.FullName, '-w', '%{http_code}', '-X', $Method, $Url)
    if ($UseCookies) { $curlArgs += @('-b', $CookieFile) }
    if ($SaveCookies) { $curlArgs += @('-c', $CookieFile) }
    if ($Body) { $curlArgs += @('-H', 'Content-Type: application/json', '-d', $Body.Replace('"', '\"')) }

    $status = & curl.exe @curlArgs
    $respBody = Get-Content -Raw $tmpOut.FullName -ErrorAction SilentlyContinue
    Remove-Item $tmpOut.FullName -ErrorAction SilentlyContinue
    [PSCustomObject]@{ Status = [int]$status; Body = $respBody }
}

function Write-Result {
    param([string]$Name, [int]$Expected, [PSCustomObject]$Result)
    $ok = $Result.Status -eq $Expected
    $tag = if ($ok) { "PASS" } else { "FAIL" }
    $color = if ($ok) { "Green" } else { "Red" }
    Write-Host ("[{0}] {1} - expected {2}, got {3}" -f $tag, $Name, $Expected, $Result.Status) -ForegroundColor $color
    if ($Result.Body) { Write-Host $Result.Body }
    Write-Host ""
}

Write-Host "=== Auth API 테스트 ===" -ForegroundColor Cyan

$suffix = Get-Random
$email = "test_$suffix@example.com"
$password = "password1234"
$nickname = "테스터$suffix"

Write-Host "[INFO] 테스트 계정 email=$email" -ForegroundColor Yellow

# 1. 회원가입
$signupBody = @{ email = $email; password = $password; nickname = $nickname } | ConvertTo-Json -Compress
$r = Invoke-Api -Method POST -Url "$BaseUrl/api/auth/signup" -Body $signupBody
Write-Result "회원가입" 200 $r

# 2. 중복 회원가입 (이미 사용 중인 이메일)
$r = Invoke-Api -Method POST -Url "$BaseUrl/api/auth/signup" -Body $signupBody
Write-Host ("[INFO] 중복 회원가입 응답 status={0}" -f $r.Status) -ForegroundColor Yellow
if ($r.Body) { Write-Host $r.Body }
Write-Host ""

# 3. 로그인 실패 (잘못된 비밀번호)
$badLoginBody = @{ email = $email; password = "wrongpassword" } | ConvertTo-Json -Compress
$r = Invoke-Api -Method POST -Url "$BaseUrl/api/auth/login" -Body $badLoginBody
Write-Result "로그인 실패(잘못된 비밀번호)" 401 $r

# 4. 로그인 성공
$loginBody = @{ email = $email; password = $password } | ConvertTo-Json -Compress
$r = Invoke-Api -Method POST -Url "$BaseUrl/api/auth/login" -Body $loginBody -SaveCookies -CookieFile $CookieFile
Write-Result "로그인 성공" 200 $r

# 5. 로그아웃
$r = Invoke-Api -Method POST -Url "$BaseUrl/api/auth/logout" -UseCookies -CookieFile $CookieFile
Write-Result "로그아웃" 200 $r

# 6. 재로그인 (탈퇴 테스트 준비)
$r = Invoke-Api -Method POST -Url "$BaseUrl/api/auth/login" -Body $loginBody -SaveCookies -CookieFile $CookieFile
Write-Result "재로그인" 200 $r

# 7. 회원 탈퇴
$r = Invoke-Api -Method DELETE -Url "$BaseUrl/api/auth/withdraw" -UseCookies -CookieFile $CookieFile
Write-Result "회원 탈퇴" 200 $r

# 8. 탈퇴 후 로그인 시도 (실패해야 함)
$r = Invoke-Api -Method POST -Url "$BaseUrl/api/auth/login" -Body $loginBody
Write-Result "탈퇴 후 로그인 시도" 401 $r

Remove-Item $CookieFile -ErrorAction SilentlyContinue
