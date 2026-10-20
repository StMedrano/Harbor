<#
.SYNOPSIS
  Builds the Harbor debug app from a branch on this PC and installs it on a USB-connected Android phone.

.DESCRIPTION
  Run from anywhere inside your Harbor checkout. It needs your own apps\parent-android\parent.properties, which stays
  on this PC: the script only checks that the file exists and never reads it out, prints it or uploads it.
  It builds the real debug app (not the CI fixture), installs it over the existing one (keeping its data) and opens it.

.EXAMPLE
  .\apps\parent-android\scripts\install-on-phone.ps1
  .\apps\parent-android\scripts\install-on-phone.ps1 -Branch feat/android-parent-restyle -Serial R58M123ABC
#>
[CmdletBinding()]
param(
  [string]$Branch = 'feat/android-parent-restyle',
  [string]$Serial = '',
  [switch]$NoFetch,
  [switch]$Fresh
)
$ErrorActionPreference = 'Stop'
$package = 'dev.stmedrano.harbor.parent'

function Fail([string]$message) { Write-Host "ERROR: $message" -ForegroundColor Red; exit 1 }

# 1. Repository
$root = (& git rev-parse --show-toplevel 2>$null)
if (-not $root) { Fail 'Run this inside your Harbor git checkout.' }
$app = Join-Path $root 'apps\parent-android'
if (-not (Test-Path $app)) { Fail "Cannot find $app" }
Set-Location $root
if (& git status --porcelain) { Fail 'You have uncommitted changes. Commit or stash them first so nothing is lost.' }
if (-not $NoFetch) { & git fetch origin $Branch; if ($LASTEXITCODE) { Fail "Could not fetch $Branch." } }
& git checkout $Branch
if ($LASTEXITCODE) { Fail "Could not check out $Branch." }
if (-not $NoFetch) { & git merge --ff-only "origin/$Branch"; if ($LASTEXITCODE) { Fail "Local $Branch has diverged from origin. Resolve that first." } }
Write-Host "On $Branch at $(& git rev-parse --short HEAD)" -ForegroundColor Cyan

# 2. Local configuration (never printed)
if (-not (Test-Path (Join-Path $app 'parent.properties'))) {
  Fail 'apps\parent-android\parent.properties is missing. Copy parent.properties.example to parent.properties and fill in your own values.'
}

# 3. Tools
if (-not $env:JAVA_HOME) {
  $jbr = 'C:\Program Files\Android\Android Studio\jbr'
  if (Test-Path $jbr) { $env:JAVA_HOME = $jbr } else { Fail 'Set JAVA_HOME (Android Studio ships one in its jbr folder).' }
}
$sdk = @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT, (Join-Path $env:LOCALAPPDATA 'Android\Sdk')) | Where-Object { $_ -and (Test-Path $_) } | Select-Object -First 1
$adb = if (Get-Command adb -ErrorAction SilentlyContinue) { 'adb' } elseif ($sdk -and (Test-Path (Join-Path $sdk 'platform-tools\adb.exe'))) { Join-Path $sdk 'platform-tools\adb.exe' } else { $null }
if (-not $adb) { Fail 'adb not found. Install Android SDK Platform-Tools or add it to PATH.' }

# 4. Phone
$lines = & $adb devices | Select-Object -Skip 1 | Where-Object { $_ -match '\S' }
$ready = @($lines | Where-Object { $_ -match '\sdevice$' } | ForEach-Object { ($_ -split '\s+')[0] })
if ($lines | Where-Object { $_ -match '\sunauthorized$' }) { Fail 'Phone is unauthorized. Unlock it and accept the USB debugging prompt, then run again.' }
if ($Serial) { if ($ready -notcontains $Serial) { Fail "Phone $Serial is not connected." } }
elseif ($ready.Count -eq 1) { $Serial = $ready[0] }
elseif ($ready.Count -eq 0) { Fail 'No phone found. Plug it in, enable USB debugging and run again.' }
else { Fail "More than one device is connected ($($ready -join ', ')). Pass -Serial <id>." }
Write-Host "Installing to $Serial" -ForegroundColor Cyan

# 5. Build
Push-Location $app
try {
  & .\gradlew.bat --no-daemon assembleDebug
  if ($LASTEXITCODE) { Fail 'Gradle build failed. The error is above.' }
} finally { Pop-Location }
$apk = Get-ChildItem (Join-Path $app 'app\build\outputs\apk\debug') -Filter '*.apk' | Sort-Object LastWriteTime -Descending | Select-Object -First 1
if (-not $apk) { Fail 'Build finished but no debug APK was found.' }

# 6. Install and open
if ($Fresh) { & $adb -s $Serial uninstall $package | Out-Null; Write-Host 'Removed the old install (-Fresh). The phone will need setting up again.' -ForegroundColor Yellow }
& $adb -s $Serial install -r $apk.FullName
if ($LASTEXITCODE) { Fail 'Install failed. If it says signatures do not match, run again with -Fresh.' }
& $adb -s $Serial shell monkey -p $package -c android.intent.category.LAUNCHER 1 | Out-Null
Write-Host "Done. Installed $($apk.Name) from $Branch and opened Harbor." -ForegroundColor Green
