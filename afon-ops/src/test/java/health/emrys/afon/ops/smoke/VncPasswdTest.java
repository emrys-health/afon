package health.emrys.afon.ops.smoke;

import org.junit.jupiter.api.Test;

import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The expected bytes are those of the files {@code x11vnc -storepasswd <password> <file>} wrote, with the x11vnc of Debian
 * bookworm.
 */
class VncPasswdTest {

    @Test
    void encodesAShortPasswordPaddedWithNulls() {
        assertThat(VncPasswd.encode("abc")).isEqualTo(HexFormat.of().parseHex("9c0a172d3482e122"));
    }

    @Test
    void encodesThePasswordTheImageUsedToDefaultTo() {
        assertThat(VncPasswd.encode("afon")).isEqualTo(HexFormat.of().parseHex("5bbfc4a0c2aad99a"));
    }

    @Test
    void encodesOnlyTheFirstEightCharactersOfALongPassword() {
        assertThat(VncPasswd.encode("golden-jupyter-vnc-password")).isEqualTo(HexFormat.of().parseHex("e8fe4904ee2b0a92"))
                                                                   .isEqualTo(VncPasswd.encode("golden-j"));
    }
}
