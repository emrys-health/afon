package health.emrys.afon.cdk;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CdkUtilsTest {

    /**
     * Map.of's order is fixed within one JVM, so building the same manifest twice here could not
     * catch a regression; insertion order is varied explicitly instead.
     */
    @Test
    void canonicalJsonIgnoresMapInsertionOrder() {
        Map<String, Object> innerForwards = new LinkedHashMap<>();
        innerForwards.put("a", "1");
        innerForwards.put("b", "2");
        Map<String, Object> innerBackwards = new LinkedHashMap<>();
        innerBackwards.put("b", "2");
        innerBackwards.put("a", "1");

        Map<String, Object> forwards = new LinkedHashMap<>();
        forwards.put("kind", "CiliumNetworkPolicy");
        forwards.put("spec", List.of(innerForwards));
        Map<String, Object> backwards = new LinkedHashMap<>();
        backwards.put("spec", List.of(innerBackwards));
        backwards.put("kind", "CiliumNetworkPolicy");

        assertThat(CdkUtils.toCanonicalJson(forwards)).isEqualTo(CdkUtils.toCanonicalJson(backwards));
    }

    @Test
    void canonicalJsonPreservesListOrder() {
        assertThat(CdkUtils.toCanonicalJson(List.of("b", "a"))).isEqualTo("[\"b\",\"a\"]");
    }
}
