package zen;

import java.util.Random;

/**
 * One tree in front of a mountain, and a camera that climbs the trunk
 * before the view opens onto the ridge.
 *
 * <p>The camera has no roll. The sky is filled from that fact, one row at a time.
 */
final class Scene {
    static final float CYCLE_SECONDS = 110f;

    /** Fixed morning light. Points toward the sun. */
    static final float SUN_X;
    static final float SUN_Y;
    static final float SUN_Z;

    static {
        float x = 0.42f, y = 0.74f, z = 0.53f;
        float len = (float) Math.sqrt(x * x + y * y + z * z);
        SUN_X = x / len;
        SUN_Y = y / len;
        SUN_Z = z / len;
    }

    final Mesh world = new Mesh();

    private final Noise noise = new Noise(27L);
    private final Random rng = new Random(11L);
    private final float[] deflect = new float[3];

    private static final float TRUNK_H = 24f;
    private static final float BASE_R = 1.22f;
    private static final float TOP_R = 0.40f;
    private static final float FLARE_H = 4.6f;

    // Journey states, as a fraction of the 110 s loop. Eye, then look-at, then vertical field of view.
    // 0.00 roots on the ground. 0.22–0.60 climb the trunk, staying outside the crown.
    // 0.76 the view opens. 0.94–1.00 the camera rests on the mountain, then the mist fade hides the cut back to the roots.
    private static final Keyframe[] KEYS = {
            new Keyframe(0.00f, 7.2f, 1.55f, 13.6f, 0.3f, 2.4f, 0.6f, 42f),
            new Keyframe(0.08f, 7.0f, 2.1f, 13.2f, 0.2f, 4.6f, 0.35f, 40f),
            new Keyframe(0.22f, 6.2f, 7.2f, 11.6f, 0.1f, 13.0f, 0.1f, 38f),
            new Keyframe(0.42f, 5.6f, 13.8f, 10.8f, 0.0f, 20.5f, -0.4f, 38f),
            new Keyframe(0.60f, 7.6f, 16.8f, 13.0f, -0.15f, 24.8f, -1.6f, 40f),
            new Keyframe(0.76f, 5.5f, 31.0f, 16.0f, -2.0f, 30.0f, -18f, 46f),
            new Keyframe(0.88f, 1.5f, 40.0f, 12.0f, -14f, 50f, -110f, 50f),
            new Keyframe(0.94f, -1.5f, 44.0f, 2.0f, -18f, 56f, -130f, 52f),
            new Keyframe(1.00f, -1.5f, 44.0f, 2.0f, -18f, 56f, -130f, 52f),
    };

    Scene() {
        // Built once. Later frames only move the camera and sway the leaves.
        buildGround();
        buildMountain();
        buildRoots();
        buildTrunk();
        buildCrown();
        world.finish();
    }

    int triangleCount() {
        return world.iCount / 3;
    }

    /** 0 is full mist, 1 is the picture. The first and last ~3 s of the loop fade, so the cut from mountain back to roots is hidden. */
    static float fade(float seconds) {
        float u = fraction(seconds);
        if (u < 0.028f) {
            return smooth(u / 0.028f);
        }
        if (u > 0.972f) {
            return smooth((1f - u) / 0.028f);
        }
        return 1f;
    }

    void camera(float seconds, Cam cam) {
        // u wraps every CYCLE_SECONDS. The segment between two keyframes is eased, not linear.
        float u = fraction(seconds);
        int i = 0;
        while (i < KEYS.length - 2 && u > KEYS[i + 1].t) {
            i++;
        }
        Keyframe a = KEYS[i];
        Keyframe b = KEYS[i + 1];
        float span = b.t - a.t;
        float f = span <= 1e-5f ? 0f : smooth((u - a.t) / span);
        float ex = lerp(a.ex, b.ex, f);
        float ey = lerp(a.ey, b.ey, f);
        float ez = lerp(a.ez, b.ez, f);
        float lx = lerp(a.lx, b.lx, f);
        float ly = lerp(a.ly, b.ly, f);
        float lz = lerp(a.lz, b.lz, f);
        look(cam, ex, ey, ez, lx, ly, lz);
        cam.fovY = (float) Math.toRadians(lerp(a.fov, b.fov, f));
    }

