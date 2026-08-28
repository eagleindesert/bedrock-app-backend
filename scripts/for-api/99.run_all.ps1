<#
.SYNOPSIS
실행 스크립트를 순차적으로 모두 실행하고 테스트 결과를 요약합니다.
#>

$ErrorActionPreference = "Stop"
$global:TestResults = @()

Write-Host "=========================================" -ForegroundColor Cyan
Write-Host "Running auth.ps1" -ForegroundColor Cyan
Write-Host "=========================================" -ForegroundColor Cyan
& (Join-Path $PSScriptRoot "auth.ps1")

Write-Host ""
Write-Host "=========================================" -ForegroundColor Cyan
Write-Host "Running item.ps1" -ForegroundColor Cyan
Write-Host "=========================================" -ForegroundColor Cyan
& (Join-Path $PSScriptRoot "item.ps1")

Write-Host ""
Write-Host "=========================================" -ForegroundColor Cyan
Write-Host "Running collection.ps1" -ForegroundColor Cyan
Write-Host "=========================================" -ForegroundColor Cyan
& (Join-Path $PSScriptRoot "collection.ps1")

Write-Host ""
Write-Host "=========================================" -ForegroundColor Cyan
Write-Host "Test Summary" -ForegroundColor Cyan
Write-Host "=========================================" -ForegroundColor Cyan

$passCount = ($global:TestResults | Where-Object { $_.Status -eq $true }).Count
$failCount = ($global:TestResults | Where-Object { $_.Status -eq $false }).Count
$totalCount = $global:TestResults.Count

Write-Host "Total Tests : $totalCount"
Write-Host "Passed      : $passCount" -ForegroundColor Green
Write-Host "Failed      : $failCount" -ForegroundColor Red

if ($failCount -gt 0) {
    Write-Host ""
    Write-Host "Failed Tests Details:" -ForegroundColor Red
    foreach ($fail in ($global:TestResults | Where-Object { $_.Status -eq $false })) {
        Write-Host "  - [FAIL] $($fail.Script): $($fail.Name)" -ForegroundColor Red
    }
} else {
    Write-Host ""
    Write-Host "All API tests executed successfully!" -ForegroundColor Green
}

# Cleanup
$global:TestResults = $null
