param(
    [long]$OrderId = 0,
    [switch]$Execute,
    [int]$AgeSeconds = 120,
    [int]$Batch = 100,
    [string]$Jar = "$PSScriptRoot/../target/smart-life-0.0.1-SNAPSHOT.jar"
)
$ErrorActionPreference = 'Stop'
if ($PSBoundParameters.ContainsKey('OrderId') -and $OrderId -le 0) { throw 'OrderId must be positive when explicitly supplied.' }
if ($AgeSeconds -lt 30 -or $AgeSeconds -gt 2592000) { throw 'AgeSeconds must be between 30 and 2592000.' }
if ($Batch -lt 1 -or $Batch -gt 500) { throw 'Batch must be between 1 and 500.' }
$javaBin = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin/java.exe' } else { 'java' }
$recoveryArgs = @('-jar', (Resolve-Path -LiteralPath $Jar).Path,
    '--spring.main.web-application-type=none', '--smartlife.orders.recovery-cli=true',
    '--smartlife.orders.consumer-enabled=false', '--smartlife.orders.recovery-enabled=false',
    "--smartlife.orders.recovery-age-seconds=$AgeSeconds", "--smartlife.orders.recovery-batch=$Batch",
    "--recover.execute=$($Execute.IsPresent.ToString().ToLowerInvariant())")
if ($OrderId -gt 0) { $recoveryArgs += "--recover.order-id=$OrderId" }
& $javaBin @recoveryArgs
exit $LASTEXITCODE
