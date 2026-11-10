<#
.SYNOPSIS
  Runs the mod compatibility tests: other mods' blocks, fluids and entities on a real ship, out and back.
.DESCRIPTION
  The compat module (src/compat) is compiled against the mods under test, which Gradle pins and downloads from
  Modrinth (see compatMods in build.gradle and gradle.properties). The mods load beside NullSpace in a disposable
  server, and every test class in src/compat runs. Per-mod classes skip themselves when their mod is not loaded.
  Reports go to the work folder as compat-*.txt. Accepted findings live in src/compat/resources/compat/known-issues.json.
  The jars are downloaded into build/ only; they are never committed or redistributed.
.EXAMPLE
  .\scripts\test\Run-CompatTests.ps1
  .\scripts\test\Run-CompatTests.ps1 -Classes com.nullandkale.nullspace.compat.BiomesOPlentyCompatGameTest
#>
param([string[]] $Classes = @())
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
& "$root/scripts/build/Invoke-Gradle.ps1" prepareCompatMods compatClasses processCompatResources --console=plain *> "$root/build/compat-build.log"
if ($LASTEXITCODE -ne 0) { Get-Content "$root/build/compat-build.log" | Select-String 'error|FAIL|Could not' | Select-Object -First 30; throw 'compat build failed' }
if (-not $Classes.Count) {
    $source = Join-Path $root 'src/compat/java'
    $Classes = @(Get-ChildItem -LiteralPath $source -File -Recurse -Filter '*.java' | Where-Object { [IO.File]::ReadAllText($_.FullName) -match '(?m)^\s*@GameTest\b' } | ForEach-Object { $_.FullName.Substring($source.Length + 1).Replace('\', '.').Replace('/', '.').Replace('.java', '') })
}
$jars = @(Get-ChildItem -LiteralPath (Join-Path $root 'build/compat-mods') -Filter '*.jar' | ForEach-Object { $_.FullName })
"Testing with $($jars.Count) mod jar(s): $(($jars | ForEach-Object { Split-Path -Leaf $_ }) -join ', ')"
$classpath = @((Join-Path $root 'build/classes/java/compat'), (Join-Path $root 'build/resources/compat'))
& "$root/scripts/test/Run-FocusedTests.ps1" -Classes $Classes -ExtraMods $jars -ExtraClasspath $classpath
