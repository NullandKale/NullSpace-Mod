param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]] $GradleArguments
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$previousJavaHome = $env:JAVA_HOME
$previousPath = $env:PATH
$previousGradleHome = $env:GRADLE_USER_HOME
$exitCode = 1

try {
    $localJdk = Get-ChildItem -LiteralPath (Join-Path $projectRoot '.tools') -Directory -Filter 'jdk-25*' -ErrorAction SilentlyContinue |
        Sort-Object Name -Descending | Select-Object -First 1

    if ($localJdk) {
        $env:JAVA_HOME = $localJdk.FullName
    }

    if (-not $env:JAVA_HOME -or -not (Test-Path -LiteralPath (Join-Path $env:JAVA_HOME 'bin\javac.exe'))) {
        throw 'Install JDK 25 and set JAVA_HOME, or extract it into .tools/jdk-25*.'
    }

    $javaVersion = & (Join-Path $env:JAVA_HOME 'bin\javac.exe') -version 2>&1
    if ($LASTEXITCODE -ne 0 -or "$javaVersion" -notmatch '^javac 25(?:\.|\s|$)') {
        throw "JDK 25 is required. JAVA_HOME currently provides: $javaVersion"
    }

    $env:PATH = "$(Join-Path $env:JAVA_HOME 'bin');$env:PATH"
    $env:GRADLE_USER_HOME = Join-Path $projectRoot '.gradle-user-home'
    Push-Location $projectRoot
    try {
        # Windows PowerShell can treat redirected native stderr warnings as
        # terminating errors. Gradle's process exit code is authoritative.
        $ErrorActionPreference = 'Continue'
        & (Join-Path $projectRoot 'gradlew.bat') @GradleArguments
        $exitCode = $LASTEXITCODE
        $ErrorActionPreference = 'Stop'
    }
    finally {
        Pop-Location
    }
}
finally {
    $env:JAVA_HOME = $previousJavaHome
    $env:PATH = $previousPath
    $env:GRADLE_USER_HOME = $previousGradleHome
}

exit $exitCode

