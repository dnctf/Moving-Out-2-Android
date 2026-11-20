# Repository Audit — ZIP Import & Settings Milestone

Audit date: 2026-11-20
Target: main, ZIP import/settings changes and existing runtime foundation.

## CI failure diagnosis

Run #142 (44706904255), commit 1886ece9b4a649d1a27ae95fb8b214511fdcca03, failed at `:app:compileDebugKotlin`. The compiler reported `GameZipImporter.kt:37:58 Unsupported escape sequence`. Root cause: the ZIP path normalization character literal was written with too many backslashes. This was corrected to Kotlin's escaped backslash character literal in a follow-up commit. Subsequent MainActivity edits were consolidated to avoid fragile partial replacement.

## Fixes applied after the failed run

- Corrected ZIP separator escape.
- Rewrote MainActivity settings and import-result sections cleanly.
- Fixed slider labels so each SeekBar updates its own TextView rather than relying on child indices (which shifted as more controls were added).
- ZIP import now reports the imported game root and invokes the local installation validator after extraction.
- Settings spinner options and stored values are aligned.
- Clarified that settings persist only and are not yet applied to the runtime.

## Current design gaps and risks

### P0 — Runtime / playability
- Wine, Box64, DXVK and Turnip are not integrated as executable runtime components. Payload checks do not launch or prove compatibility.
- FPS target, resolution scale, graphics preset and audio controls are preferences only; no runtime consumes them yet.
- Launch remains intentionally blocked. No Snapdragon 855 device benchmark, thermal test, game session, controller test, or 30 FPS measurement has been performed.

### P0 — Import usability
- ZIP-imported games are stored in app-private storage but are not yet linked into a persistent game-installation model used by the launcher. The validation report is shown, but folder selection/revalidation remains SAF-centric.
- No explicit free-space preflight is performed. A 16 GiB extraction ceiling is not a storage guarantee; disk exhaustion may still occur. Staging cleanup and rollback mitigate, but do not eliminate, device storage risk.
- Import runs on a raw daemon thread and is not lifecycle-aware/cancellable. Closing/recreating the activity can leave an import running.
- Import result does not provide a progress indicator, speed, cancel button, or resumable extraction.
- The importer permits duplicate ZIP entry names; overwrite semantics are implicit. Needs duplicate detection to avoid ambiguity.
- The game-root search only recognizes `mo2.exe` plus `MovingOut2_Data`; this is a heuristic, not a full game integrity check.
- ZIP import has bounded entries and expanded bytes, and canonical-path confinement, but should receive dedicated adversarial ZIP tests (traversal, absolute paths, duplicate entries, malformed archives, empty files, nested root, entry/size limits, rollback failure, insufficient storage).

### P1 — Settings UI
- Resolution scale slider currently represents percentage, but values are only stored. It needs runtime mapping and a validated range/preset.
- Audio settings are stored independently, but there is no audio backend/mixer binding yet.
- Settings dialog is not scrollable; may clip on short displays or large font settings.
- Preset changes do not automatically adjust resolution/FPS settings; presets currently are descriptive.
- Settings persistence tests are missing.

### P1 — Installation and compatibility
- Two installation validation paths exist (SAF and local-file validator) with partially duplicated checks and different reports.
- ZIP import is not integrated into the same canonical installation repository.
- Game validation requires core files and reads PE/metadata signatures; it is not a compatibility verdict.
- Game/profile version detection needs representative files and false-positive tests.

### P1 — Runtime bundle and process safety
- Runtime bundle importer should be stress-tested for large payloads, interrupted imports, rollback, permission edge cases, and disk-full errors.
- Manifest paths should receive exhaustive traversal and symlink/confinement tests.
- RuntimeProcess PID detection relies on parsing a process description string, which is not a robust Android/JVM process identity mechanism.
- stdout/stderr readers currently record every line; output volume could grow diagnostics memory usage unless the diagnostics store is bounded.
- Runtime process lifecycle/session management still needs integration tests, including timeouts, user stop, and child-process handling.

### P2 — Android UX / maintenance
- MainActivity uses deprecated `startActivityForResult/onActivityResult`; migrate to Activity Result APIs.
- Import tasks need lifecycle-safe architecture (ViewModel/foreground worker where appropriate), progress, cancellation, notification handling.
- Need modern UI structure, scrollable settings, accessibility labels, larger-font and landscape tests.
- Add Android storage, memory, thermal, low-space diagnostics.

## Prioritized next work

1. Keep CI green and add ZIP importer tests before any device import.
2. Unify local ZIP and SAF installation validation/repository state.
3. Add disk-space preflight, duplicate ZIP entry rejection, and import progress/cancellation.
4. Make settings screen scrollable and add persistence tests.
5. Define runtime settings contract (resolution/FPS/audio/preset) with versioned schema, then implement consumers only when runtime exists.
6. Integrate/build Wine + Box64 and graphics pipeline legally and reproducibly; add launch/session logging.
7. Device test Snapdragon 855 baseline; profile CPU/GPU, RAM, shader compilation, thermal throttling, and verify 30 FPS in representative scenes. A 30 FPS target is not a guaranteed outcome.

## CI gate

The ZIP/settings code must not be declared complete until latest Actions run builds the APK, unit tests pass, APK artifact uploads, and import-specific tests are added. CI green alone does not prove game import or runtime execution on device.
