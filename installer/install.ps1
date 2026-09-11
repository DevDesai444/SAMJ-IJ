<#
    SAMJ-IJ one-shot installer for Windows (PowerShell 5+).

    What it does:
      1. Downloads the official Fiji distribution (JDK bundled) for Windows x64.
      2. Extracts Fiji into an install directory (default: $HOME\Fiji-SAMJ).
         The zip lays down a "Fiji\" folder that contains everything (jars,
         plugins, the fiji.bat / fiji-windows-x64.exe launcher). We leave that
         layout alone -- moving pieces around breaks Fiji's own launcher.
      3. Registers the SAMJ update site and installs plugins headlessly.
      4. Applies the JNA fix documented in the SAMJ-IJ README (best-effort;
         recent Fiji builds already ship the correct jna-5.14.0 pair).
      5. Prints how to launch the resulting Fiji.

    Usage (from a normal PowerShell prompt -- admin NOT required):

      powershell -ExecutionPolicy Bypass -File .\install.ps1
      powershell -ExecutionPolicy Bypass -File .\install.ps1 -InstallDir "C:\Tools\Fiji-SAMJ"
      powershell -ExecutionPolicy Bypass -File .\install.ps1 -Force

    Or, one-liner via web:

      iwr https://raw.githubusercontent.com/DevDesai444/SAMJ-IJ/main/installer/install.ps1 -UseBasicParsing | iex
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

# ---------- helper: locate the Fiji root that the zip extracted ----------
function Find-FijiRoot([string]$base) {
    if (-not (Test-Path $base)) { return $null }
    # 1) folder that contains a fiji.bat launcher AND a jars\ folder
    $hit = Get-ChildItem -Path $base -Recurse -Depth 3 -File -Filter 'fiji.bat' -ErrorAction SilentlyContinue |
           Where-Object { Test-Path (Join-Path $_.Directory.FullName 'jars') } |
           Select-Object -First 1
    if ($hit) { return $hit.Directory.FullName }
    # 2) folder named 'Fiji' with jars\ child
    $hit = Get-ChildItem -Path $base -Recurse -Depth 3 -Directory -Filter 'Fiji' -ErrorAction SilentlyContinue |
           Where-Object { Test-Path (Join-Path $_.FullName 'jars') } |
           Select-Object -First 1
    if ($hit) { return $hit.FullName }
    # 3) legacy Fiji.app-style root
    $hit = Get-ChildItem -Path $base -Recurse -Depth 3 -Directory -Filter 'Fiji.app' -ErrorAction SilentlyContinue |
           Where-Object { Test-Path (Join-Path $_.FullName 'jars') } |
           Select-Object -First 1
    if ($hit) { return $hit.FullName }
    return $null
}

# ---------- prepare install dir ----------
New-Item -ItemType Directory -Force -Path $InstallDir | Out-Null

$fijiRoot = Find-FijiRoot $InstallDir
if ($fijiRoot) {
    if ($Force) {
        Log "Force flag set -- removing existing $fijiRoot"
        Remove-Item -Recurse -Force $fijiRoot
        $fijiRoot = $null
    } else {
        Log "Existing Fiji found at $fijiRoot -- re-running update site registration and JNA fix only."
    }
}

# ---------- download + extract ----------
if (-not $fijiRoot) {
    $zipPath = Join-Path $InstallDir $FijiZipName
    Log "Downloading Fiji (~700 MB): $FijiUrl"
    # Speed hack: turn off Invoke-WebRequest progress bar (it kills large downloads on PS5).
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

    $fijiRoot = Find-FijiRoot $InstallDir
    if (-not $fijiRoot) { Die "Extraction succeeded but no Fiji root was found under $InstallDir" }
}
Log "Fiji root: $fijiRoot"

# ---------- locate the Fiji CLI launcher ----------
$candidates = @(
    (Join-Path $fijiRoot 'fiji-windows-x64-console.exe'),  # preferred for --headless
    (Join-Path $fijiRoot 'fiji-windows-x64-gui.exe'),
    (Join-Path $fijiRoot 'fiji-windows-x64.exe'),
    (Join-Path $fijiRoot 'fiji.bat'),
    (Join-Path $fijiRoot 'ImageJ-win64.exe')               # legacy
)
$fijiExe = $null
foreach ($c in $candidates) { if (Test-Path $c) { $fijiExe = $c; break } }
if (-not $fijiExe) {
    $fijiExe = (Get-ChildItem -Path $fijiRoot -Depth 2 -File -ErrorAction SilentlyContinue |
                Where-Object { $_.Name -match '^(fiji|ImageJ).*\.(exe|bat)$' } |
                Select-Object -First 1 -ExpandProperty FullName)
}
if (-not $fijiExe) { Die "Could not find the Fiji CLI launcher inside $fijiRoot" }
Log "Fiji CLI launcher: $fijiExe"

# ---------- register SAMJ update site + install plugins headlessly ----------
Log "Registering update site: $SamjSiteName -> $SamjSiteUrl"
& $fijiExe --headless --update edit-update-site $SamjSiteName $SamjSiteUrl
if ($LASTEXITCODE -ne 0) {
    Log "edit-update-site not accepted; retrying with add-update-site."
    & $fijiExe --headless --update add-update-site $SamjSiteName $SamjSiteUrl
    if ($LASTEXITCODE -ne 0) { Die "Failed to register the SAMJ update site." }
}

Log "Downloading and installing SAMJ plugins (can take 5-10 min) ..."
& $fijiExe --headless --update update
if ($LASTEXITCODE -ne 0) { Warn "Fiji updater exited with code $LASTEXITCODE -- inspect $fijiRoot\update-log.txt if plugins are missing." }

# ---------- JNA fix per SAMJ README (idempotent) ----------
$jarsDir = Join-Path $fijiRoot 'jars'
if (Test-Path $jarsDir) {
    Log "Verifying JNA state in $jarsDir"
    foreach ($bad in @('jna-3.2.7.jar', 'jnacl-1.0.0.jar')) {
        $p = Join-Path $jarsDir $bad
        if (Test-Path $p) { Log "  removing $bad"; Remove-Item $p -Force }
    }
    $keep = @('jna-5.14.0.jar', 'jna-platform-5.14.0.jar')
    Get-ChildItem -Path $jarsDir -Filter 'jna*.jar' | ForEach-Object {
        if ($keep -notcontains $_.Name) {
            Log ("  removing stray {0}" -f $_.Name)
            Remove-Item $_.FullName -Force
        }
    }
} else {
    Warn "No jars\ directory under $fijiRoot -- skipping JNA cleanup."
}

# ---------- done ----------
Write-Host ""
Write-Host "[SAMJ] installation complete." -ForegroundColor Green
Write-Host ""
Write-Host "Fiji + SAMJ is installed at:"
Write-Host "  $fijiRoot"
Write-Host ""
Write-Host "To launch (double-click one of these, or run from PowerShell):"
Write-Host "  Start-Process '$(Join-Path $fijiRoot 'fiji.bat')'"
Write-Host "  Start-Process '$(Join-Path $fijiRoot 'fiji-windows-x64-gui.exe')'"
Write-Host ""
Write-Host "Inside Fiji:"
Write-Host "  Plugins > SAMJ > SAMJ Annotator"
Write-Host ""
Write-Host "The first time you pick a SAM model, SAMJ provisions a Python env"
Write-Host "via Appose/Micromamba. Allow up to ~15 min on modest hardware;"
Write-Host "subsequent runs are instant."
