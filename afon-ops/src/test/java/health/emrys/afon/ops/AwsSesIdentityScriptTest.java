package health.emrys.afon.ops;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code scripts/aws/ses-identity.sh} calls {@code aws sesv2}: run here by bash with an {@code aws} that only records its arguments, so that what is asked
 * of SES is what is checked, and nothing is sent to AWS.
 */
class AwsSesIdentityScriptTest {

    private static final Path ROOT = Path.of("..").toAbsolutePath().normalize();

    @TempDir
    Path dir;

    private Path calls;
    private Path home;

    @BeforeEach
    void stubAws() throws IOException {
        calls = dir.resolve("calls");
        Path bin = Files.createDirectories(dir.resolve("bin"));
        Path aws = bin.resolve("aws");
        // records its arguments, one call to a line, and answers what the script reads of a domain: its DKIM tokens
        Files.writeString(aws, "#!/usr/bin/env bash\n"
                               + "echo \"$*\" >> '" + calls + "'\n"
                               + "case \"$*\" in\n"
                               + "  *DkimAttributes.Tokens*) echo 'tok1 tok2 tok3' ;;\n"
                               + "esac\n");
        aws.toFile().setExecutable(true);
        home = Files.createDirectories(dir.resolve("afon-home/config"));
        Files.writeString(home.resolve("afon.properties"), "KEYCLOAK_SMTP_HOST=email-smtp.eu-west-2.amazonaws.com\n");
    }

    private record Result(int status, String output) {
    }

    private Result run(Map<String, String> env, String... args) throws IOException, InterruptedException {
        List<String> command = new java.util.ArrayList<>(List.of("bash", ROOT + "/scripts/aws/ses-identity.sh"));
        command.addAll(List.of(args));
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        builder.environment().put("PATH", dir.resolve("bin") + ":" + System.getenv("PATH"));
        builder.environment().put("AFON_ROOT", ROOT.toString());
        builder.environment().put("AFON_HOME", home.getParent().toString());
        builder.environment().remove("AWS_REGION");
        builder.environment().putAll(env);
        Process process = builder.start();
        String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        return new Result(process.waitFor(), out);
    }

    private Result run(String... args) throws IOException, InterruptedException {
        return run(Map.of(), args);
    }

    private List<String> calls() throws IOException {
        return Files.isRegularFile(calls) ? Files.readAllLines(calls) : List.of();
    }

    @Test
    void usesTheRegionOfTheSmtpHostOfTheConfig() throws Exception {
        assertThat(run("list").status()).isZero();
        assertThat(calls()).hasSize(1);
        assertThat(calls().get(0)).startsWith("sesv2 list-email-identities").contains("--region eu-west-2");
    }

    @Test
    void takesTheRegionGivenBeforeTheHostBeforeTheEnv() throws Exception {
        run("list", "--region", "us-east-1");
        assertThat(calls().get(0)).contains("--region us-east-1");

        Files.writeString(home.resolve("afon.properties"), "ENV_NAME=dev\n");
        assertThat(run(Map.of("AWS_REGION", "eu-west-1"), "list").status()).isZero();
        assertThat(calls().get(1)).contains("--region eu-west-1");

        Result none = run("list");
        assertThat(none.status()).isEqualTo(1);
        assertThat(none.output()).contains("No region");
    }

    @Test
    void createsAnAddressAndSaysThatALinkIsToBeClicked() throws Exception {
        Result result = run("create", "rob@emrys.health");

        assertThat(result.status()).as(result.output()).isZero();
        assertThat(calls()).containsExactly("sesv2 create-email-identity --email-identity rob@emrys.health --region eu-west-2");
        assertThat(result.output()).contains("with a link", "24 hours", "status rob@emrys.health");
    }