    private static float fraction(float seconds) {
        float u = seconds % CYCLE_SECONDS;
        if (u < 0f) {
            u += CYCLE_SECONDS;
        }
        return u / CYCLE_SECONDS;
    }

    private static void look(Cam cam, float ex, float ey, float ez, float lx, float ly, float lz) {
        float fx = lx - ex;
        float fy = ly - ey;
        float fz = lz - ez;
        float fl = (float) Math.sqrt(fx * fx + fy * fy + fz * fz);
        fx /= fl;
        fy /= fl;
        fz /= fl;

        // right = forward × worldUp, kept horizontal so the horizon stays level.
        float rx = -fz;
        float rz = fx;
        float rl = (float) Math.sqrt(rx * rx + rz * rz);
        if (rl < 1e-4f) {
            rx = 1f;
            rz = 0f;
        } else {
            rx /= rl;
            rz /= rl;
        }
        // up = right × forward, which keeps the picture upright.
        float ux = -rz * fy;
        float uy = rz * fx - rx * fz;
        float uz = rx * fy;
        float ul = (float) Math.sqrt(ux * ux + uy * uy + uz * uz);
        ux /= ul;
        uy /= ul;
        uz /= ul;

        cam.eyeX = ex;
        cam.eyeY = ey;
        cam.eyeZ = ez;
        cam.fwdX = fx;
        cam.fwdY = fy;
        cam.fwdZ = fz;
        cam.rightX = rx;
        cam.rightY = 0f;
        cam.rightZ = rz;
        cam.upX = ux;
        cam.upY = uy;
        cam.upZ = uz;
    }

    private void buildGround() {
        int cols = 48;
        int rows = 36;
        float x0 = -210f, x1 = 210f, z0 = -260f, z1 = 40f;
        float[][] h = new float[rows][cols];
        int[][] id = new int[rows][cols];
        for (int r = 0; r < rows; r++) {
            float z = lerp(z0, z1, r / (float) (rows - 1));
            for (int c = 0; c < cols; c++) {
                float x = lerp(x0, x1, c / (float) (cols - 1));
                float n = noise.fbm(x * 0.09f, z * 0.09f, 3);
                h[r][c] = n * 0.18f;
            }
        }
        float cellX = (x1 - x0) / (cols - 1);
        float cellZ = (z1 - z0) / (rows - 1);
        for (int r = 0; r < rows; r++) {
            float z = lerp(z0, z1, r / (float) (rows - 1));
            for (int c = 0; c < cols; c++) {
                float x = lerp(x0, x1, c / (float) (cols - 1));
                float[] nrm = normal(h, r, c, cellX, cellZ);
                float dist = (float) Math.sqrt(x * x + z * z);
                float soil = 1f - smooth(clamp((dist - 1.5f) / 7f, 0f, 1f));
                float nse = 0.5f + 0.35f * noise.fbm(x * 0.15f, z * 0.15f, 2);
                float cr = lerp(srgb(0.36f), srgb(0.30f), soil);
                float cg = lerp(srgb(0.46f), srgb(0.26f), soil);
                float cb = lerp(srgb(0.24f), srgb(0.14f), soil);
                float moss = clamp(noise.perlin(x * 0.35f, z * 0.35f), 0f, 1f);
                if (dist < 14f && moss > 0.15f) {
                    float m = (moss - 0.15f) * 0.7f * (1f - dist / 14f);
                    cr = lerp(cr, srgb(0.22f), m);
                    cg = lerp(cg, srgb(0.32f), m);
                    cb = lerp(cb, srgb(0.16f), m);
                }
                float shade = clamp(0.82f + 0.18f * nse, 0.7f, 1.05f);
                float contact = lerp(0.62f, 1f, smooth(clamp(dist / 5.5f, 0f, 1f)));
                id[r][c] = world.vert(x, h[r][c], z, nrm[0], nrm[1], nrm[2],
                        cr * shade * contact, cg * shade * contact, cb * shade * contact,
                        4f, 0.01f, 0f);
            }
        }
        quilt(id, rows, cols);
    }

