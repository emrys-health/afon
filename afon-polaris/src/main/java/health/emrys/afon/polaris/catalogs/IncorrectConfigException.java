package health.emrys.afon.polaris.catalogs;

public class IncorrectConfigException extends Exception {
    public IncorrectConfigException(String message) {
        super(message);
    }
    public IncorrectConfigException(String message, Exception e) {
        super(message, e);
    }
}
