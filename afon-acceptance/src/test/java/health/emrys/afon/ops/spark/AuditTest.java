package health.emrys.afon.ops.spark;

import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.config.Audit;
import health.emrys.afon.config.FilePropertyProvider;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import static health.emrys.afon.config.Audit.REDACTED;
import static java.lang.String.format;
import static org.assertj.core.api.Assertions.assertThat;

class AuditTest {

    private final static Logger logger = LoggerFactory.getLogger(AuditTest.class);

    private AfonPropertyProvider properties = new FilePropertyProvider(); // MUST test real properties to prevent insecure code from being deplopyed DO NOT CHANGE!

    @Test
    void noSecretsInProperties() {
        Properties   secrets = properties.getSecretProperties();
        List<String> lines = new ArrayList<>();
        Audit        audit = new Audit(properties);
        lines.addAll(audit.configuration());

        assertThat(secrets).isNotEmpty();
        logger.info("Secrets = {}", secrets.keySet().stream().toList());

        secrets.entrySet()
               .stream()
               .forEach(entry -> {
                   lines.forEach(line -> {
                       String key = entry.getKey().toString();
                       if (line.contains(format(" %s=", key))) {
                           assertThat(line).contains(format("%s=%s", key, REDACTED));
                       }
                   });
               });
    }

}