    @Test
    void createsADomainAndPrintsTheRecordsToPublish() throws Exception {
        Result result = run("create", "emrys.health");

        assertThat(result.status()).as(result.output()).isZero();
        assertThat(calls().get(0)).isEqualTo("sesv2 create-email-identity --email-identity emrys.health --region eu-west-2");
        assertThat(result.output()).contains("tok1._domainkey.emrys.health  CNAME  tok1.dkim.amazonses.com",
                                             "tok2._domainkey.emrys.health  CNAME  tok2.dkim.amazonses.com",
                                             "tok3._domainkey.emrys.health  CNAME  tok3.dkim.amazonses.com");
    }

    @Test
    void showsTheStatusOfAnAddressAndOfADomainWithItsDkim() throws Exception {
        run("status", "rob@emrys.health");
        run("status", "emrys.health");

        assertThat(calls().get(0)).contains("get-email-identity --email-identity rob@emrys.health").contains("VerificationStatus").doesNotContain("Dkim");
        assertThat(calls().get(1)).contains("get-email-identity --email-identity emrys.health").contains("DkimAttributes.Status");
    }

    @Test
    void printsTheDnsRecordsOfADomainOnly() throws Exception {
        assertThat(run("dns", "emrys.health").output()).contains("tok1._domainkey.emrys.health  CNAME  tok1.dkim.amazonses.com");
        Result address = run("dns", "rob@emrys.health");
        assertThat(address.status()).isEqualTo(1);
        assertThat(address.output()).contains("needs a domain");
    }

    @Test
    void deletesAnIdentityAndShowsTheAccount() throws Exception {
        run("delete", "rob@emrys.health");
        run("account");

        assertThat(calls().get(0)).isEqualTo("sesv2 delete-email-identity --email-identity rob@emrys.health --region eu-west-2");
        assertThat(calls().get(1)).startsWith("sesv2 get-account --region eu-west-2").contains("ProductionAccessEnabled");
    }

    @Test
    void acceptsAPlusAddress() throws Exception {
        assertThat(run("create", "mauro.talevi+tre@emrys.health").status()).isZero();
        assertThat(calls().get(0)).contains("--email-identity mauro.talevi+tre@emrys.health");
    }

    @Test
    void refusesWhatIsNotAnAddressOrADomain() throws Exception {
        for (String bad : List.of("rob", "--help-me", "a b@x.org", "x@y", "rob@emrys.health; ls", "$(id)@x.org")) {
            Result result = run("create", bad);
            assertThat(result.status()).as(bad).isNotZero();
        }
        assertThat(calls()).isEmpty();
    }

    @Test
    void asksForProductionAccessOnlyWithYes() throws Exception {
        Result dry = run("production-access", "--website-url", "https://example.org", "--description", "Invitations to members");
        assertThat(dry.status()).isZero();
        assertThat(dry.output()).contains("Would ask AWS", "Nothing is sent: add --yes");
        assertThat(calls()).isEmpty();

        Result sent = run("production-access", "--website-url", "https://example.org", "--description", "Invitations to members",
                          "--contact", "me@example.org", "--yes");
        assertThat(sent.status()).as(sent.output()).isZero();
        assertThat(calls()).hasSize(1);
        assertThat(calls().get(0)).startsWith("sesv2 put-account-details --region eu-west-2 --production-access-enabled --mail-type TRANSACTIONAL")
                                  .contains("--website-url https://example.org", "--use-case-description Invitations to members",
                                            "--additional-contact-email-addresses me@example.org");
    }

    @Test
    void refusesProductionAccessWithoutAWebsiteAndADescriptionOrWithABadUrl() throws Exception {
        assertThat(run("production-access", "--yes").status()).isEqualTo(1);
        assertThat(run("production-access", "--website-url", "not a url", "--description", "x", "--yes").status()).isEqualTo(1);
        assertThat(calls()).isEmpty();
    }

    @Test
    void needsTheAwsCliAndAnAction() throws Exception {
        Files.delete(dir.resolve("bin/aws"));
        // with no aws of the stub, the one of the machine may be found: only the action is then checked
        assertThat(run().status()).isEqualTo(1);
        assertThat(run("dance").output()).contains("Unknown action");
    }
}
