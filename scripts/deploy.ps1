<#
.SYNOPSIS
Deploy portal-gphotos to a Meta Portal over adb in one shot.

.DESCRIPTION
Idempotent: safe to re-run. Everything but the APK is optional, so this also
works as a "reconfigure permissions/screensaver" pass on an already-installed app.

Non-destructive: a debug<->release switch changes the signing key, which forces an
uninstall+install. We preserve the downloaded media + OAuth token across that by
stashing the app's files dir on /sdcard and moving it back after the fresh install.

.PARAMETER Serial
The serial number of the adb device.

.PARAMETER Apk
Path to the APK file.

.PARAMETER Client
Path to the client_secret.json file. Defaults to "client_secret.json".

.PARAMETER Token
Path to the token.json file. Defaults to "token.json".

.PARAMETER Build
Switch to compile a release build before deploying.
#>
param(
    [string]$Serial = "",
    [string]$Apk = "",
    [string]$Client = "client_secret.json",
    [string]$Token = "token.json",
    [switch]$Build
)

$ErrorActionPreference = "Stop"

$PKG = "com.ramnat.portalgphotos"
$DREAM = "$PKG/$PKG.PhotoDreamService"
$FILES_DIR = "/sdcard/Android/data/$PKG/files"
$FILES_PARENT = "/sdcard/Android/data/$PKG"
$BAK = "/sdcard/portal-gphotos-deploy.bak"

# --- locate adb ---
$adbCmd = ""
if (Get-Command adb -ErrorAction SilentlyContinue) {
    $adbCmd = "adb"
} elseif ($env:ANDROID_HOME -and (Test-Path "$env:ANDROID_HOME\platform-tools\adb.exe")) {
    $adbCmd = "$env:ANDROID_HOME\platform-tools\adb.exe"
} elseif (Test-Path "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe") {
    $adbCmd = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
} else {
    Write-Error "adb not found. Add it to PATH, or set ANDROID_HOME."
    exit 1
}

# --- pick the device ---
if (-not $Serial) {
    $adbDevicesOutput = & $adbCmd devices
    $devices = $adbDevicesOutput | Select-String -Pattern "^([^\s]+)\s+device$" | ForEach-Object { $_.Matches.Groups[1].Value }
    
    if ($devices.Count -eq 0) {
        Write-Error "no authorized adb devices — connect the Portal and enable ADB"
        exit 1
    } elseif ($devices.Count -eq 1) {
        $Serial = $devices[0]
    } else {
        Write-Error "multiple devices; pass -Serial SERIAL. Found: $($devices -join ', ')"
        exit 1
    }
}

Write-Host ">> device: $Serial"

function Invoke-Adb {
    param(
        [Parameter(ValueFromRemainingArguments=$true)]
        $ArgsArray
    )
    # The trick with 2>&1 in PowerShell is it merges streams, we just pass args
    & $adbCmd -s $Serial @ArgsArray
}

# --- optional build ---
if ($Build) {
    Write-Host ">> .\gradlew.bat assembleRelease"
    & .\gradlew.bat assembleRelease
    
    # Re-evaluate APK if we just built the release one and the user didn't specify one
    if (-not $Apk -or $Apk -eq "app\build\outputs\apk\debug\app-debug.apk") {
        $Apk = "app\build\outputs\apk\release\app-release.apk"
    }
}

if (-not $Apk) {
    if (Test-Path "app-release.apk") {
        $Apk = "app-release.apk"
    } elseif (Test-Path "app\build\outputs\apk\release\app-release.apk") {
        $Apk = "app\build\outputs\apk\release\app-release.apk"
    } else {
        $Apk = "app\build\outputs\apk\debug\app-debug.apk"
    }
}

if (-not (Test-Path $Apk)) {
    Write-Error "APK not found: $Apk (download it, or run with -Build)"
    exit 1
}

# --- install (non-destructive across debug<->release signature changes) ---
Write-Host ">> install $Apk"
$installOut = Invoke-Adb install -r $Apk 2>&1 | Out-String

if ($installOut -match "Success") {
    Write-Host "   ok (in-place update, data preserved)"
} elseif ($installOut -match "INSTALL_FAILED_UPDATE_INCOMPATIBLE" -or $installOut -match "signatures do not match") {
    Write-Host "   signature mismatch (debug<->release) — preserving media + token across reinstall"
    Invoke-Adb shell "rm -rf '$BAK'" | Out-Null
    
    $checkDir = Invoke-Adb shell "test -d '$FILES_DIR' && echo 1 || echo 0" | Out-String
    $haveBak = $false
    if ($checkDir.Trim() -eq "1") {
        Invoke-Adb shell "mv '$FILES_DIR' '$BAK'" | Out-Null
        if ($LASTEXITCODE -eq 0) {
            Write-Host "   backed up app files -> $BAK"
            $haveBak = $true
        }
    }
    
    Invoke-Adb uninstall $PKG | Out-Null
    Write-Host "   uninstalled old build"
    
    Invoke-Adb install $Apk | Out-Null
    Write-Host "   installed fresh"
    
    if ($haveBak) {
        Invoke-Adb shell "mkdir -p '$FILES_PARENT' && rm -rf '$FILES_DIR' && mv '$BAK' '$FILES_DIR'" | Out-Null
        Write-Host "   restored app files (media + token)"
    }
} else {
    Write-Host "   install failed:" -ForegroundColor Red
    Write-Host $installOut -ForegroundColor Red
    exit 1
}

# --- push config ---
Invoke-Adb shell mkdir -p "$FILES_DIR" | Out-Null

if (Test-Path $Client) {
    Write-Host ">> push $Client"
    Invoke-Adb push $Client "$FILES_DIR/client_secret.json" | Out-Null
    Write-Host "   ok"
} else {
    Write-Host ">> no $Client found — pass -Client PATH to push one, or use a build with baked-in creds"
}

if (Test-Path $Token) {
    Write-Host ">> push $Token (pre-minted)"
    Invoke-Adb push $Token "$FILES_DIR/token.json" | Out-Null
    Write-Host "   ok"
}

# --- permissions (non-fatal: app degrades gracefully without them) ---
Write-Host ">> grant settings permissions"
Invoke-Adb shell pm grant "$PKG" android.permission.WRITE_SECURE_SETTINGS | Out-Null
if ($LASTEXITCODE -eq 0) { Write-Host "   WRITE_SECURE_SETTINGS ok" } else { Write-Host "   WRITE_SECURE_SETTINGS failed (screensaver re-assert disabled)" }

Invoke-Adb shell appops set "$PKG" WRITE_SETTINGS allow | Out-Null
if ($LASTEXITCODE -eq 0) { Write-Host "   WRITE_SETTINGS ok" } else { Write-Host "   WRITE_SETTINGS failed (sleep-when-alone timeout change disabled)" }

# --- register our screensaver ---
Write-Host ">> register screensaver"
Invoke-Adb shell settings put secure screensaver_components "$DREAM" | Out-Null
Invoke-Adb shell settings put secure screensaver_activate_on_sleep 1 | Out-Null

# --- launch ---
Write-Host ">> launch"
Invoke-Adb shell am start -n "$PKG/.MainActivity" | Out-Null
Write-Host ">> done. If this is a first install with no token, tap 'Sign in on this device'."
