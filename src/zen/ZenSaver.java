package zen;

import javax.imageio.ImageIO;
import javax.swing.AbstractAction;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * A slow climb up one tree, then the mountain.
 *
 * <pre>
 *   javac --release 21 -d out src/zen/*.java
 *   java -cp out zen.ZenSaver
 * </pre>
 *
 * Esc quits. F is full screen. Space pauses. 1, 2 and 3 set the internal
 * resolution. The default is sized for a mid-2014 Retina MacBook Pro: a small
 * picture, drawn at 24 frames a second, then scaled up so the machine stays quiet.
 */
public final class ZenSaver {
    private static final int[] WIDTHS = {640, 1280, 1920};
    private static final int[] HEIGHTS = {360, 720, 1080};
    private static final int FPS = 24;

    public static void main(String[] args) {
        Options options = Options.parse(args);
        if (options.help) {
            System.out.println("""
                    Zen Tree & Mountain
                      java -cp out zen.ZenSaver
                      java -cp out zen.ZenSaver --fullscreen
                      java -cp out zen.ZenSaver --quality low|medium|high
                      java -cp out zen.ZenSaver --gallery out/gallery
                      java -cp out zen.ZenSaver --snapshot out/frame.png --time 0.4
                    """);
            return;
        }
        Scene scene = new Scene();
        System.out.println("Zen Tree & Mountain");
        System.out.println(scene.world.vCount + " vertices, " + scene.triangleCount() + " triangles");
        // Gallery, snapshot, and bench never open a window. The same Scene and Renderer paint the frames.
        if (options.gallery != null || options.snapshot != null || options.bench) {
            offline(scene, options);
            return;
        }
        System.setProperty("apple.awt.application.name", "Zen Tree & Mountain");
        SwingUtilities.invokeLater(() -> show(scene, options));
    }

    private static void offline(Scene scene, Options options) {
        int quality = options.quality;
        Renderer renderer = new Renderer(WIDTHS[quality], HEIGHTS[quality]);
        if (options.bench) {
            int frames = 40;
            long start = System.nanoTime();
            for (int i = 0; i < frames; i++) {
                renderer.render(scene, (i / (float) frames) * Scene.CYCLE_SECONDS);
            }
            double ms = (System.nanoTime() - start) / 1_000_000.0 / frames;
            System.out.printf("%d×%d  %.1f ms/frame  last triangles drawn %d%n",
                    WIDTHS[quality], HEIGHTS[quality], ms, renderer.trianglesDrawn);
            return;
        }
        try {
            if (options.snapshot != null) {
                float time = options.time == null ? 0.4f : options.time;
                renderer.render(scene, time * Scene.CYCLE_SECONDS);
                write(renderer.image(), Path.of(options.snapshot));
                System.out.println("Wrote " + options.snapshot + " at " + time
                        + "  (" + renderer.trianglesDrawn + " triangles)");
            }
            if (options.gallery != null) {
                Path dir = Path.of(options.gallery);
                Files.createDirectories(dir);
                float[] times = {0.06f, 0.22f, 0.42f, 0.60f, 0.78f, 0.95f};
                String[] names = {"roots", "lower-trunk", "mid-trunk", "canopy", "opening", "mountain"};
                for (int i = 0; i < times.length; i++) {
                    renderer.render(scene, times[i] * Scene.CYCLE_SECONDS);
                    Path file = dir.resolve(names[i] + ".png");
                    write(renderer.image(), file);
                    System.out.println("Wrote " + file + "  (" + renderer.trianglesDrawn + " triangles)");
                }
            }
        } catch (IOException e) {
            System.err.println("Could not write the picture: " + e.getMessage());
            System.exit(1);
        }
    }

    private static void write(BufferedImage image, Path file) throws IOException {
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        ImageIO.write(image, "png", file.toFile());
    }

