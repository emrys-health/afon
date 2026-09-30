package health.emrys.afon.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class AfonUserAclTest {

    @Test
    public void canAuthoriseAllCatalogs() {
        AfonUserAcl acl = new AfonUserAcl("will", "all");
        assertThat(acl.isAuthorised("aws")).isTrue();
        assertThat(acl.isAuthorised("aws_forbidden")).isTrue();
    }

    @Test
    public void canAuthoriseUndefinedCatalogs() {
        AfonUserAcl acl = new AfonUserAcl("will", AfonUserAcl.UNDEFINED);
        assertThat(acl.isAuthorised("aws")).isTrue();
    }

    @Test
    public void canAuthoriseListedCatalogs() {
        AfonUserAcl acl = new AfonUserAcl("will", "azure:azure_external");
        assertThat(acl.isAuthorised("azure")).isTrue();
        assertThat(acl.isAuthorised("azure_external")).isTrue();
        assertThat(acl.isAuthorised("aws")).isFalse();
    }

    @Test
    public void canExcludeCatalogsFromAll() {
        AfonUserAcl acl = new AfonUserAcl("will", "all:!aws_forbidden:!azure_forbidden:!gcp_forbidden");
        assertThat(acl.isAuthorised("aws")).isTrue();
        assertThat(acl.isAuthorised("azure")).isTrue();
        assertThat(acl.isAuthorised("aws_forbidden")).isFalse();
        assertThat(acl.isAuthorised("azure_forbidden")).isFalse();
        assertThat(acl.isAuthorised("gcp_forbidden")).isFalse();
    }

    @Test
    public void canExcludeExplicitlyIncludedCatalogs() {
        AfonUserAcl acl = new AfonUserAcl("will", "aws:aws_forbidden:!aws_forbidden");
        assertThat(acl.isAuthorised("aws")).isTrue();
        assertThat(acl.isAuthorised("aws_forbidden")).isFalse();
    }

    @Test
    public void canExcludeCatalogsByWildcard() {
        AfonUserAcl acl = new AfonUserAcl("will", "all:!*_forbidden");
        assertThat(acl.isAuthorised("aws")).isTrue();
        assertThat(acl.isAuthorised("aws_forbidden")).isFalse();
        assertThat(acl.isAuthorised("gcp_forbidden")).isFalse();
        assertThat(acl.isAuthorised("forbidden")).as("not excluded: wildcard requires the underscore").isTrue();
    }

    @Test
    public void canIncludeCatalogsByWildcard() {
        AfonUserAcl acl = new AfonUserAcl("will", "aws*:!aws_forbidden");
        assertThat(acl.isAuthorised("aws")).isTrue();
        assertThat(acl.isAuthorised("aws_external")).isTrue();
        assertThat(acl.isAuthorised("aws_forbidden")).isFalse();
        assertThat(acl.isAuthorised("azure")).isFalse();
    }

    @Test
    public void canMatchWildcardNamesLiterally() {
        AfonUserAcl acl = new AfonUserAcl("will", "a.s*");
        assertThat(acl.isAuthorised("a.s_x")).isTrue();
        assertThat(acl.isAuthorised("aws")).isFalse();
    }

    @Test
    public void cannotAuthoriseWithOnlyExclusions() {
        AfonUserAcl acl = new AfonUserAcl("will", "!aws_forbidden");
        assertThat(acl.isAuthorised("aws")).isFalse();
        assertThat(acl.isAuthorised("aws_forbidden")).isFalse();
    }
}
