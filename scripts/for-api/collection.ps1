<#
CollectionController(/api/v1/collections) 의 엔드포인트를 순서대로 호출하는 테스트 스크립트.
인증이 필요하므로 회원가입/로그인을 먼저 수행한다 (docs/api-files/auth.md 참고).
실행: powershell -File scripts\for-api\collection.ps1
#>

$BaseUrl = "http://localhost:8080"
$CookieFile = Join-Path $PSScriptRoot "cookies-collection.txt"
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
    if ($null -ne $global:TestResults) {
        $global:TestResults += [PSCustomObject]@{ Script = $MyInvocation.ScriptName | Split-Path -Leaf; Name = $Name; Status = $ok }
    }
    $tag = if ($ok) { "PASS" } else { "FAIL" }
    $color = if ($ok) { "Green" } else { "Red" }
    Write-Host ("[{0}] {1} - expected {2}, got {3}" -f $tag, $Name, $Expected, $Result.Status) -ForegroundColor $color
    if ($Result.Body) { Write-Host $Result.Body }
    Write-Host ""
}

# Setup: 회원가입 + 로그인 (collection API는 인증 필요, owner는 세션에서 자동 지정)
$suffix = Get-Random
$email = "collection_test_$suffix@example.com"
$password = "password1234"
$signupBody = @{ email = $email; password = $password; nickname = "ColTester" } | ConvertTo-Json -Compress
Invoke-Api -Method POST -Url "$BaseUrl/api/auth/signup" -Body $signupBody | Out-Null
$loginBody = @{ email = $email; password = $password } | ConvertTo-Json -Compress
$r = Invoke-Api -Method POST -Url "$BaseUrl/api/auth/login" -Body $loginBody -SaveCookies -CookieFile $CookieFile
Write-Result "로그인 (setup)" 200 $r

Write-Host "=== Collection API 테스트 ===" -ForegroundColor Cyan

# 0. 인증 없이 목록 조회 -> 401
$r = Invoke-Api -Method GET -Url "$BaseUrl/api/v1/collections"
Write-Result "인증 없이 목록 조회" 401 $r

# 1. 초기 목록 조회 (아직 소속된 컬렉션 없음) -> 200, 빈 배열
$r = Invoke-Api -Method GET -Url "$BaseUrl/api/v1/collections" -UseCookies -CookieFile $CookieFile
Write-Result "초기 목록 조회 (빈 배열)" 200 $r

# 2. 생성 (notebook)
$createBody = @{ kind = "notebook"; name = "학습 노트"; color = "#4C6EF5"; icon = "book"; attributes = @{ subject = "수학"; tags = @("study", "exam") } } | ConvertTo-Json -Compress -Depth 5
$r = Invoke-Api -Method POST -Url "$BaseUrl/api/v1/collections" -Body $createBody -UseCookies -CookieFile $CookieFile
Write-Result "컬렉션 생성 (notebook)" 201 $r
$collectionId = ($r.Body | ConvertFrom-Json).id

# 3. 생성 (calendar) - kind 필터 검증용
$createCalBody = @{ kind = "calendar"; name = "학기 일정"; color = "#F03E3E"; icon = "calendar" } | ConvertTo-Json -Compress -Depth 5
$r = Invoke-Api -Method POST -Url "$BaseUrl/api/v1/collections" -Body $createCalBody -UseCookies -CookieFile $CookieFile
Write-Result "컬렉션 생성 (calendar)" 201 $r

# 4. kind 없이 생성 -> 400
$noKindBody = @{ name = "kind 없음" } | ConvertTo-Json -Compress
$r = Invoke-Api -Method POST -Url "$BaseUrl/api/v1/collections" -Body $noKindBody -UseCookies -CookieFile $CookieFile
Write-Result "kind 없이 생성" 400 $r

# 5. 유효하지 않은 kind 로 생성 -> 400
$badKindBody = @{ kind = "invalid_kind"; name = "잘못된 종류" } | ConvertTo-Json -Compress
$r = Invoke-Api -Method POST -Url "$BaseUrl/api/v1/collections" -Body $badKindBody -UseCookies -CookieFile $CookieFile
Write-Result "유효하지 않은 kind 로 생성" 400 $r

# 6. 전체 목록 조회 -> 200 (notebook + calendar = 2건)
$r = Invoke-Api -Method GET -Url "$BaseUrl/api/v1/collections" -UseCookies -CookieFile $CookieFile
Write-Result "전체 목록 조회" 200 $r

# 7. kind 필터 조회 (notebook) -> 200
$r = Invoke-Api -Method GET -Url "$BaseUrl/api/v1/collections?kind=notebook" -UseCookies -CookieFile $CookieFile
Write-Result "kind=notebook 필터 조회" 200 $r

# 8. kind 필터 조회 (semester, 소속 없음) -> 200, 빈 배열
$r = Invoke-Api -Method GET -Url "$BaseUrl/api/v1/collections?kind=semester" -UseCookies -CookieFile $CookieFile
Write-Result "kind=semester 필터 조회 (빈 배열)" 200 $r

# 9. 유효하지 않은 kind 필터 조회 -> 400
$r = Invoke-Api -Method GET -Url "$BaseUrl/api/v1/collections?kind=invalid_kind" -UseCookies -CookieFile $CookieFile
Write-Result "유효하지 않은 kind 필터 조회" 400 $r

# Cleanup
Invoke-Api -Method DELETE -Url "$BaseUrl/api/auth/withdraw" -UseCookies -CookieFile $CookieFile | Out-Null
Remove-Item $CookieFile -ErrorAction SilentlyContinue
