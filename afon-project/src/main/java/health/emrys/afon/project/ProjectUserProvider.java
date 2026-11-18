package health.emrys.afon.project;

import health.emrys.afon.config.AfonUser;
import health.emrys.afon.config.AfonUser.Role;
import health.emrys.afon.config.AfonUserAcl;
import health.emrys.afon.config.AfonUserProvider;
import health.emrys.afon.config.ConfigUtils;
import health.emrys.afon.config.FileUserProvider;
import org.jspecify.annotations.NonNull;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The users of a project's TRE: the admins, currently defined in {@code users.csv}, who bootstrap and operate it, and the
 * members of the project, who use it.
 * <p>
 * An admin is not a member, so is not served by JupyterHub and its remote desktop, unless the project names them as a member, by the
 * same email.
 * <p>
 * A member is not an admin, so it has no password here, as the central identity invites it when {@code manage-identity sync-members}
 * adds it to the project's group, and its username is its email, as it is in the central identity.
 * A member has no login, Polaris principal or Git account made from this list.
 * <p>
 * Outside a project, the users are those of {@code users.csv} alone, who are admins and members, see {@link #forActiveConfig()}.
 */
public final class ProjectUserProvider implements AfonUserProvider {

    private static final int SLUG_NAME_LENGTH = 40;
    private static final int SLUG_HASH_LENGTH = 6;

    private final AfonUserProvider admins;
    private final AfonProject project;
    private List<AfonUser> users;

    public ProjectUserProvider(AfonUserProvider admins, AfonProject project) {
        this.admins = admins;
        this.project = project;
    }

    /**
     * @return the users of the TRE that the config dir is of: those of its project if it has a {@value FileProjectProvider#FILE}, as the
     * config env of a project has, and else the users of {@code users.csv}, as for any other env
     */
    public static AfonUserProvider forActiveConfig() {
        Path file = Path.of(ConfigUtils.configDir(), FileProjectProvider.FILE);
        AfonUserProvider admins = new FileUserProvider();
        return Files.isRegularFile(file) ? new ProjectUserProvider(admins, new FileProjectProvider(file).getProject()) : admins;
    }

    @Override
    public synchronized List<AfonUser> getUsers() {
        if (users == null) {
            users = merge(admins.getUsers(), project.members());
        }
        return users;
    }

    private static List<AfonUser> merge(List<AfonUser> admins, List<TreProject.Member> members) {
        Set<String> memberEmails = new HashSet<>();
        members.forEach(member -> memberEmails.add(member.email()));
        List<AfonUser> merged = new ArrayList<>();
        Set<String> adminEmails = new HashSet<>();
        for (AfonUser admin : admins) {
            adminEmails.add(admin.email());
            if (memberEmails.contains(admin.email())) {
                requireEmailAsUsername(admin);
                merged.add(admin);
            } else {
                merged.add(admin.withRoles(Role.ADMIN));
            }
        }
        for (TreProject.Member member : members) {
            if (!adminEmails.contains(member.email())) {
                merged.add(member(member));
            }
        }
        return List.copyOf(merged);
    }

    /**
     * A member is known in the central identity by their email, which is their username there, so an admin who is a member must be too: the
     * login that the setup makes for the admin is then the one that the project's group has.
     *
     * @throws IllegalStateException if the admin's username is not their email
     */
    private static void requireEmailAsUsername(AfonUser admin) {
        if (!admin.username().equalsIgnoreCase(admin.email())) {
            throw new IllegalStateException(String.format(
                "The user %s of users.csv is also a member of the project, by the email %s: a member is known by their email, so give the user "
                + "that email as their username in users.csv, or list them as a member by another email", admin.username(), admin.email()));
        }
    }

    private static AfonUser member(TreProject.Member member) {
        String first = member.firstName() == null ? AfonUser.UNDEFINED : member.firstName();
        String last = member.lastName() == null ? AfonUser.UNDEFINED : member.lastName();
        return new AfonUser(member.email(), null, member.email(), slug(member.email()), first, last, EnumSet.of(Role.MEMBER));
    }

    /**
     * @return the email as an RFC-1123 label that its email alone gives: the name before the {@code @}, made of lower-case alphanumerics and
     * hyphens, and the start of the hash of the email, which tells those of the same name apart
     */
    static String slug(String email) {
        String name = email.substring(0, email.indexOf('@')).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
        if (name.length() > SLUG_NAME_LENGTH) {
            name = name.substring(0, SLUG_NAME_LENGTH).replaceAll("-$", "");
        }
        return (name.isEmpty() ? "user" : name) + "-" + hash(email).substring(0, SLUG_HASH_LENGTH);
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public List<AfonUserAcl> getUserAcls() {
        return admins.getUserAcls();
    }

    @Override
    public @NonNull List<String> getUserNames() {
        return getUsers().stream().map(AfonUser::username).toList();
    }

    @Override
    public AfonUser getUser(String username) {
        return getUsers().stream().filter(user -> user.username().equals(username)).findFirst()
                         .orElseThrow(() -> new RuntimeException("No user found with username: " + username));
    }

    /**
     * Members are authorised on no catalog by this list: a Data Provider grants them, per project.
     */
    @Override
    public boolean isAuthorised(String username, String catalogName) {
        return getUsers().stream().anyMatch(user -> user.has(Role.ADMIN) && user.username().equals(username))
               && admins.isAuthorised(username, catalogName);
    }
}
