# Resolve the active development save and retire only that closed world on an explicit restart.
function Resolve-DevWorldPath {
    param([string]$Workspace, [string]$Folder)
    if ([string]::IsNullOrWhiteSpace($Folder) -or $Folder -in @('.','..') -or
        $Folder.IndexOfAny([IO.Path]::GetInvalidFileNameChars()) -ge 0) { throw 'Invalid development world folder' }
    $workspacePath = (Resolve-Path -LiteralPath $Workspace).Path.TrimEnd('\','/')
    $saves = Join-Path $workspacePath 'run/saves'
    $world = [IO.Path]::GetFullPath((Join-Path $saves $Folder))
    if (-not $world.StartsWith($saves + [IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)) { throw 'Development world must stay inside run/saves' }
    $walk = $world
    while ($walk -ne $workspacePath) {
        if ((Test-Path -LiteralPath $walk) -and ((Get-Item -LiteralPath $walk -Force).Attributes -band [IO.FileAttributes]::ReparsePoint)) { throw 'Redirected development world paths are not supported' }
        $walk = Split-Path -Parent $walk
    }
    return $world
}

function Get-DevWorldLaunch {
    param([Parameter(Mandatory)][string]$Workspace, [switch]$Restart)
    $workspacePath = (Resolve-Path -LiteralPath $Workspace).Path
    $marker = Join-Path $workspacePath 'run/nullspace-dev-world.txt'
    $folder = if (Test-Path -LiteralPath $marker) { [IO.File]::ReadAllText($marker).Trim() } else { 'World 5' }
    $world = Resolve-DevWorldPath $workspacePath $folder
    $backup = $null
    if ($Restart -and (Test-Path -LiteralPath $world)) {
        $lockPath = Join-Path $world 'session.lock'
        if (Test-Path -LiteralPath $lockPath) {
            try { $probe=[IO.File]::Open($lockPath,[IO.FileMode]::Open,[IO.FileAccess]::ReadWrite,[IO.FileShare]::None); $probe.Dispose() }
            catch { throw 'Save and quit the development world before using --restart. The world has not been moved.' }
        }
        $backupRoot = Join-Path $workspacePath 'run/backups'
        if ((Test-Path -LiteralPath $backupRoot) -and ((Get-Item -LiteralPath $backupRoot -Force).Attributes -band [IO.FileAttributes]::ReparsePoint)) { throw 'Redirected backup paths are not supported' }
        $null = New-Item -ItemType Directory -Path $backupRoot -Force
        $backup = [IO.Path]::GetFullPath((Join-Path $backupRoot ($folder+'-'+(Get-Date -Format yyyyMMdd-HHmmss)+'-'+[guid]::NewGuid().ToString('N').Substring(0,8))))
        if (-not $backup.StartsWith($backupRoot+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)) { throw 'Backup must stay inside run/backups' }
        Move-Item -LiteralPath $world -Destination $backup
    }
    if ($Restart -and (Test-Path -LiteralPath $marker)) { Remove-Item -LiteralPath $marker }
    $create = $Restart -or -not (Test-Path -LiteralPath (Join-Path $world 'level.dat'))
    return [pscustomobject]@{create=$create;folder=if($create){'World 5'}else{$folder};backup=$backup}
}

Export-ModuleMember -Function Get-DevWorldLaunch
