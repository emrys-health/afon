package health.emrys.afon.docs;

import io.github.classgraph.*;
import org.slf4j.Logger;

import static org.slf4j.LoggerFactory.getLogger;

public class DocumentAllMain {
    private static final Logger log = getLogger(DocumentAllMain.class);
    public static void main(String[] args) {
        try (ScanResult scanResult = new ClassGraph()
            .enableClassInfo()
            .acceptPackages("health.emrys.afon.demo")
            .scan()) {

            scanResult.getAllClasses()
                      .filter(info ->
                                  info.getSimpleName().startsWith("Document")
                                      && info.getSimpleName().endsWith("Main")
                                      && !info.getSimpleName().equals(DocumentAllMain.class.getSimpleName()))
                      .forEach(info -> {
                          try {
                              Class<?> clazz = info.loadClass();
                              log.info("Running {}", clazz.getSimpleName());
                              clazz.getMethod("main", String[].class).invoke(null, (Object) args);
                          } catch (Exception e) {
                              log.error("Cannot run " + info, e);
                          }
                      });
        }
    }
}
