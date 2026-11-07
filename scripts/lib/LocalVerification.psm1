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
function Get-VerificationFingerprint([string] $Root) {
    $absolute=[System.IO.Path]::GetFullPath($Root).TrimEnd('\','/')
    $files=@(& rg --files (Join-Path $absolute 'src') (Join-Path $absolute 'gradle') (Join-Path $absolute 'scripts'))
    $files+=@(Get-ChildItem -LiteralPath $absolute -File | Where-Object { $_.Extension -in @('.ps1','.gradle','.properties') -or $_.Name -in @('gradlew','gradlew.bat') } | ForEach-Object { $_.FullName })
    $lines=@($files | Sort-Object -Unique | ForEach-Object {
        $path=[System.IO.Path]::GetFullPath($_)
        $relative=$path.Substring($absolute.Length).Replace('\','/')
        "$relative`t$((Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash)"
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
            $evidence=Join-Path $run.FullName ($job.name+'/nullspace-test-evidence.json')
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
Export-ModuleMember -Function Quote-JavaArgument,Write-JavaArguments,Read-VerificationJson,Write-VerificationJson,Assert-VerificationMarker,Get-VerificationFingerprint,Assert-VerificationFingerprint,Assert-VerificationInventory,Get-VerificationShards,Get-VerificationDurations,Get-VerificationNativeInventory,Assert-VerificationNativeRegistration,Assert-VerificationDiskSpace
