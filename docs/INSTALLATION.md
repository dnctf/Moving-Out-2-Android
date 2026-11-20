# Game installation

The app will ask the user to select their own Moving Out 2 installation folder through Android's Storage Access Framework.

Expected Windows installation files (the exact layout is still being validated):
- `mo2.exe`
- `GameAssembly.dll`
- `UnityPlayer.dll`
- `baselib.dll`
- `MovingOut2_Data/`
- `MovingOut2_Data/il2cpp_data/Metadata/global-metadata.dat`

The validator will report missing files and will not copy, upload, or distribute the game contents. It also scans a bounded prefix of `globalgamemanagers` for a Unity version string, checks IL2CPP metadata magic/version, and reports `mo2.exe` PE architecture. This is file evidence only and does not validate Unity runtime compatibility or launch readiness. This list is a validation target, not a guarantee that these files alone are sufficient to launch the game.
