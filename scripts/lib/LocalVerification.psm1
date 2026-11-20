Set-StrictMode -Version Latest
function Quote-JavaArgument([string] $Value) {
    return '"' + $Value.Replace('\','\\').Replace('"','\"') + '"'
}
function Write-JavaArguments([string] $Path, [string[]] $Arguments) {
    [System.IO.File]::WriteAllLines($Path, @($Arguments | ForEach-Object { Quote-JavaArgument $_ }), [System.Text.UTF8Encoding]::new($false))
}
function Write-VerificationJson([string] $Path, $Value) {
    [System.IO.File]::WriteAllText($Path, ($Value | ConvertTo-Json -Depth 12), [System.Text.UTF8Encoding]::new($false))
}
function Read-VerificationJson([string] $Path) {
    $stream=[IO.File]::Open($Path,[IO.FileMode]::Open,[IO.FileAccess]::Read,([IO.FileShare]::ReadWrite -bor [IO.FileShare]::Delete))
    $reader=[IO.StreamReader]::new($stream)
    try { return ($reader.ReadToEnd()|ConvertFrom-Json) }
    finally {$reader.Dispose();$stream.Dispose()}
}
function Assert-VerificationMarker([string] $Path, [string] $Expected = 'passed') {
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { throw "Missing success marker: $Path" }
    if ((Get-Content -LiteralPath $Path -Raw).Trim() -ne $Expected) { throw "Invalid success marker: $Path" }
}
# The version number only names the JAR, so a passing check still certifies the sources after the release bump: the
# mod_version line of the root gradle.properties is left out of its hash. Every other line, and every other file, counts.
function Get-VerificationFileHash([string] $Path, [string] $Relative) {
    if ($Relative -ne '/gradle.properties') { return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash }
    $kept=([IO.File]::ReadAllText($Path) -replace '(?m)^mod_version=.*(\r?\n|$)','')
    $algorithm=[System.Security.Cryptography.SHA256]::Create()
    try { return ([BitConverter]::ToString($algorithm.ComputeHash([Text.Encoding]::UTF8.GetBytes($kept)))).Replace('-','') }
    finally { $algorithm.Dispose() }
}
function Get-VerificationFingerprint([string] $Root) {
    $absolute=[System.IO.Path]::GetFullPath($Root).TrimEnd('\','/')
    $files=@(& rg --files (Join-Path $absolute 'src') (Join-Path $absolute 'gradle') (Join-Path $absolute 'scripts'))
    $files+=@(Get-ChildItem -LiteralPath $absolute -File | Where-Object { $_.Extension -in @('.ps1','.gradle','.properties') -or $_.Name -in @('gradlew','gradlew.bat') } | ForEach-Object { $_.FullName })
    $lines=@($files | Sort-Object -Unique | ForEach-Object {
        $path=[System.IO.Path]::GetFullPath($_)
        $relative=$path.Substring($absolute.Length).Replace('\','/')
        "$relative`t$(Get-VerificationFileHash $path $relative)"
    })
    $algorithm=[System.Security.Cryptography.SHA256]::Create()
    try { return ([BitConverter]::ToString($algorithm.ComputeHash([Text.Encoding]::UTF8.GetBytes(($lines -join "`n"))))).Replace('-','').ToLowerInvariant() }
    finally { $algorithm.Dispose() }
}
function Assert-VerificationFingerprint([string] $Root, [string] $Expected) {
    if((Get-VerificationFingerprint $Root) -ne $Expected){throw 'Verification inputs changed during the run; results cannot certify one build'}
}
function Assert-VerificationInventory([string[]] $Expected, [string[]] $Actual) {
    if($Expected.Count -eq 0 -or $Actual.Count -ne $Expected.Count -or @($Actual | Select-Object -Unique).Count -ne $Actual.Count -or @(Compare-Object $Expected $Actual).Count -ne 0){throw 'Verification did not complete the exact declared test inventory'}
}
function Get-VerificationShards([string[]] $Classes, [ValidateRange(1, 8)] [int] $Workers, [hashtable] $Durations = @{}) {
    Assert-VerificationInventory $Classes $Classes
    $count = [Math]::Min($Workers, $Classes.Count)
    if ($Durations.Count) {
        $known = @($Durations.Values | ForEach-Object {
            $cost = [double]$_
            if ($cost -le 0 -or [double]::IsNaN($cost) -or [double]::IsInfinity($cost)) { throw 'Invalid test duration estimate' }
            $cost
        } | Sort-Object)
        $fallback = $known[[int][Math]::Floor(($known.Count-1)/2)]
        $bins = @(for($i=0;$i -lt $count;$i++){[pscustomobject]@{index=$i+1;classes=@();cost=0.0}})
        $tasks = @(foreach($class in $Classes){[pscustomobject]@{name=$class;cost=$(if($Durations.ContainsKey($class)){[double]$Durations[$class]}else{$fallback})}})
        foreach($task in @($tasks | Sort-Object @{Expression='cost';Descending=$true},@{Expression='name';Descending=$false})) {
            $bin = $bins | Sort-Object cost,@{Expression={$_.classes.Count};Descending=$false},index | Select-Object -First 1
            $bin.classes += $task.name
            $bin.cost += $task.cost
        }
        $bins
        return
    }
    for ($i = 0; $i -lt $count; $i++) {
        $assigned = @()
        for ($j = $i; $j -lt $Classes.Count; $j += $count) { $assigned += $Classes[$j] }
        [pscustomobject]@{index=$i+1; classes=@($assigned)}
    }
}
function Get-VerificationDurations([string] $Root) {
    $durations = @{}
    if(-not(Test-Path -LiteralPath $Root)){return ,$durations}
    foreach($run in @(Get-ChildItem -LiteralPath $Root -Directory | Sort-Object LastWriteTime -Descending | Select-Object -First 20)) {
        $receipt = Join-Path $run.FullName 'result.json'
        if(-not(Test-Path -LiteralPath $receipt)){continue}
        try {$state=Get-Content -LiteralPath $receipt -Raw|ConvertFrom-Json}catch{continue}
        if(-not $state.PSObject.Properties['status'] -or -not $state.PSObject.Properties['jobs'] -or $state.status -notin @('passed','passed-with-unit-skips')){continue}
        foreach($job in @($state.jobs)) {
            if($job.name -notmatch '^graphics-\d+$'){continue}
            $directory = if ($job.PSObject.Properties['workDirectory']) { [string]$job.workDirectory } else { [string]$job.name }
            if ($directory -cne $job.name -and $directory -cne ($job.name + '-retry')) { continue }
            $evidence=Join-Path $run.FullName ($directory+'/nullspace-test-evidence.json')
            if(-not(Test-Path -LiteralPath $evidence)){continue}
            try {$data=Get-Content -LiteralPath $evidence -Raw|ConvertFrom-Json}catch{continue}
            if(-not $data.PSObject.Properties['timings']){continue}
            foreach($entry in $data.timings.PSObject.Properties) {
                if(-not $data.PSObject.Properties['completed'] -or $entry.Name -notin @($data.completed)){continue}
                try {$ms=[double]$entry.Value.milliseconds}catch{continue}
                if(-not $durations.ContainsKey($entry.Name) -and $ms -gt 0 -and -not [double]::IsInfinity($ms) -and -not [double]::IsNaN($ms)){$durations[$entry.Name]=$ms}
            }
        }
    }
    return ,$durations
}
function Get-VerificationNativeDurations([string] $Root, [string] $Workspace, [string[]] $Classes) {
    # Seconds of server-test time per class, from the newest passing runs. A class is only estimated when every one of its cases was timed.
    $caseSeconds = @{}
    if(Test-Path -LiteralPath $Root) {
        foreach($run in @(Get-ChildItem -LiteralPath $Root -Directory | Sort-Object LastWriteTime -Descending | Select-Object -First 20)) {
            $receipt = Join-Path $run.FullName 'result.json'
            if(-not(Test-Path -LiteralPath $receipt)){continue}
            try {$state=Get-Content -LiteralPath $receipt -Raw|ConvertFrom-Json}catch{continue}
            if(-not $state.PSObject.Properties['status'] -or -not $state.PSObject.Properties['jobs'] -or $state.status -notin @('passed','passed-with-unit-skips')){continue}
            foreach($job in @($state.jobs)) {
                if(-not $job.PSObject.Properties['nativeCases']){continue}
                foreach($case in @($job.nativeCases)) {
                    try {$seconds=[double]$case.seconds}catch{continue}
                    if(-not $caseSeconds.ContainsKey([string]$case.name) -and $seconds -gt 0 -and -not [double]::IsInfinity($seconds) -and -not [double]::IsNaN($seconds)){$caseSeconds[[string]$case.name]=$seconds}
                }
            }
        }
    }
    $durations = @{}
    foreach($class in $Classes) {
        try {$names=@(Get-VerificationNativeInventory $Workspace @($class))}catch{continue}
        if($names.Count -gt 0 -and @($names | Where-Object { -not $caseSeconds.ContainsKey($_) }).Count -eq 0) {
            $durations[$class]=[double](($names | ForEach-Object { $caseSeconds[$_] } | Measure-Object -Sum).Sum)
        }
    }
    return ,$durations
}
function Invoke-VerificationScriptShards([string] $Script, [string] $RunRoot, [string[]] $Items, [string] $ItemParameter, [int] $Workers, [string[]] $Arguments = @()) {
    # Runs one copy of a script per slice of Items, side by side, each in its own artifact directory. Every copy finishes before a failure is reported.
    $count = [Math]::Max(1, [Math]::Min($Workers, $Items.Count))
    $children = @()
    for($i = 0; $i -lt $count; $i++) {
        $slice = @(for($j = $i; $j -lt $Items.Count; $j += $count) { $Items[$j] })
        $root = Join-Path $RunRoot ('worker-' + ($i + 1))
        $command = "& '$Script' -$ItemParameter " + (($slice | ForEach-Object { "'$_'" }) -join ',') + " -ArtifactRoot '$root' -SkipBuild " + ($Arguments -join ' ')
        $argumentList = @('-NoProfile','-NonInteractive','-ExecutionPolicy','Bypass','-Command',('"' + $command.Replace('"','\"') + '"'))
        $null = New-Item -ItemType Directory -Force -Path $RunRoot
        $process = Start-Process -FilePath (Get-Process -Id $PID).Path -ArgumentList $argumentList -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $RunRoot "worker-$($i + 1).log") -RedirectStandardError (Join-Path $RunRoot "worker-$($i + 1).err.log")
        $null = $process.Handle
        $children += [pscustomobject]@{index = $i + 1; items = $slice; root = $root; process = $process}
    }
    foreach($child in $children) { $child.process.WaitForExit() }
    $failed = @($children | Where-Object { $_.process.ExitCode -ne 0 -or -not (Test-Path -LiteralPath (Join-Path $_.root 'result.json')) })
    if($failed.Count) {
        $details = @(foreach ($child in $failed) {
            $message = "$($child.index) (exit $($child.process.ExitCode); $($child.items -join ', '))"
            try { $message += ': ' + ((Read-VerificationJson (Join-Path $child.root 'result.json')).problems -join '; ') } catch { }
            $message
        })
        throw ('Worker(s) ' + ($details -join "`n") + ". Logs: $RunRoot")
    }
    return $children
}
function Assert-VerificationDiskSpace([long] $AvailableBytes, [long] $RequiredBytes) {
    if ($AvailableBytes -lt 0 -or $RequiredBytes -le 0) { throw 'Invalid verification disk-space measurement' }
    if ($AvailableBytes -lt $RequiredBytes) {
        throw ('Insufficient space for disposable test worlds: {0:N2} GiB free; {1:N2} GiB required before launch. Remove old generated test worlds or choose another workspace drive.' -f ($AvailableBytes/1GB),($RequiredBytes/1GB))
    }
}
function Get-VerificationNativeInventory([string] $Workspace, [string[]] $Classes) {
    Assert-VerificationInventory $Classes $Classes
    $mod=Read-VerificationJson (Join-Path $Workspace 'src/gametest/resources/fabric.mod.json')
    foreach($class in $Classes) {
        if($class -notmatch '^[A-Za-z_]\w*(\.[A-Za-z_]\w*)+$'){throw 'Invalid native test class name'}
        $source=Join-Path $Workspace ('src/gametest/java/'+$class.Replace('.','/')+'.java')
        if(-not (Test-Path -LiteralPath $source)){$source=Join-Path $Workspace ('src/compat/java/'+$class.Replace('.','/')+'.java')}
        $text=[IO.File]::ReadAllText($source)
        $methods=[regex]::Matches($text,'@GameTest(?:\([^)]*\))?\s+public\s+void\s+(\w+)\s*\(')
        if(-not $methods.Count -or $methods.Count -ne [regex]::Matches($text,'(?m)^\s*@GameTest\b').Count){throw "Cannot derive the full native inventory for $class"}
        $simple=$class.Substring($class.LastIndexOf('.')+1)
        foreach($method in $methods) {
            $name=$simple+'_'+$method.Groups[1].Value
            $snake=[regex]::Replace($name,'([a-z])([A-Z])','$1_$2').ToLowerInvariant()
            $mod.id+':'+$snake
        }
    }
}
function Assert-VerificationNativeRegistration([string] $Workspace) {
    $sourceRoot = [IO.Path]::GetFullPath((Join-Path $Workspace 'src/gametest/java')).TrimEnd('\','/')
    $declared = @(foreach ($file in Get-ChildItem -LiteralPath $sourceRoot -File -Recurse -Filter '*.java') {
        if ([regex]::IsMatch([IO.File]::ReadAllText($file.FullName),'(?m)^\s*@GameTest\b')) {
            $file.FullName.Substring($sourceRoot.Length + 1).Replace('\','.').Replace('/','.').Replace('.java','')
        }
    })
    $mod = Read-VerificationJson (Join-Path $Workspace 'src/gametest/resources/fabric.mod.json')
    Assert-VerificationInventory $declared @($mod.entrypoints.'fabric-gametest')
}
Export-ModuleMember -Function Quote-JavaArgument,Write-JavaArguments,Read-VerificationJson,Write-VerificationJson,Assert-VerificationMarker,Get-VerificationFingerprint,Assert-VerificationFingerprint,Assert-VerificationInventory,Get-VerificationShards,Get-VerificationDurations,Get-VerificationNativeDurations,Invoke-VerificationScriptShards,Get-VerificationNativeInventory,Assert-VerificationNativeRegistration,Assert-VerificationDiskSpace
