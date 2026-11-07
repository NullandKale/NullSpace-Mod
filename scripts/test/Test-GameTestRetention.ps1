param([string] $ArtifactRoot = '')
$ErrorActionPreference = 'Stop'
if (-not $ArtifactRoot) { $ArtifactRoot = Join-Path (Split-Path -Parent (Split-Path -Parent $PSScriptRoot)) ('build/retention-tests/' + [guid]::NewGuid()) }
$null = New-Item -ItemType Directory -Path $ArtifactRoot -Force
$workspace = (Resolve-Path -LiteralPath $ArtifactRoot).Path
$sessions = Join-Path $workspace 'build/run/gameTest-sessions'
$null = New-Item -ItemType Directory -Path $sessions -Force
$worlds = @()
for ($i = 0; $i -lt 5; $i++) {
    $world = Join-Path $sessions ([guid]::NewGuid().ToString())
    $null = New-Item -ItemType Directory -Path (Join-Path $world 'world') -Force
    [IO.File]::WriteAllText((Join-Path $world 'world/session.lock'), 'test lock')
    (Get-Item -LiteralPath $world).LastWriteTime = (Get-Date).AddMinutes(-$i)
    $worlds += $world
}
$sentinels = @(
    (Join-Path $sessions 'handmade-test'),
    (Join-Path $workspace 'run/saves/World 5'),
    (Join-Path $workspace 'build/recovery/original-save')
)
foreach ($sentinel in $sentinels) {
    $null = New-Item -ItemType Directory -Path $sentinel -Force
    [IO.File]::WriteAllText((Join-Path $sentinel 'precious'), 'preserve')
}
$active = [IO.File]::Open((Join-Path $worlds[4] 'world/session.lock'), [IO.FileMode]::Open, [IO.FileAccess]::ReadWrite, [IO.FileShare]::None)
try {
    & (Join-Path $PSScriptRoot 'Remove-OldGameTestWorlds.ps1') -Workspace $workspace -Keep 2
    foreach ($index in @(0, 1, 4)) {
        if (-not (Test-Path -LiteralPath $worlds[$index])) { throw 'Retention deleted a recent or active test world' }
    }
    foreach ($index in @(2, 3)) {
        if (Test-Path -LiteralPath $worlds[$index]) { throw 'Old disposable test world was not pruned' }
    }
} finally { $active.Dispose() }
& (Join-Path $PSScriptRoot 'Remove-OldGameTestWorlds.ps1') -Workspace $workspace -Keep 2
if (Test-Path -LiteralPath $worlds[4]) { throw 'Closed old test world was not pruned' }
foreach ($sentinel in $sentinels) {
    if ([IO.File]::ReadAllText((Join-Path $sentinel 'precious')) -ne 'preserve') { throw 'Retention changed a protected directory' }
}
Write-Output 'GameTest retention passed: recent sessions, active locks, closed sessions, unknown folders, player saves and backups'
[IO.File]::WriteAllText((Join-Path $workspace 'result.json'), (@{
    status = 'passed'
    cases = @('recent sessions', 'active locks', 'closed sessions', 'unknown folders', 'player saves', 'backups')
} | ConvertTo-Json))
