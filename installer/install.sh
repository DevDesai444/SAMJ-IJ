#!/usr/bin/env bash
# SAMJ-IJ one-shot installer for macOS / Linux.
#
# What it does:
#   1. Detects OS + CPU architecture.
#   2. Downloads the matching Fiji distribution (JDK bundled) from downloads.imagej.net.
#   3. Extracts Fiji into an install directory (default: $HOME/Fiji-SAMJ).
#   4. Registers the SAMJ update site (https://sites.imagej.net/SAMJ/) and installs plugins headlessly.
#   5. Applies the JNA fix documented in the SAMJ-IJ README.
#   6. Prints how to launch the resulting Fiji.
#
# Usage:
#   ./install.sh                    # install to $HOME/Fiji-SAMJ
#   ./install.sh /opt/fiji-samj     # install to a custom directory
#   INSTALL_DIR=/opt/x ./install.sh # same, via env var
#   FORCE=1 ./install.sh            # wipe an existing Fiji.app before install
#
# Requirements: curl (or wget), unzip, bash 3.2+.

set -euo pipefail

# ---------- config ----------
INSTALL_DIR="${1:-${INSTALL_DIR:-$HOME/Fiji-SAMJ}}"
SAMJ_SITE_NAME="SAMJ"
SAMJ_SITE_URL="https://sites.imagej.net/SAMJ/"
FIJI_BASE="https://downloads.imagej.net/fiji/latest"
FORCE="${FORCE:-0}"

log()  { printf "\033[1;34m[SAMJ]\033[0m %s\n" "$*"; }
warn() { printf "\033[1;33m[WARN]\033[0m %s\n" "$*" >&2; }
die()  { printf "\033[1;31m[ERR ]\033[0m %s\n" "$*" >&2; exit 1; }

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
      *) die "Unsupported Linux arch: $uname_m (only x86_64 has an official Fiji build; run install.py or install manually on arm64)" ;;
    esac
    PLATFORM="linux"
    ;;
  *) die "Unsupported OS: $uname_s (use install.ps1 on Windows or install.py fallback)" ;;
esac

FIJI_URL="$FIJI_BASE/$FIJI_ZIP"
log "Platform: $PLATFORM/$uname_m   Fiji archive: $FIJI_ZIP"

# ---------- macOS Downloads-folder guard (SAMJ README requirement) ----------
if [ "$PLATFORM" = "macos" ]; then
  case "$INSTALL_DIR" in
    "$HOME/Downloads"*|"/Users/"*"/Downloads"*)
      die "On macOS, SAMJ must NOT live inside Downloads (per the SAMJ README). Pick another location, e.g. $HOME/Fiji-SAMJ."
      ;;
  esac
fi

# ---------- required tools ----------
have() { command -v "$1" >/dev/null 2>&1; }

if have curl; then DOWNLOADER="curl"; elif have wget; then DOWNLOADER="wget"; else
  die "Neither curl nor wget is installed."
fi
have unzip || die "unzip is not installed. On Debian/Ubuntu: sudo apt-get install unzip"

# ---------- prepare install dir ----------
mkdir -p "$INSTALL_DIR"
cd "$INSTALL_DIR"

if [ -d "Fiji.app" ]; then
  if [ "$FORCE" = "1" ]; then
    log "FORCE=1 -> removing existing $INSTALL_DIR/Fiji.app"
    rm -rf "Fiji.app"
  else
    log "Existing Fiji.app found at $INSTALL_DIR/Fiji.app -- re-running update site registration and JNA fix only."
  fi
fi

# ---------- download + extract if needed ----------
if [ ! -d "Fiji.app" ]; then
  log "Downloading Fiji (~650-700 MB): $FIJI_URL"
  if [ "$DOWNLOADER" = "curl" ]; then
    curl -L --fail --retry 3 -o "$FIJI_ZIP" "$FIJI_URL"
  else
    wget --tries=3 -O "$FIJI_ZIP" "$FIJI_URL"
  fi

  log "Extracting Fiji into $INSTALL_DIR ..."
  unzip -q "$FIJI_ZIP" -d "$INSTALL_DIR"
  rm -f "$FIJI_ZIP"

  # The zip extracts to a folder that may be named 'Fiji.app' or 'fiji-<...>/Fiji.app'.
  if [ ! -d "Fiji.app" ]; then
    found="$(find "$INSTALL_DIR" -maxdepth 3 -type d -name 'Fiji.app' | head -n1 || true)"
    [ -n "$found" ] || die "Extraction succeeded but Fiji.app was not found. Inspect $INSTALL_DIR manually."
    if [ "$found" != "$INSTALL_DIR/Fiji.app" ]; then
      mv "$found" "$INSTALL_DIR/Fiji.app"
    fi
  fi
