param(
    [Parameter(Mandatory = $true)]
    [string]$InstallDir,

    [Parameter(Mandatory = $true)]
    [string]$Database
)

$ErrorActionPreference = 'Stop'
$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$classpath = Join-Path $InstallDir 'lib\*'
$source = Join-Path $scriptDir 'P25HistoryResetToV11StatsSchema.java'

java -cp $classpath $source $Database
