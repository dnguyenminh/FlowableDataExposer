<#
.SYNOPSIS
  Verify end-to-end local pipeline for an Order: app start, case persist, reindex, plain-table check.

.DESCRIPTION
  - Restarts the web app (file-based H2) with file logging.
  - Waits for startup (Hikari + Spring started + H2 console line).
  - Calls POST /api/orders to create a case, triggers reindex, and verifies rows in
    sys_case_data_store and case_plain_order.
  - Prints concise diagnostics and exits non-zero on failures.

USAGE
  ./scripts/verify-e2e.ps1            # restart app (recommended), run full checks
  ./scripts/verify-e2e.ps1 -NoRestart  # do not restart app; use currently-running JVM/logs

WARNING
  - Dev-only: script may restart your local server and will replace any in-memory DB
    with the file-based DB if you allow restart. Do NOT run against production.
#>
[CmdletBinding()]
param(
    [switch]$NoRestart,
    [int]$TimeoutSeconds = 60,
    [string]$Payload = '{"total":123.45,"customer":{"id":"TST01"},"meta":{"priority":"HIGH"}}'
)

function Fail([string]$msg, [int]$code = 1) {
    Write-Error $msg
    exit $code
}

Push-Location (Split-Path -Path $MyInvocation.MyCommand.Definition -Parent) | Out-Null
Push-Location .. | Out-Null
if (-not (Test-Path .\gradlew.bat)) { Fail "run this script from the repository root (where gradlew.bat lives)" }

$logDir = 'web\build\logs'
$logFile = Join-Path $logDir 'app.log'
New-Item -ItemType Directory -Force -Path $logDir | Out-Null

