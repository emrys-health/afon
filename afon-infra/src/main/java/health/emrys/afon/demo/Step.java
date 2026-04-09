package health.emrys.afon.demo;

interface ThrowableRunnable {
    void run() throws Exception;
}

public class Step {
    final String            name;
    final String            description;
    final ThrowableRunnable function;

    Step(String name, String description, ThrowableRunnable function) {
        this.name        = name;
        this.description = description;
        this.function    = function;
    }
}