    private void buildMountain() {
        int cols = 76;
        int rows = 54;
        float x0 = -168f, x1 = 168f, z0 = -230f, z1 = -16f;
        float[][] h = new float[rows][cols];
        int[][] id = new int[rows][cols];
        for (int r = 0; r < rows; r++) {
            float z = lerp(z0, z1, r / (float) (rows - 1));
            for (int c = 0; c < cols; c++) {
                float x = lerp(x0, x1, c / (float) (cols - 1));
                h[r][c] = mountainHeight(x, z);
            }
        }
        float cellX = (x1 - x0) / (cols - 1);
        float cellZ = (z1 - z0) / (rows - 1);
        for (int r = 0; r < rows; r++) {
            float z = lerp(z0, z1, r / (float) (rows - 1));
            for (int c = 0; c < cols; c++) {
                float x = lerp(x0, x1, c / (float) (cols - 1));
                float height = h[r][c];
                float[] nrm = normal(h, r, c, cellX, cellZ);
                float slope = 1f - nrm[1];
                float snow = smooth(clamp((height - 54f) / 18f, 0f, 1f))
                        * (1f - smooth(clamp((slope - 0.35f) / 0.45f, 0f, 1f)));
                float rock = smooth(clamp((slope - 0.22f) / 0.40f, 0f, 1f));
                rock = clamp(rock + smooth(clamp((height - 36f) / 22f, 0f, 1f)) * 0.55f, 0f, 1f);
                rock *= (1f - snow);
                float grass = clamp(1f - rock - snow, 0f, 1f);
                float sum = grass + rock + snow;
                if (sum < 1e-4f) {
                    grass = 1f;
                    sum = 1f;
                }
                grass /= sum;
                rock /= sum;
                snow /= sum;
                float cr = grass * srgb(0.36f) + rock * srgb(0.48f) + snow * srgb(0.90f);
                float cg = grass * srgb(0.46f) + rock * srgb(0.44f) + snow * srgb(0.91f);
                float cb = grass * srgb(0.24f) + rock * srgb(0.38f) + snow * srgb(0.92f);
                float strata = 0.90f + 0.10f * (float) Math.sin(height * 0.55f + x * 0.03f);
                float mottling = 0.86f + 0.14f * (0.5f + 0.35f * noise.fbm(x * 0.04f, z * 0.04f, 3));
                int cl = Math.max(0, c - 1);
                int crg = Math.min(cols - 1, c + 1);
                int rd = Math.max(0, r - 1);
                int ru = Math.min(rows - 1, r + 1);
                float neighbors = (h[r][cl] + h[r][crg] + h[rd][c] + h[ru][c]) * 0.25f;
                float cavity = neighbors - height;
                float ao = clamp(1f - cavity * 0.045f, 0.55f, 1f);
                ao *= 0.78f + 0.22f * nrm[1];
                float tone = strata * mottling * ao;
                id[r][c] = world.vert(x, height, z, nrm[0], nrm[1], nrm[2],
                        cr * tone, cg * tone, cb * tone,
                        snow > 0.5f ? 18f : 10f, snow > 0.5f ? 0.10f : 0.03f, 0f);
            }
        }
        quilt(id, rows, cols);
    }

    private float mountainHeight(float x, float z) {
        float dx = (x + 14f) / 34f;
        float dz = (z + 128f) / 30f;
        float peak = (float) Math.exp(-(dx * dx + dz * dz));
        float sx = (x - 62f) / 70f;
        float sz = (z + 168f) / 52f;
        float shoulder = (float) Math.exp(-(sx * sx + sz * sz));
        float apron = (float) Math.exp(-(x * x) / (150f * 150f) - ((z + 90f) * (z + 90f)) / (80f * 80f));
        float broad = noise.fbm(x * 0.014f + 3.2f, z * 0.014f, 5);
        float detail = noise.fbm(x * 0.032f, z * 0.032f + 1.7f, 3);
        float ridge = 1f - Math.abs(noise.fbm(x * 0.011f + 9f, z * 0.011f, 4));
        float h = peak * 86f + shoulder * 40f + apron * 16f;
        h += broad * 9f + detail * 2.4f + ridge * ridge * 12f;
        float front = smooth(clamp((z - (-90f)) / (-20f - (-90f)), 0f, 1f));
        h *= (1f - front * 0.94f);
        return Math.max(0f, h);
    }

