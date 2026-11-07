param([string]$ArtifactRoot = '',[switch]$Native)
$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot '../lib/GraphicalWindows.psm1') -Force
Import-Module (Join-Path $PSScriptRoot '../lib/LocalVerification.psm1') -Force -DisableNameChecking
if (-not $ArtifactRoot) { $ArtifactRoot = Join-Path (Split-Path -Parent (Split-Path -Parent $PSScriptRoot)) ('build/window-grid-tests/' + [guid]::NewGuid().ToString('N')) }
$null = New-Item -ItemType Directory -Force -Path $ArtifactRoot
$layouts = 0
foreach ($area in @(
    @{x=0;y=0;width=2560;height=1392},
    @{x=-1920;y=120;width=1920;height=1040},
    @{x=2560;y=-1080;width=1920;height=1080},
    @{x=0;y=0;width=1080;height=1880}
)) {
    foreach ($count in 1..8) {
        $layout = Get-GraphicalWindowLayout -Count $count @area
        if ($layout.windows.Count -ne $count) { throw 'A grid must contain every worker exactly once' }
        foreach ($window in $layout.windows) {
            if ($window.width -le 0 -or $window.height -le 0 -or $window.x -lt $area.x -or $window.y -lt $area.y -or $window.x+$window.width -gt $area.x+$area.width -or $window.y+$window.height -gt $area.y+$area.height) { throw 'Worker window escaped the monitor working area' }
        }
        for ($i=0; $i -lt $count; $i++) {
            for ($j=$i+1; $j -lt $count; $j++) {
                $a=$layout.windows[$i]; $b=$layout.windows[$j]
                if ($a.x -lt $b.x+$b.width -and $b.x -lt $a.x+$a.width -and $a.y -lt $b.y+$b.height -and $b.y -lt $a.y+$a.height) { throw 'Worker windows overlap' }
            }
        }
        $layouts++
    }
}
$six = Get-GraphicalWindowLayout -Count 6 -X 0 -Y 0 -Width 2560 -Height 1392
$eight = Get-GraphicalWindowLayout -Count 8 -X 0 -Y 0 -Width 2560 -Height 1392
if ($six.columns -ne 3 -or $six.rows -ne 2 -or $eight.columns -ne 3 -or $eight.rows -ne 3) { throw 'Wide-screen six/eight layouts changed unexpectedly' }
$nativeWindows = 0
if ($Native) {
    $area = Get-GraphicalTestWorkArea
    $layout = Get-GraphicalWindowLayout -Count 8 -X $area.x -Y $area.y -Width $area.width -Height $area.height
    Add-Type -AssemblyName System.Windows.Forms
    Add-Type -ReferencedAssemblies System.Windows.Forms -TypeDefinition @'
public sealed class NullSpaceGridProbe : System.Windows.Forms.Form {
    protected override bool ShowWithoutActivation { get { return true; } }
}
'@
    $forms = @()
    try {
        for ($i=0; $i -lt 8; $i++) {
            $form = New-Object NullSpaceGridProbe
            $form.Text = 'NullSpace grid check ' + ($i+1)
            $form.ShowInTaskbar = $false
            $forms += $form
            $form.Show()
            $bounds = $layout.windows[$i]
            if (-not [NullSpace.TestWindowGrid]::PositionOwned($form.Handle,$PID,$bounds.x,$bounds.y,$bounds.width,$bounds.height)) { throw 'Native placement failed' }
        }
        [System.Windows.Forms.Application]::DoEvents()
        for ($i=0; $i -lt 8; $i++) {
            $actual = [NullSpace.TestWindowGrid]::Bounds($forms[$i].Handle)
            $expected = $layout.windows[$i]
            if ($actual.Left -ne $expected.x -or $actual.Top -ne $expected.y -or $actual.Right-$actual.Left -ne $expected.width -or $actual.Bottom-$actual.Top -ne $expected.height) { throw 'Native window did not occupy its assigned grid slot' }
            $nativeWindows++
        }
        if ([NullSpace.TestWindowGrid]::PositionOwned($forms[0].Handle,0,0,0,100,100)) { throw 'An unrelated process can never be moved' }
    } finally { foreach ($form in $forms) { $form.Dispose() } }
}
Write-VerificationJson (Join-Path $ArtifactRoot 'result.json') @{status='passed';layouts=$layouts;nativeWindows=$nativeWindows;ownershipChecked=[bool]$Native}
Write-Host "Window grid checks passed: $layouts layouts, $nativeWindows native windows"
