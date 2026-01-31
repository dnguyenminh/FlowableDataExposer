<#
Usage: .\scripts\validate-metadata.ps1
Validates all JSON metadata files under src/main/resources/metadata using MetadataTool.
#>
$gradle = Join-Path $PSScriptRoot '..\gradlew.bat'
& $gradle validateMetadata --no-daemon --console=plain
if ($LASTEXITCODE -ne 0) { throw "validateMetadata failed (exit $LASTEXITCODE)" }
Write-Host "Metadata validation complete" -ForegroundColor Green
