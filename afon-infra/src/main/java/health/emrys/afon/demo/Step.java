package health.emrys.afon.demo;

interface ThrowableRunnable {
    void run() throws Exception;
}

public class Step {
    final String            name;
    final ThrowableRunnable function;

    Step(String name, ThrowableRunnable function) {
        this.name     = name;
        this.function = function;
    }
}
