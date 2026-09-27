package zen;

/** Orthonormal camera. Right is horizontal, so the horizon is a straight row. */
final class Cam {
    float eyeX, eyeY, eyeZ;
    float rightX, rightY, rightZ;
    float upX, upY, upZ;
    float fwdX, fwdY, fwdZ;
    float fovY;
}
