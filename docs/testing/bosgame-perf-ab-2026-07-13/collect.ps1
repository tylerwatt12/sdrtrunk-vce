param(
    [string]$Root = "$env:LOCALAPPDATA\Temp\sdrtrunk-perf-ab-20260713",
    [string]$Sequence = 'A,B',
    [int]$WarmupSeconds = 60,
    [int]$MeasureSeconds = 120,
    [int]$SampleSeconds = 5,
    [string]$Batch = 'pilot'
)

$ErrorActionPreference = 'Stop'
$benchmarkTask = 'SDRTrunk Performance A-B'
$manifest = Get-Content (Join-Path $Root 'manifest.json') -Raw | ConvertFrom-Json
$launcher = Join-Path $Root 'launch-current.cmd'
$resultsRoot = Join-Path $Root "results\$Batch"
New-Item -ItemType Directory -Force -Path $resultsRoot | Out-Null
$builds = $Sequence.Split(',') | ForEach-Object { $_.Trim().ToUpperInvariant() }

function Get-SqliteFingerprint([string]$path)
{
    $files = Get-ChildItem $path -Recurse -File -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -match '\.sqlite($|-)' -or $_.Extension -in @('.db', '.db3') }
    @($files | ForEach-Object {
        [pscustomobject]@{
            Path = $_.FullName.Substring($path.Length).TrimStart('\')
            Length = $_.Length
            LastWriteTimeUtc = $_.LastWriteTimeUtc.ToString('o')
            Sha256 = (Get-FileHash $_.FullName -Algorithm SHA256).Hash
        }
    })
}

function Wait-Progress([int]$seconds, [string]$phase, [string]$runName)
{
    $remaining = $seconds
    while($remaining -gt 0)
    {
        $slice = [Math]::Min(30, $remaining)
        Start-Sleep -Seconds $slice
        $remaining -= $slice
        Write-Output "PROGRESS|$runName|$phase|remaining=$remaining"
    }
}

