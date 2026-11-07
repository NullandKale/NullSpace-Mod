$ErrorActionPreference = 'Stop'
$workspacePath = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$buildRoot = [IO.Path]::GetFullPath((Join-Path $workspacePath 'build'))
$fixture = Join-Path $buildRoot ('dev-launch-check-'+[guid]::NewGuid().ToString('N'))
foreach ($d in 'scripts/run','scripts/lib','scripts/build') { $null = New-Item -ItemType Directory -Path (Join-Path $fixture $d) -Force }
$null = New-Item -ItemType Directory -Path (Join-Path $fixture 'run/saves/World 5') -Force
function Assert-Launch([bool]$Condition,[string]$Message) { if (-not $Condition) { throw $Message } }
try {
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot '../run/dev.ps1') -Destination (Join-Path $fixture 'scripts/run')
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot '../lib/DevWorldLaunch.psm1') -Destination (Join-Path $fixture 'scripts/lib')
    @'
param([Parameter(ValueFromRemainingArguments=$true)][string[]]$GradleArguments)
[IO.File]::WriteAllLines((Join-Path (Split-Path -Parent (Split-Path -Parent $PSScriptRoot)) 'launch.txt'),$GradleArguments)
exit 0
'@ | Set-Content -LiteralPath (Join-Path $fixture 'scripts/build/Invoke-Gradle.ps1') -Encoding UTF8
    $world = Join-Path $fixture 'run/saves/World 5'
    [IO.File]::WriteAllText((Join-Path $world 'level.dat'),'world and inventory fixture')
    $hash = (Get-FileHash -LiteralPath (Join-Path $world 'level.dat')).Hash
    & (Join-Path $fixture 'scripts/run/dev.ps1')
    Assert-Launch ((Get-Content (Join-Path $fixture 'launch.txt')) -contains '-PdevWorld=World 5') 'Normal launch must reopen the existing world'
    Assert-Launch ((Get-FileHash -LiteralPath (Join-Path $world 'level.dat')).Hash -eq $hash) 'Normal launch changed the save'
    Import-Module (Join-Path $fixture 'scripts/lib/DevWorldLaunch.psm1') -Force -DisableNameChecking
    $lock = [IO.File]::Open((Join-Path $world 'session.lock'),[IO.FileMode]::Create,[IO.FileAccess]::ReadWrite,[IO.FileShare]::None)
    try {
        $rejected = $false
        try { Get-DevWorldLaunch -Workspace $fixture -Restart | Out-Null } catch { $rejected = $_.Exception.Message -like '*Save and quit*' }
        Assert-Launch $rejected 'An open world must not be moved'
        Assert-Launch (Test-Path -LiteralPath (Join-Path $world 'level.dat')) 'An open world was lost'
    } finally { $lock.Dispose() }
    & (Join-Path $fixture 'scripts/run/dev.ps1') --restart
    $arguments = Get-Content (Join-Path $fixture 'launch.txt')
    Assert-Launch ($arguments -contains '-PdevCreateWorld=World 5') '--restart must open native world creation'
    Assert-Launch ($arguments -contains '-PdevStartingShip=halcyon_horizon') 'Bare --restart was consumed as the starting ship'
    Assert-Launch ($arguments -notcontains '--restart') 'The restart flag leaked to Gradle'
    Assert-Launch (-not (Test-Path -LiteralPath $world)) 'The previous world still occupies the creation slot'
    $backup = Get-ChildItem -LiteralPath (Join-Path $fixture 'run/backups') -Directory | Select-Object -First 1
    Assert-Launch ((Get-FileHash -LiteralPath (Join-Path $backup.FullName 'level.dat')).Hash -eq $hash) 'The backup differs from the original world'
    & (Join-Path $fixture 'scripts/run/dev.ps1')
    Assert-Launch ((Get-Content (Join-Path $fixture 'launch.txt')) -contains '-PdevCreateWorld=World 5') 'Cancelling creation must not prepare a world automatically'
    & (Join-Path $fixture 'scripts/run/dev.ps1') --restart -StartingShip rivet_ranger --no-daemon
    $arguments = Get-Content (Join-Path $fixture 'launch.txt')
    Assert-Launch ($arguments -contains '-PdevStartingShip=rivet_ranger') 'Explicit starting ship preference was lost'
    Assert-Launch ($arguments -contains '--no-daemon') 'Extra Gradle arguments were lost'
    Assert-Launch ($arguments -notcontains '--restart') '--restart leaked into the Gradle arguments'
    & (Join-Path $fixture 'scripts/run/dev.ps1') -Restart
    Assert-Launch ((Get-Content (Join-Path $fixture 'launch.txt')) -contains '-PdevCreateWorld=World 5') '-Restart must open creation too'
    $custom = Join-Path $fixture 'run/saves/My explorer'
    $null = New-Item -ItemType Directory -Path $custom -Force
    [IO.File]::WriteAllText((Join-Path $custom 'level.dat'),'custom world')
    [IO.File]::WriteAllText((Join-Path $fixture 'run/nullspace-dev-world.txt'),'My explorer')
    & (Join-Path $fixture 'scripts/run/dev.ps1')
    Assert-Launch ((Get-Content (Join-Path $fixture 'launch.txt')) -contains '-PdevWorld=My explorer') 'The actual UI-created folder must reopen even after renaming'
    [IO.File]::WriteAllText((Join-Path $fixture 'run/nullspace-dev-world.txt'),'../outside')
    $rejected = $false
    try { Get-DevWorldLaunch -Workspace $fixture -Restart | Out-Null } catch { $rejected = $true }
    Assert-Launch $rejected 'A save marker must not escape run/saves'
    Assert-Launch (Test-Path -LiteralPath (Join-Path $custom 'level.dat')) 'Invalid input changed another world'
    Write-Output 'Dev launcher checks passed: reopen, --restart, locked-world protection, backup, cancellation, renamed save and path boundaries.'
} finally {
    $resolved = [IO.Path]::GetFullPath($fixture)
    if (-not $resolved.StartsWith($buildRoot+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)) { throw 'Fixture escaped build' }
    Remove-Item -LiteralPath $resolved -Recurse -Force
}
