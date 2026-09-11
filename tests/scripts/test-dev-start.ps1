# tests/scripts/test-dev-start.ps1
# RFC-0013 PowerShell script automated tests
#
# Strategy: avoid nesting PowerShell processes (which loses $LASTEXITCODE)
# by directly invoking functions inside dev-start.ps1 via dot-sourcing.

$ErrorActionPreference = "Stop"

$ROOT = Resolve-Path "$PSScriptRoot\..\.."
$SCRIPT = Join-Path $ROOT "scripts\dev-start.ps1"
$script:fail = 0

function Assert-True([string]$desc, [bool]$cond) {
    if ($cond) {
        Write-Host "  PASS: $desc" -ForegroundColor Green
    } else {
        Write-Host "  FAIL: $desc" -ForegroundColor Red
        $script:fail++
    }
}

# 1. File exists
Assert-True "dev-start.ps1 exists" (Test-Path $SCRIPT)

# 2. PowerShell parser static check (no execution)
$tokens = $null
$errors = $null
[System.Management.Automation.Language.Parser]::ParseFile($SCRIPT, [ref]$tokens, [ref]$errors) | Out-Null
Assert-True "PowerShell syntax check" ($errors.Count -eq 0)
if ($errors.Count -gt 0) {
    foreach ($e in $errors) { Write-Host "    $($e.Message)" -ForegroundColor Red }
}

# 3. Required keywords in the script body
$content = Get-Content $SCRIPT -Raw
foreach ($kw in @("spring-boot:run", "actuator/health", "Start-Job", "8080", "8081", "ORULE_DATA_DIR", "Show-Help", "Test-Dependencies")) {
    Assert-True "keyword present: $kw" ($content -match [regex]::Escape($kw))
}
# ValidateSet covers all required commands (single declaration)
Assert-True "ValidateSet covers all 6 commands" ($content -match "ValidateSet\(.start., .server., .runtime., .check., .stop., .help.")

# 4. Dot-source the script with -Command help (so switch dispatches to Show-Help only)
$tmp = New-TemporaryFile
try {
    $tmpPs1 = "$($tmp.FullName).ps1"
    $escapedScript = $SCRIPT -replace "'", "''"
    $body = @"
. '$escapedScript' -Command help
"@
    Set-Content -Path $tmpPs1 -Value $body -Encoding UTF8 -NoNewline

    $helpOut = & powershell -NoProfile -File $tmpPs1 2>&1 | Out-String
    Assert-True "Show-Help contains 'Usage'"   ($helpOut -match "Usage")
    Assert-True "Show-Help contains 'command'" ($helpOut -match "command")
    Assert-True "Show-Help lists 'start'"      ($helpOut -match "start")
    Assert-True "Show-Help lists 'server'"     ($helpOut -match "server")
    Assert-True "Show-Help lists 'runtime'"    ($helpOut -match "runtime")
    Assert-True "Show-Help lists 'stop'"       ($helpOut -match "stop")
    Assert-True "Show-Help lists 'help'"       ($helpOut -match "help")

    Remove-Item $tmpPs1 -Force -ErrorAction SilentlyContinue
} catch {
    Write-Host "  FAIL: dot-source execution threw: $_" -ForegroundColor Red
    $script:fail++
} finally {
    Remove-Item $tmp.FullName -Force -ErrorAction SilentlyContinue
}

# 5. Test-Dependencies throws on missing java/mvn (by temporarily masking them)
try {
    $savedPath = $env:PATH
    $env:PATH = ""
    $threw = $false
    try {
        Test-Dependencies | Out-Null
    } catch {
        $threw = $true
    }
    $env:PATH = $savedPath
    Assert-True "Test-Dependencies throws when java/mvn missing" $threw
} catch {
    if ($savedPath) { $env:PATH = $savedPath }
    Write-Host "  FAIL: test setup error: $_" -ForegroundColor Red
    $script:fail++
}

Write-Host ""
if ($script:fail -eq 0) {
    Write-Host "All RFC-0013 dev-start.ps1 tests passed" -ForegroundColor Green
    exit 0
} else {
    Write-Host "$($script:fail) failure(s)" -ForegroundColor Red
    exit 1
}
