package zen;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.Arrays;

/**
 * Software rasterizer. The sky is a horizon-correct gradient, then lit triangles
 * are drawn into a small buffer that the window scales up. That keeps a 2014
 * MacBook Pro quiet while the picture stays soft, which suits the mist.
 */
final class Renderer {
    private static final float NEAR = 0.18f;
    private static final int STRIDE = 7;
    private static final int FOG_ZMAX = 220;
    /** Front faces come out with negative screen area after the Y flip. */
    private static final boolean CULL_POSITIVE_AREA = true;

    private int width;
    private int height;
    private int[] pixels;
    private float[] depth;
    private BufferedImage image;

    private float[] camX = new float[0];
    private float[] camY = new float[0];
    private float[] camZ = new float[0];
    private float[] litR = new float[0];
    private float[] litG = new float[0];
    private float[] litB = new float[0];

    private final float[] bufA = new float[16 * STRIDE];
    private final float[] bufB = new float[16 * STRIDE];
    private final int[] srgbLut = new int[2048];
    private final float[] fogLut = new float[4096];

    private final float horizonR = Scene.srgb(0.83f);
    private final float horizonG = Scene.srgb(0.76f);
    private final float horizonB = Scene.srgb(0.66f);
    private final float zenithR = Scene.srgb(0.17f);
    private final float zenithG = Scene.srgb(0.25f);
    private final float zenithB = Scene.srgb(0.40f);
    private final float warmR = Scene.srgb(0.94f);
    private final float warmG = Scene.srgb(0.78f);
    private final float warmB = Scene.srgb(0.58f);
    private final float fogR = Scene.srgb(0.76f);
    private final float fogG = Scene.srgb(0.73f);
    private final float fogB = Scene.srgb(0.66f);

    private float halfW;
    private float halfH;
    private float focal;
    private int fogPixel;
    int trianglesDrawn;

    private final Cam cam = new Cam();

    Renderer(int width, int height) {
        for (int i = 0; i < srgbLut.length; i++) {
            srgbLut[i] = linearToSrgbByte(i / 2047f);
        }
        for (int i = 0; i < fogLut.length; i++) {
            float z = i * (FOG_ZMAX / 4095f);
            fogLut[i] = 1f - (float) Math.exp(-z * 0.0042f);
        }
        fogPixel = pack(fogR, fogG, fogB);
        resize(width, height);
    }

    BufferedImage image() {
        return image;
    }

    void resize(int width, int height) {
        this.width = width;
        this.height = height;
        image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        pixels = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
        depth = new float[width * height];
    }

    void render(Scene scene, float seconds) {
        // Sky first (no depth), then geometry overwrites nearer pixels, then the loop fade.
        scene.camera(seconds, cam);
        halfH = (float) Math.tan(cam.fovY * 0.5f);
        halfW = halfH * (width / (float) height);
        focal = (height * 0.5f) / halfH;
        trianglesDrawn = 0;

        fillSky();
        stampSun();
        Arrays.fill(depth, 0f);
        lightAndTransform(scene.world, seconds);
        drawMesh(scene.world);
        applyFade(Scene.fade(seconds));
    }

