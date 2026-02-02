<#
PowerShell script: report-missing-javadoc.ps1
Scans Java sources for public classes/interfaces/enums and public methods
that do not have a preceding Javadoc block (heuristic). Produces a
report at build/reports/javadoc-missing.txt (non-failing).
#>
param(
    [string[]]$paths = @("core/src/main/java","web/src/main/java"),
    [string]$out = "build/reports/javadoc-missing.txt"
)

New-Item -ItemType Directory -Force -Path (Split-Path $out) | Out-Null
"Missing Javadoc report (heuristic)" | Out-File -FilePath $out -Encoding utf8
"Scanned paths: $($paths -join ', ')" | Out-File -FilePath $out -Append -Encoding utf8
"" | Out-File -FilePath $out -Append -Encoding utf8

$patternDecl = 'public\s+(class|interface|enum)\s+\w+'
$patternMethod = 'public\s+\S+\s+\w+\s*\('

foreach ($p in $paths) {
    if (-not (Test-Path $p)) { continue }
    Get-ChildItem -Path $p -Recurse -Filter *.java | ForEach-Object {
        $file = $_.FullName
        $lines = Get-Content -Raw -Path $file -Encoding UTF8 -ErrorAction SilentlyContinue -Split "\r?\n"
        for ($i=0; $i -lt $lines.Length; $i++) {
            $line = $lines[$i]
            if ($line -match $patternDecl -or $line -match $patternMethod) {
                # look back up to 3 non-blank lines for a Javadoc start '/**'
                $has = $false
                $lookback = 1
                $nonBlankSeen = 0
                while ($lookback -le 6 -and ($i - $lookback) -ge 0) {
                    $prev = $lines[$i - $lookback].Trim()
                    if ($prev -eq '') { $lookback++; continue }
                    if ($prev.StartsWith('/**')) { $has = $true; break }
                    # if previous line is annotation or modifier continue scanning
                    if ($prev.StartsWith('@') -or $prev -match '^(public|protected|private|static|final|synchronized)') { $lookback++; continue }
                    break
                }
                if (-not $has) {
                    "${file}:${($i+1)}: ${line.Trim()}" | Out-File -FilePath $out -Append -Encoding utf8
                }
            }
        }
    }
}

Write-Output "Wrote $out"
exit 0
