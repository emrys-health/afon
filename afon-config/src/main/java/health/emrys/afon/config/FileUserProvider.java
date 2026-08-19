package health.emrys.afon.config;

import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Scanner;
import java.util.stream.Collectors;

import static org.slf4j.LoggerFactory.getLogger;

/**
 * A {@link AfonUserProvider} backed by csv files of users and their acl.
 *
 * {@link AfonUser} fields are loaded from {@link #usersFiles}, which default to {@link #USERS_FILES}.
 * The csv format is:
 * #username,password,email,slug,firstName,lastName
 * The first two fields are mandatory.
 *
 * {@link AfonUserAcl} fields are loaded from {@link #usersAclFiles}, which default to {@link #USERS_ACL_FILES}.
 * The csv format is:
 * #username,catalogs
 * The two fields are mandatory.
 *
 * The csv files are loaded from the {@link #configDir}, which defaults to {@link ConfigUtils#configDir()}.
 *
 * The `users-acl` files are allowed to be missing, in which case all user authentication is denied.
 */
public class FileUserProvider implements AfonUserProvider {

    private static final Logger logger  = getLogger(FileUserProvider.class);

    /**
     * User files.
     */
    private static final List<String> USERS_FILES = List.of("users.csv");
    private static final List<String> USERS_ACL_FILES = List.of("users-acl.csv");

    private final String configDir;
    private final List<String> usersFiles;
    private final List<String> usersAclFiles;
    private List<AfonUser> users;
    private List<AfonUserAcl> userAcls;

    public FileUserProvider() {
        this(ConfigUtils.configDir());
    }

    public FileUserProvider(String configDir){
        this(configDir, USERS_FILES, USERS_ACL_FILES);
    }

    public FileUserProvider(String configDir, List<String> usersFiles, List<String> usersAclFiles) {
        this.configDir = configDir;
        this.usersFiles = usersFiles;
        this.usersAclFiles = usersAclFiles;
        this.users = new ArrayList<>();
        this.userAcls = new ArrayList<>();
    }

    private void loadUsers() {
        usersFiles.forEach(file -> loadUsers(file));
    }

    private void loadUserAcls() {
        usersAclFiles.forEach(file -> loadUserAcls(file));
    }

    private void loadUsers(String file) {
        String dir = configDir();
        String pathname = dir + "/" + file;
        logger.info("Loading {} users from {}", file, dir);
        int users = createUsers(scan(new File(pathname), false));
        logger.info("Loaded {} users from {}", users, pathname);
    }

    private int createUsers(List<String[]> lines) {
        for (String[] fields : lines) {
            users.add(AfonUserProvider.createUser(fields));
        }
        return lines.size();
    }

    private void loadUserAcls(String file) {
        String dir = configDir();
        String pathname = dir + "/" + file;
        logger.info("Loading {} user acls from {}", file, dir);
        int userAcls = createUserAcls(scan(new File(pathname), true));
        logger.info("Loaded {} user acls from {}", userAcls, pathname);
    }

    private int createUserAcls(List<String[]> lines) {
        for (String[] fields : lines) {
            userAcls.add(AfonUserProvider.createUserAcl(fields));
        }
        return lines.size();
    }

    private @NonNull List<String[]> scan(File file, boolean allowMissing) {
        List<String[]> list = new ArrayList<>();
        Scanner scanner;
        try {
            scanner = new Scanner(file);
        } catch (FileNotFoundException e) {
            if ( allowMissing) {
                logger.warn("Allowing missing file {}", file.getName());
                return list;
            } else {
                logger.error("File {} not found", file.getName());
                throw new RuntimeException(e);
            }
        }
        while (scanner.hasNextLine()) {
            String line = scanner.nextLine();
            // skip comment lines starting with #
            if (!line.trim().startsWith("#")) {
                // split line and trim fields
                String[] fields = Arrays.stream(line.split(","))
                        .map(String::trim).toArray(String[]::new);
                if (fields.length > 1) {
                    try {
                        list.add(fields);
                    } catch (RuntimeException e) {
                        logger.warn("Failed to scan line {}", line, e);
                    }
                }
            }
        }
        return list;
    }

    @NonNull String configDir() {
        return configDir;
    }

    public List<AfonUser> getUsers() {
        loadLazily();
        return users;
    }

    public List<AfonUserAcl> getUserAcls() {
        loadLazily();
        return userAcls;
    }

    private void loadLazily() {
        // lazily load users
        if ( users.isEmpty() ){
            loadUsers();
        }
        // lazily load users acl
        if ( userAcls.isEmpty() ){
            loadUserAcls();
        }
    }

    @Override
    public List<String> getUserNames() {
        return getUsers().stream().map(AfonUser::username).collect(Collectors.toList());
    }

    @Override
    public AfonUser getUser(String username) {
        for (AfonUser user : getUsers()) {
            if (user.username().equals(username)) {
                return user;
            }
        }
        throw new RuntimeException("No user found with username: " + username);
    }

    @Override
    public boolean isAuthorised(String username, String catalogName) {
        for (AfonUserAcl acl : getUserAcls()) {
            if (acl.username().equals(username)) {
                return acl.isAuthorised(catalogName);
            }
        }
        return false;
    }

    @Override
    public String toString() {
        return getUsers().stream().map(x -> x.username()).collect(Collectors.joining("\n"));
    }
}
