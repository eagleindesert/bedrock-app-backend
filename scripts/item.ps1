<#
docs/api-files/item.md 의 엔드포인트를 순서대로 호출하는 테스트 스크립트.
인증이 필요하므로 회원가입/로그인을 먼저 수행한다 (docs/api-files/auth.md 참고).
실행: powershell -File scripts\item.ps1
#>

$BaseUrl = "http://localhost:8080"
$CookieFile = Join-Path $PSScriptRoot "cookies-item.txt"
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
    $tmpBody = $null
    $curlArgs = @('-s', '-o', $tmpOut.FullName, '-w', '%{http_code}', '-X', $Method, $Url)
    if ($UseCookies) { $curlArgs += @('-b', $CookieFile) }
    if ($SaveCookies) { $curlArgs += @('-c', $CookieFile) }
    if ($Body) {
        # JSON 값에 공백이 포함되면 PowerShell -> curl.exe 인자 전달 시 본문이 잘리는 문제가 있어
        # 본문을 임시 파일에 써서 -d @file 형태로 전달한다.
        $tmpBody = New-TemporaryFile
        [System.IO.File]::WriteAllText($tmpBody.FullName, $Body, [System.Text.Encoding]::UTF8)
        $curlArgs += @('-H', 'Content-Type: application/json', '-d', "@$($tmpBody.FullName)")
    }

    $status = & curl.exe @curlArgs
    $respBody = Get-Content -Raw $tmpOut.FullName -ErrorAction SilentlyContinue
    Remove-Item $tmpOut.FullName -ErrorAction SilentlyContinue
    if ($tmpBody) { Remove-Item $tmpBody.FullName -ErrorAction SilentlyContinue }
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

# Setup: 회원가입 + 로그인 (item API는 인증 필요, owner_id는 세션에서 자동 지정)
$suffix = Get-Random
$email = "item_test_$suffix@example.com"
$password = "password1234"
$signupBody = @{ email = $email; password = $password; nickname = "ItemTester" } | ConvertTo-Json -Compress
Invoke-Api -Method POST -Url "$BaseUrl/api/auth/signup" -Body $signupBody | Out-Null
$loginBody = @{ email = $email; password = $password } | ConvertTo-Json -Compress
$r = Invoke-Api -Method POST -Url "$BaseUrl/api/auth/login" -Body $loginBody -SaveCookies -CookieFile $CookieFile
Write-Result "로그인 (setup)" 200 $r

Write-Host "=== Item API 테스트 ===" -ForegroundColor Cyan

# 0. 인증 없이 목록 조회 -> 401
$r = Invoke-Api -Method GET -Url "$BaseUrl/api/v1/items"
Write-Result "인증 없이 목록 조회" 401 $r

# 1. 생성
$createBody = @{ name = "노트북"; attributes = @{ brand = "Dell"; ram = 32; tags = @("work", "portable") } } | ConvertTo-Json -Compress -Depth 5
$r = Invoke-Api -Method POST -Url "$BaseUrl/api/v1/items" -Body $createBody -UseCookies -CookieFile $CookieFile
Write-Result "아이템 생성" 201 $r
$itemId = ($r.Body | ConvertFrom-Json).id

# 2. 목록 조회
$r = Invoke-Api -Method GET -Url "$BaseUrl/api/v1/items" -UseCookies -CookieFile $CookieFile
Write-Result "목록 조회" 200 $r

# 3. 단건 조회
$r = Invoke-Api -Method GET -Url "$BaseUrl/api/v1/items/$itemId" -UseCookies -CookieFile $CookieFile
Write-Result "단건 조회" 200 $r

# 4. 존재하지 않는 아이템 조회 -> 404
$r = Invoke-Api -Method GET -Url "$BaseUrl/api/v1/items/00000000-0000-0000-0000-000000000000" -UseCookies -CookieFile $CookieFile
Write-Result "미존재 아이템 조회" 404 $r

# 5. 수정
$updateBody = @{ name = "노트북 (업무용)"; attributes = @{ brand = "Dell"; ram = 64; tags = @("work") } } | ConvertTo-Json -Compress -Depth 5
$r = Invoke-Api -Method PUT -Url "$BaseUrl/api/v1/items/$itemId" -Body $updateBody -UseCookies -CookieFile $CookieFile
Write-Result "수정" 200 $r

# 6. 삭제
$r = Invoke-Api -Method DELETE -Url "$BaseUrl/api/v1/items/$itemId" -UseCookies -CookieFile $CookieFile
Write-Result "삭제" 204 $r

# 7. 삭제 후 조회 -> 404 (soft delete 확인)
$r = Invoke-Api -Method GET -Url "$BaseUrl/api/v1/items/$itemId" -UseCookies -CookieFile $CookieFile
Write-Result "삭제 후 조회" 404 $r

# Cleanup
Invoke-Api -Method DELETE -Url "$BaseUrl/api/auth/withdraw" -UseCookies -CookieFile $CookieFile | Out-Null
Remove-Item $CookieFile -ErrorAction SilentlyContinue
