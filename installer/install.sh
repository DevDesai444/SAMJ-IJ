#!/usr/bin/env bash
# SAMJ-IJ one-shot installer for macOS / Linux.
#
# What it does:
#   1. Detects OS + CPU architecture.
#   2. Downloads the matching Fiji distribution (JDK bundled) from downloads.imagej.net.
#   3. Extracts Fiji into an install directory (default: $HOME/Fiji-SAMJ).
#      The zip lays down a "Fiji/" folder that contains everything (jars,
#      plugins, Fiji.app on macOS, the fiji launcher script, ...). We leave
#      that layout alone -- moving pieces around breaks Fiji's own launcher.
#   4. Registers the SAMJ update site (https://sites.imagej.net/SAMJ/) and
#      installs plugins headlessly via `fiji --headless --update`.
#   5. Applies the JNA fix documented in the SAMJ-IJ README (best-effort;
#      recent Fiji builds already ship the correct jna-5.14.0.jar pair).
#   6. Prints how to launch the resulting Fiji.
#
# Usage:
#   ./install.sh                     # install to $HOME/Fiji-SAMJ
#   ./install.sh /opt/fiji-samj      # install to a custom directory
#   INSTALL_DIR=/opt/x ./install.sh  # same, via env var
#   FORCE=1 ./install.sh             # wipe an existing install before starting
#
# Requirements: curl (or wget), unzip, bash 3.2+.

set -euo pipefail

# ---------- config ----------
INSTALL_DIR="${1:-${INSTALL_DIR:-$HOME/Fiji-SAMJ}}"
SAMJ_SITE_NAME="SAMJ"
SAMJ_SITE_URL="https://sites.imagej.net/SAMJ/"
FIJI_BASE="https://downloads.imagej.net/fiji/latest"
FORCE="${FORCE:-0}"

# NO_COLOR=1 suppresses ANSI codes for clean logs.
if [ -n "${NO_COLOR:-}" ]; then C_INFO=""; C_WARN=""; C_ERR=""; C_OK=""; C_RESET=""
else                            C_INFO='\033[1;34m'; C_WARN='\033[1;33m'; C_ERR='\033[1;31m'; C_OK='\033[1;32m'; C_RESET='\033[0m'
fi
log()  { printf "${C_INFO}[SAMJ]${C_RESET} %s\n" "$*"; }
warn() { printf "${C_WARN}[WARN]${C_RESET} %s\n" "$*" >&2; }
die()  { printf "${C_ERR}[ERR ]${C_RESET} %s\n" "$*" >&2; exit 1; }

# ---------- detect OS + arch ----------
uname_s="$(uname -s)"
uname_m="$(uname -m)"

case "$uname_s" in
  Darwin)
    case "$uname_m" in
      arm64|aarch64) FIJI_ZIP="fiji-latest-macos-arm64-jdk.zip" ;;
      x86_64)        FIJI_ZIP="fiji-latest-macos64-jdk.zip" ;;
      *) die "Unsupported macOS arch: $uname_m" ;;
    esac
    PLATFORM="macos"
    ;;
  Linux)
    case "$uname_m" in
      x86_64) FIJI_ZIP="fiji-latest-linux64-jdk.zip" ;;
      *) die "Unsupported Linux arch: $uname_m (Fiji ships x86_64 only; try install.py or install manually)" ;;
    esac
    PLATFORM="linux"
    ;;
  *) die "Unsupported OS: $uname_s (use install.ps1 on Windows, or install.py fallback)" ;;
esac

FIJI_URL="$FIJI_BASE/$FIJI_ZIP"
log "Platform: $PLATFORM/$uname_m   archive: $FIJI_ZIP"

# ---------- macOS Downloads-folder guard (per SAMJ README) ----------
if [ "$PLATFORM" = "macos" ]; then
  case "$INSTALL_DIR" in
    "$HOME/Downloads"*|"/Users/"*"/Downloads"*)
      die "On macOS, SAMJ must NOT live inside Downloads (per SAMJ README). Pick e.g. \$HOME/Fiji-SAMJ."
      ;;
  esac
fi

