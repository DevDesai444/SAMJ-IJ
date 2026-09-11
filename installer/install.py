#!/usr/bin/env python3
"""
SAMJ-IJ one-shot installer -- pure-Python, cross-platform fallback.

Prefer install.sh (macOS/Linux) or install.ps1 (Windows) when available; use this
file if you cannot run those (corporate PowerShell policies, minimal Linux
images) or if you want a single command that works everywhere Python does.

The Fiji zip lays down a "Fiji/" folder that contains everything the runtime
needs (jars, plugins, Fiji.app on macOS, the fiji launcher script, ...). We
leave that layout alone -- moving pieces around breaks Fiji's own launcher.

Steps:
  1. Detect OS + architecture.
  2. Download the official Fiji distribution (JDK bundled).
  3. Extract into an install directory (default: ~/Fiji-SAMJ).
  4. Register the SAMJ update site (https://sites.imagej.net/SAMJ/) and install
     plugins headlessly via Fiji's own updater CLI.
  5. Apply the JNA fix documented in the SAMJ-IJ README (best-effort;
     recent Fiji builds already ship the correct jna-5.14.0 pair).

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
from typing import Optional

SAMJ_SITE_NAME = "SAMJ"
SAMJ_SITE_URL = "https://sites.imagej.net/SAMJ/"
FIJI_BASE = "https://downloads.imagej.net/fiji/latest"

FIJI_ARCHIVES = {
    ("Darwin",  "arm64"):   "fiji-latest-macos-arm64-jdk.zip",
    ("Darwin",  "aarch64"): "fiji-latest-macos-arm64-jdk.zip",
    ("Darwin",  "x86_64"):  "fiji-latest-macos64-jdk.zip",
    ("Linux",   "x86_64"):  "fiji-latest-linux64-jdk.zip",
    ("Windows", "AMD64"):   "fiji-latest-win64-jdk.zip",
    ("Windows", "x86_64"):  "fiji-latest-win64-jdk.zip",
}


def log(msg: str)  -> None: print(f"[SAMJ] {msg}", flush=True)
def warn(msg: str) -> None: print(f"[WARN] {msg}", file=sys.stderr, flush=True)
def die(msg: str)  -> None: print(f"[ERR ] {msg}", file=sys.stderr, flush=True); sys.exit(1)


def pick_archive() -> tuple[str, str, str]:
    system = platform.system()
    machine = platform.machine()
    key = (system, machine)
    if key not in FIJI_ARCHIVES:
        die(f"Unsupported OS/arch: {system}/{machine}. Fiji ships x86_64 for Linux/Windows and arm64/x86_64 for macOS.")
    return system, machine, FIJI_ARCHIVES[key]


def download(url: str, dest: Path) -> None:
    """Streamed download with a coarse progress meter (works in dumb terminals)."""
    log(f"Downloading {url}")
    log(f"  -> {dest}")
    with urllib.request.urlopen(url) as resp, open(dest, "wb") as out:
        total = int(resp.headers.get("Content-Length") or 0)
        downloaded = 0
        chunk = 1024 * 1024
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


def extract_zip(zip_path: Path, out_dir: Path) -> None:
    log(f"Extracting {zip_path.name} ...")
    with zipfile.ZipFile(zip_path) as zf:
        zf.extractall(out_dir)
        # Restore +x on POSIX (Fiji ships shell wrappers and launcher binaries).
        if os.name == "posix":
            for info in zf.infolist():
                mode = (info.external_attr >> 16) & 0o777
                if mode & 0o111:
                    target = out_dir / info.filename
                    if target.is_file():
                        target.chmod(target.stat().st_mode | stat.S_IXUSR | stat.S_IXGRP | stat.S_IXOTH)


def find_fiji_root(base: Path) -> Optional[Path]:
    """Find the folder Fiji was extracted into (the one holding jars/, plugins/, fiji, fiji.bat).

    The zip normally lays down a 'Fiji/' folder; we discover it rather than hard-code
    in case upstream renames it.
    """
    if not base.exists():
        return None
    # 1) folder containing the 'fiji' or 'fiji.bat' launcher AND a jars/ folder.
    for name in ("fiji", "fiji.bat"):
        for cand in base.rglob(name):
            if cand.is_file() and (cand.parent / "jars").is_dir():
                return cand.parent
    # 2) folder literally named 'Fiji' with a jars/ child.
    for cand in base.rglob("Fiji"):
        if cand.is_dir() and (cand / "jars").is_dir():
            return cand
    # 3) legacy Fiji.app as its own root (old Fiji layout).
    for cand in base.rglob("Fiji.app"):
        if cand.is_dir() and (cand / "jars").is_dir():
            return cand
    return None


def repair_split_layout(install_dir: Path) -> None:
    """Fix a common mis-extraction where Fiji.app landed as a sibling of Fiji/
    instead of a child of Fiji/. Fiji's own launcher script needs the .app
    bundle next to it, not one directory up.
    """
    outer_app = install_dir / "Fiji.app"
    inner = install_dir / "Fiji"
    if outer_app.is_dir() and inner.is_dir() and not (inner / "Fiji.app").exists():
        log(f"Repairing layout: moving Fiji.app into {inner}/")
        shutil.move(str(outer_app), str(inner / "Fiji.app"))


def find_launcher(fiji_root: Path, system: str) -> Path:
    """Pick a CLI launcher for `fiji --headless --update ...`.

    Preferred: the portable 'fiji' shell script (macOS/Linux) or 'fiji.bat' (Windows).
    They discover the native launcher themselves.
    """
    if system == "Windows":
        candidates = [
            fiji_root / "fiji-windows-x64-console.exe",  # console = best for --headless
            fiji_root / "fiji-windows-x64-gui.exe",
            fiji_root / "fiji-windows-x64.exe",
            fiji_root / "fiji.bat",
            fiji_root / "ImageJ-win64.exe",              # legacy
        ]
    elif system == "Darwin":
        candidates = [
            fiji_root / "fiji",
            fiji_root / "Fiji.app" / "Contents" / "MacOS" / "fiji-macos-arm64",
            fiji_root / "Fiji.app" / "Contents" / "MacOS" / "fiji-macos-x64",
            fiji_root / "Contents" / "MacOS" / "fiji-macos-arm64",
            fiji_root / "Contents" / "MacOS" / "fiji-macos-x64",
            fiji_root / "Contents" / "MacOS" / "ImageJ-macosx",
        ]
    elif system == "Linux":
        candidates = [
            fiji_root / "fiji",
            fiji_root / "fiji-linux-x64",
            fiji_root / "fiji-linux64",
            fiji_root / "ImageJ-linux64",
        ]
    else:
        die(f"Unsupported system for launcher lookup: {system}")

    for c in candidates:
        if c.exists():
            if os.name == "nt" or os.access(c, os.X_OK):
                return c

    # Last-resort glob.
    for pattern in ("fiji*", "ImageJ-*"):
        for cand in fiji_root.rglob(pattern):
            if cand.is_file() and (os.name == "nt" or os.access(cand, os.X_OK)):
                return cand
    die(f"Could not find the Fiji CLI launcher inside {fiji_root}")


def run_fiji_update(fiji_exe: Path, *args: str) -> int:
    cmd = [str(fiji_exe), "--headless", "--update", *args]
    log("  $ " + " ".join(cmd))
    return subprocess.call(cmd)


def register_update_site(fiji_exe: Path) -> None:
    log(f"Registering update site: {SAMJ_SITE_NAME} -> {SAMJ_SITE_URL}")
    rc = run_fiji_update(fiji_exe, "edit-update-site", SAMJ_SITE_NAME, SAMJ_SITE_URL)
    if rc != 0:
        log("edit-update-site not accepted; retrying with add-update-site.")
        rc = run_fiji_update(fiji_exe, "add-update-site", SAMJ_SITE_NAME, SAMJ_SITE_URL)
        if rc != 0:
            die("Failed to register the SAMJ update site.")


def install_plugins(fiji_exe: Path) -> None:
    log("Downloading and installing SAMJ plugins (can take 5-10 min) ...")
    rc = run_fiji_update(fiji_exe, "update")
    if rc != 0:
        warn(f"Fiji updater exited with code {rc}. If plugins are missing, inspect the update-log.txt next to the launcher.")


def fix_jna(fiji_root: Path) -> None:
    jars = fiji_root / "jars"
    if not jars.is_dir():
        warn(f"No jars/ directory under {fiji_root}; skipping JNA cleanup.")
        return
    log(f"Verifying JNA state in {jars}")
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


def strip_quarantine(fiji_root: Path, system: str) -> None:
    if system != "Darwin":
        return
    if shutil.which("xattr"):
        log("Removing macOS quarantine attribute so Gatekeeper won't block launch.")
        subprocess.call(["xattr", "-dr", "com.apple.quarantine", str(fiji_root)])


def main() -> None:
    ap = argparse.ArgumentParser(description="One-shot SAMJ-IJ installer (Fiji + SAMJ plugin).")
    default_dir = Path.home() / "Fiji-SAMJ"
    ap.add_argument("--install-dir", "-d", type=Path, default=default_dir,
                    help=f"where to place the Fiji folder (default: {default_dir})")
    ap.add_argument("--force", "-f", action="store_true",
                    help="wipe an existing Fiji install before starting")
    args = ap.parse_args()

    system, machine, archive_name = pick_archive()
    install_dir: Path = args.install_dir.expanduser().resolve()

    log(f"Platform: {system}/{machine}   archive: {archive_name}")
    log(f"Install directory: {install_dir}")

    macos_downloads_guard(install_dir, system)
    install_dir.mkdir(parents=True, exist_ok=True)

    fiji_root = find_fiji_root(install_dir)
    if fiji_root:
        if args.force:
            log(f"--force: removing existing {fiji_root}")
            shutil.rmtree(fiji_root)
            # If the old buggy installer left a sibling Fiji.app, clean it up too.
            outer_app = install_dir / "Fiji.app"
            if outer_app.is_dir():
                shutil.rmtree(outer_app)
            fiji_root = None
        else:
            log(f"Existing Fiji found at {fiji_root} -- re-running update site registration and JNA fix only.")

    if not fiji_root:
        zip_path = install_dir / archive_name
        download(f"{FIJI_BASE}/{archive_name}", zip_path)
        extract_zip(zip_path, install_dir)
        try:
            zip_path.unlink()
        except OSError:
            pass
        repair_split_layout(install_dir)
        fiji_root = find_fiji_root(install_dir)
        if not fiji_root:
            die(f"Extraction succeeded but no Fiji root was found under {install_dir}")

    strip_quarantine(fiji_root, system)
    fiji_exe = find_launcher(fiji_root, system)
    log(f"Fiji CLI launcher: {fiji_exe}")

    register_update_site(fiji_exe)
    install_plugins(fiji_exe)
    fix_jna(fiji_root)

    print()
    log("installation complete.")
    print()
    print(f"Fiji + SAMJ is installed at:\n  {fiji_root}")
    print()
    print("To launch:")
    if system == "Darwin":
        app = fiji_root / "Fiji.app"
        if app.is_dir():
            print(f"  open '{app}'")
        else:
            print(f"  '{fiji_exe}'")
    elif system == "Windows":
        bat = fiji_root / "fiji.bat"
        exe = fiji_root / "fiji-windows-x64-gui.exe"
        if bat.exists():
            print(f"  Start-Process '{bat}'")
        elif exe.exists():
            print(f"  Start-Process '{exe}'")
        else:
            print(f"  Start-Process '{fiji_exe}'")
    else:
        print(f"  '{fiji_exe}'")
    print()
    print("Inside Fiji:")
    print("  Plugins > SAMJ > SAMJ Annotator")
    print()
    print("First run of any SAM model triggers a one-time env setup via Appose/Micromamba.")
    print("Allow up to ~15 min on modest hardware; subsequent runs are instant.")


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        die("Interrupted.")
