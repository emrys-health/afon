package health.emrys.afon.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junitpioneer.jupiter.ClearEnvironmentVariable;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static health.emrys.afon.config.AfonProperty.ENV_NAME;
import static health.emrys.afon.config.ConfigUtils.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

// ConfigUtils.envOrProperty prefers each AFON_* env var over its afon.* system property, so every
// one is cleared here for the duration of every test: afon.sh and the workflow both export several
// of these for real (e.g. AFON_HOME, AFON_LOGGING_CONSOLE), which would otherwise silently win over
// the system properties withProperties/homeOnly below set, and the test would exercise the real
// environment instead of its fixture.
@ExtendWith(MockitoExtension.class)
@ClearEnvironmentVariable(key = AFON_HOME_ENV)
@ClearEnvironmentVariable(key = AFON_CDKTF_ENV)
@ClearEnvironmentVariable(key = AFON_CLOUD_ENV)
@ClearEnvironmentVariable(key = AFON_LOGGING_ENV)
@ClearEnvironmentVariable(key = AFON_LOGGING_CONSOLE_ENV)
public class AfonEnvPrinterTest {

    @Mock
    AfonPropertyProvider properties;

    @TempDir
    Path tempDir;

    AfonEnvPrinter printer;

    @BeforeEach
    public void setUp() {
        printer = new AfonEnvPrinter(properties);
        // sensible default so tests that don't care about the env name don't need to stub it themselves
        lenient().when(properties.getValue(ENV_NAME, "ENV_NAME not set")).thenReturn("ENV_NAME not set");
    }

    private String printEnv() {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        printer.printEnv(new PrintStream(buffer));
        return buffer.toString();
    }

    /**
     * Sets (or, for a null value, clears) each given system property, runs the action, then restores
     * whatever value (or absence) each property had before. Needed because the build script (afon.sh)
     * injects afon.* system properties on the mvn command line, which surefire forwards to the test JVM.
     */
    private void withProperties(Map<String, String> overrides, Runnable action) {
        Map<String, String> previous = new LinkedHashMap<>();
        overrides.forEach((key, value) -> {
            previous.put(key, System.getProperty(key));
            if (value == null) {
                System.clearProperty(key);
            } else {
                System.setProperty(key, value);
            }
        });
        try {
            action.run();
        } finally {
            previous.forEach((key, value) -> {
                if (value == null) {
                    System.clearProperty(key);
                } else {
                    System.setProperty(key, value);
                }
            });
        }
    }

    private Map<String, String> homeOnly(String home) {
        Map<String, String> props = new LinkedHashMap<>();
        props.put(AFON_HOME_PROPERTY, home);
        props.put(AFON_CDKTF_PROPERTY, null);
        props.put(AFON_CLOUD_PROPERTY, null);
        return props;
    }

    @Test
    public void printsEnvNameFromPropertyProvider() throws Exception {
        Files.createDirectories(tempDir.resolve("home"));
        when(properties.getValue(ENV_NAME, "ENV_NAME not set")).thenReturn("dev");

        withProperties(homeOnly(tempDir.resolve("home").toString()),
                () -> assertThat(printEnv()).contains("name: dev"));
    }

    @Test
    public void printsEnvNotSetWhenPropertyMissing() throws Exception {
        Files.createDirectories(tempDir.resolve("home"));

        withProperties(homeOnly(tempDir.resolve("home").toString()),
                () -> assertThat(printEnv()).contains("name: ENV_NAME not set"));
    }

    @Test
    public void printsRealDirsAsRealDir() throws Exception {
        Path home = tempDir.resolve("home");
        Path config = home.resolve("config");
        Path cdktf = home.resolve("cdktf");
        Files.createDirectories(config);
        Files.createDirectories(cdktf);

        withProperties(homeOnly(home.toString()), () -> {
            String output = printEnv();
            assertThat(output).contains("home dir: " + home + " (real dir)");
            assertThat(output).contains("config dir: " + config + " (real dir)");
            assertThat(output).contains("cdktf dir: " + cdktf + " (real dir)");
        });
    }

    @Test
    public void printsSymlinkedDirsWithTarget() throws Exception {
        Path home = tempDir.resolve("home");
        Files.createDirectories(home);
        Path configEnv = tempDir.resolve("config-dev");
        Files.createDirectories(configEnv);
        Path config = home.resolve("config");
        Files.createSymbolicLink(config, configEnv);

        withProperties(homeOnly(home.toString()), () -> {
            String output = printEnv();
            assertThat(output).contains("config dir: " + config + " (symlink -> " + configEnv + ")");
        });
    }

    @Test
    public void printsMissingDirsAsNotFound() {
        Path home = tempDir.resolve("does-not-exist");

        withProperties(homeOnly(home.toString()), () -> {
            String output = printEnv();
            assertThat(output).contains("home dir: " + home + " (not found)");
            assertThat(output).contains("config dir: " + home.resolve("config") + " (not found)");
            assertThat(output).contains("cdktf dir: " + home.resolve("cdktf") + " (not found)");
        });
    }

    @Test
    public void printsCloudActiveWhenPropertySet() throws Exception {
        Files.createDirectories(tempDir.resolve("home"));
        Map<String, String> props = homeOnly(tempDir.resolve("home").toString());
        props.put(AFON_CLOUD_PROPERTY, "aws");

        withProperties(props, () -> assertThat(printEnv()).contains("cloud active: aws"));
    }

    @Test
    public void printsNoCloudPropertyFoundWhenUnset() throws Exception {
        Files.createDirectories(tempDir.resolve("home"));

        withProperties(homeOnly(tempDir.resolve("home").toString()),
                () -> assertThat(printEnv()).contains("cloud active: no afon.cloud property found"));
    }

    @Test
    public void printsLoggingLevels() throws Exception {
        Files.createDirectories(tempDir.resolve("home"));
        Map<String, String> props = homeOnly(tempDir.resolve("home").toString());
        props.put(AFON_LOGGING_PROPERTY, "WARN");
        props.put(AFON_LOGGING_CONSOLE_PROPERTY, "ERROR");

        withProperties(props, () -> {
            String output = printEnv();
            assertThat(output).contains("logging level: WARN");
            assertThat(output).contains("logging console level: ERROR");
        });
    }

}
