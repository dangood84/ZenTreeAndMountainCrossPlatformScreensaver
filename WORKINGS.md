# How Zen Tree & Mountain works

This note is for someone who wants to see how a small Java desktop picture is structured: where it starts, who owns the journey, who paints pixels, and how a key changes the size of the buffer.

You do not need to be a Swing or graphics expert. The same ideas show up in the other sample savers: an entry point, a model, a view, and a timed loop that updates a camera then redraws.

There is **no OpenGL and no game engine**. The tree and the mountain are triangles with colours. Each frame those triangles are projected and filled into an `int[]` of pixels. Swing only stretches that image to the window.

## Build / run workflows

Work from the project root. `java` and `javac` must both be **21**. Output always lands in `out/` (gitignored). The same class files run on each OS; you compile on the machine you want to run on.

| What you want | Where | Command |
|---------------|--------|---------|
| macOS window | Terminal | `./run.sh` |
| macOS full screen | Terminal | `./run.sh --fullscreen` |
| Windows 10 window | Command Prompt or PowerShell | `javac --release 21 -d out src\zen\*.java` then `java -cp out zen.ZenSaver` |
| Windows 10 full screen | same | `java -cp out zen.ZenSaver --fullscreen` |
| Raspberry Pi OS desktop | terminal on the desktop | `sudo apt install openjdk-21-jdk` once, then `./run.sh --quality low` |
| One still frame, any OS | no window | `java -cp out zen.ZenSaver --snapshot frame.png --time 0.4` |
| Timing, any OS | no window | `java -cp out zen.ZenSaver --bench` |

Windows does not use `run.sh` unless you are in Git Bash. Raspberry Pi OS needs the Bookworm desktop image so `apt` can install Java 21, and a real display. Step-by-step for both is in `README.md`.

## Mental model

```
main()
  → new Scene()                 # mesh, once
  → either paint PNGs and exit
     or SwingUtilities.invokeLater
          → JFrame + View
          → painter thread (~24 Hz)
               → read Holder (pause, quality)
               → Renderer.render(scene, seconds)
               → EDT paints the image, scaled up
```

| Layer | Class | Tester-friendly analogy |
|-------|--------|-------------------------|
| Entry / routing | `ZenSaver` | Test runner: window, or snapshot / gallery / bench |
| Live flags | `Holder` | The fixture the keys write and the painter reads |
| World | `Scene` | The set: mesh plus the camera path |
| Camera | `Cam` | Eye and three axes for one instant |
| Picture | `Renderer` | Turns the mesh into pixels |
| Geometry | `Mesh` | Vertices and triangle indices |
| Hills and bark | `Noise` | Used while building, never during a frame |

## Two threads

The marquee saver does motion and paint on the Swing event thread. This one splits them, because a frame of triangles can take longer than a text blit.

- **main** builds the `Scene`, then either exits after offline paints or posts the window onto the EDT.
- **EDT** owns the `JFrame`, the key bindings, and `paintComponent`. It writes `Holder` fields. It does not walk triangles.
- **`zen-painter`** (a daemon thread) owns the pixel buffer. Each lap it renders, then `SwingUtilities.invokeAndWait` asks the EDT to draw the finished image. The painter does not start the next frame until that paint has returned, so the EDT never reads pixels the painter is still writing.

`Holder` fields are `volatile`. A key on the EDT is visible to the painter on the next lap without a lock.

## 1. Entry

`ZenSaver.main` is the only entry point.

1. `Options.parse` reads the flags.
2. `new Scene()` builds the ground, mountain, roots, trunk, and crown into one `Mesh`. That work does not repeat.
3. `--gallery`, `--snapshot`, or `--bench` call `Renderer.render` on the main thread and return. No window.
4. Otherwise `SwingUtilities.invokeLater` opens the window. `main` returns. The AWT thread and the painter keep the process alive until **Esc** or the window close, both of which call `System.exit(0)`.

## 2. What each class owns

### `Scene` — the world and the path