    private void buildRoots() {
        for (int i = 0; i < 6; i++) {
            float ang = new float[]{0.15f, 1.05f, 2.25f, 3.15f, 4.25f, 5.35f}[i];
            float len = 6.2f + (i % 3) * 1.3f;
            float rad = 0.62f - i * 0.04f;
            float y0 = 1.15f;
            float start = radiusAt(y0) * 0.72f;
            float x0 = (float) Math.cos(ang) * start;
            float z0 = (float) Math.sin(ang) * start;
            float x1 = (float) Math.cos(ang) * (start + len);
            float z1 = (float) Math.sin(ang) * (start + len);
            addCylinder(x0, y0, z0, x1, 0.05, z1, rad, rad * 0.28f, 8, 6, 0f, true, false);
            if (i % 2 == 0) {
                float ang2 = ang + 0.42f;
                float len2 = len * 0.48f;
                addCylinder(x1, 0.12, z1,
                        x1 + (float) Math.cos(ang2) * len2, -0.02,
                        z1 + (float) Math.sin(ang2) * len2,
                        rad * 0.34f, rad * 0.14f, 6, 3, 0f, true, false);
            }
        }
    }

    private void buildTrunk() {
        addCylinder(0, 0, 0, 0, TRUNK_H, 0, BASE_R, TOP_R, 40, 28, 0f, true, true);
    }

    private void buildCrown() {
        float[][] arms = {
                {11.2f, 0.45f, 0.40f, 8.2f, 0.36f},
                {12.6f, 2.15f, 0.36f, 8.6f, 0.34f},
                {14.4f, 3.85f, 0.46f, 7.6f, 0.32f},
                {15.8f, 5.35f, 0.38f, 7.2f, 0.30f},
                {18.2f, 1.15f, 0.55f, 6.4f, 0.27f},
                {20.0f, 4.15f, 0.60f, 5.8f, 0.24f},
                {22.2f, 2.55f, 0.74f, 5.0f, 0.21f},
                {23.6f, 0.15f, 0.86f, 4.4f, 0.18f},
        };
        for (float[] arm : arms) {
            float y = arm[0];
            float ang = arm[1];
            float lift = arm[2];
            float len = arm[3];
            float rad = arm[4];
            float horiz = (float) Math.sqrt(Math.max(0f, 1f - lift * lift));
            float dx = (float) Math.cos(ang) * horiz;
            float dy = lift;
            float dz = (float) Math.sin(ang) * horiz;
            float r = radiusAt(y) * 0.45f;
            grow((float) Math.cos(ang) * r, y, (float) Math.sin(ang) * r, dx, dy, dz, len, rad, 2);
        }
        grow(0f, TRUNK_H, 0f, 0.06f, 0.97f, 0.12f, 5.2f, TOP_R * 0.85f, 2);
    }

    // depth 2 is a limb, 1 a branch, 0 a twig with leaves. Each step splits in two and gets thinner.
    private void grow(float x, float y, float z, float dx, float dy, float dz,
                      float len, float rad, int depth) {
        float ex = x + dx * len;
        float ey = y + dy * len;
        float ez = z + dz * len;
        int sides = depth >= 2 ? 8 : 5;
        int segs = depth >= 2 ? 4 : 2;
        float sway = depth == 0 ? 0.16f : depth == 1 ? 0.06f : 0.02f;
        addCylinder(x, y, z, ex, ey, ez, rad, Math.max(0.03f, rad * 0.62f), sides, segs, sway, true, false);
        if (depth <= 2) {
            int clumps = depth == 0 ? 3 : depth == 1 ? 2 : 1;
            for (int i = 1; i <= clumps; i++) {
                float t = i / (float) (clumps + 0.2f);
                float puff = depth == 0 ? 0.85f : depth == 1 ? 1.05f : 1.25f;
                addFoliage(lerp(x, ex, t), lerp(y, ey, t), lerp(z, ez, t), puff);
            }
        }
        if (depth == 0) {
            return;
        }
        int children = 2;
        for (int i = 0; i < children; i++) {
            float yaw = (i - 0.5f) * 0.85f + (rng.nextFloat() - 0.5f) * 0.35f;
            float pitch = (depth >= 2 ? 0.25f : 0.02f) + rng.nextFloat() * 0.55f;
            deflect(dx, dy, dz, yaw, pitch, deflect);
            deflect[1] += depth >= 2 ? 0.16f : 0.04f;
            float l = (float) Math.sqrt(deflect[0] * deflect[0] + deflect[1] * deflect[1] + deflect[2] * deflect[2]);
            deflect[0] /= l;
            deflect[1] /= l;
            deflect[2] /= l;
            float nlen = len * (0.62f + rng.nextFloat() * 0.1f);
            float nrad = rad * (0.56f + rng.nextFloat() * 0.06f);
            if (nrad < 0.045f) {
                addFoliage(ex, ey, ez, 0.8f);
                continue;
            }
            grow(ex, ey, ez, deflect[0], deflect[1], deflect[2], nlen, nrad, depth - 1);
        }
    }

