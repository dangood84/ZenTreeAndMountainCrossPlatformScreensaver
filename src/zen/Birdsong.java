package zen;

import java.io.IOException;
import java.nio.file.Path;
import java.util.function.BooleanSupplier;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.sound.sampled.FloatControl;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.UnsupportedAudioFileException;

/**
 * Plays the recorded blackbird in {@code sounds/blackbird.wav} while the window is open.
 * The file is a real song phrase. This class only opens it, plays it, then waits, then plays it again.
 * Headless runs ({@code --bench}, {@code --snapshot}, {@code --gallery}) never call {@link #start}.
 */
final class Birdsong {
    private static final Path FILE = Path.of("sounds", "blackbird.wav");

    private volatile boolean running;
    private BooleanSupplier paused = () -> false;
    private Thread thread;
    private Clip clip;

    /** Opens the recording and starts calling. {@code paused} is the same flag Space toggles. */
    void start(BooleanSupplier paused) {
        this.paused = paused;
        running = true;
        thread = new Thread(this::loop, "zen-blackbird");
        thread.setDaemon(true);
        thread.start();
    }

    void stop() {
        running = false;
        Thread worker = thread;
        if (worker != null) {
            worker.interrupt();
        }
        closeClip();
    }

    private void loop() {
        if (!openClip()) {
            return;
        }
        System.out.println("Blackbird recording on. Space pauses the picture and the song.");
        try {
            while (running) {
                playPhrase();
                rest(4_000);
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } finally {
            closeClip();
        }
    }

    /** The whole change from the synthesized branch: read a WAV off disk into a Clip. */
    private boolean openClip() {
        try (AudioInputStream in = AudioSystem.getAudioInputStream(FILE.toFile())) {
            Clip opened = AudioSystem.getClip();
            opened.open(in);
            if (opened.isControlSupported(FloatControl.Type.MASTER_GAIN)) {
                FloatControl gain = (FloatControl) opened.getControl(FloatControl.Type.MASTER_GAIN);
                float quieter = Math.max(gain.getMinimum(), -8f);
                gain.setValue(quieter);
            }
            clip = opened;
            return true;
        } catch (UnsupportedAudioFileException | IOException | LineUnavailableException ex) {
            System.out.println("No blackbird recording (" + FILE + "), so the picture stays quiet.");
            return false;
        }
    }

    private void playPhrase() throws InterruptedException {
        Clip line = clip;
        if (line == null) {
            return;
        }
        line.setFramePosition(0);
        line.start();
        while (running && line.getFramePosition() < line.getFrameLength()) {
            if (paused.getAsBoolean()) {
                line.stop();
                while (running && paused.getAsBoolean()) {
                    Thread.sleep(50);
                }
                if (running) {
                    line.start();
                }
            } else {
                Thread.sleep(40);
            }
        }
        line.stop();
    }

    /** The recording is one phrase. The wait is the gap a blackbird leaves before the next one. */
    private void rest(int millis) throws InterruptedException {
        int left = millis;
        while (running && left > 0) {
            if (paused.getAsBoolean()) {
                Thread.sleep(50);
                continue;
            }
            Thread.sleep(50);
            left -= 50;
        }
    }

    private void closeClip() {
        Clip line = clip;
        clip = null;
        if (line != null) {
            line.close();
        }
    }
}
