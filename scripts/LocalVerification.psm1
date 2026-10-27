Set-StrictMode -Version Latest
function Quote-JavaArgument([string] $Value) {
    return '"' + $Value.Replace('\','\\').Replace('"','\"') + '"'
}
function Write-JavaArguments([string] $Path, [string[]] $Arguments) {
    [System.IO.File]::WriteAllLines($Path, @($Arguments | ForEach-Object { Quote-JavaArgument $_ }), [System.Text.UTF8Encoding]::new($false))
}
function Write-VerificationJson([string] $Path, $Value) {
    [System.IO.File]::WriteAllText($Path, ($Value | ConvertTo-Json -Depth 12), [System.Text.UTF8Encoding]::new($false))
}
function Assert-VerificationMarker([string] $Path, [string] $Expected = 'passed') {
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { throw "Missing success marker: $Path" }
    if ((Get-Content -LiteralPath $Path -Raw).Trim() -ne $Expected) { throw "Invalid success marker: $Path" }
}
function Get-VerificationFingerprint([string] $Root) {
    $absolute=[System.IO.Path]::GetFullPath($Root).TrimEnd('\','/')
    $files=@(& rg --files (Join-Path $absolute 'src') (Join-Path $absolute 'gradle') (Join-Path $absolute 'scripts'))
    $files+=@(Get-ChildItem -LiteralPath $absolute -File | Where-Object { $_.Extension -in @('.ps1','.gradle','.properties') -or $_.Name -in @('gradlew','gradlew.bat') } | ForEach-Object { $_.FullName })
    $lines=@($files | Sort-Object -Unique | ForEach-Object {
        $path=[System.IO.Path]::GetFullPath($_)
        $relative=$path.Substring($absolute.Length).Replace('\','/')
        "$relative`t$((Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash)"
    })
    $algorithm=[System.Security.Cryptography.SHA256]::Create()
    try { return ([BitConverter]::ToString($algorithm.ComputeHash([Text.Encoding]::UTF8.GetBytes(($lines -join "`n"))))).Replace('-','').ToLowerInvariant() }
    finally { $algorithm.Dispose() }
}
function Assert-VerificationFingerprint([string] $Root, [string] $Expected) {
    if((Get-VerificationFingerprint $Root) -ne $Expected){throw 'Verification inputs changed during the run; results cannot certify one build'}
}
function Assert-VerificationInventory([string[]] $Expected, [string[]] $Actual) {
    if($Expected.Count -eq 0 -or $Actual.Count -ne $Expected.Count -or @($Actual | Select-Object -Unique).Count -ne $Actual.Count -or @(Compare-Object $Expected $Actual).Count -ne 0){throw 'Verification did not complete the exact declared test inventory'}
}
Export-ModuleMember -Function Quote-JavaArgument,Write-JavaArguments,Write-VerificationJson,Assert-VerificationMarker,Get-VerificationFingerprint,Assert-VerificationFingerprint,Assert-VerificationInventory