    private void addFoliage(float x, float y, float z, float scale) {
        int cards = 4;
        for (int i = 0; i < cards; i++) {
            float spin = i * 1.57f + rng.nextFloat() * 0.4f;
            float lift = i == 3 ? 0.85f : 0.15f + rng.nextFloat() * 0.35f;
            float nnx = (float) Math.cos(spin) * (1f - lift);
            float nny = lift;
            float nnz = (float) Math.sin(spin) * (1f - lift);
            float nl = (float) Math.sqrt(nnx * nnx + nny * nny + nnz * nnz);
            nnx /= nl;
            nny /= nl;
            nnz /= nl;
            float cx = x + nnx * scale * 0.15f;
            float cy = y + (rng.nextFloat() - 0.45f) * scale * 0.35f;
            float cz = z + nnz * scale * 0.15f;
            float sx = -nnz;
            float sy = 0f;
            float sz = nnx;
            float sl = (float) Math.sqrt(sx * sx + sz * sz);
            if (sl < 1e-3f) {
                sx = 1f;
                sy = 0f;
                sz = 0f;
            } else {
                sx /= sl;
                sz /= sl;
            }
            float ux = sy * nnz - sz * nny;
            float uy = sz * nnx - sx * nnz;
            float uz = sx * nny - sy * nnx;
            float size = scale * (0.55f + rng.nextFloat() * 0.35f);
            float aspect = size * (0.42f + rng.nextFloat() * 0.2f);
            int tone = rng.nextInt(4);
            float cr;
            float cg;
            float cb;
            switch (tone) {
                case 0 -> {
                    cr = srgb(0.27f);
                    cg = srgb(0.40f);
                    cb = srgb(0.16f);
                }
                case 1 -> {
                    cr = srgb(0.20f);
                    cg = srgb(0.33f);
                    cb = srgb(0.13f);
                }
                case 2 -> {
                    cr = srgb(0.38f);
                    cg = srgb(0.44f);
                    cb = srgb(0.17f);
                }
                default -> {
                    cr = srgb(0.16f);
                    cg = srgb(0.28f);
                    cb = srgb(0.12f);
                }
            }
            addCard(cx, cy, cz, sx, sy, sz, ux, uy, uz, nnx, nny, nnz, size, aspect, cr, cg, cb);
        }
    }

    private void addCard(float cx, float cy, float cz,
                         float sx, float sy, float sz,
                         float ux, float uy, float uz,
                         float nnx, float nny, float nnz,
                         float size, float aspect,
                         float cr, float cg, float cb) {
        float x0 = cx - sx * size - ux * aspect;
        float y0 = cy - sy * size - uy * aspect;
        float z0 = cz - sz * size - uz * aspect;
        float x1 = cx + sx * size - ux * aspect;
        float y1 = cy + sy * size - uy * aspect;
        float z1 = cz + sz * size - uz * aspect;
        float x2 = cx + sx * size + ux * aspect;
        float y2 = cy + sy * size + uy * aspect;
        float z2 = cz + sz * size + uz * aspect;
        float x3 = cx - sx * size + ux * aspect;
        float y3 = cy - sy * size + uy * aspect;
        float z3 = cz - sz * size + uz * aspect;
        int a = world.vert(x0, y0, z0, nnx, nny, nnz, cr, cg, cb, 26f, 0.14f, 0.26f);
        int b = world.vert(x1, y1, z1, nnx, nny, nnz, cr, cg, cb, 26f, 0.14f, 0.26f);
        int c = world.vert(x2, y2, z2, nnx, nny, nnz, cr, cg, cb, 26f, 0.14f, 0.26f);
        int d = world.vert(x3, y3, z3, nnx, nny, nnz, cr, cg, cb, 26f, 0.14f, 0.26f);
        world.tri(a, b, c);
        world.tri(a, c, d);
        int e = world.vert(x0, y0, z0, -nnx, -nny, -nnz, cr, cg, cb, 26f, 0.08f, 0.26f);
        int f = world.vert(x1, y1, z1, -nnx, -nny, -nnz, cr, cg, cb, 26f, 0.08f, 0.26f);
        int g = world.vert(x2, y2, z2, -nnx, -nny, -nnz, cr, cg, cb, 26f, 0.08f, 0.26f);
        int h = world.vert(x3, y3, z3, -nnx, -nny, -nnz, cr, cg, cb, 26f, 0.08f, 0.26f);
        world.tri(e, g, f);
        world.tri(e, h, g);
    }

