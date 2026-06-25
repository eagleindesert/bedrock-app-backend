<#
docs/api-files/debug.md 의 엔드포인트를 순서대로 호출하는 테스트 스크립트.
인증이 필요하므로 회원가입/로그인을 먼저 수행한다 (docs/api-files/auth.md 참고).
실행: powershell -File scripts\debug.ps1
#>

$BaseUrl = "http://localhost:8080"
$CookieFile = Join-Path $PSScriptRoot "cookies-debug.txt"
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

# Setup: 회원가입 + 로그인 (debug API는 인증 필요)
$suffix = Get-Random
$email = "debug_test_$suffix@example.com"
$password = "password1234"
$signupBody = @{ email = $email; password = $password; nickname = "DebugTester" } | ConvertTo-Json -Compress
Invoke-Api -Method POST -Url "$BaseUrl/api/auth/signup" -Body $signupBody | Out-Null
$loginBody = @{ email = $email; password = $password } | ConvertTo-Json -Compress
$r = Invoke-Api -Method POST -Url "$BaseUrl/api/auth/login" -Body $loginBody -SaveCookies -CookieFile $CookieFile
Write-Result "로그인 (setup)" 200 $r

Write-Host "=== Debug API 테스트 ===" -ForegroundColor Cyan

# 1. 인증 없이 조회 -> 401
$r = Invoke-Api -Method GET -Url "$BaseUrl/api/debug"
Write-Result "인증 없이 전체 조회" 401 $r

# 2. 메시지 저장
$r = Invoke-Api -Method POST -Url "$BaseUrl/api/debug?message=hello-db" -UseCookies -CookieFile $CookieFile
Write-Result "메시지 저장" 200 $r

# 3. 전체 메시지 조회
$r = Invoke-Api -Method GET -Url "$BaseUrl/api/debug" -UseCookies -CookieFile $CookieFile
Write-Result "전체 메시지 조회" 200 $r

# Cleanup
Invoke-Api -Method DELETE -Url "$BaseUrl/api/auth/withdraw" -UseCookies -CookieFile $CookieFile | Out-Null
Remove-Item $CookieFile -ErrorAction SilentlyContinue
