param(
    [Parameter(Mandatory)][string] $WorkRoot,
    [string] $Workspace = (Split-Path -Parent (Split-Path -Parent $PSScriptRoot))
)
$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot '../lib/TestWorldCleanup.psm1') -Force
Remove-TestWorlds -Workspace $Workspace -WorkRoot $WorkRoot
