# Execution flow: from `main` to one frame of the climb

A step-by-step trace of what happens from `ZenSaver.main` through the window, and then what one painter lap does.

Default launch (`./run.sh`) opens the **window at quality 2**. `--fullscreen` is the same path with full screen applied before the painter starts. `--snapshot`, `--gallery`, and `--bench` never reach the window; they are at the end of this note.

Three threads matter after a normal launch:

- **main** — builds the `Scene`, posts the window, returns
- **EDT** — the `JFrame`, keys, mouse, `paintComponent`
- **`zen-painter`** — the camera clock and the rasterizer

---

## Phase A — process entry (main thread)

**1.** The JVM loads `zen.ZenSaver` and calls `main`.

**2.** `Options.parse` walks the arguments. Unknown flags throw. Default quality is index 1 (1280×720). `--quality low|medium|high` sets the index and marks it as chosen by the user.

**3.** `new Scene()` runs on main, before any window:

- ground grid, mountain height field, roots, trunk, crown
- one `Mesh`, then `finish()` trims the arrays
- vertex count and triangle count are printed

Nothing in that mesh is allocated again during the climb.

**4.** If the arguments asked for a still image or a bench, `offline` runs and `main` returns. See **Phase F**.

**5.** Otherwise:

```java
SwingUtilities.invokeLater(() -> show(scene, options));
```

`main` returns. The EDT has not opened the window yet. The JVM stays up because AWT starts a non-daemon thread once the window work begins.

---

## Phase B — the window (EDT, then the painter)

**6.** `show` builds a `Renderer` at the starting size, a `View`, a `Holder`, and a 1200×720 `JFrame`.

`Holder` starts as: running, not paused, not full screen, quality 1 unless `--quality` said otherwise. `pinned` is true only when `--quality` was passed, so a manual size is not replaced by the automatic drop.

**7.** Key bindings are installed on `WHEN_IN_FOCUSED_WINDOW`:

| Input | State change |
|-------|----------------|
| Esc | `running = false`, dispose, `System.exit(0)` |
| F | `toggleFullscreen` |
| Space | `paused` flips |
| 1 / 2 / 3 | `quality` becomes 0 / 1 / 2, `pinned = true`, a notice is shown for 3 seconds |

**8.** `frame.setVisible(true)`. The first paint may find `view.frame == null` and draw only the background. That is one blank moment before the painter publishes an image.

**9.** If `--fullscreen` was set, `toggleFullscreen` runs now: dispose, undecorated, `setFullScreenWindow`, hide the cursor, `fullscreen = true`, an 800 ms grace period so the enter does not count as a mouse exit.

**10.** The painter thread starts. It is a daemon. From here the EDT only reacts to keys, mouse, and `invokeAndWait` paints.

---

## Phase C — one painter lap

**11.** If `holder.paused` is true, the thread remembers `pausedAt` (the first time only) and sleeps 50 ms. It does not render. The picture on screen stays at the last image.

**12.** When pause ends, `pausedTotal` grows by the time spent paused. The line that turns the clock into a camera time is:

```text
seconds = (now - origin - pausedTotal) / 1e9
```

A pause does not skip the climb forward.

**13.** If `holder.quality` is not the size already applied, `Renderer.resize` allocates a new `BufferedImage` and a new depth array. The old image is dropped. Work-time averages reset so a slow frame at the old size cannot force a drop at the new size.

**14.** `renderer.render(scene, seconds)`:

1. `scene.camera(seconds)` writes eye, right, up, forward, and field of view into the renderer's `Cam`. The fraction `seconds % 110 / 110` selects a keyframe segment. Smoothstep blends eye and look-at. The last two keyframes are the same pose, so the mountain holds still before the fade.
2. Sky rows are filled. Depth is cleared to 0 (nothing is close yet).
3. Each vertex is swayed, lit, and moved into camera space. `worldY` is pointed at the mesh heights for the fog test.
4. Each triangle is masked, maybe clipped, and rasterized. A pixel is written only when its `1/z` is greater than the value already stored.
5. `Scene.fade(seconds)` blends toward mist when the fraction is under 0.028 or over 0.972. In the middle of the loop this step returns immediately.

**15.** `invokeAndWait` on the EDT:

- `view.frame` becomes the renderer's image
- `paintImmediately` draws it with bilinear filtering into the panel's current width and height
- the window title becomes `Zen Tree & Mountain — N · width×height`
- if a quality notice is still inside its 3 seconds, the size is drawn in the corner

The painter is blocked inside `invokeAndWait` for that paint. It will not overwrite those pixels until the EDT returns.

**16.** If the user has not pinned a quality, and this check has not already run, frame times accumulate. After about two seconds (24 frames), an average above 34 ms steps `quality` down by one and resizes immediately. The terminal prints the new size. The check does not run again.

**17.** If the lap finished in less than 1/24 s, the thread sleeps the remainder. The climb stays at 24 frames a second even when the machine could go faster.

Then the lap repeats from step 11.

---

## Phase D — a key, in the order it is felt

**18. Space.** EDT flips `paused`. The current image stays. The next painter lap hits step 11 and stops advancing `seconds`. Space again clears the pause; step 12 folds the gap into `pausedTotal`.

**19. 1, 2, or 3.** EDT sets `quality`, `pinned`, and the notice string. The painter does not resize inside the key handler. The next lap's step 13 sees `wanted != applied` and replaces the buffer. The following `invokeAndWait` paints the new size and the corner label. The title updates on that same paint. `pinned` stops step 16 from ever stepping the size back down.

**20. F.** EDT disposes the frame, toggles undecorated and full screen, and shows it again. `holder.fullscreen` flips. The painter may catch `InvocationTargetException` during the dispose; if `running` is still true it ignores that and keeps going. Entering full screen hides the cursor and ignores mouse motion for 800 ms. After that, a move of more than 28 pixels calls `toggleFullscreen` again and returns to the window. It does not quit.

**21. Esc.** `running = false`, the frame is disposed, `System.exit(0)`. The painter's loop condition fails if it is between laps. The process ends either way.

---

## Phase E — the end of the 110 seconds

**22.** As `seconds` approaches a multiple of 110, `fade` falls from 1 to 0. `applyFade` walks every pixel toward the mist colour. The camera is already holding the mountain pose (the last two keyframes match).

**23.** `seconds % 110` wraps to 0. `fade` is 0 again, then rises. The camera is back on the roots keyframe. The viewer sees mist, then the base of the tree, not a jump cut.

---

## Phase F — no window

**24. `--bench`.** Forty calls to `render`, spread evenly across one loop. Prints width, height, average milliseconds, and how many triangles the last frame actually drew (back faces and off-screen triangles are not in that count).

**25. `--snapshot out/frame.png --time 0.4`.** One `render` at 40% of the loop, then `ImageIO.write`. `--time` defaults to 0.4 if omitted. Values near 0 or 1 are mostly mist, because that is the fade.

**26. `--gallery dir`.** Six frames, in order: roots (0.06), lower trunk (0.22), mid trunk (0.42), canopy (0.60), opening (0.78), mountain (0.95). Same renderer as the window. No painter thread.
