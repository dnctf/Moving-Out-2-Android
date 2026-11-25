import json
import subprocess
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).resolve().parents[1] / "write_runtime_manifest.py"

class RuntimeManifestWriterTest(unittest.TestCase):
    def make_root(self, root):
        files = [
            "box64/bin/box64", "wine/bin/wine64", "wine/bin/wineserver",
            "wine/lib/wine/x86_64-windows/xinput1_3.dll",
            "wine/lib/wine/x86_64-windows/xinput1_4.dll",
            "wine/lib/wine/x86_64-windows/xinput9_1_0.dll",
            "wine/lib/wine/x86_64-windows/vcruntime140.dll",
            "wine/lib/wine/x86_64-windows/vcruntime140_1.dll",
            "wine/lib/wine/x86_64-windows/msvcp140.dll",
            "dxvk/x64/d3d11.dll", "dxvk/x64/dxgi.dll",
            "wine/lib/wine/libwine.so",
        ]
        for name in files:
            path = root / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(("fixture:" + name).encode())
    def test_manifest_valid_without_turnip(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            self.make_root(root)
            subprocess.run(["python3", str(SCRIPT), "--root", str(root), "--version", "test-1"], check=True)
            manifest = json.loads((root / "runtime-manifest.json").read_text())
            self.assertFalse(manifest["components"]["turnip"]["included"])
            for key in ("turnipSo", "turnipIcd"):
                self.assertFalse(manifest["components"][key]["included"])
                self.assertEqual(manifest["components"][key]["sha256"], "")
    def test_manifest_valid_with_turnip(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            self.make_root(root)
            turnip = root / "turnip"
            turnip.mkdir()
            (turnip / "libvulkan_freedreno.so").write_bytes(b"turnip-so")
            (turnip / "freedreno_icd.aarch64.json").write_text('{"file_format_version":"1.0.0"}')
            subprocess.run(["python3", str(SCRIPT), "--root", str(root), "--version", "test-2"], check=True)
            manifest = json.loads((root / "runtime-manifest.json").read_text())
            self.assertTrue(manifest["components"]["turnip"]["included"])
            self.assertEqual(len(manifest["components"]["turnipSo"]["sha256"]), 64)
            self.assertEqual(len(manifest["components"]["turnipIcd"]["sha256"]), 64)

if __name__ == "__main__":
    unittest.main()
