param(
    [string]$DatabasePath = (Join-Path $env:USERPROFILE 'SDRTrunk\database\sdrtrunk.sqlite'),
    [string]$AppHome = $env:SDRTRUNK_APP_HOME,
    [string]$JavaHome = $env:JAVA_HOME
)

$ErrorActionPreference = 'Stop'

$scriptDirectory = Split-Path -Parent $MyInvocation.MyCommand.Path
$migrator = Join-Path $scriptDirectory 'P25HistoryV9ToV10CompactMigrator.java'

if ([string]::IsNullOrWhiteSpace($AppHome)) {
    $repoAppHome = Resolve-Path -LiteralPath (Join-Path $scriptDirectory '..\..\..\build\install\sdr-trunk') -ErrorAction SilentlyContinue

    if ($repoAppHome) {
        $AppHome = $repoAppHome.Path
    } else {
        throw 'Unable to locate SDRTrunk app home. Pass -AppHome or set SDRTRUNK_APP_HOME.'
    }
}

if (-not (Test-Path -LiteralPath $DatabasePath)) {
    throw "Database not found: $DatabasePath"
}

if (-not (Test-Path -LiteralPath (Join-Path $AppHome 'lib'))) {
    throw "SDRTrunk app home does not contain lib directory: $AppHome"
}

if (-not (Test-Path -LiteralPath $migrator)) {
    throw "Migration source file not found: $migrator"
}

if (Test-Path -LiteralPath (Join-Path $AppHome 'runtime\bin\java.exe')) {
    $java = Join-Path $AppHome 'runtime\bin\java.exe'
} elseif ($JavaHome -and (Test-Path -LiteralPath (Join-Path $JavaHome 'bin\java.exe'))) {
    $java = Join-Path $JavaHome 'bin\java.exe'
} elseif (Get-Command java -ErrorAction SilentlyContinue) {
    $java = 'java'
} else {
    throw 'Unable to locate java. Set JAVA_HOME or use a bundled runtime.'
}

& $java --enable-native-access=ALL-UNNAMED -cp (Join-Path $AppHome 'lib\*') $migrator $DatabasePath
if ($LASTEXITCODE -ne 0) {
    throw "Migration failed with exit code $LASTEXITCODE"
}