    private void lightAndTransform(Mesh mesh, float seconds) {
        worldY = mesh.py;
        int n = mesh.vCount;
        if (camX.length < n) {
            camX = new float[n];
            camY = new float[n];
            camZ = new float[n];
            litR = new float[n];
            litG = new float[n];
            litB = new float[n];
        }
        // Trunk sway is 0. Leaves and twigs carry a sway weight, so only they move.
        float phase = seconds * 0.48f;
        float ex = cam.eyeX;
        float ey = cam.eyeY;
        float ez = cam.eyeZ;
        for (int i = 0; i < n; i++) {
            float sway = (float) Math.sin(phase + mesh.py[i] * 0.17f + mesh.px[i] * 0.05f) * mesh.sway[i];
            float x = mesh.px[i] + sway;
            float y = mesh.py[i];
            float z = mesh.pz[i] + sway * 0.38f;
            float nnx = mesh.nx[i];
            float nny = mesh.ny[i];
            float nnz = mesh.nz[i];
            float hemi = nny * 0.5f + 0.5f;
            float ndl = nnx * Scene.SUN_X + nny * Scene.SUN_Y + nnz * Scene.SUN_Z;
            if (ndl < 0f) {
                ndl = 0f;
            }
            float wrap = (ndl + 0.32f) * (1f / 1.32f);
            float ambR = 0.16f + 0.40f * hemi;
            float ambG = 0.15f + 0.46f * hemi;
            float ambB = 0.11f + 0.58f * hemi;
            float r = mesh.ar[i] * (ambR + wrap * 1.05f);
            float g = mesh.ag[i] * (ambG + wrap * 0.98f);
            float b = mesh.ab[i] * (ambB + wrap * 0.82f);
            float specAmt = mesh.spec[i];
            if (specAmt > 0.001f && ndl > 0f) {
                float vx = ex - x;
                float vy = ey - y;
                float vz = ez - z;
                float vl = (float) Math.sqrt(vx * vx + vy * vy + vz * vz);
                if (vl > 1e-4f) {
                    vx /= vl;
                    vy /= vl;
                    vz /= vl;
                    float hx = Scene.SUN_X + vx;
                    float hy = Scene.SUN_Y + vy;
                    float hz = Scene.SUN_Z + vz;
                    float hl = (float) Math.sqrt(hx * hx + hy * hy + hz * hz);
                    float ndh = (nnx * hx + nny * hy + nnz * hz) / hl;
                    if (ndh > 0f) {
                        float sp = (float) Math.pow(ndh, mesh.shine[i]) * specAmt;
                        r += sp;
                        g += sp * 0.96f;
                        b += sp * 0.82f;
                    }
                }
            }
            litR[i] = r;
            litG[i] = g;
            litB[i] = b;
            float dx = x - ex;
            float dy = y - ey;
            float dz = z - ez;
            camX[i] = dx * cam.rightX + dy * cam.rightY + dz * cam.rightZ;
            camY[i] = dx * cam.upX + dy * cam.upY + dz * cam.upZ;
            camZ[i] = dx * cam.fwdX + dy * cam.fwdY + dz * cam.fwdZ;
        }
    }

    private void drawMesh(Mesh mesh) {
        int[] idx = mesh.idx;
        int n = mesh.iCount;
        for (int t = 0; t < n; t += 3) {
            submit(idx[t], idx[t + 1], idx[t + 2]);
        }
    }

    private void submit(int i0, int i1, int i2) {
        // Bit mask of which frustum planes each vertex is outside.
        // All three outside the same plane: drop. All inside: draw. Otherwise clip.
        int m0 = mask(i0);
        int m1 = mask(i1);
        int m2 = mask(i2);
        if ((m0 & m1 & m2) != 0) {
            return;
        }
        pack(bufA, 0, i0);
        pack(bufA, 1, i1);
        pack(bufA, 2, i2);
        if ((m0 | m1 | m2) == 0) {
            drawTriangle(bufA, 0, 1, 2);
            return;
        }
        int count = 3;
        float[] in = bufA;
        float[] out = bufB;
        for (int plane = 0; plane < 5; plane++) {
            int clipped = clipPlane(in, count, out, plane);
            if (clipped < 3) {
                return;
            }
            float[] swap = in;
            in = out;
            out = swap;
            count = clipped;
        }
        for (int i = 1; i < count - 1; i++) {
            drawTriangle(in, 0, i, i + 1);
        }
    }

    private int mask(int vi) {
        float x = camX[vi];
        float y = camY[vi];
        float z = camZ[vi];
        int m = 0;
        if (z < NEAR) {
            m |= 1;
        }
        if (x + z * halfW < 0f) {
            m |= 2;
        }
        if (-x + z * halfW < 0f) {
            m |= 4;
        }
        if (y + z * halfH < 0f) {
            m |= 8;
        }
        if (-y + z * halfH < 0f) {
            m |= 16;
        }
        return m;
    }

    private void pack(float[] dst, int slot, int vi) {
        int o = slot * STRIDE;
        dst[o] = camX[vi];
        dst[o + 1] = camY[vi];
        dst[o + 2] = camZ[vi];
        dst[o + 3] = litR[vi];
        dst[o + 4] = litG[vi];
        dst[o + 5] = litB[vi];
        dst[o + 6] = pyOf(vi);
    }

    private float pyOf(int vi) {
        return worldY[vi];
    }

    /** Points at the mesh height array for the frame being drawn. */
    private float[] worldY;

