<#
Usage: .\scripts\validate-models.ps1 [-Files <string[]>]

Runs the Gradle helper task that validates model XMLs and emits PNG thumbnails to build/model-validator.
By default it scans src/main/resources for BPMN, CMMN and DMN files.
#>
param(
    [string[]]$Files
)

$gradle = Join-Path $PSScriptRoot '..\gradlew.bat'
if ($Files) {
    foreach ($f in $Files) {
        & $gradle validateModels --no-daemon --console=plain --args="$f"
    }
} else {
    & $gradle validateModels --no-daemon --console=plain
}
if ($LASTEXITCODE -ne 0) { throw "validateModels failed (exit $LASTEXITCODE)" }
Write-Host "Model validation + rendering complete — see build\model-validator" -ForegroundColor Green
