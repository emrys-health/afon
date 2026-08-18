package health.emrys.afon.config;

import org.slf4j.Logger;

import java.io.File;
import java.io.FileNotFoundException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

import static org.slf4j.LoggerFactory.getLogger;

/**
 * A {@link AfonUserProvider} backed by a CSV file of users.
 *
 * Fields are:
 * username,password,email,slug,firstName,lastName
 *
 * The first two fields are mandatory.
 *
 * The csv files are loaded from the ConfigUtils.configDir().
 */
public class FileUserProvider implements AfonUserProvider {

    private static final Logger logger  = getLogger(FileUserProvider.class);

    /**
     * User files.
     */
    private static final List<String> ALL_FILES = List.of("users.csv");

    private final List<String> files;
    private List<AfonUser> users;

    public FileUserProvider() {
        this(ALL_FILES);
    }

    public FileUserProvider(List<String> files){
        this.files = files;
        this.users = new ArrayList<>();
    }

    private void loadUsers() {
        files.forEach(file -> loadUsers(file));
    }

    private void loadUsers(String file) {
        String dir = ConfigUtils.configDir();
        String pathname = dir + "/" + file;
        logger.info("Loading {} users from {}", file, dir);
        Path path = Path.of(pathname);
        if (!Files.exists(path) ){
            logger.warn("File {} not found", pathname);
            throw new RuntimeException("File not found: "+pathname);
        }
        Scanner scanner = null;
        try {
            scanner = new Scanner(new File(pathname));
        } catch (FileNotFoundException e) {
            logger.error("Failed to load users from {}", file);
            throw new RuntimeException(e);
        }
        List<AfonUser> list = new ArrayList<>();
        while (scanner.hasNextLine()) {
            String line = scanner.nextLine();
            // skip comment lines starting with #
            if (!line.trim().startsWith("#")) {
                // split line and trim fields
                String[] fields = Arrays.stream(line.split(","))
                        .map(String::trim).toArray(String[]::new);
                if (fields.length > 1) {
                    try {
                        list.add(AfonUserProvider.createUser(fields));
                    } catch (RuntimeException e) {
                        logger.warn("Failed to create user", e);
                    }
                }
            }
        }
        users.addAll(list);
        logger.info("Loaded {} users from {}", list.size(), pathname);
    }

    public List<AfonUser> getUsers() {
        // lazily load users
        if ( users.isEmpty() ){
            loadUsers();
        }
        return users;
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
    public String toString() {
        return getUsers().stream().map(x -> x.username()).collect(Collectors.joining("\n"));
    }
}