    private int clipPlane(float[] in, int count, float[] out, int plane) {
        int written = 0;
        for (int i = 0; i < count; i++) {
            int j = (i + 1) % count;
            float d0 = planeDist(in, i, plane);
            float d1 = planeDist(in, j, plane);
            boolean in0 = d0 >= 0f;
            boolean in1 = d1 >= 0f;
            if (in0) {
                copyVert(in, i, out, written++);
            }
            if (in0 != in1) {
                float t = d0 / (d0 - d1);
                lerpVert(in, i, j, t, out, written++);
            }
        }
        return written;
    }

    private float planeDist(float[] v, int i, int plane) {
        int o = i * STRIDE;
        float x = v[o];
        float y = v[o + 1];
        float z = v[o + 2];
        return switch (plane) {
            case 0 -> z - NEAR;
            case 1 -> x + z * halfW;
            case 2 -> -x + z * halfW;
            case 3 -> y + z * halfH;
            default -> -y + z * halfH;
        };
    }

    private static void copyVert(float[] src, int from, float[] dst, int to) {
        System.arraycopy(src, from * STRIDE, dst, to * STRIDE, STRIDE);
    }

    private static void lerpVert(float[] src, int a, int b, float t, float[] dst, int to) {
        int oa = a * STRIDE;
        int ob = b * STRIDE;
        int od = to * STRIDE;
        float u = 1f - t;
        for (int k = 0; k < STRIDE; k++) {
            dst[od + k] = src[oa + k] * u + src[ob + k] * t;
        }
    }

