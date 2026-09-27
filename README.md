# Zen Tree & Mountain

A slow climb up one tree, then the view opens onto a mountain.

Written in **Java 21**. There is no game engine and no native 3D library. The picture is a software rasterizer (`Graphics2D` only scales the finished buffer up to the window). The same class files run on an Intel Mac, an Apple Silicon Mac, Windows, and Linux, as long as Java 21 is installed.

The journey lasts about **110 seconds**, then fades through mist and starts again at the roots:

- the camera begins low, at the roots
- it climbs the trunk, staying outside the crown
- it opens onto the mountain and rests there

How the pieces fit together (same style as the other sample projects): `WORKINGS.md` for responsibilities and the picture math, `EXECUTION_FLOW.md` for a frame-by-frame trace.

## Requirements

- **Java 21** or later (`java` and `javac` on your `PATH`)

Check with:

```bash
java -version
```

That should say 21. A machine that also has Java 8 may still print `1.8` from `java` until you point the command at the Java 21 install.

This was sized for two Macs:

- a **mid-2014 Retina MacBook Pro** on macOS Catalina (the picture is drawn small and scaled up, so the fans can stay quiet)
- an **M1 Mac** (quality **3** is sharp and still holds 24 frames a second)

## Run on macOS

From the project root:

```bash
./run.sh
```

That compiles to `out/` and opens the window. Flags pass straight through:

```bash
./run.sh --fullscreen
./run.sh --quality high
```

Manual compile and run:

```bash
javac --release 21 -d out src/zen/*.java
java -cp out zen.ZenSaver
```

## Windows 10

You need a **JDK 21** (Temurin, Oracle, or the Microsoft build all work). The Java 8 that sometimes ships with older tools cannot compile this project.

**1. See which Java the prompt will use**

```bat
java -version
javac -version
```

Both should say 21. If you see `1.8`, the JDK 21 `bin` folder is not the first `java` on `PATH`. The installer usually offers to set that. Open a **new** Command Prompt after changing it.

**2. Compile**

From the project folder in Command Prompt or PowerShell:

```bat
mkdir out
javac --release 21 -d out src\zen\*.java
```

`javac` on Windows accepts the `*` itself, so this works in Command Prompt. In PowerShell the same line is fine: PowerShell expands `src\zen\*.java` to the source files before `javac` sees them.

**3. Run**

```bat
java -cp out zen.ZenSaver
java -cp out zen.ZenSaver --fullscreen
java -cp out zen.ZenSaver --quality low
```

Same keys as on the Mac: **Esc** quits, **F** is full screen, **Space** pauses, **1** / **2** / **3** set the picture size. Closing the window quits.

`run.sh` is a bash script. Use it from Git Bash if you like; Command Prompt and PowerShell use the `javac` / `java` lines above.

## Raspberry Pi OS

Build and run **on the Pi**, on the **desktop** image (Bookworm). A Lite install or an SSH session with no display has no window to open. Swing draws on the Pi’s own screen.

**1. Packages**

```bash
sudo apt update
sudo apt install openjdk-21-jdk
java -version
```

`openjdk-21-jdk` is in the Bookworm repositories (current Raspberry Pi OS). An older Bullseye image often only has Java 17, and this project will not compile there.

**2. Compile and run**

From the project folder, in a terminal on the desktop:

```bash
chmod +x run.sh
./run.sh
./run.sh --fullscreen
./run.sh --quality low
```

Or without the script:

```bash
mkdir -p out
javac --release 21 -d out src/zen/*.java
java -cp out zen.ZenSaver
```

32-bit (`armhf`) and 64-bit (`aarch64`) Pi OS both work. `apt` installs the JDK that matches the OS you are running. You do not cross-compile from the Mac.

Start at **1** (`--quality low`, 640×360) on a Pi 4. The painter will also drop one quality step on its own if a frame is taking too long. **3** (1920×1080) is the sharp picture for a faster machine, not the one to leave running on a small Pi.

If the window never appears, you are not on a graphical session. Run it from a terminal on the desktop, not over SSH, unless you are forwarding a display.

## Using it

1. The window opens at quality **2** (1280×720, scaled up to the window).
2. **Esc** quits. **F** toggles full screen. In full screen, moving the mouse returns to the window. **Space** pauses; the climb continues from the same pose when you resume.
3. **1**, **2**, and **3** set the internal picture size. A label shows the size for a few seconds, and the window title keeps it.

| Key | Picture | What you see |
|-----|---------|----------------|
| **1** | 640×360 | Soft. The light choice on a 2014 MacBook. |
| **2** | 1280×720 | Clear. The default. |
| **3** | 1920×1080 | Sharp. Comfortable on an M1; heavy on a 2014 machine. |

If you never press 1, 2, or 3, and a frame is taking too long, the painter drops one quality step on its own and says so in the terminal. Pressing a number pins the size you chose.

Closing the window quits. This is a desk toy, not an OS screensaver module (no `.saver` / `.scr` hook).

## Command-line flags

| Flag | Action |
|------|--------|
| *(none)* | Window at quality 2 |
| `--fullscreen` | Start in full screen |
| `--quality low` \| `medium` \| `high` | Start at 1, 2, or 3 |
| `--snapshot file.png --time 0.4` | Paint one frame (0–1 along the journey) and exit |
| `--gallery dir` | Paint six frames (roots, trunk, canopy, opening, mountain) and exit |
| `--bench` | Time 40 frames and print milliseconds per frame |
| `--help` | Print the flags |

`--snapshot`, `--gallery`, and `--bench` do not open a window.

## Project layout

```
src/zen/
  ZenSaver.java    # main, window, keys, painter thread
  Scene.java       # tree, mountain, ground, camera path
  Renderer.java    # sky, lighting, clip, raster, mist fade
  Mesh.java        # vertices and triangles
  Cam.java         # eye, right, up, forward
  Noise.java       # height and bark, used only while the world is built
run.sh
README.md
WORKINGS.md
EXECUTION_FLOW.md
```

`out/` and `*.png` are gitignored.
