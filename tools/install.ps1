# vivo automated install script (handles security-confirm dialog + version verify)
# Usage: powershell -File tools\install.ps1 -Apk <path-to-apk>
param(
    [Parameter(Mandatory = $true)]
    [string]$Apk
)

$pkg = "com.rhythm.shots"

# locate aapt2 from ANDROID_HOME (or default SDK path)
$sdk = $env:ANDROID_HOME
if (-not $sdk) { $sdk = "$env:LOCALAPPDATA\Android\Sdk" }
$bt = Get-ChildItem "$sdk\build-tools" -Directory -ErrorAction SilentlyContinue |
    Sort-Object Name -Descending | Select-Object -First 1 -ExpandProperty FullName
if (-not $bt) { Write-Error "cannot locate build-tools under $sdk"; exit 1 }

# 1. read versionName from APK (aapt2)
$badging = & "$bt\aapt2.exe" dump badging $Apk 2>$null
$m = [regex]::Match($badging, "versionName='([^']+)'")
if (-not $m.Success) { Write-Error "cannot read APK versionName"; exit 1 }
$expectedVer = $m.Groups[1].Value
Write-Output "expected: $pkg v$expectedVer"

# 2. check device online & unlocked
$dev = adb devices | Select-String "device$"
if (-not $dev) { Write-Error "no device"; exit 1 }
$focus = adb shell "dumpsys window 2>/dev/null | grep mCurrentFocus"
if ($focus -match "keyguard|NotificationShade|Dream") {
    Write-Warning "device seems LOCKED (focus: $focus). Unlock the pad first!"
    exit 1
}

# record pre-install lastUpdateTime for verification
$preDump = adb shell "dumpsys package $pkg" 2>$null
$preTime = [regex]::Match(($preDump -join "`n"), "lastUpdateTime=([^\r\n]+)").Groups[1].Value

# 3. start install in background
Write-Output "starting adb install ..."
$proc = Start-Process adb -ArgumentList "install","-r",$apk -NoNewWindow -PassThru

# 4. poll and handle vivo security dialog (checkbox + continue button)
$deadline = (Get-Date).AddMinutes(5)
$tapped = @{}
$lastXml = ""
while ((Get-Date) -lt $deadline -and -not $proc.HasExited) {
    Start-Sleep -Seconds 2
    adb shell "uiautomator dump /sdcard/ui_install.xml" 2>$null | Out-Null
    adb pull /sdcard/ui_install.xml "$env:TEMP\ui_install.xml" 2>$null | Out-Null
    if (-not (Test-Path "$env:TEMP\ui_install.xml")) { continue }
    $xml = Get-Content "$env:TEMP\ui_install.xml" -Raw -Encoding UTF8
    if (-not $xml -or $xml -eq $lastXml) { continue }
    $lastXml = $xml

    # checkbox
    if (-not $tapped.ContainsKey("cb")) {
        $cb = [regex]::Match($xml, 'class="android.widget.CheckBox"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
        if ($cb.Success) {
            $x = ([int]$cb.Groups[1].Value + [int]$cb.Groups[3].Value) / 2
            $y = ([int]$cb.Groups[2].Value + [int]$cb.Groups[4].Value) / 2
            adb shell input tap $x $y | Out-Null
            $tapped["cb"] = $true
            Write-Output "checked checkbox ($x,$y)"
            Start-Sleep -Milliseconds 800
        }
    }

    # continue button: try android:id/button1, then text-based match
    if (-not $tapped.ContainsKey("btn")) {
        $btn = [regex]::Match($xml, 'resource-id="android:id/button1"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
        if (-not $btn.Success) {
            $btn = [regex]::Match($xml, 'text="[^"]*(continue|install|ok|allow|install|continue)[^"]*"[^>]*class="android.widget.Button"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', [System.Text.RegularExpressions.RegexOptions]::IgnoreCase)
        }
        if ($btn.Success) {
            $g = if ($btn.Groups.Count -eq 5) { $btn } else { $btn }
            # normalize groups: with resource-id form groups 1-4 are coords; with text form coords are 2-5
            $x = 0; $y = 0
            if ($btn.Groups.Count -ge 5) {
                $x = ([int]$btn.Groups[$btn.Groups.Count - 4].Value + [int]$btn.Groups[$btn.Groups.Count - 2].Value) / 2
                $y = ([int]$btn.Groups[$btn.Groups.Count - 3].Value + [int]$btn.Groups[$btn.Groups.Count - 1].Value) / 2
            }
            adb shell input tap $x $y | Out-Null
            $tapped["btn"] = $true
            Write-Output "tapped continue ($x,$y)"
        }
    }
}

$proc.WaitForExit(30000) | Out-Null
Write-Output "adb install exit code: $($proc.ExitCode)"

# 5. verify via dumpsys package (versionName AND lastUpdateTime changed)
Start-Sleep -Seconds 3
$dump = adb shell "dumpsys package $pkg" 2>$null
$installed = [regex]::Match(($dump -join "`n"), "versionName=([^\s]+)")
$newTime = [regex]::Match(($dump -join "`n"), "lastUpdateTime=([^\r\n]+)").Groups[1].Value
if ($installed.Success -and $installed.Groups[1].Value -eq $expectedVer -and $newTime -ne $preTime -and $newTime -ne "") {
    Write-Output "INSTALL VERIFIED: $pkg v$expectedVer (updated at $newTime)"
    exit 0
} else {
    Write-Output "verify failed: installed=$($installed.Groups[1].Value) expected=$expectedVer pre=$preTime new=$newTime"
    adb shell "pm path $pkg" 2>$null
    exit 1
}
