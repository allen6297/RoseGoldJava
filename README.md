# RoseGold for IntelliJ

Language support for `.rg` files, plus a Java interpreter for run, test, format, and debug. Functions that lower to register IR run on a bytecode VM (`ir` prints it); the rest still walk the AST. Native LLVM is the later backend.

## Plugin

Open a `.rg` file in IntelliJ IDEA 2025.3+ after **Run IDE with Plugin** (`./gradlew runIde`).

- Diagnostics, completion, hover, go to definition, find usages, rename
- Format Document, structure view, breadcrumbs, inlay hints, signature help
- Color chips, Alt+Enter quick fixes, live templates
- Run `fn main` and `@test` from the gutter; `project.toml` Run/Test uses `entry` / `[profile.test]`
- Debug: breakpoints, stepping, watches (in-process, not DAP)
- Standard library is bundled; a project-local `builtin/std` still wins

Settings: **Settings → Language → RoseGold** (compact format, strip comments).

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
./gradlew rg --args="test tests"
./gradlew rg --args="test examples/project.toml"
./gradlew rg --args="new tmp-app"
./gradlew rg --args="ir examples/hello.rg"
```

Aliases: first letter, `-letter`, `--letter`. No arguments starts a `rosegold ` prompt; `quit` / `exit` leave it.

| Command | What it does |
| --- | --- |
| `run [file] [args...]` | Call `main`. No file (or `project.toml`) uses `[project].entry`. Extra args are `argv`. |
| `check [--json] [--stdin] [file\|dir]` | Parse and typecheck. `--json` prints diagnostics. No file uses `[project].entry`. A directory checks every `.rg` file. |
| `fmt [--write\|-w] [--check] [--compact] [--no-comments] [file\|dir]` | Format to stdout, or rewrite the file. No file (or `project.toml`) uses `[project].entry`. A directory formats every `.rg` file (writes unless `--check`). |
| `test [file\|dir]` | `@test` functions, a pass/fail suite directory, or `project.toml` `[profile.test]`. No args: project tests, or `examples/tests.rg` plus `tests/pass` and `tests/fail`. |
| `new [dir]` | Write `project.toml`, `main.rg`, and `tests.rg`. No dir uses the current directory. Fails if `project.toml` already exists. |
| `new module <name>` | Create `<name>/lib.rg` and add it to `[modules]`. Needs a `project.toml`. |
| `ir [file]` | Print register IR for entry functions. Unsupported constructs stay `{ tree-walk }`. |
| `version` | Print the interpreter version. |

Language samples live under `examples/` (`hello.rg`, `enum.rg`, `generics.rg`, `async.rg`, …). UI demos: `window.rg`, `widgets.rg`, `contacts.rg`, `todo.rg`.

`std.ui` opens real OS windows (Swing). Tests that should not show a frame use `ui.open_hidden`.

## Layout

```text
src/main/Java/com/rosegoldc/idea/   IntelliJ plugin
src/main/Java/com/rosegoldc/lang/   parser, checker, interpreter
builtin/std/                        standard library (.rg; also packed into the plugin/CLI JAR)
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