# ---------- required tools ----------
have() { command -v "$1" >/dev/null 2>&1; }
if   have curl; then DOWNLOADER="curl"
elif have wget; then DOWNLOADER="wget"
else die "Neither curl nor wget is installed."
fi
have unzip || die "unzip is not installed. On Debian/Ubuntu: sudo apt-get install unzip"

# ---------- helper: find the Fiji root that the zip extracted ----------
# The archive lays down ONE folder holding fiji, fiji.bat, jars/, plugins/, and (on macOS) Fiji.app/.
# It is usually named "Fiji", but we discover it rather than hard-code, in case upstream renames it.
find_fiji_root() {
  local base="$1"
  # First: a directory that contains the 'fiji' launcher script AND a jars/ folder.
  local hit
  hit="$(find "$base" -maxdepth 3 -type f -name fiji -perm -u+x 2>/dev/null | head -n1 || true)"
  if [ -n "$hit" ]; then
    dirname "$hit"
    return 0
  fi
  # Fallback: any dir named 'Fiji' with a jars/ child.
  hit="$(find "$base" -maxdepth 3 -type d -name Fiji 2>/dev/null | head -n1 || true)"
  if [ -n "$hit" ] && [ -d "$hit/jars" ]; then
    echo "$hit"
    return 0
  fi
  # Legacy layout (older Fiji): a Fiji.app/ directory that is itself the root.
  hit="$(find "$base" -maxdepth 3 -type d -name 'Fiji.app' 2>/dev/null | head -n1 || true)"
  if [ -n "$hit" ] && [ -d "$hit/jars" ]; then
    echo "$hit"
    return 0
  fi
  return 1
}

# ---------- prepare install dir ----------
mkdir -p "$INSTALL_DIR"

EXISTING_ROOT="$(find_fiji_root "$INSTALL_DIR" || true)"
if [ -n "$EXISTING_ROOT" ]; then
  if [ "$FORCE" = "1" ]; then
    log "FORCE=1 -> removing existing $EXISTING_ROOT"
    rm -rf "$EXISTING_ROOT"
    # Also wipe any stray sibling Fiji.app the old buggy installer may have left behind.
    [ -d "$INSTALL_DIR/Fiji.app" ] && [ ! -d "$INSTALL_DIR/Fiji/Fiji.app" ] && rm -rf "$INSTALL_DIR/Fiji.app"
    EXISTING_ROOT=""
  else
    log "Existing Fiji found at $EXISTING_ROOT -- re-running update site registration and JNA fix only."
  fi
fi

# ---------- download + extract if needed ----------
if [ -z "$EXISTING_ROOT" ]; then
  zip_path="$INSTALL_DIR/$FIJI_ZIP"
  log "Downloading Fiji (~650-700 MB): $FIJI_URL"
  if [ "$DOWNLOADER" = "curl" ]; then
    curl -L --fail --retry 3 -o "$zip_path" "$FIJI_URL"
  else
    wget --tries=3 -O "$zip_path" "$FIJI_URL"
  fi

  log "Extracting Fiji into $INSTALL_DIR ..."
  unzip -q "$zip_path" -d "$INSTALL_DIR"
  rm -f "$zip_path"

  # Repair a common mis-extraction: if a sibling Fiji.app landed next to Fiji/,
  # the correct layout requires Fiji.app to live INSIDE Fiji/ (Fiji's own
  # launcher script looks for *.app next to it). Move it back.
  if [ -d "$INSTALL_DIR/Fiji.app" ] && [ -d "$INSTALL_DIR/Fiji" ] && [ ! -e "$INSTALL_DIR/Fiji/Fiji.app" ]; then
    log "Repairing layout: moving Fiji.app into $INSTALL_DIR/Fiji/"
    mv "$INSTALL_DIR/Fiji.app" "$INSTALL_DIR/Fiji/Fiji.app"
  fi

  EXISTING_ROOT="$(find_fiji_root "$INSTALL_DIR" || true)"
  [ -n "$EXISTING_ROOT" ] || die "Extraction succeeded but no Fiji root was found under $INSTALL_DIR. Inspect it manually."
fi

FIJI_ROOT="$EXISTING_ROOT"
log "Fiji root: $FIJI_ROOT"

