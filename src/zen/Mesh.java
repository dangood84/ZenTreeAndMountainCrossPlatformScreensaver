package zen;

import java.util.Arrays;

/** Triangle mesh. Colors are linear albedo; lighting is applied each frame. */
final class Mesh {
    float[] px = new float[16384];
    float[] py = new float[16384];
    float[] pz = new float[16384];
    float[] nx = new float[16384];
    float[] ny = new float[16384];
    float[] nz = new float[16384];
    float[] ar = new float[16384];
    float[] ag = new float[16384];
    float[] ab = new float[16384];
    float[] shine = new float[16384];
    float[] spec = new float[16384];
    float[] sway = new float[16384];
    int[] idx = new int[65536];
    int vCount;
    int iCount;

    int vert(float x, float y, float z,
             float nnx, float nny, float nnz,
             float r, float g, float b,
             float shineAmt, float specAmt, float swayAmt) {
        float len = (float) Math.sqrt(nnx * nnx + nny * nny + nnz * nnz);
        if (len > 1e-8f) {
            nnx /= len;
            nny /= len;
            nnz /= len;
        } else {
            nny = 1f;
        }
        if (vCount == px.length) {
            grow();
        }
        int i = vCount++;
        px[i] = x;
        py[i] = y;
        pz[i] = z;
        nx[i] = nnx;
        ny[i] = nny;
        nz[i] = nnz;
        ar[i] = r;
        ag[i] = g;
        ab[i] = b;
        shine[i] = shineAmt;
        spec[i] = specAmt;
        sway[i] = swayAmt;
        return i;
    }

    void tri(int a, int b, int c) {
        if (iCount + 3 > idx.length) {
            idx = Arrays.copyOf(idx, idx.length * 2);
        }
        idx[iCount++] = a;
        idx[iCount++] = b;
        idx[iCount++] = c;
    }

    /** Keep the winding whose geometric normal points away from an interior point. */
    void triOutward(int i0, int i1, int i2, float ix, float iy, float iz) {
        float x0 = px[i0], y0 = py[i0], z0 = pz[i0];
        float x1 = px[i1], y1 = py[i1], z1 = pz[i1];
        float x2 = px[i2], y2 = py[i2], z2 = pz[i2];
        float cx = (y1 - y0) * (z2 - z0) - (z1 - z0) * (y2 - y0);
        float cy = (z1 - z0) * (x2 - x0) - (x1 - x0) * (z2 - z0);
        float cz = (x1 - x0) * (y2 - y0) - (y1 - y0) * (x2 - x0);
        float ox = x0 - ix, oy = y0 - iy, oz = z0 - iz;
        if (cx * ox + cy * oy + cz * oz < 0f) {
            int s = i1;
            i1 = i2;
            i2 = s;
        }
        tri(i0, i1, i2);
    }

    void finish() {
        px = Arrays.copyOf(px, vCount);
        py = Arrays.copyOf(py, vCount);
        pz = Arrays.copyOf(pz, vCount);
        nx = Arrays.copyOf(nx, vCount);
        ny = Arrays.copyOf(ny, vCount);
        nz = Arrays.copyOf(nz, vCount);
        ar = Arrays.copyOf(ar, vCount);
        ag = Arrays.copyOf(ag, vCount);
        ab = Arrays.copyOf(ab, vCount);
        shine = Arrays.copyOf(shine, vCount);
        spec = Arrays.copyOf(spec, vCount);
        sway = Arrays.copyOf(sway, vCount);
        idx = Arrays.copyOf(idx, iCount);
    }

    private void grow() {
        int n = px.length * 2;
        px = Arrays.copyOf(px, n);
        py = Arrays.copyOf(py, n);
        pz = Arrays.copyOf(pz, n);
        nx = Arrays.copyOf(nx, n);
        ny = Arrays.copyOf(ny, n);
        nz = Arrays.copyOf(nz, n);
        ar = Arrays.copyOf(ar, n);
        ag = Arrays.copyOf(ag, n);
        ab = Arrays.copyOf(ab, n);
        shine = Arrays.copyOf(shine, n);
        spec = Arrays.copyOf(spec, n);
        sway = Arrays.copyOf(sway, n);
    }
}
