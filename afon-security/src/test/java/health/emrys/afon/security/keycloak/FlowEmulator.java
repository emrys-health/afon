package health.emrys.afon.security.keycloak;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The authentication flows of a KeyCloak realm, as far as the admin API of them is used to make a project's login: a flow holds
 * executions, which are other flows or providers, each with a requirement and, for a provider, a configuration.
 */
final class FlowEmulator {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String BASE = "/admin/realms/polaris/authentication";

    /** An answer: its code and body. */
    record Answer(int code, String body) {
    }

    /** The executions of each flow, by alias. */
    final Map<String, List<Map<String, Object>>> flows = new LinkedHashMap<>();
    /** The configurations, by id. */
    final Map<String, Map<String, Object>> configs = new LinkedHashMap<>();
    private int next;

    /** @return the answer to the call, or null if it is not about authentication flows */
    Answer handle(String method, String path, String body) throws IOException {
        if (!path.startsWith(BASE)) {
            return null;
        }
        String rest = path.substring(BASE.length());
        Matcher matcher;
        if (rest.equals("/flows") && method.equals("GET")) {
            List<Map<String, Object>> list = new ArrayList<>();
            flows.keySet().forEach(alias -> list.add(Map.of("alias", alias, "id", "flow-" + alias)));
            return json(list);
        }
        if (rest.equals("/flows") && method.equals("POST")) {
            flows.putIfAbsent(String.valueOf(MAPPER.readValue(body, Map.class).get("alias")), new ArrayList<>());
            return new Answer(201, "");
        }
        if ((matcher = Pattern.compile("/flows/([^/]+)/executions").matcher(rest)).matches()) {
            List<Map<String, Object>> executions = flows.get(matcher.group(1));
            if (executions == null) {
                return new Answer(404, "");
            }
            if (method.equals("GET")) {
                return json(listing(matcher.group(1), 0));
            }
            Map<String, Object> changed = MAPPER.readValue(body, Map.class);
            find(String.valueOf(changed.get("id"))).put("requirement", changed.get("requirement"));
            return new Answer(204, "");
        }
        if ((matcher = Pattern.compile("/flows/([^/]+)/executions/(execution|flow)").matcher(rest)).matches() && method.equals("POST")) {
            List<Map<String, Object>> executions = flows.get(matcher.group(1));
            if (executions == null) {
                return new Answer(404, "");
            }
            Map<String, Object> request = MAPPER.readValue(body, Map.class);
            Map<String, Object> execution = new LinkedHashMap<>();
            execution.put("id", "execution-" + next++);
            execution.put("requirement", "DISABLED");
            if (matcher.group(2).equals("flow")) {
                String alias = String.valueOf(request.get("alias"));
                execution.put("authenticationFlow", true);
                execution.put("displayName", alias);
                flows.putIfAbsent(alias, new ArrayList<>());
            } else {
                execution.put("authenticationFlow", false);
                execution.put("providerId", request.get("provider"));
                execution.put("displayName", request.get("provider"));
            }
            executions.add(execution);
            return new Answer(201, "");
        }
        if ((matcher = Pattern.compile("/executions/([^/]+)/config").matcher(rest)).matches() && method.equals("POST")) {
            String id = "config-" + next++;
            Map<String, Object> config = MAPPER.readValue(body, Map.class);
            config.put("id", id);
            configs.put(id, config);
            find(matcher.group(1)).put("authenticationConfig", id);
            return new Answer(201, "");
        }
        if ((matcher = Pattern.compile("/config/([^/]+)").matcher(rest)).matches() && method.equals("PUT")) {
            configs.put(matcher.group(1), MAPPER.readValue(body, Map.class));
            return new Answer(202, "");
        }
        return null;
    }

    private List<Map<String, Object>> listing(String alias, int level) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (Map<String, Object> execution : flows.get(alias)) {
            Map<String, Object> shown = new LinkedHashMap<>(execution);
            shown.put("level", level);
            list.add(shown);
            if (Boolean.TRUE.equals(execution.get("authenticationFlow"))) {
                list.addAll(listing(String.valueOf(execution.get("displayName")), level + 1));
            }
        }
        return list;
    }

    private Map<String, Object> find(String id) {
        return flows.values().stream().flatMap(List::stream).filter(execution -> id.equals(execution.get("id"))).findFirst().orElseThrow();
    }

    /** @return the executions that the flow holds directly */
    List<Map<String, Object>> children(String alias) {
        return flows.get(alias);
    }

    /** @return the configuration of the execution, or null if it has none */
    Map<String, Object> configOf(Map<String, Object> execution) {
        return configs.get(String.valueOf(execution.get("authenticationConfig")));
    }

    private static Answer json(Object value) throws IOException {
        return new Answer(200, MAPPER.writeValueAsString(value));
    }
}