The mesh is fixed. What changes with time is the camera, plus a sway offset on leaves and twigs (the trunk's sway weight is 0).

`camera(seconds)` maps time onto a 0–1 fraction of `CYCLE_SECONDS` (110). That fraction picks a segment of `KEYS`. Eye, look-at, and field of view are eased with smoothstep, then `look` builds a basis whose **right axis stays horizontal**. The sky fill depends on that: with no roll, each row of the sky is one colour.

`fade(seconds)` is a separate state from the camera. It is 0 in the mist and 1 during the picture. The first and last 2.8% of the loop (about 3 seconds) fade, so the jump from the mountain pose back to the roots is not a cut you see.

The crown is recursive, not a formal L-system. `grow` at depth 2 is a limb, depth 1 a branch, depth 0 a twig. Each step splits in two, gets shorter and thinner, and hangs leaf cards on the way.

### `Renderer` — one frame

`render` always does the same steps:

1. Place the camera.
2. Fill the sky by row (horizon warm, zenith blue-grey). If the sun is in front of the camera, stamp a soft disc. Sky pixels are not in the depth buffer.
3. Clear depth to 0.
4. For every vertex: sway, light (hemisphere ambient, wrapped sun, a little specular), then transform into camera space.
5. For every triangle: drop it, draw it, or clip it, then fill pixels.
6. If `fade` is below 1, blend every pixel toward the mist colour.

Depth stores **1/z**. A larger value is closer, so a nearer triangle replaces what is already there. The ground extends under the mountain on purpose: where both exist, the higher surface wins, and a gap does not show the sky as a bright slit.

A triangle has three states in `submit`:

| Mask | Meaning | What happens |
|------|---------|----------------|
| All three vertices outside the **same** frustum plane | Fully off screen or behind the near plane | Dropped |
| All three inside | Fully visible | Drawn as one triangle |
| Mixed | Crosses the near plane or the screen edge | Clipped to a polygon, drawn as a fan |

Front faces arrive with **negative** screen area because screen Y grows downward. Those are kept. The two far vertices are then swapped so the pixel walk can treat the triangle as counter-clockwise.

Colours are stored as linear light and converted to sRGB through a lookup table when a pixel is written. Fog is another lookup on view-space Z, with a little extra mist near the ground.

### `ZenSaver` — window and clock

`paintLoop` is the clock.

- **Running.** `holder.running` is false on Esc or window close. The loop ends.
- **Paused.** Space sets `holder.paused`. The painter sleeps and records when the pause began. On resume it adds that gap to `pausedTotal`, so `seconds` does not jump and the camera stays on the same pose.
- **Quality.** 1, 2, and 3 set `holder.quality` and `holder.pinned`. The painter compares that with the size it last applied and, on a change, `Renderer.resize` replaces the `BufferedImage`. The EDT must not resize the buffer; the painter owns it.
- **Automatic drop.** If the user has not pinned a size, the painter averages frame time for about two seconds. Above 34 ms it steps down once (3→2 or 2→1) and pins nothing further. One step only.
- **Full screen.** F disposes the frame, toggles `setUndecorated`, and shows it again. `dispose` is what makes `setUndecorated` legal after the window has been shown. The painter thread is not stopped. Mouse motion leaves full screen only, and only after a short grace period so the click that entered full screen does not immediately leave it.

The window title is `Zen Tree & Mountain — N · width×height`, where N is 1, 2, or 3.

## 3. The picture, in numbers

| | |
|--|--|
| Loop | 110 s, then mist and repeat |
| Frames | 24 per second, sleep fills the rest of the slot |
| Default buffer | 1280×720 |
| Sun | A fixed direction, up and slightly toward the camera's side of the trunk |
| Trunk | About 24 units tall, wider at the roots |
| Mountain | A height field behind the tree, green below, pale at the top |

The leaves are a handful of crossed cards on each twig. They read as a crown from the opening and the mountain. Up close they are still flat cards. That is the trade that keeps a 2014 MacBook from spending the whole frame filling a forest.