# ---------- macOS: strip quarantine so Gatekeeper doesn't block launch ----------
if [ "$PLATFORM" = "macos" ] && have xattr; then
  log "Removing macOS quarantine attribute (avoids 'app is damaged' Gatekeeper block)."
  xattr -dr com.apple.quarantine "$FIJI_ROOT" 2>/dev/null || true
fi

# ---------- locate the Fiji CLI launcher ----------
# Preferred: the portable 'fiji' shell script (macOS/Linux). It discovers the
# native launcher itself (fiji-macos-arm64, fiji-linux-x64, ...).
FIJI_EXE=""
if   [ -x "$FIJI_ROOT/fiji" ];             then FIJI_EXE="$FIJI_ROOT/fiji"
elif [ -x "$FIJI_ROOT/fiji-linux-x64" ];   then FIJI_EXE="$FIJI_ROOT/fiji-linux-x64"
elif [ -x "$FIJI_ROOT/fiji-linux64" ];     then FIJI_EXE="$FIJI_ROOT/fiji-linux64"
elif [ -x "$FIJI_ROOT/ImageJ-linux64" ];   then FIJI_EXE="$FIJI_ROOT/ImageJ-linux64"    # legacy
elif [ -x "$FIJI_ROOT/Contents/MacOS/fiji-macos-arm64" ]; then FIJI_EXE="$FIJI_ROOT/Contents/MacOS/fiji-macos-arm64"
elif [ -x "$FIJI_ROOT/Contents/MacOS/fiji-macos-x64" ];   then FIJI_EXE="$FIJI_ROOT/Contents/MacOS/fiji-macos-x64"
elif [ -x "$FIJI_ROOT/Contents/MacOS/ImageJ-macosx" ];    then FIJI_EXE="$FIJI_ROOT/Contents/MacOS/ImageJ-macosx"
fi
[ -n "$FIJI_EXE" ] || die "Could not find the Fiji CLI launcher inside $FIJI_ROOT"
log "Fiji CLI launcher: $FIJI_EXE"

# ---------- register SAMJ update site + install plugins headlessly ----------
log "Registering update site: $SAMJ_SITE_NAME -> $SAMJ_SITE_URL"
# 'edit-update-site' upserts; fall back to 'add-update-site' on older Fiji.
"$FIJI_EXE" --headless --update edit-update-site "$SAMJ_SITE_NAME" "$SAMJ_SITE_URL" || \
  "$FIJI_EXE" --headless --update add-update-site  "$SAMJ_SITE_NAME" "$SAMJ_SITE_URL" || \
  die "Failed to register the SAMJ update site."

log "Downloading and installing SAMJ plugins (can take 5-10 min) ..."
"$FIJI_EXE" --headless --update update

# ---------- JNA fix per SAMJ README (idempotent) ----------
JARS_DIR="$FIJI_ROOT/jars"
if [ -d "$JARS_DIR" ]; then
  log "Verifying JNA state in $JARS_DIR"
  for bad in jna-3.2.7.jar jnacl-1.0.0.jar; do
    if [ -f "$JARS_DIR/$bad" ]; then log "  removing $bad"; rm -f "$JARS_DIR/$bad"; fi
  done
  for f in "$JARS_DIR"/jna*.jar; do
    [ -e "$f" ] || continue
    case "$(basename "$f")" in
      jna-5.14.0.jar|jna-platform-5.14.0.jar) : ;;
      *) log "  removing stray $(basename "$f")"; rm -f "$f" ;;
    esac
  done
else
  warn "No jars/ directory under $FIJI_ROOT -- skipping JNA cleanup."
fi

# ---------- done ----------
if [ "$PLATFORM" = "macos" ]; then
  LAUNCH_HINT="open '$FIJI_ROOT/Fiji.app'"
else
  LAUNCH_HINT="'$FIJI_EXE'"
fi

printf "\n${C_OK}[SAMJ]${C_RESET} installation complete.\n\n"
cat <<EOF
Fiji + SAMJ is installed at:
  $FIJI_ROOT

To launch:
  $LAUNCH_HINT

Inside Fiji:
  Plugins > SAMJ > SAMJ Annotator

The first time you pick a SAM model, SAMJ provisions a Python env via
Appose/Micromamba. Allow up to ~15 min on modest hardware; subsequent
runs are instant.
EOF
