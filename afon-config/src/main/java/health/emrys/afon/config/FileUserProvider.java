package health.emrys.afon.config;

import org.jspecify.annotations.NonNull;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;
import java.util.stream.Collectors;

/**
 * A {@link AfonUserProvider} backed by a CSV file of users.
 */
public class FileUserProvider implements AfonUserProvider {

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
        String pathname = configDir() + "/" + file;
        Scanner scanner = null;
        try {
            scanner = new Scanner(new File(pathname));
        } catch (FileNotFoundException e) {
            throw new RuntimeException(e);
        }
        while (scanner.hasNextLine()) {
            String[] fields = scanner.nextLine().split(",");
            if ( fields.length > 1 ) {
                users.add(AfonUserProvider.createUser(fields));
            }
        }
    }

    public List<AfonUser> getUsers() {
        if ( users.isEmpty() ){
            loadUsers();
        }
        return users;
    }

    private @NonNull String configDir() {
        return System.getProperty("afon.home", "..") + "/config";
    }

    @Override
    public String toString() {
        return getUsers().stream().map(x -> x.username()).collect(Collectors.joining("\n"));
    }
}
