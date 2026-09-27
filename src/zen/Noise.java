package zen;

import java.util.Random;

/** Seeded value noise. Used while the world is built, not during the frame loop. */
final class Noise {
    private final int[] perm = new int[512];

    Noise(long seed) {
        int[] source = new int[256];
        for (int i = 0; i < 256; i++) {
            source[i] = i;
        }
        Random random = new Random(seed);
        for (int i = 255; i > 0; i--) {
            int j = random.nextInt(i + 1);
            int tmp = source[i];
            source[i] = source[j];
            source[j] = tmp;
        }
        for (int i = 0; i < 512; i++) {
            perm[i] = source[i & 255];
        }
    }

    float perlin(float x, float y) {
        int x0 = floor(x);
        int y0 = floor(y);
        float xf = x - x0;
        float yf = y - y0;
        x0 &= 255;
        y0 &= 255;
        float u = fade(xf);
        float v = fade(yf);
        int aa = perm[perm[x0] + y0];
        int ab = perm[perm[x0] + y0 + 1];
        int ba = perm[perm[x0 + 1] + y0];
        int bb = perm[perm[x0 + 1] + y0 + 1];
        float x1 = lerp(grad(aa, xf, yf), grad(ba, xf - 1, yf), u);
        float x2 = lerp(grad(ab, xf, yf - 1), grad(bb, xf - 1, yf - 1), u);
        return lerp(x1, x2, v);
    }

    /** Roughly -1..1. */
    float fbm(float x, float y, int octaves) {
        float sum = 0f;
        float amp = 1f;
        float freq = 1f;
        float norm = 0f;
        for (int i = 0; i < octaves; i++) {
            sum += amp * perlin(x * freq, y * freq);
            norm += amp;
            amp *= 0.5f;
            freq *= 2.03f;
        }
        return sum / norm;
    }

    private static int floor(float v) {
        int i = (int) v;
        return v < i ? i - 1 : i;
    }

    private static float fade(float t) {
        return t * t * t * (t * (t * 6f - 15f) + 10f);
    }

    private static float lerp(float a, float b, float t) {
        return a + t * (b - a);
    }

    private static float grad(int hash, float x, float y) {
        return switch (hash & 7) {
            case 0 -> x + y;
            case 1 -> -x + y;
            case 2 -> x - y;
            case 3 -> -x - y;
            case 4 -> x;
            case 5 -> -x;
            case 6 -> y;
            default -> -y;
        };
    }
}
