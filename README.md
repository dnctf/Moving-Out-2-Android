# Moving Out 2 Android

> **Penting:** Aplikasi ini **HANYA merupakan launcher** untuk game **Moving Out 2** di Android ARM64. **APK ini TIDAK termasuk file game.** Anda **harus memiliki dan menyediakan base game Moving Out 2 sendiri** secara legal (folder game berisi `mo2.exe` dan `mo2_Data/` atau arsip ZIP game milik Anda).

*Independent compatibility launcher project, not affiliated with Team17 or SMG Studio.*

---

## Kondisi APK Saat Ini

Launcher ini sudah mengintegrasikan alur lengkap untuk persiapan, instalasi runtime, konfigurasi kontroler, dan peluncuran sesi game di Android ARM64:

1. **Deteksi & Import Base Game Pengguna:**
   - Mendukung deteksi otomatis struktur `mo2.exe` + `mo2_Data/` (serta `MovingOut2_Data/`), pemilihan folder via SAF (`Select game folder`), import ZIP transaksional dengan rollback (`Import game from ZIP`), dan pemindaian direktori game umum di perangkat.
2. **Instalasi Runtime Otomatis (Auto-Download dari GitHub Releases):**
   - Pada peluncuran pertama atau melalui tombol instalasi runtime di launcher, `RuntimeRepositoryInstaller` mengunduh paket runtime ARM64 (`moving-out-2-runtime-arm64-v8a.zip`) beserta verifikasi checksum `.sha256` dan validasi manifest/ABI (`box64` ARM64, `wine64` + `wineserver` x86_64, `DXVK`, dan `xinput1_3`/`xinput1_4`).
   - Juga mendukung import runtime bundle secara manual via SAF (ZIP atau folder).
3. **Peluncuran Game & Supervisor Proses (`WineSessionSupervisor`):**
   - Menjalankan `AndroidExecCapabilityProbe` (cek SELinux), bootstrap `WINEPREFIX`, `wineserver`, dan `mo2.exe` (`-force-d3d11`) dengan pengaturan resolusi, FPS cap, dan profil Dynarec dari `RuntimeSettingsConsumer`.
   - Menampilkan `GameSurfaceHost` (Vulkan `SurfaceView` + swapchain probe), kontroler sentuh `VirtualControllerView` & gamepad fisik (XInput shared-memory IPC), serta `TelemetryOverlayView`.
4. **UI/UX & Diagnostik:**
   - Antarmuka responsif untuk ponsel dan tablet, panel diagnostik Vulkan/GPU & perangkat, serta ekspor log diagnostik ZIP untuk troubleshooting.

---

## Cara Menggunakan

1. Unduh dan install APK dari halaman **Releases** repository ini.
2. Pastikan Anda sudah memiliki **base game Moving Out 2** sendiri di perangkat Anda.
3. Buka launcher, lalu:
   - Pilih **Import game from ZIP** atau **Select game folder** untuk mengarahkan launcher ke base game Anda.
   - Biarkan launcher mengunduh runtime secara otomatis dari GitHub Releases (atau gunakan tombol instalasi/perbaikan runtime).
4. Setelah status game dan runtime siap, tekan tombol **Play / Launch**.

---

## Lisensi

- Kode sumber launcher ini dilisensikan di bawah **MIT License** (lihat [LICENSE](LICENSE)).
- Moving Out 2 adalah merek dagang terdaftar dari Team17 / SMG Studio.
