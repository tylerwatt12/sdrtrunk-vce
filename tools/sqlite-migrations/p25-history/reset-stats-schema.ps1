param(
    [Parameter(Mandatory = $true)]
    [string]$InstallDir,

    [Parameter(Mandatory = $true)]
    [string]$Database
)

$ErrorActionPreference = 'Stop'
$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$classpath = Join-Path $InstallDir 'lib\*'
$source = Join-Path $scriptDir 'P25HistoryResetStatsSchema.java'
$java = if(Test-Path (Join-Path $InstallDir 'runtime\bin\java.exe')) {
    Join-Path $InstallDir 'runtime\bin\java.exe'
} else {
    'java.exe'
}

& $java --enable-native-access=ALL-UNNAMED -cp $classpath $source $Database
if($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}