    private void addCylinder(double x0d, double y0d, double z0d, double x1d, double y1d, double z1d,
                             float r0, float r1, int sides, int segs, float swayAmp,
                             boolean bark, boolean trunk) {
        float x0 = (float) x0d, y0 = (float) y0d, z0 = (float) z0d;
        float x1 = (float) x1d, y1 = (float) y1d, z1 = (float) z1d;
        float dx = x1 - x0, dy = y1 - y0, dz = z1 - z0;
        float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 1e-4f) {
            return;
        }
        dx /= len;
        dy /= len;
        dz /= len;
        float hx = Math.abs(dy) < 0.92f ? 0f : 1f;
        float hy = Math.abs(dy) < 0.92f ? 1f : 0f;
        float hz = 0f;
        float tx = dy * hz - dz * hy;
        float ty = dz * hx - dx * hz;
        float tz = dx * hy - dy * hx;
        float tl = (float) Math.sqrt(tx * tx + ty * ty + tz * tz);
        tx /= tl;
        ty /= tl;
        tz /= tl;
        float bx = ty * dz - tz * dy;
        float by = tz * dx - tx * dz;
        float bz = tx * dy - ty * dx;

        int[][] ring = new int[segs + 1][sides];
        for (int i = 0; i <= segs; i++) {
            float t = i / (float) segs;
            float px = x0 + (x1 - x0) * t;
            float py = y0 + (y1 - y0) * t;
            float pz = z0 + (z1 - z0) * t;
            float rad = trunk ? radiusAt(py) : r0 + (r1 - r0) * t;
            for (int s = 0; s < sides; s++) {
                float ang = (float) (s * Math.PI * 2.0 / sides);
                float c = (float) Math.cos(ang);
                float sn = (float) Math.sin(ang);
                float nnx = tx * c + bx * sn;
                float nny = ty * c + by * sn;
                float nnz = tz * c + bz * sn;
                float g = 0.5f + 0.5f * (float) Math.sin(ang * 8.0 + py * 1.6f);
                float fine = 0.5f + 0.5f * (float) Math.sin(ang * 17.0 - py * 0.7f);
                float rr = rad * (0.86f + 0.14f * g) * (0.97f + 0.03f * fine);
                if (trunk) {
                    rr *= 1f + 0.07f * (float) Math.sin(ang * 2.0 + 0.4f);
                }
                float shade = (0.38f + 0.62f * g) * (0.86f + 0.14f * fine);
                float nse = 0.5f + 0.35f * noise.fbm(px * 0.55f + pz * 0.4f, py * 0.11f, 3);
                shade *= 0.80f + 0.20f * clamp(nse, 0f, 1f);
                float cr = srgb(0.40f) * shade;
                float cg = srgb(0.27f) * shade;
                float cb = srgb(0.16f) * shade;
                if (bark && py < 6.5f) {
                    float mossN = 0.5f + 0.5f * noise.perlin(px * 1.6f + 2f, pz * 1.6f);
                    float m = Math.max(0f, (mossN - 0.48f) * 2.4f) * (1f - py / 6.5f);
                    if (m > 0f) {
                        m = Math.min(1f, m);
                        cr = lerp(cr, srgb(0.20f), m);
                        cg = lerp(cg, srgb(0.30f), m);
                        cb = lerp(cb, srgb(0.14f), m);
                    }
                }
                if (t < 0.14f && !trunk) {
                    float k = 1f - t / 0.14f;
                    float dark = 1f - 0.12f * k;
                    cr *= dark;
                    cg *= dark;
                    cb *= dark;
                }
                float sw = swayAmp * (0.30f + 0.70f * t);
                ring[i][s] = world.vert(px + nnx * rr, py + nny * rr, pz + nnz * rr,
                        nnx, nny, nnz, cr, cg, cb, 8f, 0.045f, sw);
            }
        }
        for (int i = 0; i < segs; i++) {
            float ax = x0 + (x1 - x0) * (i + 0.5f) / segs;
            float ay = y0 + (y1 - y0) * (i + 0.5f) / segs;
            float az = z0 + (z1 - z0) * (i + 0.5f) / segs;
            for (int s = 0; s < sides; s++) {
                int s2 = (s + 1) % sides;
                world.triOutward(ring[i][s], ring[i][s2], ring[i + 1][s], ax, ay, az);
                world.triOutward(ring[i][s2], ring[i + 1][s2], ring[i + 1][s], ax, ay, az);
            }
        }
    }

    private float radiusAt(float y) {
        float t = clamp(y / TRUNK_H, 0f, 1f);
        float r = lerp(BASE_R, TOP_R, t);
        if (y < FLARE_H) {
            float f = 1f - y / FLARE_H;
            r *= 1f + f * f * 1.25f;
        }
        return r;
    }

    private void deflect(float dx, float dy, float dz, float yaw, float pitch, float[] out) {
        float sx = -dz;
        float sy = 0f;
        float sz = dx;
        float sl = (float) Math.sqrt(sx * sx + sz * sz);
        if (sl < 1e-3f) {
            sx = 1f;
            sz = 0f;
        } else {
            sx /= sl;
            sz /= sl;
        }
        float ux = sy * dz - sz * dy;
        float uy = sz * dx - sx * dz;
        float uz = sx * dy - sy * dx;
        float cp = (float) Math.cos(pitch);
        float sp = (float) Math.sin(pitch);
        float cy = (float) Math.cos(yaw);
        float syaw = (float) Math.sin(yaw);
        out[0] = dx * cp + (sx * cy + ux * syaw) * sp;
        out[1] = dy * cp + (sy * cy + uy * syaw) * sp;
        out[2] = dz * cp + (sz * cy + uz * syaw) * sp;
        float l = (float) Math.sqrt(out[0] * out[0] + out[1] * out[1] + out[2] * out[2]);
        out[0] /= l;
        out[1] /= l;
        out[2] /= l;
    }

    private static float[] normal(float[][] h, int r, int c, float cellX, float cellZ) {
        int rows = h.length;
        int cols = h[0].length;
        int cl = Math.max(0, c - 1);
        int cr = Math.min(cols - 1, c + 1);
        int rd = Math.max(0, r - 1);
        int ru = Math.min(rows - 1, r + 1);
        float dhdx = (h[r][cr] - h[r][cl]) / ((cr - cl) * cellX);
        float dhdz = (h[ru][c] - h[rd][c]) / ((ru - rd) * cellZ);
        float nx = -dhdx;
        float ny = 1f;
        float nz = -dhdz;
        float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        return new float[]{nx / len, ny / len, nz / len};
    }

    /** Two triangles per cell, facing upward. */
    private void quilt(int[][] id, int rows, int cols) {
        for (int r = 0; r < rows - 1; r++) {
            for (int c = 0; c < cols - 1; c++) {
                int i00 = id[r][c];
                int i10 = id[r][c + 1];
                int i01 = id[r + 1][c];
                int i11 = id[r + 1][c + 1];
                world.tri(i00, i01, i10);
                world.tri(i10, i01, i11);
            }
        }
    }

    static float srgb(float channel) {
        if (channel <= 0.04045f) {
            return channel / 12.92f;
        }
        return (float) Math.pow((channel + 0.055f) / 1.055f, 2.4);
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static float smooth(float t) {
        return t * t * (3f - 2f * t);
    }

    record Keyframe(float t, float ex, float ey, float ez, float lx, float ly, float lz, float fov) {
    }
}
