# Delete only disposable Minecraft world directories, never a worker's evidence.
function Remove-TestWorlds {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)][string] $Workspace,
        [Parameter(Mandatory)][string] $WorkRoot,
        [string[]] $Leaves = @('world','saves'),
        [int[]] $ProcessIds = @()
    )
    $ErrorActionPreference = 'Stop'
    $workspacePath = (Resolve-Path -LiteralPath $Workspace).Path.TrimEnd('\','/')
    $buildRoot = Join-Path $workspacePath 'build'
    $work = [IO.Path]::GetFullPath($WorkRoot).TrimEnd('\','/')
    if (-not $work.StartsWith($buildRoot + [IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)) {
        throw 'Test cleanup must stay inside this workspace build directory'
    }
    if (@($Leaves | Where-Object { $_ -notin @('world','saves') }).Count) { throw 'Only test world and saves directories are disposable' }
    # Resolve-Path alone does not detect Windows junctions. Check every ancestor.
    $ancestor = $work
    while ($ancestor -ne $workspacePath) {
        if ((Test-Path -LiteralPath $ancestor) -and ((Get-Item -LiteralPath $ancestor -Force).Attributes -band [IO.FileAttributes]::ReparsePoint)) { throw 'Refusing a redirected test directory' }
        $ancestor = Split-Path -Parent $ancestor
    }
    if (-not (Test-Path -LiteralPath $work -PathType Container)) { return }
    foreach ($processId in $ProcessIds) {
        if ($processId -gt 0 -and (Get-Process -Id $processId -ErrorAction SilentlyContinue)) {
            return [pscustomobject]@{path=$work.Substring($workspacePath.Length+1);bytes=0;status='skipped-active-process'}
        }
    }
    foreach ($leaf in $Leaves) {
        $target = Join-Path $work $leaf
        if (-not (Test-Path -LiteralPath $target)) { continue }
        $files = [Collections.Generic.List[IO.FileInfo]]::new()
        $pending = [Collections.Generic.Queue[string]]::new()
        $pending.Enqueue($target)
        # Inspect a directory before descending into it; never follow links.
        while ($pending.Count) {
            $directory = $pending.Dequeue()
            $item = Get-Item -LiteralPath $directory -Force
            if (-not $item.PSIsContainer -or $item.Attributes -band [IO.FileAttributes]::ReparsePoint) { throw 'Refusing a disposable world containing redirected paths' }
            foreach ($child in Get-ChildItem -LiteralPath $directory -Force) {
                if ($child.Attributes -band [IO.FileAttributes]::ReparsePoint) { throw 'Refusing a disposable world containing redirected paths' }
                if ($child.PSIsContainer) { $pending.Enqueue($child.FullName) } else { $files.Add($child) }
            }
        }
        $active = $false
        foreach ($lock in @($files | Where-Object Name -eq 'session.lock')) {
            try {
                $probe = [IO.File]::Open($lock.FullName,[IO.FileMode]::Open,[IO.FileAccess]::ReadWrite,[IO.FileShare]::None)
                $probe.Dispose()
            } catch [IO.IOException] { $active = $true; break }
        }
        if ($active) {
            [pscustomobject]@{path=$target.Substring($workspacePath.Length+1);bytes=0;status='skipped-locked-world'}
            continue
        }
        $bytes = [long]($files | Measure-Object Length -Sum).Sum
        # Absolute, validated target; all traversal and removal uses one shell.
        Remove-Item -LiteralPath $target -Recurse -Force
        [pscustomobject]@{path=$target.Substring($workspacePath.Length+1);bytes=$bytes;status='removed'}
    }
}
Export-ModuleMember -Function Remove-TestWorlds
