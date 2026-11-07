Set-StrictMode -Version Latest

function Initialize-GraphicalWindows {
    if ('NullSpace.TestWindowGrid' -as [type]) { return }
    Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
using System.Text;
namespace NullSpace {
    public static class TestWindowGrid {
        [StructLayout(LayoutKind.Sequential)]
        public struct Rect { public int Left, Top, Right, Bottom; }
        [StructLayout(LayoutKind.Sequential)]
        struct MonitorInfo { public int Size; public Rect Monitor, Work; public uint Flags; }
        delegate bool EnumWindow(IntPtr window, IntPtr argument);
        [DllImport("user32.dll")] static extern bool EnumWindows(EnumWindow callback, IntPtr argument);
        [DllImport("user32.dll")] static extern uint GetWindowThreadProcessId(IntPtr window, out uint process);
        [DllImport("user32.dll")] static extern bool IsWindowVisible(IntPtr window);
        [DllImport("user32.dll")] static extern IntPtr GetWindow(IntPtr window, uint command);
        [DllImport("user32.dll", CharSet=CharSet.Unicode)] static extern int GetClassName(IntPtr window, StringBuilder name, int count);
        [DllImport("user32.dll", CharSet=CharSet.Unicode)] static extern int GetWindowText(IntPtr window, StringBuilder text, int count);
        [DllImport("user32.dll")] static extern IntPtr GetForegroundWindow();
        [DllImport("user32.dll")] static extern IntPtr MonitorFromWindow(IntPtr window, uint flags);
        [DllImport("user32.dll", CharSet=CharSet.Unicode)] static extern bool GetMonitorInfo(IntPtr monitor, ref MonitorInfo info);
        [DllImport("user32.dll")] static extern IntPtr SetThreadDpiAwarenessContext(IntPtr context);
        [DllImport("user32.dll")] static extern bool SetWindowPos(IntPtr window, IntPtr after, int x, int y, int width, int height, uint flags);
        [DllImport("user32.dll")] static extern bool GetWindowRect(IntPtr window, out Rect rectangle);
        [DllImport("user32.dll", CharSet=CharSet.Unicode)] static extern IntPtr SendMessageTimeout(IntPtr window, uint message, IntPtr argument, string text, uint flags, uint timeout, out UIntPtr result);

        public static Rect WorkArea() {
            var previous = SetThreadDpiAwarenessContext(new IntPtr(-4));
            try {
                var info = new MonitorInfo { Size = Marshal.SizeOf(typeof(MonitorInfo)) };
                if (!GetMonitorInfo(MonitorFromWindow(GetForegroundWindow(), 2), ref info))
                    throw new InvalidOperationException("Cannot read the active monitor's working area");
                return info.Work;
            } finally { if (previous != IntPtr.Zero) SetThreadDpiAwarenessContext(previous); }
        }

        public static bool PositionOwned(IntPtr window, int processId, int x, int y, int width, int height) {
            uint owner;
            GetWindowThreadProcessId(window, out owner);
            if (owner != (uint)processId || window == IntPtr.Zero) return false;
            var previous = SetThreadDpiAwarenessContext(new IntPtr(-4));
            try {
                // Keep focus and stacking order; post the resize to the owning render thread.
                return SetWindowPos(window, IntPtr.Zero, x, y, width, height, 0x4014);
            } finally { if (previous != IntPtr.Zero) SetThreadDpiAwarenessContext(previous); }
        }

        public static Rect Bounds(IntPtr window) {
            var previous = SetThreadDpiAwarenessContext(new IntPtr(-4));
            try {
                Rect rectangle;
                if (!GetWindowRect(window, out rectangle)) throw new InvalidOperationException("Cannot read test window bounds");
                return rectangle;
            } finally { if (previous != IntPtr.Zero) SetThreadDpiAwarenessContext(previous); }
        }

        public static IntPtr FindGameWindow(int processId) {
            IntPtr found = IntPtr.Zero;
            EnumWindows((window, argument) => {
                uint owner;
                GetWindowThreadProcessId(window, out owner);
                if (owner != (uint)processId || !IsWindowVisible(window) || GetWindow(window, 4) != IntPtr.Zero) return true;
                var name = new StringBuilder(256);
                GetClassName(window, name, name.Capacity);
                // SDL's top-level game window, excluding Java consoles and helper windows.
                if (!name.ToString().StartsWith("SDL", StringComparison.Ordinal)) return true;
                found = window;
                return false;
            }, IntPtr.Zero);
            return found;
        }

        public static long Arrange(int processId, long previousWindow, string label, int x, int y, int width, int height) {
            var found = FindGameWindow(processId);
            if (found == IntPtr.Zero) return 0;
            if (found.ToInt64() != previousWindow && !PositionOwned(found, processId, x, y, width, height)) return 0;
            var title = new StringBuilder(512);
            GetWindowText(found, title, title.Capacity);
            if (title.ToString() != label) {
                UIntPtr result;
                // A stalled game window must never block the test runner's timeout handling.
                SendMessageTimeout(found, 0x000C, IntPtr.Zero, label, 2, 100, out result);
            }
            return found.ToInt64();
        }
    }
}
'@
}

