package health.emrys.afon.docs;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.ScanResult;
import org.slf4j.Logger;

import static org.slf4j.LoggerFactory.getLogger;

public class DocumentAllMain {
    private static final Logger logger = getLogger(DocumentAllMain.class);
    public static void main(String[] args) {
        try (ScanResult scanResult = new ClassGraph()
            .enableClassInfo()
            .acceptPackages("health.emrys.afon.docs")
            .scan()) {

            scanResult.getAllClasses()
              .filter(info ->
              info.getSimpleName().startsWith("Document")
                  && info.getSimpleName().endsWith("Main")
                  && !info.getSimpleName().equals(DocumentAllMain.class.getSimpleName())
              )
              .forEach(info -> {
                  try {
                      Class<?> documentClass = info.loadClass();
                      logger.info("Running {}", documentClass.getSimpleName());
                      documentClass.getMethod("main", String[].class).invoke(null, (Object) args);
                  } catch (Exception e) {
                      logger.error("Failed to run {}: {}", info.getName(), e.getCause(), e);
                      throw new RuntimeException(e);
                  }
              });
        }
    }
}
