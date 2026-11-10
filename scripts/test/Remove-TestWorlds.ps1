param(
    [Parameter(Mandatory)][string] $WorkRoot,
    [string] $Workspace
)
$ErrorActionPreference = 'Stop'
# Windows PowerShell 5.1 leaves $PSScriptRoot empty inside a parameter default, so resolve it here.
if (-not $Workspace) { $Workspace = Split-Path -Parent (Split-Path -Parent $PSScriptRoot) }
Import-Module (Join-Path $PSScriptRoot '../lib/TestWorldCleanup.psm1') -Force
Remove-TestWorlds -Workspace $Workspace -WorkRoot $WorkRoot
