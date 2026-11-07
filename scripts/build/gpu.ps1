param([switch] $Apply,[switch] $Restore)
$RepoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$ErrorActionPreference='Stop'
if($Apply -and $Restore){throw 'Choose Apply or Restore.'}
$gpuPreferenceRegistry='HKCU:\Software\Microsoft\DirectX\UserGpuPreferences'
$gpuPreferenceBackup=Join-Path $RepoRoot '.tools/gpu-preference-backup.json'
$gpuJdk=Get-ChildItem -LiteralPath (Join-Path $RepoRoot '.tools') -Directory -Filter 'jdk-25*' | Sort-Object Name -Descending | Select-Object -First 1
if(-not $gpuJdk){throw 'No project JDK 25 found.'}
$gpuExecutables=@('java.exe','javaw.exe') | ForEach-Object {Join-Path $gpuJdk.FullName "bin/$_"}
if($Restore){
    if(-not (Test-Path -LiteralPath $gpuPreferenceBackup)){throw 'No saved preference backup.'}
    foreach($gpuPrevious in @(Get-Content -LiteralPath $gpuPreferenceBackup -Raw | ConvertFrom-Json)){
        if($gpuPrevious.executable -notin $gpuExecutables){throw 'Backup is not for the current project JDK.'}
        if($gpuPrevious.existed){Set-ItemProperty -LiteralPath $gpuPreferenceRegistry -Name $gpuPrevious.executable -Value $gpuPrevious.value -Type String}
        else{Remove-ItemProperty -LiteralPath $gpuPreferenceRegistry -Name $gpuPrevious.executable -ErrorAction SilentlyContinue}
    }
    Write-Host 'Restored the saved GPU preferences for the project JDK.'
    exit 0
}
$gpuRecords=@()
foreach($gpuExecutable in $gpuExecutables){
    $gpuExisting=$null
    if(Test-Path -LiteralPath $gpuPreferenceRegistry){$gpuExisting=(Get-Item -LiteralPath $gpuPreferenceRegistry).GetValue($gpuExecutable)}
    $gpuRecords += [pscustomobject]@{executable=$gpuExecutable;existed=($null -ne $gpuExisting);value=$gpuExisting}
}
if($Apply){
    if(-not (Test-Path -LiteralPath $gpuPreferenceBackup)){
        $null=New-Item -ItemType Directory -Force -Path (Join-Path $RepoRoot '.tools')
        $gpuRecords | ConvertTo-Json | Set-Content -LiteralPath $gpuPreferenceBackup -Encoding UTF8
    }
    if(-not (Test-Path -LiteralPath $gpuPreferenceRegistry)){$null=New-Item -Path $gpuPreferenceRegistry}
    foreach($gpuRecord in $gpuRecords){
        $gpuValue=([string]$gpuRecord.value -replace 'GpuPreference=[^;]*;?','').Trim(';')
        if($gpuValue){$gpuValue+=';'}
        Set-ItemProperty -LiteralPath $gpuPreferenceRegistry -Name $gpuRecord.executable -Value ($gpuValue+'GpuPreference=2;') -Type String
    }
    Write-Host 'Windows high-performance GPU preference applied to this project JDK. It takes effect for newly launched processes. The Minecraft log verifies the actual adapter.'
}
foreach($gpuExecutable in $gpuExecutables){
    $gpuValue=if(Test-Path -LiteralPath $gpuPreferenceRegistry){(Get-Item -LiteralPath $gpuPreferenceRegistry).GetValue($gpuExecutable)}else{$null}
    [pscustomobject]@{Executable=$gpuExecutable;Preference=$gpuValue}
}
