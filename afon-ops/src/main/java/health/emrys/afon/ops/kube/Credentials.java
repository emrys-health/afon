package health.emrys.afon.ops.kube;

import java.util.regex.Pattern;

/**
 * Keeps credentials out of what is logged or reported: the commands run in pods carry them, eg {@code wget --password=…} or a
 * {@code git} URL with a user and password, and so do the errors that echo those commands.
 */
public final class Credentials {

    public static final String REDACTED = "<redacted>";

    private static final Pattern[] PATTERNS = {
        // --password=secret, --token=secret, ..., as the args of a command
        Pattern.compile("(?i)(--[a-z-]*(?:password|passwd|secret|token)=)\\S+"),
        // scheme://user:secret@host
        Pattern.compile("(?i)([a-z][a-z0-9+.-]*://[^/\\s:@]+:)[^/\\s@]+(@)"),
        // ?user=u&password=secret, in a JDBC URL for one
        Pattern.compile("(?i)([?&;](?:password|passwd|pwd|secret|token)=)[^&;\\s\"']+"),
        // PGPASSWORD=secret, AWS_SECRET_ACCESS_KEY=secret, JUPYTER_VNC_PASSWORD=secret
        // (not what an earlier pattern has redacted, whose value ends at an & or a ;)
        Pattern.compile("(?i)(\\b[a-z0-9_]*(?:password|passwd|secret|token|access_key)=)(?!<redacted>)[^\\s\"']+")
    };

    private Credentials() {
    }

    /**
     * @param text a command, or output that echoes one; may be null
     * @return the text with the values of passwords, secrets and tokens in it replaced by {@value #REDACTED}
     */
    public static String redact(String text) {
        if (text == null) {
            return null;
        }
        String redacted = text;
        for (Pattern pattern : PATTERNS) {
            redacted = pattern.matcher(redacted).replaceAll(match -> {
                String tail = match.groupCount() > 1 && match.group(2) != null ? match.group(2) : "";
                return java.util.regex.Matcher.quoteReplacement(match.group(1) + REDACTED + tail);
            });
        }
        return redacted;
    }
}
