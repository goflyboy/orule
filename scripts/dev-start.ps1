# scripts/dev-start.ps1
# One-shot launcher for orule local development (Windows PowerShell)
#
# Usage:
#   .\scripts\dev-start.ps1           # start server + runtime
#   .\scripts\dev-start.ps1 server    # only server
#   .\scripts\dev-start.ps1 runtime   # only runtime
#   .\scripts\dev-start.ps1 check     # dependency check only
#   .\scripts\dev-start.ps1 stop      # stop all backend services
#   .\scripts\dev-start.ps1 db-console # open H2 console in default browser (auto-start server if needed)

[CmdletBinding()]
param(
    [ValidateSet('start', 'server', 'runtime', 'check', 'stop', 'db-console', 'help')]
    [string]$Command = 'start',

    [int]$ServerPort = 8080,
    [int]$RuntimePort = 8081,
    [int]$HealthTimeoutSeconds = 60
)

$ErrorActionPreference = "Stop"

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$RootDir = Split-Path -Parent $ScriptDir

function Log($msg) { Write-Host "[$(Get-Date -Format 'HH:mm:ss')] $msg" -ForegroundColor Green }
function Warn($msg) { Write-Host "[$(Get-Date -Format 'HH:mm:ss')] $msg" -ForegroundColor Yellow }
function Err($msg) { Write-Host "[$(Get-Date -Format 'HH:mm:ss')] ERROR: $msg" -ForegroundColor Red; exit 1 }

function Test-Dependencies {
    if (-not (Get-Command java -ErrorAction SilentlyContinue)) { Err "java not found, please install JDK 21+" }
    if (-not (Get-Command mvn  -ErrorAction SilentlyContinue)) { Err "mvn not found, please install Maven 3.9+" }

    # java -version writes to stderr; capture without letting it surface as a terminating error under $ErrorActionPreference=Stop
    $javaRaw = & {
        $prev = $ErrorActionPreference
        $ErrorActionPreference = 'Continue'
        try { java -version 2>&1 } finally { $ErrorActionPreference = $prev }
    }
    $javaVer = ($javaRaw | Select-String -Pattern '"(\d+)\.' | ForEach-Object { $_.Matches[0].Groups[1].Value }) | Select-Object -First 1
    if ([int]$javaVer -lt 21) { Err "JDK 21+ required, current $javaVer" }

    Log "[OK] dependency check passed (java=$javaVer)"
}

function Get-DataDir {
    $envOverride = $env:ORULE_DATA_DIR
    if ($envOverride) { return $envOverride }
    return (Join-Path $env:USERPROFILE "orule\data")
}

function Initialize-DataDirs {
    $dataDir = Get-DataDir
    foreach ($sub in @('db', 'artifacts', 'logs')) {
        $p = Join-Path $dataDir $sub
        if (-not (Test-Path $p)) { New-Item -ItemType Directory -Force -Path $p | Out-Null }
    }
    return $dataDir
}

function Wait-Health {
    param(
        [Parameter(Mandatory = $true)][string]$Url,
        [int]$TimeoutSeconds = 60
    )
    Log "waiting for $Url (timeout ${TimeoutSeconds}s)..."
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        try {
            $resp = Invoke-WebRequest -Uri $Url -UseBasicParsing -TimeoutSec 3 -ErrorAction Stop
            if ($resp.StatusCode -eq 200) {
                Log "[OK] health check passed: $Url"
                return
            }
        } catch {
            # ignore and retry
        }
        Start-Sleep -Seconds 2
    }
    Err "health check timeout: $Url"
}

function Start-BackendModule {
    param(
        [Parameter(Mandatory = $true)][ValidateSet('server', 'runtime')][string]$Name,
        [Parameter(Mandatory = $true)][int]$Port,
        [Parameter(Mandatory = $true)][string]$LogDir
    )

    Log "starting orule-${Name} on port ${Port}... (logs -> ${logPath})"
    $moduleDir = Join-Path $RootDir "packages\orule-${Name}"
    $logPath = Join-Path $LogDir "${Name}.log"

    # Tee Maven/Spring output to the log file so failures are diagnosable.
    $job = Start-Job -ScriptBlock {
        param($dir, $logPath)
        Set-Location $dir
        mvn -DskipTests spring-boot:run 2>&1 | Tee-Object -FilePath $logPath | Out-Null
    } -ArgumentList $moduleDir, $logPath

    return [PSCustomObject]@{ Job = $job; LogPath = $logPath }
}

