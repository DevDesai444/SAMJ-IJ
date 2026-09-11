#!/usr/bin/env python3
"""
SAMJ-IJ one-shot installer -- pure-Python, cross-platform fallback.

Prefer install.sh (macOS/Linux) or install.ps1 (Windows) when available; use this
file if you cannot run those (e.g. corporate PowerShell policies, minimal Linux
images) or if you want a single command that works everywhere Python does.

Steps:
  1. Detect OS + architecture.
  2. Download the official Fiji distribution (JDK bundled).
  3. Extract into an install directory (default: ~/Fiji-SAMJ).
  4. Register the SAMJ update site (https://sites.imagej.net/SAMJ/) and install
     plugins headlessly via Fiji's own updater CLI.
  5. Apply the JNA fix documented in the SAMJ-IJ README.

Usage:
  python3 install.py
  python3 install.py --install-dir /opt/fiji-samj
  python3 install.py --force
"""

from __future__ import annotations

import argparse
import os
import platform
import shutil
import stat
import subprocess
import sys
import urllib.request
import zipfile
from pathlib import Path

SAMJ_SITE_NAME = "SAMJ"
SAMJ_SITE_URL = "https://sites.imagej.net/SAMJ/"
FIJI_BASE = "https://downloads.imagej.net/fiji/latest"

FIJI_ARCHIVES = {
    ("Darwin", "arm64"):   "fiji-latest-macos-arm64-jdk.zip",
    ("Darwin", "aarch64"): "fiji-latest-macos-arm64-jdk.zip",
    ("Darwin", "x86_64"):  "fiji-latest-macos64-jdk.zip",
    ("Linux",  "x86_64"):  "fiji-latest-linux64-jdk.zip",
    ("Windows","AMD64"):   "fiji-latest-win64-jdk.zip",
    ("Windows","x86_64"):  "fiji-latest-win64-jdk.zip",
}


def log(msg: str)  -> None: print(f"[SAMJ] {msg}", flush=True)
def warn(msg: str) -> None: print(f"[WARN] {msg}", file=sys.stderr, flush=True)
def die(msg: str)  -> None: print(f"[ERR ] {msg}", file=sys.stderr, flush=True); sys.exit(1)


def pick_archive() -> tuple[str, str, str]:
    system = platform.system()
    machine = platform.machine()
    key = (system, machine)
    if key not in FIJI_ARCHIVES:
        die(f"Unsupported OS/arch: {system}/{machine}. Fiji only ships x86_64 for Linux/Windows and arm64/x86_64 for macOS.")
    return system, machine, FIJI_ARCHIVES[key]


def download(url: str, dest: Path) -> None:
    """Streamed download with a coarse progress meter (works even in dumb terminals)."""
    log(f"Downloading {url}")
    log(f"  -> {dest}")
    with urllib.request.urlopen(url) as resp, open(dest, "wb") as out:
        total = int(resp.headers.get("Content-Length") or 0)
        downloaded = 0
        chunk = 1024 * 1024  # 1 MiB
        last_pct = -1
        while True:
            buf = resp.read(chunk)
            if not buf:
                break
            out.write(buf)
            downloaded += len(buf)
            if total:
                pct = int(downloaded * 100 / total)
                if pct != last_pct and pct % 5 == 0:
                    print(f"  {pct:3d}%  ({downloaded // (1024*1024)} / {total // (1024*1024)} MiB)", flush=True)
                    last_pct = pct
    log("Download complete.")


def extract_zip(zip_path: Path, out_dir: Path) -> Path:
    log(f"Extracting {zip_path.name} ...")
    with zipfile.ZipFile(zip_path) as zf:
        # ZipFile.extractall preserves paths but not POSIX permissions -- restore executable bits below.
        zf.extractall(out_dir)
        # Restore +x on Unix (Fiji ships shell wrappers and launcher binaries inside the zip).
        if os.name == "posix":
            for info in zf.infolist():
                mode = (info.external_attr >> 16) & 0o777
                if mode & 0o111:  # any exec bit
                    target = out_dir / info.filename
                    if target.is_file():
                        target.chmod(target.stat().st_mode | stat.S_IXUSR | stat.S_IXGRP | stat.S_IXOTH)

    # Locate Fiji.app
    fiji_app = out_dir / "Fiji.app"
    if fiji_app.is_dir():
        return fiji_app
    for cand in out_dir.rglob("Fiji.app"):
        if cand.is_dir():
            if cand != fiji_app:
                shutil.move(str(cand), str(fiji_app))
            return fiji_app
    die(f"Extraction succeeded but Fiji.app was not found under {out_dir}")


def find_launcher(fiji_app: Path, system: str) -> Path:
    if system == "Darwin":
        candidates = [
            fiji_app / "Contents" / "MacOS" / "fiji-macos-arm64",
            fiji_app / "Contents" / "MacOS" / "fiji-macos64",
            fiji_app / "Contents" / "MacOS" / "ImageJ-macosx",
        ]
    elif system == "Linux":
        candidates = [
            fiji_app / "fiji-linux64",
            fiji_app / "ImageJ-linux64",
        ]
    elif system == "Windows":
        candidates = [
            fiji_app / "fiji-windows-x64.exe",
            fiji_app / "ImageJ-win64.exe",
        ]
    else:
        die(f"Unsupported system for launcher lookup: {system}")

    for c in candidates:
        if c.exists() and (os.name == "nt" or os.access(c, os.X_OK)):
            return c

    # Fallback: glob for anything that looks like a Fiji launcher.
    patterns = ["fiji-*", "ImageJ-*"]
    for pattern in patterns:
        for cand in fiji_app.rglob(pattern):
            if cand.is_file() and (os.name == "nt" or os.access(cand, os.X_OK)):
                return cand
    die(f"Could not find the Fiji launcher inside {fiji_app}")


