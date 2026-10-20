<#
.SYNOPSIS
  Builds the Harbor debug app from a branch on this PC, then either installs it on a USB-connected Android phone or
  (with -Distribute) uploads it to Firebase App Distribution so testers can install it from their own phones.

.DESCRIPTION
  Run from anywhere inside your Harbor checkout. It needs your own apps\parent-android\parent.properties, which stays
  on this PC: the script only checks that the file exists and never reads it out, prints it or uploads it.
  It builds the real debug app (not the CI fixture), installs it over the existing one (keeping its data) and opens it.

  -Distribute needs the Firebase CLI on this PC (npm install -g firebase-tools) and a one-time "firebase login" with the
  Google account that owns the Firebase project. Nothing is stored or printed by this script: the CLI keeps its own login
  on this PC, and the app id is read from your local app\google-services.json only to tell the CLI which app to upload to.
  Name who gets the build with -Groups (a group alias from the Firebase console) and/or -Testers (email addresses,
  comma separated), or set HARBOR_FIREBASE_GROUPS / HARBOR_FIREBASE_TESTERS once on this PC. No phone needs to be plugged in.

.EXAMPLE
  .\apps\parent-android\scripts\install-on-phone.ps1
  .\apps\parent-android\scripts\install-on-phone.ps1 -Branch feat/checkin-alert -Serial R58M123ABC
  .\apps\parent-android\scripts\install-on-phone.ps1 -Branch feat/checkin-alert -Distribute -Groups family-testers
  .\apps\parent-android\scripts\install-on-phone.ps1 -Distribute -Testers "me@example.com" -ReleaseNotes "Check-in alert test"
#>
[CmdletBinding()]
param(
  [string]$Branch = 'feat/checkin-alert',
  [string]$Serial = '',
  [switch]$NoFetch,
  [switch]$Fresh,
  [switch]$Distribute,
  [string]$Groups = $env:HARBOR_FIREBASE_GROUPS,
  [string]$Testers = $env:HARBOR_FIREBASE_TESTERS,
  [string]$ReleaseNotes = ''
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

# 2b. Distribution settings (checked before the build so a missing piece fails fast)
$firebaseAppId = $null
if ($Distribute) {
  if (-not (Get-Command firebase -ErrorAction SilentlyContinue)) {
    Fail 'Firebase CLI not found. Run: npm install -g firebase-tools   then: firebase login'
  }
  if (-not $Groups -and -not $Testers) {
    Fail 'Say who gets the build: -Groups <group alias> and/or -Testers <emails>, or set HARBOR_FIREBASE_GROUPS / HARBOR_FIREBASE_TESTERS.'
  }
  $services = Join-Path $app 'app\google-services.json'
  if (-not (Test-Path $services)) { Fail 'apps\parent-android\app\google-services.json is missing. Download it for the Harbor Parent Android app from the Firebase console.' }
  $client = (Get-Content $services -Raw | ConvertFrom-Json).client |
    Where-Object { $_.client_info.android_client_info.package_name -eq $package } | Select-Object -First 1
  $firebaseAppId = $client.client_info.mobilesdk_app_id
  if (-not $firebaseAppId) { Fail "google-services.json has no Android app for $package." }
  if (-not $ReleaseNotes) { $ReleaseNotes = "$Branch $(& git rev-parse --short HEAD), $(Get-Date -Format 'yyyy-MM-dd HH:mm')" }
}

# 3. Tools
if (-not $env:JAVA_HOME) {
  $jbr = 'C:\Program Files\Android\Android Studio\jbr'
  if (Test-Path $jbr) { $env:JAVA_HOME = $jbr } else { Fail 'Set JAVA_HOME (Android Studio ships one in its jbr folder).' }
}
if (-not $Distribute) {
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
}

# 5. Build
Push-Location $app
try {
  & .\gradlew.bat --no-daemon assembleDebug
  if ($LASTEXITCODE) { Fail 'Gradle build failed. The error is above.' }
} finally { Pop-Location }
$apk = Get-ChildItem (Join-Path $app 'app\build\outputs\apk\debug') -Filter '*.apk' | Sort-Object LastWriteTime -Descending | Select-Object -First 1
if (-not $apk) { Fail 'Build finished but no debug APK was found.' }

# 6a. Distribute through Firebase App Distribution (no phone needed)
if ($Distribute) {
  $send = @('appdistribution:distribute', $apk.FullName, '--app', $firebaseAppId, '--release-notes', $ReleaseNotes)
  if ($Groups) { $send += @('--groups', $Groups) }
  if ($Testers) { $send += @('--testers', $Testers) }
  & firebase @send
  if ($LASTEXITCODE) { Fail 'Firebase upload failed. The error is above. Check "firebase login", that App Distribution is turned on for the project, and that the group alias exists.' }
  Write-Host "Done. Uploaded $($apk.Name) from $Branch to Firebase App Distribution. Testers get an email; open it on the phone and install." -ForegroundColor Green
  exit 0
}

# 6b. Install and open
if ($Fresh) { & $adb -s $Serial uninstall $package | Out-Null; Write-Host 'Removed the old install (-Fresh). The phone will need setting up again.' -ForegroundColor Yellow }
& $adb -s $Serial install -r $apk.FullName
if ($LASTEXITCODE) { Fail 'Install failed. If it says signatures do not match, run again with -Fresh.' }
& $adb -s $Serial shell monkey -p $package -c android.intent.category.LAUNCHER 1 | Out-Null
Write-Host "Done. Installed $($apk.Name) from $Branch and opened Harbor." -ForegroundColor Green
