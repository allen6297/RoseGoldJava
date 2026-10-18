# RoseGold for IntelliJ

Language support for `.rg` files, plus a Java interpreter for run, test, format, and debug. Functions that lower to register IR run on a bytecode VM (`ir` prints it); the rest still walk the AST. `llvm --run` links a native executable.

## Plugin

Open a `.rg` file in IntelliJ IDEA 2025.3+ after **Run IDE with Plugin** (`./gradlew runIde`).

- Diagnostics, completion, hover, go to definition, find usages, rename
- Format Document, structure view, breadcrumbs, inlay hints, signature help
- Color chips, Alt+Enter quick fixes, live templates
- Run `fn main` and `@test` from the gutter; `project.toml` Run/Test uses `entry` / `[profile.test]`
- Run, Test, and Debug use native LLVM (`clang`) by default for `.rg` files; uncheck **Run natively** to use the in-process interpreter
- Debug: breakpoints, stepping, stack (native: `lldb-dap` + DWARF; otherwise the interpreter)
- Standard library is bundled; a project-local `builtin/std` still wins

Settings: **Settings → Language → RoseGold** (compact format, strip comments, run natively).

New file: **File → New → RoseGold File**. New project: **File → New → RoseGold Project** (`project.toml`, `main.rg`, `tests.rg`). New module: **File → New → RoseGold Module** (adds `[modules]`).

## CLI

Java 21. From the repo root:

```text
./gradlew rg                          # REPL (help, quit)
./gradlew rg --args="help"
./gradlew rg --args="version"
./gradlew rg --args="run examples/hello.rg"
./gradlew rg --args="run examples/project.toml"
./gradlew rg --args="check examples/hello.rg"
./gradlew rg --args="check examples"
./gradlew rg --args="fmt examples/hello.rg"
./gradlew rg --args="fmt examples/project.toml"
./gradlew rg --args="fmt --check examples"
./gradlew rg --args="test"
./gradlew rg --args="test --native tests"
./gradlew rg --args="test tests"
./gradlew rg --args="test examples/project.toml"
./gradlew rg --args="new tmp-app"
./gradlew rg --args="ir examples/hello.rg"
./gradlew rg --args="llvm examples/hello.rg"
./gradlew rg --args="llvm --link examples/hello.rg"
./gradlew rg --args="llvm --run examples/hello.rg"
./gradlew rg --args="llvm --test examples/tests.rg"
./gradlew rg --args="llvm --test --run examples/tests.rg"
```

Aliases: first letter, `-letter`, `--letter`. No arguments starts a `rosegold ` prompt; `quit` / `exit` leave it.

| Command | What it does |
| --- | --- |
| `run [file] [args...]` | Call `main`. No file (or `project.toml`) uses `[project].entry`. Extra args are `argv`. |
| `check [--json] [--stdin] [file\|dir]` | Parse and typecheck. `--json` prints diagnostics. No file uses `[project].entry`. A directory checks every `.rg` file. |
| `fmt [--write\|-w] [--check] [--compact] [--no-comments] [file\|dir]` | Format to stdout, or rewrite the file. No file (or `project.toml`) uses `[project].entry`. A directory formats every `.rg` file (writes unless `--check`). |
| `test [--native] [file\|dir]` | `@test` functions, a pass/fail suite directory, or `project.toml` `[profile.test]`. `--native` uses LLVM (falls back to the interpreter if a file is not lowered). No args: project tests, or `examples/tests.rg` plus `tests/pass` and `tests/fail`. |
| `new [dir]` | Write `project.toml`, `main.rg`, and `tests.rg`. No dir uses the current directory. Fails if `project.toml` already exists. |
| `new module <name>` | Create `<name>/lib.rg` and add it to `[modules]`. Needs a `project.toml`. |
| `ir [file]` | Print register IR for entry functions. Unsupported constructs stay `{ tree-walk }`. |
| `llvm [file]` | Print LLVM IR for the entry file. |
| `llvm --link [file] [-o out]` | Lower, emit LLVM IR, and link a native executable with clang (LLVM 15+). Repeats reuse a cached exe under `%LOCALAPPDATA%\RoseGold\run`. |
| `llvm --run [file] [args...]` | Link (or reuse the cache) and run. Extra args are `argv`. |
| `llvm --test [file]` | Print LLVM IR for a `@test` harness. |
| `llvm --test --run [file]` | Link and run the `@test` harness. |
| `llvm --godot [file] [-o out]` | Link a Godot script shared library and write a sidecar manifest (base class, methods, vars, signals). |
| `version` | Print the interpreter version. |

