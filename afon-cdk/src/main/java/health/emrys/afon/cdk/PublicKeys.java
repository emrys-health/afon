package health.emrys.afon.cdk;

/**
 * The PEM public key that the images are verified with, as the {@code <CLOUD>_COSIGN_PUBLIC_KEY} property holds it: the base64 of the key
 * alone, without the lines that begin and end the PEM, so that it is a single word with no space, which the shell reads too, as the
 * scripts {@code source} the config files, and a properties file takes on one line.
 */
public final class PublicKeys {

    private static final String BEGIN = "-----BEGIN PUBLIC KEY-----";
    private static final String END = "-----END PUBLIC KEY-----";
    private static final int LINE_LENGTH = 64;

    private PublicKeys() {
    }

    /**
     * @param output the output of {@code cosign public-key}, from which the PEM is taken, as it may also print warnings
     * @return the base64 of the key, as the property holds it
     * @throws IllegalArgumentException if it has no PEM public key
     */
    public static String toProperty(String output) {
        String text = output.replace("\r", "");
        int begin = text.indexOf(BEGIN);
        int end = text.indexOf(END);
        if (begin < 0 || end < begin) {
            throw new IllegalArgumentException("Not a PEM public key: " + output);
        }
        return text.substring(begin + BEGIN.length(), end).replaceAll("\\s", "");
    }

    /**
     * @param value the value of the property: the base64 of the key, or, as it used to be, the PEM, on one line with {@code \n} between
     *              its lines, or as it is
     * @return the PEM
     */
    public static String fromProperty(String value) {
        String text = value.replace("\\n", "\n").strip();
        if (text.contains(BEGIN)) {
            return text.replace("\r", "");
        }
        String body = text.replaceAll("\\s", "");
        StringBuilder pem = new StringBuilder(BEGIN).append('\n');
        for (int i = 0; i < body.length(); i += LINE_LENGTH) {
            pem.append(body, i, Math.min(body.length(), i + LINE_LENGTH)).append('\n');
        }
        return pem.append(END).toString();
    }
}
