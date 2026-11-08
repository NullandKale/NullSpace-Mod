# Formats Java in the Visual Studio C# style (gradle/java-format.xml) through Spotless and the Eclipse formatter.
#   .\scripts\build\format.ps1          check only; fails and lists the files that would change
#   .\scripts\build\format.ps1 -Write   rewrite files in place
param([switch] $Write)

$RepoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$task = if ($Write) { 'spotlessApply' } else { 'spotlessCheck' }
& (Join-Path $RepoRoot 'scripts/build/Invoke-Gradle.ps1') $task
exit $LASTEXITCODE