    private void drawTriangle(float[] poly, int ia, int ib, int ic) {
        int a = ia * STRIDE;
        int b = ib * STRIDE;
        int c = ic * STRIDE;
        float ax = poly[a];
        float ay = poly[a + 1];
        float az = poly[a + 2];
        float ar = poly[a + 3];
        float ag = poly[a + 4];
        float ab = poly[a + 5];
        float aw = poly[a + 6];
        float bx = poly[b];
        float by = poly[b + 1];
        float bz = poly[b + 2];
        float br = poly[b + 3];
        float bg = poly[b + 4];
        float bb = poly[b + 5];
        float bw = poly[b + 6];
        float cx = poly[c];
        float cy = poly[c + 1];
        float cz = poly[c + 2];
        float cr = poly[c + 3];
        float cg = poly[c + 4];
        float cb = poly[c + 5];
        float cw = poly[c + 6];
        if (az < NEAR || bz < NEAR || cz < NEAR) {
            return;
        }

        float asx = width * 0.5f + (ax / az) * focal;
        float asy = height * 0.5f - (ay / az) * focal;
        float bsx = width * 0.5f + (bx / bz) * focal;
        float bsy = height * 0.5f - (by / bz) * focal;
        float csx = width * 0.5f + (cx / cz) * focal;
        float csy = height * 0.5f - (cy / cz) * focal;
        if (!Float.isFinite(asx) || !Float.isFinite(bsx) || !Float.isFinite(csx)) {
            return;
        }

        float area = (bsx - asx) * (csy - asy) - (bsy - asy) * (csx - asx);
        // Screen Y grows downward, so a front face arrives with negative area.
        // The edge walk below wants counter-clockwise, so the two far vertices are swapped.
        boolean front = CULL_POSITIVE_AREA ? area < 0f : area > 0f;
        if (!front) {
            return;
        }
        if (Math.abs(area) < 0.75f) {
            return;
        }
        if (area < 0f) {
            float tx = bsx;
            bsx = csx;
            csx = tx;
            float ty = bsy;
            bsy = csy;
            csy = ty;
            float tz = bz;
            bz = cz;
            cz = tz;
            float tr = br;
            br = cr;
            cr = tr;
            float tg = bg;
            bg = cg;
            cg = tg;
            float tb = bb;
            bb = cb;
            cb = tb;
            float tw = bw;
            bw = cw;
            cw = tw;
            area = -area;
        }

        int minX = (int) Math.floor(Math.min(asx, Math.min(bsx, csx)));
        int maxX = (int) Math.floor(Math.max(asx, Math.max(bsx, csx)));
        int minY = (int) Math.floor(Math.min(asy, Math.min(bsy, csy)));
        int maxY = (int) Math.floor(Math.max(asy, Math.max(bsy, csy)));
        if (minX < 0) {
            minX = 0;
        }
        if (minY < 0) {
            minY = 0;
        }
        if (maxX >= width) {
            maxX = width - 1;
        }
        if (maxY >= height) {
            maxY = height - 1;
        }
        if (minX > maxX || minY > maxY) {
            return;
        }

        float invArea = 1f / area;
        float iz0 = (1f / az) * invArea;
        float iz1 = (1f / bz) * invArea;
        float iz2 = (1f / cz) * invArea;
        float r0 = ar * (1f / az) * invArea;
        float g0 = ag * (1f / az) * invArea;
        float b0 = ab * (1f / az) * invArea;
        float y0 = aw * (1f / az) * invArea;
        float r1 = br * (1f / bz) * invArea;
        float g1 = bg * (1f / bz) * invArea;
        float b1 = bb * (1f / bz) * invArea;
        float y1 = bw * (1f / bz) * invArea;
        float r2 = cr * (1f / cz) * invArea;
        float g2 = cg * (1f / cz) * invArea;
        float b2 = cb * (1f / cz) * invArea;
        float y2 = cw * (1f / cz) * invArea;

        float startX = minX + 0.5f;
        float startY = minY + 0.5f;
        // eA is the edge opposite A (through B and C), and likewise for the others.
        float e0 = (csx - bsx) * (startY - bsy) - (csy - bsy) * (startX - bsx);
        float e1 = (asx - csx) * (startY - csy) - (asy - csy) * (startX - csx);
        float e2 = (bsx - asx) * (startY - asy) - (bsy - asy) * (startX - asx);
        float d0dx = -(csy - bsy);
        float d1dx = -(asy - csy);
        float d2dx = -(bsy - asy);
        float d0dy = csx - bsx;
        float d1dy = asx - csx;
        float d2dy = bsx - asx;

        int[] pix = pixels;
        float[] dep = depth;
        int iw = width;
        float fogScale = 4095f / FOG_ZMAX;
        trianglesDrawn++;

        for (int y = minY; y <= maxY; y++) {
            float aE = e0;
            float bE = e1;
            float cE = e2;
            int row = y * iw;
            for (int x = minX; x <= maxX; x++) {
                if (aE >= 0f && bE >= 0f && cE >= 0f) {
                    float invZ = aE * iz0 + bE * iz1 + cE * iz2;
                    int pi = row + x;
                    // Depth stores 1/z. A larger value is closer to the camera.
                        if (invZ > dep[pi]) {
                        dep[pi] = invZ;
                        float viewZ = 1f / invZ;
                        float red = (aE * r0 + bE * r1 + cE * r2) * viewZ;
                        float green = (aE * g0 + bE * g1 + cE * g2) * viewZ;
                        float blue = (aE * b0 + bE * b1 + cE * b2) * viewZ;
                        float wy = (aE * y0 + bE * y1 + cE * y2) * viewZ;
                        int fi = (int) (viewZ * fogScale);
                        if (fi < 0) {
                            fi = 0;
                        } else if (fi > 4095) {
                            fi = 4095;
                        }
                        float fog = fogLut[fi];
                        float mist = (14f - wy) * (1f / 14f);
                        if (mist < 0f) {
                            mist = 0f;
                        } else if (mist > 1f) {
                            mist = 1f;
                        }
                        mist *= mist;
                        float dist = viewZ * 0.01f;
                        if (dist > 1f) {
                            dist = 1f;
                        }
                        fog += mist * dist * 0.38f * (1f - fog);
                        red += (fogR - red) * fog;
                        green += (fogG - green) * fog;
                        blue += (fogB - blue) * fog;
                        pix[pi] = pack(red, green, blue);
                    }
                }
                aE += d0dx;
                bE += d1dx;
                cE += d2dx;
            }
            e0 += d0dy;
            e1 += d1dy;
            e2 += d2dy;
        }
    }

    private void fillSky() {
        for (int y = 0; y < height; y++) {
            skyColorAt(y);
            int rgb = pack(skyLR, skyLG, skyLB);
            int row = y * width;
            Arrays.fill(pixels, row, row + width, rgb);
        }
    }

    private float skyLR, skyLG, skyLB;

