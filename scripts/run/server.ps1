$RepoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
& "$RepoRoot/scripts/build/Invoke-Gradle.ps1" runServer @args
exit $LASTEXITCODE

