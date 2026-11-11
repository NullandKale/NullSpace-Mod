[CmdletBinding(PositionalBinding = $false)]
param(
    [switch]$Restart,
    [switch]$ResetWorld,
    [switch]$Remote,
    [string]$Server = $env:NULLSPACE_TEST_ADDRESS,
    [string]$StartingShip = 'halcyon_horizon',
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$LaunchArguments
)
$RepoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$ErrorActionPreference = 'Stop'
$gradleArguments = @()
foreach ($argument in $LaunchArguments) {
    if ($argument -eq '--restart') { $Restart = $true }
    elseif ($argument -eq '--remote') { $Remote = $true }
    elseif ($argument -like '--remote=*') { $Remote = $true; $Server = $argument.Substring(9) }
    else { $gradleArguments += $argument }
}
if ($Restart -and $ResetWorld) { throw 'Choose --restart for the creation menu or -ResetWorld for the prepared playtest world.' }
if ($Remote -and ($Restart -or $ResetWorld)) { throw '--remote joins the hosted server; it does not use or reset a local world.' }
if ($Remote) {
    # Use the signed-in Modrinth profile and its installed release asset. Development
    # builds must never overwrite a release installed for multiplayer testing.
    $modrinthProfile = if ($env:NULLSPACE_MODRINTH_PROFILE) { $env:NULLSPACE_MODRINTH_PROFILE } else { Join-Path $env:APPDATA 'ModrinthApp\profiles\Fabric 26.3' }
    if (-not (Test-Path -LiteralPath (Join-Path $modrinthProfile 'mods'))) { throw "Modrinth profile not found: $modrinthProfile (set NULLSPACE_MODRINTH_PROFILE)" }
    if ($gradleArguments.Count) { throw 'Gradle arguments apply only to local development launches.' }
    Write-Host 'Using the release already installed in the Modrinth profile. Install updates from GitHub release CI/CD.'
    $app = Join-Path $env:LOCALAPPDATA 'Modrinth App\Modrinth App.exe'
    if (Test-Path -LiteralPath $app) { Start-Process -FilePath $app } else { Write-Warning "Modrinth App not found at $app; open it yourself." }
    Write-Host "In the Modrinth App, press Play on '$(Split-Path $modrinthProfile -Leaf)', then Multiplayer and choose your saved entry."
    exit 0
}
Import-Module "$RepoRoot/scripts/lib/DevWorldLaunch.psm1" -Force -DisableNameChecking
if ($ResetWorld) {
    & "$RepoRoot/scripts/run/Reset-DevWorld.ps1" -StartingShip $StartingShip
    if (-not $?) { exit 1 }
    [IO.File]::WriteAllText((Join-Path $RepoRoot 'run/nullspace-dev-world.txt'),'World 5')
}
$launch = Get-DevWorldLaunch -Workspace $RepoRoot -Restart:$Restart
if ($launch.backup) { Write-Host "Previous dev world backed up: $($launch.backup)" }
if ($launch.create) {
    Write-Host 'Opening Create New World. Choose generation settings and starting ship in the UI.'
    & "$RepoRoot/scripts/build/Invoke-Gradle.ps1" runClient '-PdevCreateWorld=World 5' "-PdevStartingShip=$StartingShip" @gradleArguments
} else {
    Write-Host "Opening development world: $($launch.folder)"
    & "$RepoRoot/scripts/build/Invoke-Gradle.ps1" runClient "-PdevWorld=$($launch.folder)" @gradleArguments
}
exit $LASTEXITCODE
