param(
    [Parameter(Mandatory = $true)]
    [string]$DatabasePath,
    [Parameter(Mandatory = $true)]
    [string]$AppHome,
    [string]$JavaHome
)

$ErrorActionPreference = 'Stop'
$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$source = Join-Path $scriptDir 'P25HistoryV17ToV18CallOutputMetricsMigrator.java'
$java = if($JavaHome) {
    Join-Path $JavaHome 'bin\java.exe'
} elseif(Test-Path (Join-Path $AppHome 'runtime\bin\java.exe')) {
    Join-Path $AppHome 'runtime\bin\java.exe'
} else {
    'java.exe'
}

& $java --enable-native-access=ALL-UNNAMED -cp (Join-Path $AppHome 'lib\*') $source $DatabasePath
if($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}
