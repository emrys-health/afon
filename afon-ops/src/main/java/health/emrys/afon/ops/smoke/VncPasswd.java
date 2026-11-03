package health.emrys.afon.ops.smoke;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;

/**
 * The contents of a VNC password file, eg the {@code ~/.vnc/passwd} that {@code x11vnc -storepasswd} writes: the password,
 * truncated or padded with nulls to 8 bytes, encrypted with DES under VNC's well-known fixed key.  That is obfuscation, not
 * protection: anyone can reverse it, so a file's contents are as secret as the password itself.
 */
public final class VncPasswd {

    /**
     * The key of libvncserver's {@code d3des.c}, whose DES takes the bits of each key byte in the reverse order of standard
     * DES, so each is reversed for {@link Cipher}.
     */
    private static final int[] FIXED_KEY = {23, 82, 107, 6, 35, 78, 88, 7};

    /** VNC authentication only uses this many bytes of a password. */
    private static final int LENGTH = 8;

    private VncPasswd() {
    }

    /**
     * @return the 8 bytes of the password file that {@code x11vnc -storepasswd <password>} writes
     */
    public static byte[] encode(String password) {
        byte[] key = new byte[LENGTH];
        for (int i = 0; i < LENGTH; i++) {
            key[i] = (byte) (Integer.reverse(FIXED_KEY[i]) >>> 24);
        }
        byte[] block = Arrays.copyOf(password.getBytes(StandardCharsets.UTF_8), LENGTH);
        try {
            Cipher des = Cipher.getInstance("DES/ECB/NoPadding");
            des.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "DES"));
            return des.doFinal(block);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("DES unavailable", e);
        }
    }
}
