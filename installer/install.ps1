<#
    SAMJ-IJ one-shot installer for Windows (PowerShell 5+).

    What it does:
      1. Downloads the official Fiji distribution (JDK bundled) for Windows x64.
      2. Extracts Fiji into an install directory (default: $HOME\Fiji-SAMJ).
      3. Registers the SAMJ update site and installs plugins headlessly.
      4. Applies the JNA fix documented in the SAMJ-IJ README.
      5. Prints how to launch the resulting Fiji.

    Usage (from a normal PowerShell prompt -- admin NOT required):

      powershell -ExecutionPolicy Bypass -File .\install.ps1
      powershell -ExecutionPolicy Bypass -File .\install.ps1 -InstallDir "C:\Tools\Fiji-SAMJ"
      powershell -ExecutionPolicy Bypass -File .\install.ps1 -Force
#>

param(
    [string]$InstallDir = "$env:USERPROFILE\Fiji-SAMJ",
    [switch]$Force
)

$ErrorActionPreference = "Stop"

$SamjSiteName = "SAMJ"
$SamjSiteUrl  = "https://sites.imagej.net/SAMJ/"
$FijiUrl      = "https://downloads.imagej.net/fiji/latest/fiji-latest-win64-jdk.zip"
$FijiZipName  = "fiji-latest-win64-jdk.zip"

function Log($msg)  { Write-Host "[SAMJ] $msg" -ForegroundColor Cyan }
function Warn($msg) { Write-Host "[WARN] $msg" -ForegroundColor Yellow }
function Die($msg)  { Write-Host "[ERR ] $msg" -ForegroundColor Red; exit 1 }

# ---------- arch check ----------
if ([System.Environment]::Is64BitOperatingSystem -eq $false) {
    Die "Fiji requires a 64-bit version of Windows."
}

# ---------- prepare install dir ----------
New-Item -ItemType Directory -Force -Path $InstallDir | Out-Null
Set-Location $InstallDir

$fijiApp = Join-Path $InstallDir "Fiji.app"
if (Test-Path $fijiApp) {
    if ($Force) {
        Log "Force flag set -- removing existing $fijiApp"
        Remove-Item -Recurse -Force $fijiApp
    } else {
        Log "Existing Fiji.app found at $fijiApp -- re-running update site registration and JNA fix only."
    }
}

# ---------- download + extract ----------
if (-not (Test-Path $fijiApp)) {
    $zipPath = Join-Path $InstallDir $FijiZipName
    Log "Downloading Fiji (~700 MB): $FijiUrl"
    # Speed hack: turn off Invoke-WebRequest progress bar (it slows large downloads to a crawl on PS5).
    $prev = $ProgressPreference
    $ProgressPreference = 'SilentlyContinue'
    try {
        Invoke-WebRequest -Uri $FijiUrl -OutFile $zipPath -UseBasicParsing
    } finally {
        $ProgressPreference = $prev
    }

    Log "Extracting Fiji into $InstallDir ..."
    Expand-Archive -Path $zipPath -DestinationPath $InstallDir -Force
    Remove-Item $zipPath -Force

    if (-not (Test-Path $fijiApp)) {
        $nested = Get-ChildItem -Path $InstallDir -Recurse -Depth 3 -Directory -Filter 'Fiji.app' | Select-Object -First 1
        if (-not $nested) { Die "Extraction succeeded but Fiji.app was not found under $InstallDir" }
        if ($nested.FullName -ne $fijiApp) { Move-Item $nested.FullName $fijiApp }
    }
}
Log "Fiji.app at: $fijiApp"

# ---------- locate Fiji executable ----------
$candidates = @(
    (Join-Path $fijiApp 'fiji-windows-x64.exe'),
    (Join-Path $fijiApp 'ImageJ-win64.exe')
)
$fijiExe = $null
foreach ($c in $candidates) { if (Test-Path $c) { $fijiExe = $c; break } }
if (-not $fijiExe) {
    $fijiExe = (Get-ChildItem -Path $fijiApp -Filter '*.exe' -Recurse -Depth 2 |
                Where-Object { $_.Name -match '^(fiji|ImageJ)-' } |
                Select-Object -First 1 -ExpandProperty FullName)
}
if (-not $fijiExe) { Die "Could not find the Fiji launcher inside $fijiApp" }
Log "Fiji launcher: $fijiExe"

# ---------- register SAMJ update site + install plugins headlessly ----------
Log "Adding update site: $SamjSiteName -> $SamjSiteUrl"
# 'edit-update-site' upserts (add if missing, update if present); fall back to 'add-update-site' on old Fiji builds.
& $fijiExe --headless --update edit-update-site $SamjSiteName $SamjSiteUrl
if ($LASTEXITCODE -ne 0) {
    Log "edit-update-site not supported by this Fiji build; using add-update-site instead."
    & $fijiExe --headless --update add-update-site $SamjSiteName $SamjSiteUrl
    if ($LASTEXITCODE -ne 0) { Die "Failed to register the SAMJ update site." }
}

Log "Downloading and installing SAMJ plugins (this can take several minutes) ..."
& $fijiExe --headless --update update
if ($LASTEXITCODE -ne 0) { Warn "Fiji updater exited with code $LASTEXITCODE -- inspect $fijiApp\update-log.txt if plugins are missing." }

# ---------- JNA fix per SAMJ README ----------
$jarsDir = Join-Path $fijiApp 'jars'
if (Test-Path $jarsDir) {
    Log "Applying JNA fix in $jarsDir"
    $badFiles = @('jna-3.2.7.jar', 'jnacl-1.0.0.jar')
    foreach ($bad in $badFiles) {
        $p = Join-Path $jarsDir $bad
        if (Test-Path $p) { Log "  removing $bad"; Remove-Item $p -Force }
    }
    $keep = @('jna-5.14.0.jar','jna-platform-5.14.0.jar')
    Get-ChildItem -Path $jarsDir -Filter 'jna*.jar' | ForEach-Object {
        if ($keep -notcontains $_.Name) {
            Log ("  removing extra {0}" -f $_.Name)
            Remove-Item $_.FullName -Force
        }
    }
} else {
    Warn "No jars\ directory under $fijiApp -- skipping JNA cleanup (Fiji layout may have changed)."
}

# ---------- done ----------
Write-Host ""
Write-Host "[SAMJ] installation complete." -ForegroundColor Green
Write-Host ""
Write-Host "Fiji + SAMJ is installed at:"
Write-Host "  $fijiApp"
Write-Host ""
Write-Host "To launch:"
Write-Host "  Start-Process '$fijiExe'"
Write-Host ""
Write-Host "Inside Fiji:"
Write-Host "  Plugins > SAMJ > SAMJ Annotator"
Write-Host ""
Write-Host "First run of any SAM model triggers a one-time environment setup (Appose/Micromamba). Allow up to ~15 min on modest hardware."
