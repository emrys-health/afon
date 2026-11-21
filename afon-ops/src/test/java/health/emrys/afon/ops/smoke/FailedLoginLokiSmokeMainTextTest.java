package health.emrys.afon.ops.smoke;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** A login page that is not one says why, in the words of KeyCloak's error page, rather than by its status alone. */
class FailedLoginLokiSmokeMainTextTest {

    @Test
    void textOfAnErrorPageIsWhatItSaysWithoutMarkupScriptsOrStyle() {
        String html = "<html><head><style>.x{color:red}</style><script>var a=1;</script></head>"
                      + "<body><div id=\"kc-error-message\"><p class=\"instruction\">Invalid parameter:\n   redirect_uri</p></div></body></html>";

        assertThat(FailedLoginLokiSmokeMain.textOf(html)).isEqualTo("Invalid parameter: redirect_uri");
    }

    @Test
    void textOfALongPageIsCutShort() {
        assertThat(FailedLoginLokiSmokeMain.textOf("<p>" + "word ".repeat(200) + "</p>")).hasSize(303).endsWith("...");
    }
}
