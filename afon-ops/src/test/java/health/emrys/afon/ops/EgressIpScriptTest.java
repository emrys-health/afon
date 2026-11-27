package health.emrys.afon.ops;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code scripts/<cloud>/egress-ip.sh delete} releases the address that a stack reserved for its egress, unless it is in use: run here by bash with a stub of the cloud's command that
 * says what the address is and records what is deleted, so that nothing reaches a cloud.
 */
class EgressIpScriptTest {

    private static final Path ROOT = Path.of("..").toAbsolutePath().normalize();

    @TempDir
    Path dir;

    private record Result(int status, String output) {
    }

    /** What the stub of the cloud says of the address: whether it exists, and whether a stack uses it. */
    private record Address(boolean exists, boolean inUse) {
    }

    private void write(Path file, String text) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
    }

    private Path calls() {
        return dir.resolve("calls");
    }

    private List<String> deleted() throws IOException {
        return Files.exists(calls()) ? Files.readAllLines(calls()) : List.of();
    }

    private Result run(String cloud, Address address) throws IOException, InterruptedException {
        Path config = dir.resolve("home/config");
        write(config.resolve("afon.properties"), "X=1\n");
        write(config.resolve("secret.properties"), "");
        write(config.resolve("catalogs.properties"), "");
        write(config.resolve("catalogs-" + cloud + ".properties"), "");
        write(config.resolve("afon-" + cloud + ".properties"), switch (cloud) {
            case "aws" -> "AWS_REGION=eu-west-2\n";
            case "azure" -> "AZURE_RESOURCE_GROUP=rg\nAZURE_REGION=uksouth\n";
            default -> "GCP_PROJECT_ID=proj\nGCP_REGION=europe-west2\n";
        });
        String exists = address.exists() ? "1" : "0";
        String used = address.inUse() ? "1" : "0";
        String stub = switch (cloud) {
            // describe-addresses is asked for one field at a time, by --query "Addresses[0].<field>": None for an address that is not there
            case "aws" -> "#!/usr/bin/env bash\ncase \"$*\" in\n"
                          + "  *release-address*) echo \"release $*\" >> '" + calls() + "' ;;\n"
                          + "  *Addresses\\[0\\].PublicIp*) [ " + exists + " = 1 ] && echo 203.0.113.7 || echo None ;;\n"
                          + "  *Addresses\\[0\\].AllocationId*) [ " + exists + " = 1 ] && echo eipalloc-123 || echo None ;;\n"
                          + "  *Addresses\\[0\\].AssociationId*) [ " + used + " = 1 ] && echo eipassoc-456 || echo None ;;\n"
                          + "  *) echo None ;;\nesac\n";
            case "azure" -> "#!/usr/bin/env bash\ncase \"$*\" in\n"
                            + "  *'public-ip delete'*) echo \"delete $*\" >> '" + calls() + "' ;;\n"
                            + "  *'-o none'*) [ " + exists + " = 1 ] ;;\n"
                            + "  *ipAddress*) echo 203.0.113.7 ;;\n"
                            + "  *ipConfiguration.id*) [ " + used + " = 1 ] && echo /subscriptions/x/loadBalancers/lb/frontendIPConfigurations/f ;;\nesac\n";
            default -> "#!/usr/bin/env bash\ncase \"$*\" in\n"
                       + "  *'addresses delete'*) echo \"delete $*\" >> '" + calls() + "' ;;\n"
                       + "  *'value(address)'*) [ " + exists + " = 1 ] && echo 203.0.113.7 || exit 1 ;;\n"
                       + "  *'value(status)'*) [ " + used + " = 1 ] && echo IN_USE || echo RESERVED ;;\nesac\n";
        };
        Path stubFile = dir.resolve("bin").resolve(switch (cloud) {
            case "aws" -> "aws";
            case "azure" -> "az";
            default -> "gcloud";
        });
        write(stubFile, stub);
        stubFile.toFile().setExecutable(true);
        ProcessBuilder builder = new ProcessBuilder("bash", ROOT + "/scripts/" + cloud + "/egress-ip.sh", "delete", "afon-egress-ip-id-test")
            .redirectErrorStream(true).directory(ROOT.resolve("scripts").toFile());
        builder.environment().put("AFON_ROOT", ROOT.toString());
        builder.environment().put("AFON_HOME", dir.resolve("home").toString());
        builder.environment().put("PATH", dir.resolve("bin") + ":" + System.getenv("PATH"));
        builder.environment().remove("AFON_ENV");
        Process process = builder.start();
        String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        return new Result(process.waitFor(), out);
    }

    @Test
    void releasesAnAddressThatNothingUsesAndSaysWhatItReleases() throws Exception {
        for (String cloud : List.of("aws", "azure", "gcp")) {
            Files.deleteIfExists(calls());
            Result result = run(cloud, new Address(true, false));

            assertThat(result.status()).as(cloud + ": " + result.output()).isZero();
            assertThat(result.output()).as(cloud).contains("203.0.113.7", "afon-egress-ip-id-test");
            assertThat(deleted()).as(cloud).hasSize(1);
            // AWS releases by the allocation id, the others delete by name
            assertThat(deleted().get(0)).as(cloud).contains(cloud.equals("aws") ? "eipalloc-123" : "afon-egress-ip-id-test");
        }
    }

    @Test
    void refusesAnAddressThatAStackUsesAndDeletesNothing() throws Exception {
        for (String cloud : List.of("aws", "azure", "gcp")) {
            Files.deleteIfExists(calls());
            Result result = run(cloud, new Address(true, true));

            assertThat(result.status()).as(cloud + ": " + result.output()).isEqualTo(1);
            assertThat(result.output()).as(cloud).contains("a stack uses it", "nothing");
            assertThat(deleted()).as(cloud).isEmpty();
        }
    }

    @Test
    void doesNothingAndSucceedsForAnAddressThatIsNotThere() throws Exception {
        for (String cloud : List.of("aws", "azure", "gcp")) {
            Files.deleteIfExists(calls());
            Result result = run(cloud, new Address(false, false));

            assertThat(result.status()).as(cloud + ": " + result.output()).isZero();
            assertThat(result.output()).as(cloud).contains("does not exist", "nothing to");
            assertThat(deleted()).as(cloud).isEmpty();
        }
    }
}
