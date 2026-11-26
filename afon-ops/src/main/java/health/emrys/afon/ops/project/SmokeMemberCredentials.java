package health.emrys.afon.ops.project;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * What the smoke tests of a project stack's TRE sign in with, as a smoke member that {@code project.yaml} lists: the member's email, a password and the secret of
 * an authenticator, which {@code manage-identity smoke-credentials} gives the member.  They are kept for all the smoke members in the project stack's secrets dir,
 * as {@code smoke-members.csv}, a line for each, {@code <email>,<password>,<authenticator secret>}, readable by its owner only; a line that starts with {@code #} is a
 * comment.  They are the member's, in the central identity, as a person's are: the tests sign in with them as that person would.
 */
public record SmokeMemberCredentials(String email, String password, String totpSecret) {

    /** The name of the file, in the project stack's secrets dir. */
    public static final String FILE = "smoke-members.csv";

    private static final String HEADER = "# The smoke members: <email>,<password>,<authenticator secret>.  Written by manage-identity smoke-credentials: keep it secret.";
    private static final String ALPHABET = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final int PASSWORD_LENGTH = 32;

    /** @return the credentials of every smoke member that the secrets dir has, by email, in the order of the file; none if it has no file; lines that are not whole are skipped */
    public static Map<String, SmokeMemberCredentials> readAll(Path secretsDir) throws IOException {
        Map<String, SmokeMemberCredentials> all = new LinkedHashMap<>();
        Path file = secretsDir.resolve(FILE);
        if (!Files.isRegularFile(file)) {
            return all;
        }
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            String text = line.strip();
            if (text.isEmpty() || text.startsWith("#")) {
                continue;
            }
            String[] fields = text.split(",", -1);
            if (fields.length == 3 && !fields[0].isBlank() && !fields[1].isBlank() && !fields[2].isBlank()) {
                String email = fields[0].strip().toLowerCase(Locale.ROOT);
                all.put(email, new SmokeMemberCredentials(email, fields[1].strip(), fields[2].strip()));
            }
        }
        return all;
    }

    /** @return the credentials of the smoke member in the secrets dir, or none if it has none, or they are not whole */
    public static Optional<SmokeMemberCredentials> read(Path secretsDir, String member) throws IOException {
        return Optional.ofNullable(readAll(secretsDir).get(member.toLowerCase(Locale.ROOT)));
    }

    /** Writes the credentials to the secrets dir, which it makes, replacing those that the file has for the member and keeping those of the others, readable by their owner only. */
    public void write(Path secretsDir) throws IOException {
        for (String value : new String[]{email, password, totpSecret}) {
            if (value.contains(",") || value.contains("\n") || value.contains("\r")) {
                throw new IOException("A credential of " + email + " has a comma or a line break, which the CSV of the smoke members cannot hold");
            }
        }
        Map<String, SmokeMemberCredentials> all = readAll(secretsDir);
        all.put(email.toLowerCase(Locale.ROOT), new SmokeMemberCredentials(email.toLowerCase(Locale.ROOT), password, totpSecret));
        StringBuilder text = new StringBuilder(HEADER).append('\n');
        all.values().forEach(each -> text.append(each.email).append(',').append(each.password).append(',').append(each.totpSecret).append('\n'));
        Files.createDirectories(secretsDir);
        Path file = secretsDir.resolve(FILE);
        if (!Files.exists(file)) {
            try {
                Files.createFile(file, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            } catch (UnsupportedOperationException e) {
                Files.createFile(file);
            }
        }
        Files.writeString(file, text, StandardCharsets.UTF_8);
    }

    /** @return a password that is random and long, of letters and digits, which no form or shell makes trouble of */
    static String randomPassword() {
        SecureRandom random = new SecureRandom();
        StringBuilder password = new StringBuilder();
        for (int i = 0; i < PASSWORD_LENGTH; i++) {
            password.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return password.toString();
    }

    @Override
    public String toString() {
        return "SmokeMemberCredentials[email=" + email + ", password=***, totpSecret=***]";
    }
}