Language samples live under `examples/` (`hello.rg`, `enum.rg`, `generics.rg`, `async.rg`, …). UI demos: `window.rg`, `widgets.rg`, `contacts.rg`, `todo.rg`.

`std.ui` opens real OS windows (Swing in the interpreter; Win32 in `llvm --run`). Tests that should not show a frame use `ui.open_hidden`. Native UI tests set `RG_UI_HEADLESS=1` so `Window.run` exits without a message pump.

Native LLVM needs **clang** (LLVM 15+) on PATH, `CLANG`, or `C:\Program Files\LLVM\bin`. Native Debug also needs **lldb-dap** next to clang (the official Windows LLVM build wants **Python 3** / `python3.dll` on PATH). Windows links `user32`, `gdi32`, `ole32`, `uuid`, and `windowscodecs` (PNG/JPG/BMP via WIC; a small SVG subset for `rect`/`circle`). Image paths are relative to the process working directory. Linked natives include DWARF (`clang -g` plus `!dbg` in the IR). Repeat `llvm --link` / plugin Run reuse `%LOCALAPPDATA%\RoseGold\run\l<stamp>.exe`. Godot script libraries reuse `g<stamp>.dll` in that same cache.

`llvm --godot` is the Godot 4 host. A script `extends Node2D` (stubs in `builtin/godot`), and the extension in `godot/` loads the shared library. Calls such as `queue_free()` and `get_node()` run on the live node. `Color`, `Rect2`, `Rect2i`, and `Transform2D` pass through those calls (`r`/`g`/`b`/`a`, `position`/`size`, and the `x`/`y`/`origin` columns). `Vector4i`, `Plane`, `Quaternion`, `AABB`, `Basis`, `Transform3D`, and `Projection` pass through as those classes. A `Signal` value has `name` and `object_id`. A `Variant` is the value it holds. `Input`, `Time`, `Engine`, and the other engine singletons are values, so `Input.is_action_pressed("ui_right")` calls that object. A trailing parameter can be left out when the stub gives it a default, and the engine receives that default. Static methods are called on the class, as in `Image.create_empty(8, 4, false, 5)`. A method ending in `...` accepts extra arguments, as in `call("set_meta", "n", 7)`. `bind` is called on a function, and a `Callable` returned by the engine stays a function that `connect` can take. Value types keep their engine methods, as in `position.lerp(target, 0.5)`. `Array` and `Dictionary` pass through as `Array` and `Map` (dictionary keys become strings). Packed arrays pass through as `Array` of ints, floats, strings, `Vec2`, `Vector3`, `Color`, or `Vector4`. `RID` passes through as `Int`. A function passed to `connect` is a Godot `Callable`. `position`, `rotation`, and `scale` on the script node are copied around each call. The same three fields on a node returned from `get_node()` read and write that node directly. The script editor colors keywords, comments, strings, and numbers. It checks the open buffer on a background thread and marks errors when that check finishes. Ctrl+Space completes keywords, names in the open script, and the node's fields and methods. Ctrl+click jumps to a name declared in that script or another `.rg` file in the same project, and opens the built-in docs for those node fields, methods, and for `Node`, `Node2D`, `Object`, and `Vec2`. `get_node` returns `Node`; cast it with `as` to an engine class such as `Timer` or `Node2D`. A `struct` or `data` declaration is a Godot `Resource`. `export var` is shown in the inspector, and an export of that type shows its fields. A `data` value stays immutable in the script. `Array` and `Map` exports show as an array and a dictionary. Open `godot/demo` in Godot 4.4 or newer. The demo DLL is `godot/demo/bin/rosegold_godot.dll` (`cmake -S godot -B godot/build`, or clang `-shared`). Reload shells out to `java` (`ROSEGOLD_JAVA`) with `ROSEGOLD_CP`, or walks up from the `.rg` file to `build/classes/java/main`.

Project status and leftover work: [TASKS.md](TASKS.md).

## Layout

```text
src/main/Java/com/rosegoldc/idea/   IntelliJ plugin
src/main/Java/com/rosegoldc/lang/   parser, checker, interpreter, LLVM
native/                             C runtime linked by `llvm --link`
builtin/std/                        standard library (.rg; also packed into the plugin/CLI JAR)
builtin/godot/                      Node / Node2D stubs for `llvm --godot`
godot/                              Godot 4.4 GDExtension and demo project
examples/                           sample programs
tests/                              pass/ and fail/ language suite
```

## Build

```text
./gradlew test          # language tests
./gradlew runIde        # IDEA sandbox with the plugin
./gradlew buildPlugin   # zip under build/distributions
```

Predefined run configs: **Run IDE with Plugin**, **Run Tests**, **Run Verifications**.
