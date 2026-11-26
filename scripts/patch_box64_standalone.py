#!/usr/bin/env python3
"""Apply a narrow Box64 patch for standalone Android builds without Termux paths."""
from pathlib import Path
import sys

root = Path(sys.argv[1]).resolve()

def replace_once(relative: str, old: str, new: str) -> None:
    path = root / relative
    source = path.read_text(encoding="utf-8")
    count = source.count(old)
    if count != 1:
        raise SystemExit(f"{relative}: expected exactly one patch anchor, found {count}")
    path.write_text(source.replace(old, new, 1), encoding="utf-8")

replace_once(
    "CMakeLists.txt",
    'option(WINLATOR_GLIBC "Set to ON if targeting an Android device with Winlator Glibc" ${WINLATOR_GLIBC})\n',
    'option(WINLATOR_GLIBC "Set to ON if targeting an Android device with Winlator Glibc" ${WINLATOR_GLIBC})\n'
    'option(BOX64_STANDALONE_ANDROID "Disable Termux-specific paths for standalone Android builds" OFF)\n'
    'if(BOX64_STANDALONE_ANDROID)\n'
    '    add_compile_definitions(BOX64_STANDALONE_ANDROID=1)\n'
    'endif()\n'
)
replace_once(
    "src/emu/entrypoint.c",
    "#ifdef ANDROID\\nvoid EXPORT my___libc_init",
    "#if defined(ANDROID) && !defined(WINLATOR_GLIBC)\\nvoid EXPORT my___libc_init"
)
replace_once(
    "src/tools/fileutils.c",
    '    if(FileExist("/data/data/com.termux/files/usr/tmp", 0)) return "/data/data/com.termux/files/usr/tmp";\n',
    '#ifndef BOX64_STANDALONE_ANDROID\n'
    '    if(FileExist("/data/data/com.termux/files/usr/tmp", 0)) return "/data/data/com.termux/files/usr/tmp";\n'
    '#endif\n'
)
replace_once(
    "src/tools/env.c",
    '    else if (FileExist("/data/data/com.termux/files/usr/glibc/etc/box64.box64rc", IS_FILE))\n'
    '        initializeEnvFile("/data/data/com.termux/files/usr/glibc/etc/box64.box64rc", priority++);\n',
    '#ifndef BOX64_STANDALONE_ANDROID\n'
    '    else if (FileExist("/data/data/com.termux/files/usr/glibc/etc/box64.box64rc", IS_FILE))\n'
    '        initializeEnvFile("/data/data/com.termux/files/usr/glibc/etc/box64.box64rc", priority++);\n'
    '#endif\n'
)
replace_once(
    "src/core.c",
    '        if(FileExist("/data/data/com.termux/files/usr/glibc/lib/i386-linux-gnu", 0))\n'
    '            AddPath("/data/data/com.termux/files/usr/glibc/lib/i386-linux-gnu", &context->box64_ld_lib, 1);\n'
    '        if(FileExist("/data/data/com.termux/files/usr/glibc/lib/box64-i386-linux-gnu", 0))\n'
    '            AddPath("/data/data/com.termux/files/usr/glibc/lib/box64-i386-linux-gnu", &context->box64_ld_lib, 1);\n'
    '        if(FileExist("/data/data/com.termux/files/usr/lib/box64-i386-linux-gnu", 0))\n'
    '            AddPath("/data/data/com.termux/files/usr/lib/box64-i386-linux-gnu", &context->box64_ld_lib, 1);\n',
    '        #ifndef BOX64_STANDALONE_ANDROID\n'
    '        if(FileExist("/data/data/com.termux/files/usr/glibc/lib/i386-linux-gnu", 0))\n'
    '            AddPath("/data/data/com.termux/files/usr/glibc/lib/i386-linux-gnu", &context->box64_ld_lib, 1);\n'
    '        if(FileExist("/data/data/com.termux/files/usr/glibc/lib/box64-i386-linux-gnu", 0))\n'
    '            AddPath("/data/data/com.termux/files/usr/glibc/lib/box64-i386-linux-gnu", &context->box64_ld_lib, 1);\n'
    '        if(FileExist("/data/data/com.termux/files/usr/lib/box64-i386-linux-gnu", 0))\n'
    '            AddPath("/data/data/com.termux/files/usr/lib/box64-i386-linux-gnu", &context->box64_ld_lib, 1);\n'
    '        #endif\n'
)
replace_once(
    "src/core.c",
    '        if(FileExist("/data/data/com.termux/files/usr/glibc/lib/x86_64-linux-gnu", 0))\n'
    '            AddPath("/data/data/com.termux/files/usr/glibc/lib/x86_64-linux-gnu", &context->box64_ld_lib, 1);\n'
    '        if(FileExist("/data/data/com.termux/files/usr/glibc/lib/box64-x86_64-linux-gnu", 0))\n'
    '            AddPath("/data/data/com.termux/files/usr/glibc/lib/box64-x86_64-linux-gnu", &context->box64_ld_lib, 1);\n'
    '        if(FileExist("/data/data/com.termux/files/usr/lib/box64-x86_64-linux-gnu", 0))\n'
    '            AddPath("/data/data/com.termux/files/usr/lib/box64-x86_64-linux-gnu", &context->box64_ld_lib, 1);\n',
    '        #ifndef BOX64_STANDALONE_ANDROID\n'
    '        if(FileExist("/data/data/com.termux/files/usr/glibc/lib/x86_64-linux-gnu", 0))\n'
    '            AddPath("/data/data/com.termux/files/usr/glibc/lib/x86_64-linux-gnu", &context->box64_ld_lib, 1);\n'
    '        if(FileExist("/data/data/com.termux/files/usr/glibc/lib/box64-x86_64-linux-gnu", 0))\n'
    '            AddPath("/data/data/com.termux/files/usr/glibc/lib/box64-x86_64-linux-gnu", &context->box64_ld_lib, 1);\n'
    '        if(FileExist("/data/data/com.termux/files/usr/lib/box64-x86_64-linux-gnu", 0))\n'
    '            AddPath("/data/data/com.termux/files/usr/lib/box64-x86_64-linux-gnu", &context->box64_ld_lib, 1);\n'
    '        #endif\n'
)
print("Applied standalone Android path guards and selected the glibc __libc_start_main entrypoint for WINLATOR_GLIBC builds.")