function Stop-AllBackends {
    Get-Job | Where-Object { $_.Command -like '*spring-boot:run*' -or $_.Name -like '*orule-*' } | ForEach-Object {
        Log "stopping Job $($_.Id)"
        Stop-Job -Job $_ -PassThru | Remove-Job -Force
    }
}

function Invoke-StartAll {
    Test-Dependencies
    $dataDir = Initialize-DataDirs

    $server = Start-BackendModule -Name server -Port $ServerPort -LogDir "$dataDir\logs"
    Wait-Health -Url "http://localhost:$ServerPort/actuator/health" -TimeoutSeconds $HealthTimeoutSeconds

    $runtime = Start-BackendModule -Name runtime -Port $RuntimePort -LogDir "$dataDir\logs"
    Wait-Health -Url "http://localhost:$RuntimePort/actuator/health" -TimeoutSeconds $HealthTimeoutSeconds

    Write-Host ""
    Log "[OK] all services started!"
    Write-Host ""
    Write-Host "  orule-server:  http://localhost:$ServerPort" -ForegroundColor Cyan
    Write-Host "  orule-runtime: http://localhost:$RuntimePort" -ForegroundColor Cyan
    Write-Host ""
    Write-Host "Press Ctrl+C to stop..."
}

function Invoke-StartServer {
    Test-Dependencies
    $dataDir = Initialize-DataDirs
    $server = Start-BackendModule -Name server -Port $ServerPort -LogDir "$dataDir\logs"
    Wait-Health -Url "http://localhost:$ServerPort/actuator/health" -TimeoutSeconds $HealthTimeoutSeconds
}

function Invoke-StartRuntime {
    Test-Dependencies
    $dataDir = Initialize-DataDirs
    $runtime = Start-BackendModule -Name runtime -Port $RuntimePort -LogDir "$dataDir\logs"
    Wait-Health -Url "http://localhost:$RuntimePort/actuator/health" -TimeoutSeconds $HealthTimeoutSeconds
}

function Invoke-Check {
    Test-Dependencies
    Log "current modules:"
    Get-ChildItem (Join-Path $RootDir "packages") | ForEach-Object { Write-Host "  - $($_.Name)" }
}

function Invoke-Stop {
    Log "stopping all spring-boot:run processes..."
    Stop-AllBackends
    Log "[OK] stopped"
}

function Test-ServerReachable {
    param([int]$Port)
    try {
        $resp = Invoke-WebRequest -Uri "http://localhost:$Port/actuator/health" -UseBasicParsing -TimeoutSec 2 -ErrorAction Stop
        return ($resp.StatusCode -eq 200)
    } catch {
        return $false
    }
}

function Invoke-DbConsole {
    # JDBC URL 与 application.yml 中的 dev profile 必须保持一致；
    # 如改了 application.yml，请同步修改这里。
    $h2Url = "http://localhost:$ServerPort/h2-console"
    $jdbcUrl = "jdbc:h2:mem:orule;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1"

    if (-not (Test-ServerReachable -Port $ServerPort)) {
        Log "orule-server is not running on port $ServerPort, starting it now..."
        Invoke-StartServer
    } else {
        Log "[OK] orule-server already running on port $ServerPort"
    }

    Log "opening H2 Console in your default browser..."
    Log "  URL:    $h2Url"
    Log "  Driver: org.h2.Driver"
    Log "  JDBC:   $jdbcUrl"
    Log "  User:   sa   (Password: <empty>)"
    try {
        Start-Process $h2Url
    } catch {
        Warn "could not launch browser automatically. Open the URL above manually: $h2Url"
    }

    Write-Host ""
    Write-Host "When you're done, run:  .\scripts\dev-start.ps1 stop" -ForegroundColor Cyan
    Write-Host ""
}

function Show-Help {
    @"
orule one-shot launcher (Windows PowerShell)

Usage:
  .\scripts\dev-start.ps1 [-Command <command>] [-ServerPort <port>] [-RuntimePort <port>]

Commands:
  start       start server + runtime (default)
  server      only server
  runtime     only runtime
  check       dependency check
  stop        stop all spring-boot:run processes
  db-console  open H2 web console in default browser (auto-start server if needed)
  help        show this help
"@
}

switch ($Command) {
    'start'      { Invoke-StartAll }
    'server'     { Invoke-StartServer }
    'runtime'    { Invoke-StartRuntime }
    'check'      { Invoke-Check }
    'stop'       { Invoke-Stop }
    'db-console' { Invoke-DbConsole }
    'help'       { Show-Help }
}
