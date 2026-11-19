package health.emrys.afon.ops.project;

import health.emrys.afon.project.FileProjectProvider;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The members of a TRE project, by their user id in the central identity, kept in the project's dir as {@code members.csv}: a line for each, {@code <user id>,<email>},
 * written by {@code manage-identity} as it makes, adds or removes them, so that what needs their ids, eg the grants of a Data Provider, reads them from
 * there and nobody copies them by hand.  A line that starts with {@code #} is a comment.
 */
final class ProjectMembers {

    /** The name of the file, in the project's dir. */
    static final String FILE = "members.csv";

    private static final String HEADER = "# The members of the project: <user id>,<email>.  Written by manage-identity, which keeps it as the project's group is.";

    private ProjectMembers() {
    }

    /**
     * @return the file of the members of the project: {@code <afon-home>/projects/<project>/members.csv}
     */
    static Path file(Path afonHome, String project) {
        return FileProjectProvider.projectDir(afonHome, project).resolve(FILE);
    }

    /**
     * @return the members in the file, by user id, with their email; none if there is no file
     */
    static Map<String, String> read(Path file) throws IOException {
        Map<String, String> members = new LinkedHashMap<>();
        if (!Files.isRegularFile(file)) {
            return members;
        }
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            String text = line.strip();
            if (text.isEmpty() || text.startsWith("#")) {
                continue;
            }
            int comma = text.indexOf(',');
            String id = (comma < 0 ? text : text.substring(0, comma)).strip();
            if (!id.isEmpty()) {
                members.put(id, comma < 0 ? "" : text.substring(comma + 1).strip());
            }
        }
        return members;
    }

    /**
     * Writes the members, replacing what the file had.
     */
    static void write(Path file, Map<String, String> members) throws IOException {
        StringBuilder text = new StringBuilder(HEADER).append('\n');
        members.forEach((id, email) -> text.append(id).append(',').append(email).append('\n'));
        Files.createDirectories(file.getParent());
        Files.writeString(file, text, StandardCharsets.UTF_8);
    }

    /**
     * Records a member, who is already in the file with another email if the email changed.
     */
    static void add(Path file, String userId, String email) throws IOException {
        Map<String, String> members = read(file);
        members.put(userId, email);
        write(file, members);
    }

    /**
     * Forgets a member, if the file has them.
     */
    static void remove(Path file, String userId) throws IOException {
        Map<String, String> members = read(file);
        if (members.remove(userId) != null) {
            write(file, members);
        }
    }

    /**
     * @param missing the emails that the project's file lists and the members file has no one of: not yet synced, so the grant would leave them out
     * @param extra   the emails that the members file has and the project's file does not list: not the project's members, so the next sync takes them out
     */
    record Drift(Set<String> missing, Set<String> extra) {
        boolean none() {
            return missing.isEmpty() && extra.isEmpty();
        }
    }

    /**
     * How the members file differs from the members that the project's file lists, which is the authority: by email, in lower case.  An entry of the file with no
     * email, as a list of user ids only has, is not compared.
     */
    static Drift drift(Map<String, String> inFile, Collection<String> listed) {
        Set<String> wanted = new LinkedHashSet<>();
        listed.forEach(email -> wanted.add(email.toLowerCase(Locale.ROOT)));
        Set<String> present = new LinkedHashSet<>();
        inFile.values().stream().filter(email -> !email.isBlank()).forEach(email -> present.add(email.toLowerCase(Locale.ROOT)));
        Set<String> missing = new LinkedHashSet<>(wanted);
        missing.removeAll(present);
        Set<String> extra = new LinkedHashSet<>(present);
        extra.removeAll(wanted);
        return new Drift(missing, extra);
    }
}
