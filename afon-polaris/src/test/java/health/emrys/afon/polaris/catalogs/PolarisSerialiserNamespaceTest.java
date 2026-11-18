package health.emrys.afon.polaris.catalogs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.polaris.core.admin.model.NamespacePrivilege;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The requests that grant a catalog role a privilege on a namespace, take it away, make a namespace and move a table.
 */
class PolarisSerialiserNamespaceTest {

    private final PolarisSerialiser serialiser = new PolarisSerialiser();
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void grantsAPrivilegeOnANamespace() throws Exception {
        JsonNode grant = mapper.readTree(serialiser.namespaceGrant(List.of("released"), NamespacePrivilege.TABLE_READ_DATA)).get("grant");
        assertThat(grant.get("type").asText()).isEqualTo("namespace");
        assertThat(grant.get("privilege").asText()).isEqualTo("TABLE_READ_DATA");
        assertThat(grant.get("namespace")).extracting(JsonNode::asText).containsExactly("released");
    }

    @Test
    void revokesAPrivilegeOnANamespaceAsAGrantIsMade() throws Exception {
        JsonNode grant = mapper.readTree(serialiser.namespaceRevoke(List.of("released"), NamespacePrivilege.TABLE_LIST)).get("grant");
        assertThat(grant.get("type").asText()).isEqualTo("namespace");
        assertThat(grant.get("privilege").asText()).isEqualTo("TABLE_LIST");
    }

    @Test
    void makesANamespaceAndMovesATable() throws Exception {
        JsonNode namespace = mapper.readTree(serialiser.namespaceCreation(List.of("staging")));
        assertThat(namespace.get("namespace")).extracting(JsonNode::asText).containsExactly("staging");
        JsonNode rename = mapper.readTree(serialiser.tableRename(List.of("staging"), "patients", List.of("released")));
        assertThat(rename.get("source").get("name").asText()).isEqualTo("patients");
        assertThat(rename.get("source").get("namespace")).extracting(JsonNode::asText).containsExactly("staging");
        assertThat(rename.get("destination").get("namespace")).extracting(JsonNode::asText).containsExactly("released");
    }
}
