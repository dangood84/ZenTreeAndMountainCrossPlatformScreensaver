package zen;

import java.util.Random;
import java.util.function.BooleanSupplier;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;

/**
 * A synthesized Eurasian blackbird song. Real blackbirds sing a short fluty phrase, then stay quiet
 * for a few seconds, then sing a different phrase. This does the same with pure tones: no sound file,
 * so the same bytecode is the soundtrack on macOS, Windows, and a Raspberry Pi desktop.
 *
 * <p>Headless runs ({@code --bench}, {@code --snapshot}, {@code --gallery}) never call {@link #start}.
 */
final class Birdsong {
    private static final int RATE = 22050;
    private static final float AMP = 0.22f;

    private final Random rng = new Random();
    private volatile boolean running;
    private BooleanSupplier paused = () -> false;
    private Thread thread;

    /** Opens the speakers and starts calling. {@code paused} is the same flag Space toggles. */
    void start(BooleanSupplier paused) {
        this.paused = paused;
        running = true;
        thread = new Thread(this::loop, "zen-blackbird");
        thread.setDaemon(true);
        thread.start();
    }

    void stop() {
        running = false;
        if (thread != null) {
            thread.interrupt();
        }
    }

    private void loop() {
        AudioFormat format = new AudioFormat(RATE, 16, 1, true, false);
        try (SourceDataLine line = AudioSystem.getSourceDataLine(format)) {
            line.open(format, RATE * 2);
            line.start();
            System.out.println("Blackbird calling. Space pauses the picture and the song.");
            while (running) {
                waitIfPaused(line);
                if (!running) {
                    break;
                }
                phrase(line);
                rest(line, 2.6f + rng.nextFloat() * 3.4f);
            }
            line.drain();
        } catch (LineUnavailableException ex) {
            System.out.println("No sound output, so the blackbird stays quiet.");
        }
    }

    /** One song phrase: a few clear notes, a quicker flourish, then a long falling whistle. */
    private void phrase(SourceDataLine line) {
        float root = 1700f + rng.nextFloat() * 500f;
        int notes = 2 + rng.nextInt(3);
        float hz = root;
        for (int i = 0; i < notes; i++) {
            float next = hz * (0.92f + rng.nextFloat() * 0.18f);
            tone(line, hz, next, 0.16f + rng.nextFloat() * 0.14f);
            gap(line, 0.04f + rng.nextFloat() * 0.05f);
            hz = next;
        }
        int flourish = 3 + rng.nextInt(4);
        for (int i = 0; i < flourish; i++) {
            float jump = root * (0.85f + rng.nextFloat() * 0.7f);
            tone(line, jump, jump * (0.97f + rng.nextFloat() * 0.08f), 0.07f + rng.nextFloat() * 0.05f);
            gap(line, 0.02f + rng.nextFloat() * 0.03f);
        }
        float tail = root * (1.05f + rng.nextFloat() * 0.25f);
        tone(line, tail, tail * 0.72f, 0.38f + rng.nextFloat() * 0.22f);
    }

    /** A whistled note that may slide from {@code hz0} to {@code hz1}. The envelope starts and ends at silence, so notes do not click. */
    private void tone(SourceDataLine line, float hz0, float hz1, float seconds) {
        if (!running || paused.getAsBoolean()) {
            return;
        }
        int n = Math.max(1, (int) (RATE * seconds));
        byte[] pcm = new byte[n * 2];
        double phase = 0.0;
        float vibrato = 4.5f + rng.nextFloat() * 2f;
        for (int i = 0; i < n; i++) {
            float u = i / (float) n;
            float hz = hz0 + (hz1 - hz0) * u;
            hz *= 1f + 0.012f * (float) Math.sin(2 * Math.PI * vibrato * i / RATE);
            phase += 2 * Math.PI * hz / RATE;
            float env = envelope(i, n);
            float s = (float) Math.sin(phase);
            s += 0.22f * (float) Math.sin(phase * 2);
            int sample = Math.round(s * env * AMP * 32767f);
            pcm[i * 2] = (byte) sample;
            pcm[i * 2 + 1] = (byte) (sample >> 8);
        }
        line.write(pcm, 0, pcm.length);
    }

    private static float envelope(int i, int n) {
        int attack = Math.min(n / 3, (int) (RATE * 0.018f));
        int release = Math.min(n / 2, (int) (RATE * 0.045f));
        if (i < attack) {
            return i / (float) Math.max(1, attack);
        }
        if (i > n - release) {
            return (n - i) / (float) Math.max(1, release);
        }
        return 1f;
    }

    private void gap(SourceDataLine line, float seconds) {
        rest(line, seconds);
    }

    private void rest(SourceDataLine line, float seconds) {
        int n = Math.max(1, (int) (RATE * seconds));
        byte[] pcm = new byte[Math.min(n, RATE / 5) * 2];
        int left = n;
        while (left > 0 && running && !paused.getAsBoolean()) {
            int chunk = Math.min(left, pcm.length / 2);
            line.write(pcm, 0, chunk * 2);
            left -= chunk;
        }
    }

    private void waitIfPaused(SourceDataLine line) {
        byte[] quiet = new byte[(RATE / 10) * 2];
        while (running && paused.getAsBoolean()) {
            line.write(quiet, 0, quiet.length);
        }
    }
}
