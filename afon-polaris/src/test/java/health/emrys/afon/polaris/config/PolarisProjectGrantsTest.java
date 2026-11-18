package health.emrys.afon.polaris.config;

import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.polaris.core.admin.model.NamespacePrivilege;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.IOException;
import java.util.List;

import static health.emrys.afon.polaris.config.PolarisProjectGrants.READ_ONLY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What a Data Provider grants a project: the principal role and catalog role of the project, read-only access to one namespace, and a principal for each
 * member it names.
 */
class PolarisProjectGrantsTest {

    private static final String ALICE = "11111111-aaaa-bbbb-cccc-000000000001";
    private static final String BOB = "22222222-aaaa-bbbb-cccc-000000000002";
    private static final String ROB = "33333333-aaaa-bbbb-cccc-000000000003";

    private final ClassicHttpResponse ok = mock(ClassicHttpResponse.class);
    private final CatalogConfigurer configurer = mock(CatalogConfigurer.class, invocation ->
        ClassicHttpResponse.class.isAssignableFrom(invocation.getMethod().getReturnType()) ? ok : Mockito.RETURNS_DEFAULTS.answer(invocation));
    private final PolarisProjectGrants grants = new PolarisProjectGrants(configurer);

    @BeforeEach
    void polaris() throws Exception {
        when(ok.getCode()).thenReturn(200);
        when(configurer.principalsOf(anyString())).thenReturn(List.of());
        when(configurer.principalRolesOf(anyString())).thenReturn(List.of());
        when(configurer.principalRoleExists(anyString())).thenReturn(true);
    }

    @Test
    void namesTheRolesOfAProjectByAFixedSuffixAndTheMemberByTheirIdentity() {
        assertThat(PolarisProjectGrants.projectPrincipalRole("cvd-2026")).isEqualTo("cvd-2026Project");
        assertThat(PolarisProjectGrants.projectCatalogRole("cvd-2026")).isEqualTo("cvd-2026Reader");
        assertThat(PolarisProjectGrants.memberPrincipal(ALICE)).isEqualTo("afon:" + ALICE);
    }