function Get-GraphicalWindowLayout {
    param(
        [ValidateRange(1,8)] [int] $Count,
        [int] $X, [int] $Y,
        [ValidateRange(64,100000)] [int] $Width,
        [ValidateRange(64,100000)] [int] $Height
    )
    $columns = 1; $best = -1.0
    for ($candidate = 1; $candidate -le $Count; $candidate++) {
        $rows = [int][Math]::Ceiling($Count / [double]$candidate)
        $score = [Math]::Min(($Width / [double]$candidate - 24) / 1280, ($Height / [double]$rows - 48) / 720)
        if ($score -gt $best) { $columns = $candidate; $best = $score }
    }
    $rows = [int][Math]::Ceiling($Count / [double]$columns)
    $windows = @(for ($index = 0; $index -lt $Count; $index++) {
        $column = $index % $columns; $row = [int][Math]::Floor($index / [double]$columns)
        $left = [int][Math]::Floor($Width * $column / [double]$columns)
        $top = [int][Math]::Floor($Height * $row / [double]$rows)
        $cellWidth = [int][Math]::Floor($Width * ($column + 1) / [double]$columns) - $left
        $cellHeight = [int][Math]::Floor($Height * ($row + 1) / [double]$rows) - $top
        $windowWidth = [Math]::Min(1296, $cellWidth - 8)
        $windowHeight = [Math]::Min(760, $cellHeight - 8)
        [pscustomobject]@{x=$X+$left+[int][Math]::Floor(($cellWidth-$windowWidth)/2);y=$Y+$top+[int][Math]::Floor(($cellHeight-$windowHeight)/2);width=$windowWidth;height=$windowHeight}
    })
    [pscustomobject]@{columns=$columns;rows=$rows;windows=$windows}
}

function Get-GraphicalTestWorkArea {
    Initialize-GraphicalWindows
    $area = [NullSpace.TestWindowGrid]::WorkArea()
    [pscustomobject]@{x=$area.Left;y=$area.Top;width=$area.Right-$area.Left;height=$area.Bottom-$area.Top}
}

function Set-GraphicalTestWindow {
    param([int]$ProcessId,[long]$PreviousWindow,[string]$Name,$Bounds)
    Initialize-GraphicalWindows
    [NullSpace.TestWindowGrid]::Arrange($ProcessId,$PreviousWindow,('NullSpace tests | '+$Name),$Bounds.x,$Bounds.y,$Bounds.width,$Bounds.height)
}

Export-ModuleMember -Function Get-GraphicalWindowLayout,Get-GraphicalTestWorkArea,Set-GraphicalTestWindow
