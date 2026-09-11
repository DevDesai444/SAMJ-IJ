# SAMJ-IJ one-shot installer

These scripts download the official Fiji distribution (with a bundled JDK), extract it into a folder you choose, register the SAMJ update site (`https://sites.imagej.net/SAMJ/`), install the plugins headlessly, and apply the JNA fix documented in the top-level README. When the script finishes, launching Fiji from that folder gives you the SAMJ Annotator under `Plugins > SAMJ > SAMJ Annotator`.

No admin/root privileges are required. Fiji is installed for the current user only.

## What you get

- Fiji **bundled with a Java 21 JDK** (~700 MB download, ~1.4 GB extracted).
- The SAMJ plugin family (SAMJ Annotator + supported SAM/SAM2 backends).
- The JNA jar cleanup so `NoSuchMethodError: com.sun.jna.Native.load` never appears.
- On macOS, the quarantine flag is removed so Gatekeeper does not block the first launch.

The first time you pick a SAM model inside Fiji, SAMJ will download that model's Python environment through Appose/Micromamba (~1000 s on a modest CPU per the paper). That is expected and is a one-time cost per model.

## Requirements

| OS      | Arch          | Extra tools you need              |
| ------- | ------------- | --------------------------------- |
| macOS   | Apple Silicon | none (system `curl` + `unzip`)    |
| macOS   | Intel         | none                              |
| Linux   | x86_64        | `curl` (or `wget`) and `unzip`    |
| Windows | x64           | PowerShell 5+ (ships with Win 10/11) |

Any recent Python 3 also works as a fallback -- see `install.py`.

## Usage

### macOS / Linux

```bash
cd installer
chmod +x install.sh
./install.sh                          # installs to ~/Fiji-SAMJ
./install.sh ~/tools/fiji-samj        # custom location (positional arg)
INSTALL_DIR=/opt/fiji-samj ./install.sh   # or via env var
FORCE=1 ./install.sh                  # wipe an existing Fiji.app first
```

> **macOS:** do **not** install into `~/Downloads`. The SAMJ README says SAMJ misbehaves when Fiji lives under `Downloads`; the script refuses that path.

### Windows (PowerShell)

Open a normal PowerShell prompt (no admin needed) and run:

```powershell
cd installer
powershell -ExecutionPolicy Bypass -File .\install.ps1
powershell -ExecutionPolicy Bypass -File .\install.ps1 -InstallDir "C:\Tools\Fiji-SAMJ"
powershell -ExecutionPolicy Bypass -File .\install.ps1 -Force
```

If Windows Defender SmartScreen warns about Fiji on first launch, that is expected -- it is the standard signed Fiji distribution downloaded from `downloads.imagej.net`.

### Cross-platform Python fallback

Use `install.py` on any OS that has Python 3.8+ and no shell of your choice:

```bash
python3 installer/install.py
python3 installer/install.py --install-dir /opt/fiji-samj
python3 installer/install.py --force
```

## Launching after install

The script prints an OS-specific launch command at the end. In short:

- **macOS:** `open ~/Fiji-SAMJ/Fiji.app`
- **Windows:** double-click `%USERPROFILE%\Fiji-SAMJ\Fiji.app\fiji-windows-x64.exe` (or `Start-Process` it)
- **Linux:** `~/Fiji-SAMJ/Fiji.app/fiji-linux64`

Then inside Fiji: **Plugins → SAMJ → SAMJ Annotator**.

## What the script does under the hood

1. Detect OS + CPU architecture; pick the matching zip from `https://downloads.imagej.net/fiji/latest/`.
2. Download the zip (retries up to 3× on network hiccups).
3. Extract into `<install-dir>` so you end with `<install-dir>/Fiji.app/…`.
4. macOS only: `xattr -dr com.apple.quarantine Fiji.app` so Gatekeeper does not block it.
5. Run Fiji's own updater in headless mode:
   - `fiji --headless --update edit-update-site SAMJ https://sites.imagej.net/SAMJ/`
     (falls back to `add-update-site` on older Fiji builds)
   - `fiji --headless --update update`
6. Delete `jars/jna-3.2.7.jar`, `jars/jnacl-1.0.0.jar`, and any stray `jna*.jar` other than `jna-5.14.0.jar` / `jna-platform-5.14.0.jar` (the pair SAMJ requires).

If you already had a `Fiji.app` in that directory, the script keeps your existing binary and only re-runs steps 4–6 (unless you pass `--force` / `FORCE=1`, which wipes it first).

## Reinstalling / uninstalling

- **Reinstall from scratch:** pass `--force` (Python/PowerShell) or `FORCE=1 ./install.sh`.
- **Uninstall:** delete the whole install directory (`~/Fiji-SAMJ` by default). Everything the script created lives inside that folder; no system files are touched.

## Troubleshooting

- **Download stalls or fails.** The script uses `downloads.imagej.net` (US mirror). If you're on a slow link, run once, let it partial-download, then re-run with `--force`. Alternatively, replace `FIJI_BASE` in the script with a closer mirror listed on <https://imagej.net/software/fiji/downloads>.
- **`NoSuchMethodError: com.sun.jna.Native.load` at plugin launch.** The JNA cleanup step should prevent this. If you built the environment manually, re-run the installer -- the JNA step is idempotent.
- **First SAM model click hangs for many minutes.** Expected on the first run: Appose is provisioning the Python environment via Micromamba. Progress lands in Fiji's console (Window → Console) and in `Fiji.app/appose/` on disk.
- **Windows: "running scripts is disabled on this system".** Use the exact command shown above with `-ExecutionPolicy Bypass`; that flag only affects the single invocation, so you don't have to change your machine-wide execution policy.

## Files in this folder

- `install.sh` -- macOS / Linux (bash).
- `install.ps1` -- Windows (PowerShell 5+).
- `install.py` -- pure-Python fallback for any OS with Python 3.8+.
- `README.md` -- this file.