    @Test
    void refusesNamesThatAreNotOnesOrAreNotAUserId() {
        assertThatThrownBy(() -> PolarisProjectGrants.projectPrincipalRole("a b")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PolarisProjectGrants.projectPrincipalRole("../x")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PolarisProjectGrants.memberPrincipal("root")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PolarisProjectGrants.memberPrincipal("a/b-c-d-e-f-g")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PolarisProjectGrants.memberPrincipal(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void letsTheProjectReadOnlyTheReleasedNamespaceWithReadOnlyPrivileges() throws Exception {
        grants.grant("cvd-2026", "cvd", "released", List.of(ALICE));

        verify(configurer).createNamespaceIfAbsent("cvd", "staging");
        verify(configurer).createNamespaceIfAbsent("cvd", "released");
        verify(configurer).createPrincipalRole("cvd-2026Project");
        verify(configurer).createCatalogRole("cvd", "cvd-2026Reader");
        for (NamespacePrivilege privilege : READ_ONLY) {
            verify(configurer).createNamespaceGrant("cvd", "cvd-2026Reader", "released", privilege);
        }
        verify(configurer, times(READ_ONLY.size())).createNamespaceGrant(anyString(), anyString(), anyString(), any());
        verify(configurer).associatePrincipalRole("cvd", "cvd-2026Project", "cvd-2026Reader");
        // nothing on the namespace where tables are checked, and nothing that writes
        verify(configurer, never()).createNamespaceGrant(anyString(), anyString(), eq("staging"), any());
        assertThat(READ_ONLY).noneMatch(privilege -> privilege.name().contains("WRITE") || privilege.name().contains("CREATE")
                                                       || privilege.name().contains("DROP") || privilege.name().contains("MANAGE"));
    }

    @Test
    void makesAPrincipalForEachMemberNamedAndGivesItTheProjectsRole() throws Exception {
        PolarisProjectGrants.Members changed = grants.grant("cvd-2026", "cvd", "released", List.of(ALICE, BOB));

        verify(configurer).createPrincipal("afon:" + ALICE);
        verify(configurer).createPrincipal("afon:" + BOB);
        verify(configurer).associatePrincipalRole("afon:" + ALICE, "cvd-2026Project");
        verify(configurer).associatePrincipalRole("afon:" + BOB, "cvd-2026Project");
        assertThat(changed.added()).containsExactly("afon:" + ALICE, "afon:" + BOB);
        assertThat(changed.removed()).isEmpty();
    }

    @Test
    void makesTheMembersExactlyThoseNamedTakingTheRoleFromTheOthers() throws Exception {
        when(configurer.principalsOf("cvd-2026Project")).thenReturn(List.of("afon:" + ALICE, "afon:" + BOB, "operator"));
        when(configurer.principalRolesOf("afon:" + BOB)).thenReturn(List.of());
        when(configurer.principalRolesOf("afon:" + ALICE)).thenReturn(List.of("cvd-2026Project"));

        PolarisProjectGrants.Members changed = grants.grant("cvd-2026", "cvd", "released", List.of(ALICE, ROB));

        assertThat(changed.added()).containsExactly("afon:" + ROB);
        assertThat(changed.removed()).containsExactly("afon:" + BOB);
        verify(configurer).removePrincipalRole("afon:" + BOB, "cvd-2026Project");
        verify(configurer).deletePrincipal("afon:" + BOB);
        // alice keeps her principal and is not made again, and a principal that is not a member's is not for this to touch
        verify(configurer, never()).createPrincipal("afon:" + ALICE);
        verify(configurer, never()).removePrincipalRole(eq("operator"), anyString());
    }

    @Test
    void refusesToGrantTheNamespaceWhereTablesAreChecked() {
        assertThatThrownBy(() -> grants.grant("cvd-2026", "cvd", "staging", List.of(ALICE))).isInstanceOf(IllegalArgumentException.class)
                                                                                         .hasMessageContaining("staging");
    }

    @Test
    void keepsThePrincipalOfAMemberWhoHasAnotherProjectsRole() throws Exception {
        when(configurer.principalRolesOf("afon:" + ALICE)).thenReturn(List.of("dbt-2026Project"));
        grants.removeMember("cvd-2026", ALICE);

        verify(configurer).removePrincipalRole("afon:" + ALICE, "cvd-2026Project");
        verify(configurer, never()).deletePrincipal(anyString());
    }

    @Test
    void deletesThePrincipalOfAMemberWhoseLastRoleWasTheProjects() throws Exception {
        grants.removeMember("cvd-2026", ALICE);

        verify(configurer).removePrincipalRole("afon:" + ALICE, "cvd-2026Project");
        verify(configurer).deletePrincipal("afon:" + ALICE);
    }

    @Test
    void addsAMemberOnlyToAProjectThatHasBeenGranted() throws Exception {
        grants.addMember("cvd-2026", ALICE);
        verify(configurer).createPrincipal("afon:" + ALICE);
        verify(configurer).associatePrincipalRole("afon:" + ALICE, "cvd-2026Project");

        when(configurer.principalRoleExists("dbt-2026Project")).thenReturn(false);
        assertThatThrownBy(() -> grants.addMember("dbt-2026", ALICE)).isInstanceOf(IllegalStateException.class)
                                                                         .hasMessageContaining("grant it first");
    }

    @Test
    void revokesTheProjectsAccessAndTheMembersWhoHaveNoOtherRole() throws Exception {
        when(configurer.principalsOf("cvd-2026Project")).thenReturn(List.of("afon:" + ALICE, "afon:" + BOB));
        when(configurer.principalRolesOf("afon:" + ALICE)).thenReturn(List.of("dbt-2026Project"));

        List<String> removed = grants.revoke("cvd-2026", "cvd");

        assertThat(removed).containsExactly("afon:" + ALICE, "afon:" + BOB);
        verify(configurer).deletePrincipal("afon:" + BOB);
        verify(configurer, never()).deletePrincipal("afon:" + ALICE);
        verify(configurer).deletePrincipalRole("cvd-2026Project");
        verify(configurer).deleteCatalogRole("cvd", "cvd-2026Reader");
    }

    @Test
    void releasesATableByMovingItFromStagingToTheNamespaceTheProjectsRead() throws Exception {
        grants.release("cvd", "patients", "released");
        verify(configurer).renameTable("cvd", "staging", "released", "patients");
    }

    @Test
    void saysWhatPolarisRefusedWithItsCode() throws Exception {
        ClassicHttpResponse refused = mock(ClassicHttpResponse.class);
        when(refused.getCode()).thenReturn(403);
        when(configurer.createPrincipalRole(anyString())).thenReturn(refused);

        assertThatThrownBy(() -> grants.grant("cvd-2026", "cvd", "released", List.of(ALICE))).isInstanceOf(IOException.class)
            .hasMessageContaining("could not make the principal role cvd-2026Project").hasMessageContaining("HTTP 403");
        verify(configurer, never()).createPrincipal(anyString());
    }
}