fi

FIJI_APP="$INSTALL_DIR/Fiji.app"
log "Fiji.app at: $FIJI_APP"

# ---------- macOS Gatekeeper: strip quarantine bit so it launches ----------
if [ "$PLATFORM" = "macos" ] && have xattr; then
  log "Removing macOS quarantine attribute (avoids 'app is damaged' Gatekeeper block)."
  xattr -dr com.apple.quarantine "$FIJI_APP" 2>/dev/null || true
fi

# ---------- locate the Fiji executable ----------
find_fiji_exe() {
  # New Fiji naming ('fiji-*') is preferred; fall back to legacy 'ImageJ-*'.
  local candidates=()
  if [ "$PLATFORM" = "macos" ]; then
    candidates=(
      "$FIJI_APP/Contents/MacOS/fiji-macos-arm64"
      "$FIJI_APP/Contents/MacOS/fiji-macos64"
      "$FIJI_APP/Contents/MacOS/ImageJ-macosx"
    )
  else
    candidates=(
      "$FIJI_APP/fiji-linux64"
      "$FIJI_APP/ImageJ-linux64"
    )
  fi
  for c in "${candidates[@]}"; do
    if [ -x "$c" ]; then echo "$c"; return 0; fi
  done
  # last-resort glob
  find "$FIJI_APP" -maxdepth 3 -type f \( -name 'fiji-*' -o -name 'ImageJ-*' \) -perm -u+x 2>/dev/null | head -n1
}

FIJI_EXE="$(find_fiji_exe || true)"
[ -n "$FIJI_EXE" ] && [ -x "$FIJI_EXE" ] || die "Could not find the Fiji launcher inside $FIJI_APP"
log "Fiji launcher: $FIJI_EXE"

# ---------- register SAMJ update site + install plugins headlessly ----------
log "Adding update site: $SAMJ_SITE_NAME -> $SAMJ_SITE_URL"
# 'edit-update-site' upserts the entry (add if missing, update if present).
"$FIJI_EXE" --headless --update edit-update-site "$SAMJ_SITE_NAME" "$SAMJ_SITE_URL" || \
  "$FIJI_EXE" --headless --update add-update-site "$SAMJ_SITE_NAME" "$SAMJ_SITE_URL"

log "Downloading and installing SAMJ plugins (this can take several minutes on first run) ..."
"$FIJI_EXE" --headless --update update

# ---------- JNA fix per SAMJ README ----------
JARS_DIR="$FIJI_APP/jars"
if [ -d "$JARS_DIR" ]; then
  log "Applying JNA fix in $JARS_DIR"
  # Remove known-bad older JNA jars if present.
  for bad in jna-3.2.7.jar jnacl-1.0.0.jar; do
    if [ -f "$JARS_DIR/$bad" ]; then
      log "  removing $bad"
      rm -f "$JARS_DIR/$bad"
    fi
  done
  # Also strip any stray jna*.jar that isn't the required 5.14.0.
  for f in "$JARS_DIR"/jna*.jar; do
    [ -e "$f" ] || continue
    case "$(basename "$f")" in
      jna-5.14.0.jar|jna-platform-5.14.0.jar) : ;;
      *) log "  removing extra $(basename "$f")"; rm -f "$f" ;;
    esac
  done
else
  warn "No jars/ directory under $FIJI_APP -- skipping JNA cleanup (Fiji layout may have changed)."
fi

# ---------- done ----------
cat <<EOF

$(printf "\033[1;32m[SAMJ]\033[0m installation complete.")

Fiji + SAMJ is installed at:
  $FIJI_APP

To launch:
$(  if [ "$PLATFORM" = "macos" ]; then
    echo "  open '$FIJI_APP'"
  else
    echo "  '$FIJI_EXE'"
  fi )

Inside Fiji:
  Plugins > SAMJ > SAMJ Annotator

First run of any SAM model triggers a one-time environment setup (Appose/Micromamba). Allow up to ~15 min on modest hardware.
EOF
