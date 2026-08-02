package health.emrys.afon.ops;

public class ArgsUtils {
    // workaround when args are passed as a single space-separated arg.
    public static String[] splitFirstBySpace(String[] args) {
        if ( args.length == 1 ){
            return args[0].split(" ");
        } else {
            return args;
        }
    }
}
