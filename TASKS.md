# RoseGold Java — tasks / backlog

Living list of what this repo already does and what is still open. The C++ RoseGoldC tree is out of scope. Do not invent `[profile.debug]` / `[profile.release]` keys.

## Done

- IntelliJ plugin for `.rg` (diagnostics, completion, run/debug, format, `project.toml`)
- In-process interpreter plus register bytecode VM
- LLVM lowering, clang link, `llvm --run` / `llvm --test --run`
- Native hosts: math, str, uuid, path, io, json, regex, time, process/argv, checks, async, signals, `__ui`
- Win32 `__ui`: HWND, software framebuffer, DPI-aware text, rounded rects, PNG/JPG/BMP (WIC), simple SVG (`rect`/`circle`)
- Plugin **Run** and **Test** default to native LLVM for `.rg` files (Debug stays interpreter)
- Native pass/fail directory suite (`test --native`, plugin Test); files that are not lowered fall back to the interpreter
- DWARF in native binaries (`clang -g` and `!dbg` / `DILocation` in the IR)
- Private same-module calls (`helper` inside `mod`) work from bytecode
- GitHub Actions on `windows-latest` (`RG_UI_HEADLESS=1`)
- `--run` exes staged under `%LOCALAPPDATA%\RoseGold\run` to dodge WDAC `CreateProcess` error 4551

## Next

- **Linux / macOS `__ui`**: native windows, not headless stubs
- **Richer SVG**: paths, groups, transforms; current parser is enough for `examples/assets/dot.svg`
- **Native debug in the plugin**: drive lldb/gdb / DAP from DWARF instead of the tree-walker
- **Watch CI**: confirm `.github/workflows/test.yml` stays green after LLVM/choco updates

## Later

- Source maps from `.rg` lines to LLVM IR beyond the current `!dbg` attachments
- Ship prebuilt native runtimes so clang is optional for Run/Test
- More UI widgets on the native raster path
- Incremental / cached `llvm --link` when sources are unchanged

## Not doing

- Porting or tracking RoseGoldC
- JVM bytecode as the long-run backend
- Extra `project.toml` profile tables beyond `[profile.test]`