    private void skyColorAt(int y) {
        float ndcY = (height * 0.5f - (y + 0.5f)) / focal;
        float dirX = ndcY * cam.upX + cam.fwdX;
        float dirY = ndcY * cam.upY + cam.fwdY;
        float dirZ = ndcY * cam.upZ + cam.fwdZ;
        float inv = 1f / (float) Math.sqrt(dirX * dirX + dirY * dirY + dirZ * dirZ);
        float elev = dirY * inv;
        float up = elev <= 0f ? 0f : elev >= 1f ? 1f : elev;
        float k = (float) Math.sqrt(up);
        float band = (float) Math.exp(-elev * elev * 22.0);
        skyLR = horizonR + (zenithR - horizonR) * k + (warmR - horizonR) * band * 0.55f;
        skyLG = horizonG + (zenithG - horizonG) * k + (warmG - horizonG) * band * 0.55f;
        skyLB = horizonB + (zenithB - horizonB) * k + (warmB - horizonB) * band * 0.55f;
    }

    private void stampSun() {
        float sx = Scene.SUN_X * cam.rightX + Scene.SUN_Y * cam.rightY + Scene.SUN_Z * cam.rightZ;
        float sy = Scene.SUN_X * cam.upX + Scene.SUN_Y * cam.upY + Scene.SUN_Z * cam.upZ;
        float sz = Scene.SUN_X * cam.fwdX + Scene.SUN_Y * cam.fwdY + Scene.SUN_Z * cam.fwdZ;
        if (sz < 0.08f) {
            return;
        }
        float px = width * 0.5f + (sx / sz) * focal;
        float py = height * 0.5f - (sy / sz) * focal;
        float radius = height * 0.28f;
        int x0 = Math.max(0, (int) (px - radius));
        int y0 = Math.max(0, (int) (py - radius));
        int x1 = Math.min(width - 1, (int) (px + radius));
        int y1 = Math.min(height - 1, (int) (py + radius));
        float r2 = radius * radius;
        for (int y = y0; y <= y1; y++) {
            skyColorAt(y);
            float dy = y + 0.5f - py;
            int row = y * width;
            for (int x = x0; x <= x1; x++) {
                float dx = x + 0.5f - px;
                float d2 = dx * dx + dy * dy;
                if (d2 >= r2) {
                    continue;
                }
                float d = (float) Math.sqrt(d2) / radius;
                float glow = 1f - d;
                glow = glow * glow * (1f - d * 0.35f);
                float red = skyLR + glow * 0.72f;
                float green = skyLG + glow * 0.48f;
                float blue = skyLB + glow * 0.18f;
                pixels[row + x] = pack(red, green, blue);
            }
        }
    }

    private void applyFade(float amount) {
        // amount 1 leaves the picture alone. Toward 0, every pixel walks to the mist colour.
        if (amount > 0.998f) {
            return;
        }
        int fr = (fogPixel >> 16) & 255;
        int fg = (fogPixel >> 8) & 255;
        int fb = fogPixel & 255;
        float keep = amount;
        float mix = 1f - amount;
        int n = width * height;
        for (int i = 0; i < n; i++) {
            int p = pixels[i];
            int r = (int) (((p >> 16) & 255) * keep + fr * mix);
            int g = (int) (((p >> 8) & 255) * keep + fg * mix);
            int b = (int) ((p & 255) * keep + fb * mix);
            pixels[i] = (r << 16) | (g << 8) | b;
        }
    }

    private int pack(float r, float g, float b) {
        int ri = (int) (r * 2047f + 0.5f);
        int gi = (int) (g * 2047f + 0.5f);
        int bi = (int) (b * 2047f + 0.5f);
        if (ri < 0) {
            ri = 0;
        } else if (ri > 2047) {
            ri = 2047;
        }
        if (gi < 0) {
            gi = 0;
        } else if (gi > 2047) {
            gi = 2047;
        }
        if (bi < 0) {
            bi = 0;
        } else if (bi > 2047) {
            bi = 2047;
        }
        return (srgbLut[ri] << 16) | (srgbLut[gi] << 8) | srgbLut[bi];
    }

    private static int linearToSrgbByte(float c) {
        if (c <= 0.0031308f) {
            c *= 12.92f;
        } else {
            c = 1.055f * (float) Math.pow(c, 1.0 / 2.4) - 0.055f;
        }
        int v = (int) (c * 255f + 0.5f);
        if (v < 0) {
            return 0;
        }
        if (v > 255) {
            return 255;
        }
        return v;
    }
}