def run_fiji_update(fiji_exe: Path, *args: str) -> int:
    cmd = [str(fiji_exe), "--headless", "--update", *args]
    log("  $ " + " ".join(cmd))
    return subprocess.call(cmd)


def register_update_site(fiji_exe: Path) -> None:
    log(f"Registering update site: {SAMJ_SITE_NAME} -> {SAMJ_SITE_URL}")
    # 'edit-update-site' upserts; fall back to 'add-update-site' for older Fiji.
    rc = run_fiji_update(fiji_exe, "edit-update-site", SAMJ_SITE_NAME, SAMJ_SITE_URL)
    if rc != 0:
        log("edit-update-site not accepted; retrying with add-update-site.")
        rc = run_fiji_update(fiji_exe, "add-update-site", SAMJ_SITE_NAME, SAMJ_SITE_URL)
        if rc != 0:
            die("Failed to register the SAMJ update site.")


def install_plugins(fiji_exe: Path) -> None:
    log("Downloading and installing SAMJ plugins (this can take several minutes) ...")
    rc = run_fiji_update(fiji_exe, "update")
    if rc != 0:
        warn(f"Fiji updater exited with code {rc}. If plugins are missing, inspect the Fiji.app/update-log.txt file.")


def fix_jna(fiji_app: Path) -> None:
    jars = fiji_app / "jars"
    if not jars.is_dir():
        warn(f"No jars/ directory under {fiji_app}; skipping JNA cleanup.")
        return
    log(f"Applying JNA fix in {jars}")
    keep = {"jna-5.14.0.jar", "jna-platform-5.14.0.jar"}
    for f in list(jars.glob("jna*.jar")) + list(jars.glob("jnacl*.jar")):
        if f.name in keep:
            continue
        log(f"  removing {f.name}")
        try:
            f.unlink()
        except OSError as e:
            warn(f"  couldn't remove {f}: {e}")


def macos_downloads_guard(install_dir: Path, system: str) -> None:
    if system != "Darwin":
        return
    parts = [p.lower() for p in install_dir.resolve().parts]
    if "downloads" in parts:
        die("On macOS, SAMJ must NOT live inside the Downloads folder (per SAMJ README). Choose e.g. ~/Fiji-SAMJ.")


def strip_quarantine(fiji_app: Path, system: str) -> None:
    if system != "Darwin":
        return
    if shutil.which("xattr"):
        log("Removing macOS quarantine attribute so Gatekeeper won't block launch.")
        subprocess.call(["xattr", "-dr", "com.apple.quarantine", str(fiji_app)])


def main() -> None:
    ap = argparse.ArgumentParser(description="One-shot SAMJ-IJ installer (Fiji + SAMJ plugin).")
    default_dir = Path.home() / "Fiji-SAMJ"
    ap.add_argument("--install-dir", "-d", type=Path, default=default_dir,
                    help=f"where to place Fiji.app (default: {default_dir})")
    ap.add_argument("--force", "-f", action="store_true",
                    help="wipe an existing Fiji.app before installing")
    args = ap.parse_args()

    system, machine, archive_name = pick_archive()
    install_dir: Path = args.install_dir.expanduser().resolve()

    log(f"Platform: {system}/{machine}   Archive: {archive_name}")
    log(f"Install directory: {install_dir}")

    macos_downloads_guard(install_dir, system)
    install_dir.mkdir(parents=True, exist_ok=True)

    fiji_app = install_dir / "Fiji.app"
    if fiji_app.is_dir():
        if args.force:
            log(f"--force: removing existing {fiji_app}")
            shutil.rmtree(fiji_app)
        else:
            log(f"Existing Fiji.app found at {fiji_app} -- re-running update site registration and JNA fix only.")

    if not fiji_app.is_dir():
        zip_path = install_dir / archive_name
        download(f"{FIJI_BASE}/{archive_name}", zip_path)
        fiji_app = extract_zip(zip_path, install_dir)
        try:
            zip_path.unlink()
        except OSError:
            pass

    strip_quarantine(fiji_app, system)
    fiji_exe = find_launcher(fiji_app, system)
    log(f"Fiji launcher: {fiji_exe}")

    register_update_site(fiji_exe)
    install_plugins(fiji_exe)
    fix_jna(fiji_app)

    print()
    log("installation complete.")
    print()
    print(f"Fiji + SAMJ is installed at:\n  {fiji_app}")
    print()
    print("To launch:")
    if system == "Darwin":
        print(f"  open '{fiji_app}'")
    elif system == "Windows":
        print(f"  Start-Process '{fiji_exe}'")
    else:
        print(f"  '{fiji_exe}'")
    print()
    print("Inside Fiji:")
    print("  Plugins > SAMJ > SAMJ Annotator")
    print()
    print("First run of any SAM model triggers a one-time environment setup (Appose/Micromamba).")
    print("Allow up to ~15 min on modest hardware.")


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        die("Interrupted.")
