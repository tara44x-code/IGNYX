#!/usr/bin/env python3
"""Boots a disk image in QEMU, screenshots it and OCRs the Ventoy menu.

    qemu-boot-check.py <disk.img> <bios|uefi> <expected text> <out.png>

Exits 0 when the expected text (case-insensitive) shows up on screen.
Used by CI to prove that a drive prepared by IGNYX really boots.
"""
import os
import socket
import subprocess
import sys
import tempfile
import time

disk, mode, expected, out_png = sys.argv[1:5]
tmp = tempfile.mkdtemp()
mon = os.path.join(tmp, "mon.sock")

cmd = [
    "qemu-system-x86_64", "-m", "1024", "-display", "none",
    "-drive", f"file={disk},format=raw,if=ide,snapshot=on",
    "-monitor", f"unix:{mon},server,nowait",
    "-accel", "kvm", "-accel", "tcg",
]
if mode == "uefi":
    fw = next(p for p in ["/usr/share/ovmf/OVMF.fd", "/usr/share/OVMF/OVMF_CODE.fd"] if os.path.exists(p))
    cmd += ["-bios", fw]

print("Running:", " ".join(cmd), flush=True)
qemu = subprocess.Popen(cmd)


def monitor(command: str) -> None:
    s = socket.socket(socket.AF_UNIX)
    s.connect(mon)
    s.settimeout(5)
    try:
        s.recv(4096)
    except socket.timeout:
        pass
    s.sendall((command + "\n").encode())
    time.sleep(1.5)
    s.close()


found = False
text = ""
try:
    for attempt in range(18):  # up to ~3 minutes
        time.sleep(10)
        if qemu.poll() is not None:
            print("QEMU exited early", flush=True)
            break
        ppm = os.path.join(tmp, "screen.ppm")
        monitor(f"screendump {ppm}")
        if not os.path.exists(ppm):
            continue
        subprocess.run(["convert", ppm, "-resize", "200%", out_png], check=True)
        text = subprocess.run(["tesseract", out_png, "-", "--psm", "6"], capture_output=True, text=True).stdout
        print(f"--- screen after {(attempt + 1) * 10}s ---\n{text.strip()}\n", flush=True)
        if expected.lower() in text.lower().replace(" ", ""):
            found = True
            break
finally:
    qemu.kill()

print(("FOUND" if found else "NOT FOUND") + f": '{expected}' in {mode} boot", flush=True)
if os.environ.get("DUMP_SCREEN") and os.path.exists(os.path.join(tmp, "screen.ppm")):
    import base64
    small = os.path.join(tmp, "small.png")
    subprocess.run(["convert", os.path.join(tmp, "screen.ppm"), "-resize", "800x", small], check=True)
    print("SCREEN-BASE64-BEGIN " + mode)
    print(base64.b64encode(open(small, "rb").read()).decode())
    print("SCREEN-BASE64-END " + mode, flush=True)
sys.exit(0 if found else 1)
