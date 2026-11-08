Add-Type @"
  using System;
  using System.Runtime.InteropServices;
  using System.Text;
  public class WinUtils {
    public delegate bool EnumWindowsProc(IntPtr hWnd, IntPtr lParam);
    [DllImport("user32.dll")]
    public static extern bool EnumWindows(EnumWindowsProc lpEnumFunc, IntPtr lParam);
    [DllImport("user32.dll")]
    public static extern int GetWindowText(IntPtr hWnd, StringBuilder lpString, int nMaxCount);
    [DllImport("user32.dll")]
    public static extern bool IsWindowVisible(IntPtr hWnd);
    [DllImport("user32.dll")]
    public static extern uint GetWindowThreadProcessId(IntPtr hWnd, out uint lpdwProcessId);
    [DllImport("user32.dll")]
    public static extern bool ShowWindow(IntPtr hWnd, int nCmdShow);
    [DllImport("user32.dll")]
    public static extern bool SetForegroundWindow(IntPtr hWnd);
  }
"@

$targetPids = @(Get-Process -Name emulator*, qemu* -ErrorAction SilentlyContinue | Select-Object -ExpandProperty Id)
Write-Host "Target PIDs: $($targetPids -join ', ')"

[WinUtils]::EnumWindows({
  param($h, $l)
  $p = 0
  [WinUtils]::GetWindowThreadProcessId($h, [ref]$p)
  if ($targetPids -contains $p) {
    $sb = New-Object System.Text.StringBuilder 256
    [WinUtils]::GetWindowText($h, $sb, 256)
    $vis = [WinUtils]::IsWindowVisible($h)
    $title = $sb.ToString()
    Write-Host "PID $p HWND $h Vis: $vis Title: '$title'"
    if ($title -ne "") {
      Write-Host "Restoring and bringing to front..."
      [WinUtils]::ShowWindow($h, 9) # SW_RESTORE
      [WinUtils]::SetForegroundWindow($h)
    }
  }
  return $true
}, [IntPtr]::Zero)