if (-not $NoRestart) {
    Write-Output "[1/7] Stopping any local FlowableExposer java processes (if present)"
    Get-Process -Name java -ErrorAction SilentlyContinue | Where-Object { $_.Path -like '*FlowableDataExposer*' } | Stop-Process -Force -ErrorAction SilentlyContinue

    Write-Output "[2/7] Starting app with file-based H2 and logging to $logFile"
    if (Test-Path $logFile) { Remove-Item $logFile -Force }

    $args = ':web:bootRun','--no-daemon','--console=plain',"--args=`"--spring.datasource.username=sa --spring.datasource.url=jdbc:h2:file:./web/build/h2/flowable;DB_CLOSE_DELAY=-1;AUTO_SERVER=TRUE --spring.h2.console.enabled=true --spring.h2.console.path=/h2-console --logging.file.name=$logFile`""
    $proc = Start-Process -FilePath .\gradlew.bat -ArgumentList $args -NoNewWindow -PassThru

    Write-Output "Waiting up to $TimeoutSeconds seconds for app to be ready..."
    $end = (Get-Date).AddSeconds($TimeoutSeconds)
    $started = $false
    while ((Get-Date) -lt $end) {
        if (Test-Path $logFile) {
            $txt = Get-Content $logFile -Raw -ErrorAction SilentlyContinue
            if ($txt -match 'HikariPool-1 - Added connection' -and $txt -match 'Started FlowableExposerApplication') { $started = $true; break }
            if ($txt -match 'APPLICATION FAILED TO START') { Write-Output "App failed to start; see $logFile"; break }
        }
        Start-Sleep -Seconds 1
    }
    if (-not $started) { Fail "application did not start within timeout; check $logFile" }
} else {
    Write-Output "NoRestart specified — using currently-running server and existing logs (if any)."
    if (-not (Test-Path $logFile)) { Write-Output "No log file found at $logFile — you must provide the JDBC URL from the running JVM's console." }
}

# show Hikari / H2 console lines (if present)
if (Test-Path $logFile) {
    Get-Content $logFile -Tail 200 | Select-String 'HikariPool-1 - Added connection|H2 console available|Started FlowableExposerApplication' -Context 0,0 | ForEach-Object { Write-Output $_.Line }
} else {
    Write-Output "(no app.log available to parse)"
}

# Create case via REST
Write-Output "[3/7] Creating an Order via REST"
try {
    $resp = Invoke-RestMethod -Uri 'http://localhost:8080/api/orders' -Method Post -Body $Payload -ContentType 'application/json' -TimeoutSec 10
} catch {
    Fail "POST /api/orders failed: $($_.Exception.Message)"
}
$caseId = $resp.id
if (-not $caseId) { Fail "server did not return case id in response: $(ConvertTo-Json $resp -Depth 3)" }
Write-Output "CASE_ID: $caseId"

# Attempt to discover JDBC URL from log
$jdbcUrl = $null
if (Test-Path $logFile) {
    $h = Select-String -Path $logFile -Pattern 'HikariPool-1 - Added connection' -SimpleMatch | Select-Object -Last 1
    if ($h -and ($h.Line -match 'url=(\S+)')) { $jdbcUrl = $matches[1]; Write-Output "JDBC URL discovered: $jdbcUrl" }
    $h2 = Select-String -Path $logFile -Pattern 'H2 console available' -SimpleMatch | Select-Object -Last 1
    if ($h2) { Write-Output $h2.Line }
}

# If file-based DB available, run direct checks; otherwise instruct using H2 Console
if ($jdbcUrl -and $jdbcUrl -like 'jdbc:h2:file:*') {
    Write-Output "[4/7] Querying file H2 DB ($jdbcUrl)"
    Add-Type -AssemblyName 'System.Data'
    try {
        $conn = [System.Data.DriverManager]::GetConnection($jdbcUrl, 'SA', '')
        $cmd = $conn.CreateCommand()
        $cmd.CommandText = "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME IN ('SYS_CASE_DATA_STORE','CASE_PLAIN_ORDER')"
        Write-Output "TABLES_PRESENT_COUNT: $($cmd.ExecuteScalar())"

        $cmd.CommandText = "SELECT id, case_instance_id, substring(payload,1,400) FROM sys_case_data_store WHERE case_instance_id = '$caseId' ORDER BY created_at DESC LIMIT 5"
        $r = $cmd.ExecuteReader(); $found=0
        while ($r.Read()) { $found++; Write-Output ("BLOB_ROW: " + $r.GetValue(0) + ' | ' + $r.GetString(1) + ' | ' + $r.GetString(2)) }
        if ($found -eq 0) { Write-Output "BLOB_ROW: (no rows for $caseId)" }

        $cmd.CommandText = "SELECT * FROM case_plain_order WHERE case_instance_id = '$caseId'"
        $r2 = $cmd.ExecuteReader(); $plainFound = 0
        while ($r2.Read()) { $plainFound++; $cols=@(); for ($i=0;$i -lt $r2.FieldCount;$i++){ $cols += ($r2.GetName($i) + '=' + ($r2.IsDBNull($i) ? '<null>' : $r2.GetValue($i))) } ; Write-Output ('PLAIN_ROW: ' + ($cols -join ', ')) }
        if ($plainFound -eq 0) { Write-Output "PLAIN_ROW: (no rows yet for $caseId)" }
        $conn.Close()
    } catch {
        Write-Output "DB query failed: $($_.Exception.Message)";
    }
} else {
    Write-Output "No file-based JDBC URL discovered — if the app is using an in-memory DB, open H2 Console in the same JVM using the JDBC URL printed in the app log."
}

# Trigger reindex (best-effort) and re-check
Write-Output "[5/7] Triggering reindex for the case (best-effort)"
try { Invoke-RestMethod -Uri "http://localhost:8080/api/orders/$caseId/reindex" -Method Post -TimeoutSec 10; Write-Output 'REINDEX_TRIGGERED' } catch { Write-Output "REINDEX_FAILED: $($_.Exception.Message)" }
Start-Sleep -Seconds 2
if ($jdbcUrl -and $jdbcUrl -like 'jdbc:h2:file:*') {
    Add-Type -AssemblyName 'System.Data'
    $conn=[System.Data.DriverManager]::GetConnection($jdbcUrl,'SA',''); $cmd=$conn.CreateCommand(); $cmd.CommandText = "SELECT * FROM case_plain_order WHERE case_instance_id = '$caseId'"; $r=$cmd.ExecuteReader(); $found=0; while($r.Read()){ $found++; $vals=@(); for($i=0;$i -lt $r.FieldCount;$i++){ $vals += ($r.GetName($i) + '=' + ($r.IsDBNull($i) ? '<null>' : $r.GetValue($i))) } ; Write-Output ('PLAIN_AFTER: ' + ($vals -join ', ')) }; if($found -eq 0){ Write-Output 'PLAIN_AFTER: (no rows)'}; $conn.Close()
}

# Tail relevant log snippets for diagnostics
Write-Output "[6/7] Recent diagnostic log entries (case + errors)"
if (Test-Path $logFile) {
    Get-Content $logFile -Tail 400 | Select-String -Pattern $caseId,'persisted blob for','CasePersistDelegate vars before persist','Annotated JSON for case','reindex error for','ERROR' -Context 3,3 | ForEach-Object { Write-Output $_.ToString() }
} else { Write-Output "No log file to tail ($logFile)" }

# Run focused tests (optional)
Write-Output "[7/7] Running focused unit/integration tests (fast checks)"
.\gradlew.bat :core:test --tests "**.CaseDataPersistServiceIntegrationTest" --no-daemon --console=plain
.\gradlew.bat :core:test --tests "**.CasePersistDelegateTest" --no-daemon --console=plain

Write-Output "VERIFY SCRIPT COMPLETE"
Pop-Location; Pop-Location