for($index = 0; $index -lt $builds.Count; $index++)
{
    $build = $builds[$index]
    if($build -notin @('A', 'B')) { throw "Unknown build: $build" }
    $runNumber = $index + 1
    $runName = ('{0:D2}-{1}' -f $runNumber, $build)
    $runRoot = Join-Path $resultsRoot $runName
    Remove-Item $runRoot -Recurse -Force -ErrorAction SilentlyContinue
    New-Item -ItemType Directory -Force -Path $runRoot | Out-Null

    if($build -eq 'A')
    {
        $buildRoot = $manifest.aBuildRoot
        $dataRoot = Join-Path $runRoot 'data'
        Copy-Item (Join-Path $Root 'baselines\A\data') $dataRoot -Recurse -Force
        $specificArgs = @(
            "-Dsdrtrunk.vce.data.root=$dataRoot"
        )
    }
    else
    {
        $buildRoot = $manifest.bBuildRoot
        $homeRoot = Join-Path $runRoot 'home'
        Copy-Item (Join-Path $Root 'baselines\B\home') $homeRoot -Recurse -Force
        $preferencesPath = Join-Path $runRoot 'preferences.properties'
        Copy-Item (Join-Path $Root 'baselines\B\preferences.properties') $preferencesPath
        $specificArgs = @(
            "-Duser.home=$homeRoot",
            '-Djava.util.prefs.PreferencesFactory=io.github.dsheirer.preference.portable.PortablePreferencesFactory',
            "-Dsdrtrunk.preferences.file=$preferencesPath"
        )
    }

    $gcLog = Join-Path $runRoot 'gc.log'
    $commonArgs = @(
        '--add-exports=javafx.base/com.sun.javafx.event=ALL-UNNAMED',
        '--add-exports=java.desktop/com.sun.java.swing.plaf.windows=ALL-UNNAMED',
        '--add-modules=jdk.incubator.vector',
        '--enable-preview',
        '--enable-native-access=ALL-UNNAMED',
        '--enable-native-access=javafx.graphics',
        '--sun-misc-unsafe-memory-access=allow',
        '-Xmx2g',
        '-XX:+UseCompactObjectHeaders',
        '-Djava.awt.headless=false',
        '-Dsun.java2d.d3d=false',
        '-Dsdrtrunk.benchmark.readOnly=true',
        '-Djava.library.path=C:\Program Files\SDRplay\API\x64',
        "-Xlog:gc*,safepoint:file=$gcLog`:time,uptime,level,tags"
    )
    $java = Join-Path $buildRoot 'bin\java.exe'
    $classPath = Join-Path $buildRoot 'lib\*'
    $quotedArgs = @($commonArgs + $specificArgs) | ForEach-Object { '"' + $_ + '"' }
    $command = '"' + $java + '" ' + ($quotedArgs -join ' ') + ' -classpath "' + $classPath + '" io.github.dsheirer.gui.SDRTrunk'
    @('@echo off', "cd /d `"$buildRoot`"", $command) | Set-Content -Encoding ASCII $launcher

    Write-Output "RUN_START|$runName|$(Get-Date -Format o)|warmup=$WarmupSeconds|measure=$MeasureSeconds"
    Start-ScheduledTask -TaskName $benchmarkTask
    $deadline = (Get-Date).AddSeconds(90)
    $process = $null
    while((Get-Date) -lt $deadline -and $null -eq $process)
    {
        Start-Sleep -Seconds 2
        $process = Get-Process -Name java -ErrorAction SilentlyContinue |
            Where-Object { $_.Path -eq $java } | Select-Object -First 1
    }
    if($null -eq $process) { throw "Java process did not start for $runName" }

    $startedAt = Get-Date
    Wait-Progress $WarmupSeconds 'warmup' $runName
    $process = Get-Process -Id $process.Id -ErrorAction Stop
    $sqliteBefore = Get-SqliteFingerprint $runRoot
    $sqliteBefore | ConvertTo-Json -Depth 5 | Set-Content -Encoding UTF8 (Join-Path $runRoot 'sqlite-before.json')
    $listenersBefore = @(Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue |
        Where-Object { $_.OwningProcess -eq $process.Id })

    $samples = New-Object System.Collections.Generic.List[object]
    $stopwatch = [System.Diagnostics.Stopwatch]::StartNew()
    $nextSample = 0.0
    while($stopwatch.Elapsed.TotalSeconds -le $MeasureSeconds)
    {
        $process.Refresh()
        $osProcessor = Get-CimInstance Win32_PerfFormattedData_PerfOS_Processor -Filter "Name='_Total'"
        $osMemory = Get-CimInstance Win32_PerfFormattedData_PerfOS_Memory
        $cimProcess = Get-CimInstance Win32_Process -Filter "ProcessId=$($process.Id)"
        $samples.Add([pscustomobject]@{
            Run = $runName
            Build = $build
            TimestampUtc = (Get-Date).ToUniversalTime().ToString('o')
            ElapsedSeconds = [Math]::Round($stopwatch.Elapsed.TotalSeconds, 6)
            ProcessCpuSeconds = [Math]::Round($process.TotalProcessorTime.TotalSeconds, 6)
            WorkingSetBytes = $process.WorkingSet64
            PrivateBytes = $process.PrivateMemorySize64
            PagedBytes = $process.PagedMemorySize64
            VirtualBytes = $process.VirtualMemorySize64
            Threads = $process.Threads.Count
            Handles = $process.HandleCount
            ReadTransferBytes = $cimProcess.ReadTransferCount
            WriteTransferBytes = $cimProcess.WriteTransferCount
            HostCpuPercent = $osProcessor.PercentProcessorTime
            HostAvailableMBytes = $osMemory.AvailableMBytes
        })
        $nextSample += $SampleSeconds
        $sleepMilliseconds = [Math]::Max(0, [int](($nextSample - $stopwatch.Elapsed.TotalSeconds) * 1000))
        if($sleepMilliseconds -gt 0) { Start-Sleep -Milliseconds $sleepMilliseconds }
        if(([int]$stopwatch.Elapsed.TotalSeconds % 30) -lt $SampleSeconds)
        {
            Write-Output "PROGRESS|$runName|measure|elapsed=$([int]$stopwatch.Elapsed.TotalSeconds)"
        }
    }
    $stopwatch.Stop()
    $samples | Export-Csv -NoTypeInformation -Encoding UTF8 (Join-Path $runRoot 'samples.csv')
    $sqliteAfter = Get-SqliteFingerprint $runRoot
    $sqliteAfter | ConvertTo-Json -Depth 5 | Set-Content -Encoding UTF8 (Join-Path $runRoot 'sqlite-after.json')
    $listenersAfter = @(Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue |
        Where-Object { $_.OwningProcess -eq $process.Id })

    $jcmd = Join-Path $buildRoot 'bin\jcmd.exe'
    if(Test-Path $jcmd)
    {
        & $jcmd $process.Id GC.heap_info 2>&1 | Set-Content -Encoding UTF8 (Join-Path $runRoot 'heap-info.txt')
    }

    $summary = [ordered]@{
        run = $runName
        build = $build
        processId = $process.Id
        processStartedAt = $process.StartTime.ToString('o')
        warmupSeconds = $WarmupSeconds
        requestedMeasureSeconds = $MeasureSeconds
        actualMeasureSeconds = $stopwatch.Elapsed.TotalSeconds
        sampleSeconds = $SampleSeconds
        sampleCount = $samples.Count
        listenersBefore = @($listenersBefore | ForEach-Object { "$($_.LocalAddress):$($_.LocalPort)" })
        listenersAfter = @($listenersAfter | ForEach-Object { "$($_.LocalAddress):$($_.LocalPort)" })
        sqliteUnchanged = ((ConvertTo-Json $sqliteBefore -Compress) -eq (ConvertTo-Json $sqliteAfter -Compress))
        completedAt = (Get-Date).ToString('o')
    }
    $summary | ConvertTo-Json -Depth 6 | Set-Content -Encoding UTF8 (Join-Path $runRoot 'run.json')

    Stop-ScheduledTask -TaskName $benchmarkTask -ErrorAction SilentlyContinue
    Start-Sleep -Seconds 3
    Get-Process -Id $process.Id -ErrorAction SilentlyContinue | Stop-Process -Force
    Start-Sleep -Seconds 3
    Write-Output "RUN_END|$runName|$(Get-Date -Format o)|samples=$($samples.Count)|sqliteUnchanged=$($summary.sqliteUnchanged)|listeners=$($listenersAfter.Count)"
}

Write-Output "BATCH_COMPLETE|$Batch|$(Get-Date -Format o)|runs=$($builds.Count)"
