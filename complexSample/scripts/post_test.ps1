Set-Location 'C:\DDrive\projects\java\FlowableDataExposer'
for ($i=0; $i -lt 60; $i++) {
    if (Test-NetConnection -ComputerName 'localhost' -Port 8080 -InformationLevel 'Quiet') {
        Write-Output "Port 8080 open"
        break
    }
    Write-Output "Waiting... $i"
    Start-Sleep -Seconds 1
}
$json = @'
{"total":43,"customer":{"id":"C123","name":"Alice Nguyen"},"meta":{"priority":"MEDIUM"},"notes":"Test note 1"}
'@
try {
    $resp = Invoke-RestMethod -Uri 'http://localhost:8080/api/orders?type=cmmn' -Method Post -Body $json -ContentType 'application/json' -TimeoutSec 30 -ErrorAction Stop
    $resp | ConvertTo-Json -Depth 5 | Write-Output
} catch {
    Write-Output "Request failed:"
    $_ | Format-List -Force
}
