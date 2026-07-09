param(
    [string]$DatabasePath = (Join-Path $env:USERPROFILE 'SDRTrunk\database\sdrtrunk.sqlite')
)

$ErrorActionPreference = 'Stop'

$scriptDirectory = Split-Path -Parent $MyInvocation.MyCommand.Path
$sqlFile = Join-Path $scriptDirectory 'v8-to-v9-drop-neighbor-nac.sql'

if (-not (Get-Command sqlite3 -ErrorAction SilentlyContinue)) {
    throw 'sqlite3 is required for this external migration.'
}

if (-not (Test-Path -LiteralPath $DatabasePath)) {
    throw "Database not found: $DatabasePath"
}

function Invoke-SqliteScalar {
    param([string]$Sql)

    $output = $Sql | sqlite3 "$DatabasePath"
    if ($LASTEXITCODE -ne 0) {
        throw "sqlite3 failed while running: $Sql"
    }

    $first = $output | Select-Object -First 1

    if ($null -eq $first) {
        return ''
    }

    return $first.ToString().Trim()
}

$schemaVersion = Invoke-SqliteScalar "SELECT value FROM database_metadata WHERE key = 'p25_activity_schema_version';"
$nacColumns = Invoke-SqliteScalar "SELECT COUNT(*) FROM pragma_table_info('site_neighbor') WHERE name = 'nac';"

if ($schemaVersion -eq '9' -and $nacColumns -eq '0') {
    Write-Host 'Database is already at P25 activity schema v9 with no site_neighbor.nac column.'
    exit 0
}

if ($schemaVersion -ne '8') {
    throw "Expected p25_activity_schema_version 8, found [$schemaVersion]. Refusing migration."
}

if ($nacColumns -ne '1') {
    throw "Expected exactly one site_neighbor.nac column, found [$nacColumns]. Refusing migration."
}

$integrity = Invoke-SqliteScalar 'PRAGMA integrity_check;'
if ($integrity -ne 'ok') {
    throw "Integrity check failed before migration: $integrity"
}

[void](Invoke-SqliteScalar 'PRAGMA wal_checkpoint(TRUNCATE);')

$timestamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$backupPath = "$DatabasePath.backup-v8-to-v9-$timestamp"
Copy-Item -LiteralPath $DatabasePath -Destination $backupPath
Write-Host "Backup created: $backupPath"

Get-Content -Raw -LiteralPath $sqlFile | sqlite3 "$DatabasePath"
if ($LASTEXITCODE -ne 0) {
    throw "Migration SQL failed. Backup remains at: $backupPath"
}

$schemaVersion = Invoke-SqliteScalar "SELECT value FROM database_metadata WHERE key = 'p25_activity_schema_version';"
$nacColumns = Invoke-SqliteScalar "SELECT COUNT(*) FROM pragma_table_info('site_neighbor') WHERE name = 'nac';"
$integrity = Invoke-SqliteScalar 'PRAGMA integrity_check;'

if ($schemaVersion -ne '9' -or $nacColumns -ne '0' -or $integrity -ne 'ok') {
    throw "Migration verification failed. schema=$schemaVersion site_neighbor.nac_columns=$nacColumns integrity=$integrity backup=$backupPath"
}

Write-Host 'Migration complete: P25 activity schema v9, site_neighbor.nac removed.'
