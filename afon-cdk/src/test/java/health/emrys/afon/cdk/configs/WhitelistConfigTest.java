package health.emrys.afon.cdk.configs;

import health.emrys.afon.config.AfonCloud;
import health.emrys.afon.config.FilePropertyProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WhitelistConfigTest {

    private static final String TEMPLATES_CONFIG_DIR = "../templates/config";

    private static final String PROPERTIES = """
        OIDC_DOMAIN_NAME=oidc.example
        AWS_POLARIS_DOMAIN_NAME=pol-aws.example
        AWS_JUPYTER_DOMAIN_NAME=jup-aws.example
        AWS_REGION=eu-west-1
        AZURE_POLARIS_DOMAIN_NAME=pol-az.example
        AZURE_OIDC_DOMAIN_NAME=oidc-az.example
        AZURE_STORAGE_ACCOUNT=store
        AWS_CATALOG_IDS=one,two,three
        AWS_CATALOG_TYPE_one=internal
        AWS_BUCKET_NAME_one=bucket-1
        AWS_CATALOG_TYPE_two=internal
        AWS_BUCKET_NAME_two=bucket-2
        AWS_CATALOG_TYPE_three=external
        AWS_BUCKET_NAME_three=bucket-2
        """;

    private static final String WHITELIST = """
        common:
          domains:
            - pypi.org
        aws:
          domains:
            - ${OIDC_DOMAIN_NAME}
            - ${POLARIS_DOMAIN_NAME}
            - ${JUPYTER_DOMAIN_NAME}
          catalogs:
            - ${BUCKET_NAME}.s3.${AWS_REGION}.amazonaws.com
        azure:
          domains:
            - ${OIDC_DOMAIN_NAME}
            - ${POLARIS_DOMAIN_NAME}
            - ${JUPYTER_DOMAIN_NAME}
            - ${AZURE_STORAGE_ACCOUNT}.blob.core.windows.net
        gcp:
          domains:
            - ${GCP_UNSET_PROPERTY}.example
            - storage.googleapis.com
        """;

    @TempDir
    Path dir;

    private WhitelistConfig whitelist(String yaml) throws IOException {
        Files.writeString(dir.resolve("afon.properties"), PROPERTIES);
        Files.writeString(dir.resolve("whitelist.yaml"), yaml);
        return new WhitelistConfig(new FilePropertyProvider(dir.toString(), List.of("afon")), dir.toString());
    }

    @Test
    void awsDomainsAreThoseOfCommonAndAws() throws IOException {
        assertThat(whitelist(WHITELIST).domains(AfonCloud.AWS))
            .containsExactly("pypi.org", "oidc.example", "pol-aws.example", "jup-aws.example",
                             "bucket-1.s3.eu-west-1.amazonaws.com", "bucket-2.s3.eu-west-1.amazonaws.com");
    }

    @Test
    void cloudAgnosticNamesAreThoseOfTheCloudWhichUsesItsOwnOidcDomainIfItHasOne() throws IOException {
        assertThat(whitelist(WHITELIST).domains(AfonCloud.AZURE))
            .containsExactly("pypi.org", "oidc-az.example", "pol-az.example", "store.blob.core.windows.net");
    }

    @Test
    void entriesWithAPropertyNotSetAreLeftOut() throws IOException {
        assertThat(whitelist(WHITELIST).domains(AfonCloud.GCP)).containsExactly("pypi.org", "storage.googleapis.com");
    }

    @Test
    void allDomainsAreThoseOfEverySectionWithoutDuplicates() throws IOException {
        assertThat(whitelist(WHITELIST).domains())
            .containsExactly("pypi.org", "oidc.example", "pol-aws.example", "jup-aws.example",
                             "bucket-1.s3.eu-west-1.amazonaws.com", "bucket-2.s3.eu-west-1.amazonaws.com",
                             "oidc-az.example", "pol-az.example", "store.blob.core.windows.net", "storage.googleapis.com");
    }

    @Test
    void missingSectionsHaveNoDomains() throws IOException {
        assertThat(whitelist("common:\n  domains: [pypi.org]\n").domains(AfonCloud.AWS)).containsExactly("pypi.org");
    }

    @Test
    void missingFileFallsBackToTheDeprecatedCsv() throws IOException {
        Files.writeString(dir.resolve("afon.properties"), "ALLOWED_DOMAINS_CSV=a.example, b.example,\n");
        WhitelistConfig whitelist = new WhitelistConfig(new FilePropertyProvider(dir.toString(), List.of("afon")), dir.toString());
        assertThat(whitelist.domains(AfonCloud.AZURE)).containsExactly("a.example", "b.example");
    }

    @Test
    void missingFileFails() {
        assertThatThrownBy(() -> new WhitelistConfig(new FilePropertyProvider(dir.toString(), List.of()), dir.toString()))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("whitelist.yaml");
    }

    @Test
    void unknownSectionFails() {
        assertThatThrownBy(() -> whitelist("mars:\n  domains: [a.example]\n"))
            .hasMessageContaining("unknown section 'mars'");
    }

    @Test
    void unknownKeyFails() {
        assertThatThrownBy(() -> whitelist("aws:\n  domain: [a.example]\n"))
            .hasMessageContaining("unknown key 'domain'");
    }

    @Test
    void catalogsOfCommonFail() {
        assertThatThrownBy(() -> whitelist("common:\n  catalogs: [a.example]\n"))
            .hasMessageContaining("unknown key 'catalogs'");
    }

    @Test
    void domainsThatAreNotAListFail() {
        assertThatThrownBy(() -> whitelist("aws:\n  domains: a.example\n"))
            .hasMessageContaining("aws.domains must be a list");
    }

    @Test
    void theTemplateIsValid() {
        WhitelistConfig whitelist = new WhitelistConfig(new FilePropertyProvider(TEMPLATES_CONFIG_DIR), TEMPLATES_CONFIG_DIR);
        assertThat(whitelist.domains()).isNotNull();
    }
}