    private static void show(Scene scene, Options options) {
        int quality = options.quality;
        Renderer renderer = new Renderer(WIDTHS[quality], HEIGHTS[quality]);
        View view = new View();
        Holder holder = new Holder();
        view.holder = holder;
        JFrame frame = new JFrame("Zen Tree & Mountain");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setBackground(new Color(196, 186, 168));
        frame.setContentPane(view);
        frame.setSize(1200, 720);
        frame.setLocationRelativeTo(null);

        holder.running = true;
        holder.quality = quality;
        holder.pinned = options.qualitySet;

        bind(view, "ESCAPE", () -> {
            holder.running = false;
            frame.dispose();
            System.exit(0);
        });
        bind(view, "F", () -> toggleFullscreen(frame, view));
        bind(view, "SPACE", () -> holder.paused = !holder.paused);
        bind(view, KeyEvent.VK_1, () -> pin(holder, 0));
        bind(view, KeyEvent.VK_2, () -> pin(holder, 1));
        bind(view, KeyEvent.VK_3, () -> pin(holder, 2));

        view.addMouseMotionListener(new MouseAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) {
                if (!holder.fullscreen) {
                    return;
                }
                if (System.nanoTime() < holder.fullscreenGrace) {
                    holder.mouse = e.getPoint();
                    return;
                }
                if (holder.mouse != null && holder.mouse.distance(e.getPoint()) > 28) {
                    toggleFullscreen(frame, view);
                }
            }
        });
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                holder.running = false;
            }
        });

        System.out.println("Esc quit    F full screen    Space pause");
        System.out.println("1 soft 640×360    2 clear 1280×720    3 sharp 1920×1080");

        Thread painter = new Thread(() -> paintLoop(scene, renderer, view, frame, holder), "zen-painter");
        painter.setDaemon(true);
        frame.setVisible(true);
        view.requestFocusInWindow();
        if (options.fullscreen) {
            toggleFullscreen(frame, view);
        }
        painter.start();
    }

    private static void pin(Holder holder, int quality) {
        holder.quality = quality;
        holder.pinned = true;
        holder.notice = (quality + 1) + "    " + WIDTHS[quality] + "×" + HEIGHTS[quality];
        holder.noticeUntil = System.nanoTime() + 3_000_000_000L;
    }

    private static void paintLoop(Scene scene, Renderer renderer, View view, JFrame frame, Holder holder) {
        long origin = System.nanoTime();
        long pausedAt = 0L;
        long pausedTotal = 0L;
        int applied = holder.quality;
        long workSum = 0L;
        int workFrames = 0;
        boolean dropped = false;
        long budget = 1_000_000_000L / FPS;

        while (holder.running) {
            // Pause freezes the journey clock. Resume continues from the same pose, it does not skip ahead.
            if (holder.paused) {
                if (pausedAt == 0L) {
                    pausedAt = System.nanoTime();
                }
                sleep(50);
                continue;
            }
            if (pausedAt != 0L) {
                pausedTotal += System.nanoTime() - pausedAt;
                pausedAt = 0L;
            }

            // 1 / 2 / 3 write holder.quality from the EDT. This thread owns the pixel buffer, so the resize happens here.
            int wanted = holder.quality;
            if (wanted != applied) {
                renderer.resize(WIDTHS[wanted], HEIGHTS[wanted]);
                applied = wanted;
                workSum = 0L;
                workFrames = 0;
            }

            float seconds = (System.nanoTime() - origin - pausedTotal) / 1_000_000_000f;
            long began = System.nanoTime();
            renderer.render(scene, seconds);
            long work = System.nanoTime() - began;

            try {
                SwingUtilities.invokeAndWait(() -> {
                    view.frame = renderer.image();
                    view.seconds = seconds;
                    if (view.getWidth() > 0 && view.getHeight() > 0) {
                        view.paintImmediately(0, 0, view.getWidth(), view.getHeight());
                    }
                    int shown = holder.quality + 1;
                    frame.setTitle("Zen Tree & Mountain  —  " + shown + "  ·  "
                            + renderer.image().getWidth() + "×" + renderer.image().getHeight());
                });
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (InvocationTargetException e) {
                if (!holder.running) {
                    return;
                }
            }

            // One automatic step down, and only if the user has not chosen a size. Pressing 1, 2, or 3 pins it.
            if (!holder.pinned && !dropped) {
                workSum += work;
                workFrames++;
                if (workFrames >= FPS * 2 && applied > 0) {
                    double avgMs = workSum / (double) workFrames / 1_000_000.0;
                    if (avgMs > 34.0) {
                        applied--;
                        holder.quality = applied;
                        dropped = true;
                        renderer.resize(WIDTHS[applied], HEIGHTS[applied]);
                        System.out.println(String.format(
                                "Easing the picture to %d×%d so this machine can rest (a frame was taking %.0f ms).",
                                WIDTHS[applied], HEIGHTS[applied], avgMs));
                    } else {
                        dropped = true;
                    }
                }
            }

            long rest = budget - (System.nanoTime() - began);
            if (rest > 2_000_000L) {
                sleep(rest / 1_000_000L);
            }
        }
    }

    // dispose() is what makes setUndecorated legal. The painter thread keeps running across the swap.
    private static void toggleFullscreen(JFrame frame, View view) {
        GraphicsDevice device = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice();
        Holder holder = view.holder;
        frame.dispose();
        if (holder.fullscreen) {
            device.setFullScreenWindow(null);
            frame.setUndecorated(false);
            frame.setCursor(Cursor.getDefaultCursor());
            holder.fullscreen = false;
        } else {
            frame.setUndecorated(true);
            device.setFullScreenWindow(frame);
            frame.setCursor(frame.getToolkit().createCustomCursor(
                    new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB), new Point(0, 0), "hide"));
            holder.fullscreen = true;
            holder.fullscreenGrace = System.nanoTime() + 800_000_000L;
            holder.mouse = null;
        }
        frame.setVisible(true);
        view.requestFocusInWindow();
    }

    private static void bind(JPanel panel, String key, Runnable action) {
        bind(panel, KeyStroke.getKeyStroke(key), key, action);
    }

    private static void bind(JPanel panel, int keyCode, Runnable action) {
        bind(panel, KeyStroke.getKeyStroke(keyCode, 0), "key-" + keyCode, action);
    }

    private static void bind(JPanel panel, KeyStroke stroke, String name, Runnable action) {
        panel.getInputMap(JPanel.WHEN_IN_FOCUSED_WINDOW).put(stroke, name);
        panel.getActionMap().put(name, new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                action.run();
            }
        });
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static final class View extends JPanel {
        private BufferedImage frame;
        private float seconds;
        private Holder holder;

        View() {
            setBackground(new Color(196, 186, 168));
            setFocusable(true);
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            BufferedImage img = frame;
            if (img == null) {
                return;
            }
            Graphics2D g2 = (Graphics2D) g;
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g2.drawImage(img, 0, 0, getWidth(), getHeight(), null);
            if (seconds < 8f && !holder.fullscreen) {
                int alpha = seconds < 6f ? 180 : (int) (180 * (8f - seconds) / 2f);
                g2.setColor(new Color(255, 252, 245, alpha));
                g2.drawString("Esc  quit      F  full screen      Space  pause      1 soft   2 clear   3 sharp", 22, getHeight() - 24);
            }
            if (holder.notice != null && System.nanoTime() < holder.noticeUntil) {
                g2.setFont(g2.getFont().deriveFont(26f));
                g2.setColor(new Color(20, 16, 12, 90));
                g2.drawString(holder.notice, 29, 49);
                g2.setColor(new Color(255, 252, 245, 220));
                g2.drawString(holder.notice, 28, 48);
            }
        }
    }

    /**
     * Live flags shared by the EDT (keys, mouse) and the painter thread.
     * {@code volatile} so a key press is visible on the next frame without a lock.
     */
    private static final class Holder {
        volatile boolean running;
        volatile boolean paused;
        volatile boolean fullscreen;
        volatile boolean pinned;
        volatile int quality;
        volatile long fullscreenGrace;
        volatile Point mouse;
        volatile String notice;
        volatile long noticeUntil;
    }

    private static final class Options {
        boolean help;
        boolean fullscreen;
        boolean bench;
        boolean qualitySet;
        int quality = 1;
        String gallery;
        String snapshot;
        Float time;

        static Options parse(String[] args) {
            Options options = new Options();
            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                switch (arg) {
                    case "--help", "-h" -> options.help = true;
                    case "--fullscreen" -> options.fullscreen = true;
                    case "--bench" -> options.bench = true;
                    case "--gallery" -> options.gallery = need(args, ++i, arg);
                    case "--snapshot" -> options.snapshot = need(args, ++i, arg);
                    case "--time" -> options.time = Float.parseFloat(need(args, ++i, arg));
                    case "--quality" -> {
                        options.quality = quality(need(args, ++i, arg));
                        options.qualitySet = true;
                    }
                    default -> throw new IllegalArgumentException("Unknown option: " + arg);
                }
            }
            return options;
        }

        private static String need(String[] args, int index, String flag) {
            if (index >= args.length) {
                throw new IllegalArgumentException(flag + " needs a value");
            }
            return args[index];
        }

        private static int quality(String name) {
            return switch (name) {
                case "low", "1" -> 0;
                case "medium", "2" -> 1;
                case "high", "3" -> 2;
                default -> throw new IllegalArgumentException("quality is low, medium, or high");
            };
        }
    }
}
