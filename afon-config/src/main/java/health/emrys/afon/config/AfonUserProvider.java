package health.emrys.afon.config;

import java.util.List;

public interface AfonUserProvider {


    static AfonUser createUser(String username, String password) {
        return createUser(username, password, null);
    }

    static AfonUser createUser(String username, String password, String email) {
        return new AfonUser(username, password, email, null, "undefined", "undefined");
    }

    static AfonUser createUser(String username, String password, String email, String slug) {
        return new AfonUser(username, password, email, slug, "undefined", "undefined");
    }

    static AfonUser createUser(String username, String password, String email, String slug, String firstName, String lastName) {
        return new AfonUser(username, password, email, slug, firstName, lastName);
    }

    static AfonUser createUser(String... fields) {
        if ( fields.length == 6){
            return createUser(fields[0], fields[1], fields[2], fields[3], fields[4], fields[5]);
        } else if ( fields.length == 4 ){
            return createUser(fields[0], fields[1], fields[2], fields[3]);
        } else if ( fields.length == 3  ){
            return createUser(fields[0], fields[1], fields[2]);
        } else if ( fields.length == 2 ){
            return createUser(fields[0], fields[1]);
        } else {
            throw new RuntimeException("Invalid user fields: "+List.of(fields));
        }
    }

    List<AfonUser> getUsers();

    AfonUser getUser(String username);
}
