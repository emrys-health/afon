package health.emrys.afon.ops.synth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * Snapshots of the synthesised plans of the stacks, to check that a change, eg a refactoring, leaves them as they were.
 * <p>
 * A snapshot is a dir holding, for each stack, the plan that was synthesised into the stacks dir, as {@code <stack-id>/cdk.tf.json},
 * ie the layout of the stacks dir, and nothing else.  It does not depend on anything but the files: a stacks dir can be compared
 * as well as a snapshot.  The renaming of a resource, which makes tofu destroy and recreate it, shows as one removed and one added.
 */
public final class PlanSnapshots {

    static final String PLAN_FILE = "cdk.tf.json";
    private static final int MAX_VALUE = 200;
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Set<String> ADDRESSED = Set.of("resource", "data");

    private PlanSnapshots() {
    }

    /**
     * Copies the plan of each stack that was synthesised in the stacks dir to the target dir.
     *
     * @param stacksDir the dir holding the stack dirs, as synthesised
     * @param target    the dir to copy to, which is created if need be
     * @param stacks    the ids of the stacks to copy, or none for all of those that were synthesised
     * @return the ids of the stacks that were copied
     * @throws IllegalArgumentException if a stack was not synthesised
     */
    public static List<String> snapshot(Path stacksDir, Path target, List<String> stacks) throws IOException {
        List<String> ids = stacks.isEmpty() ? synthesised(stacksDir) : stacks;
        for (String id : ids) {
            if (!Files.isRegularFile(stacksDir.resolve(id).resolve(PLAN_FILE))) {
                throw new IllegalArgumentException("Stack " + id + " not synthesised in " + stacksDir);
            }
        }
        for (String id : ids) {
            Path plan = Files.createDirectories(target.resolve(id)).resolve(PLAN_FILE);
            Files.copy(stacksDir.resolve(id).resolve(PLAN_FILE), plan, StandardCopyOption.REPLACE_EXISTING);
        }
        return ids;
    }

    /**
     * @return the ids of the stacks that were synthesised in the dir, sorted
     */
    public static List<String> synthesised(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> children = Files.list(dir)) {
            return children.filter(child -> Files.isRegularFile(child.resolve(PLAN_FILE)))
                           .map(child -> child.getFileName().toString()).sorted().toList();
        }
    }

    /**
     * The differences between the plans of the stacks of two dirs.
     *
     * @param lines   the report, one line for each stack and for each difference
     * @param differs whether any stack differs
     */
    public record Comparison(List<String> lines, boolean differs) {
    }

    /**
     * Compares the plans of the stacks of two dirs, each a snapshot or a stacks dir: first the resources that were added and removed,
     * then the values that changed in the others, with the long ones, eg scripts, reduced to their hash.  The order of the {@code depends_on} of a
     * resource does not matter to tofu, so it does not count.
     */
    public static Comparison compare(Path before, Path after) throws IOException {
        return compare(before, after, true);
    }

    /**
     * As {@link #compare(Path, Path)}, but with the values left out of the report if {@code showValues} is false, ie
     * only the paths that differ are given, as the plans of a stack hold its config, eg its secrets.
     */
    public static Comparison compare(Path before, Path after, boolean showValues) throws IOException {
        List<String> beforeStacks = synthesised(before);
        List<String> afterStacks = synthesised(after);
        if (beforeStacks.isEmpty() || afterStacks.isEmpty()) {
            throw new IllegalArgumentException("No plans in " + (beforeStacks.isEmpty() ? before : after));
        }
        Set<String> stacks = new TreeSet<>(beforeStacks);
        stacks.addAll(afterStacks);
        List<String> lines = new ArrayList<>();
        boolean differs = false;
        for (String stack : stacks) {
            if (!beforeStacks.contains(stack) || !afterStacks.contains(stack)) {
                lines.add("== " + stack + ": only in " + (beforeStacks.contains(stack) ? before : after));
                differs = true;
                continue;
            }
            List<String> changes = compareStack(flatten(before.resolve(stack).resolve(PLAN_FILE)),
                                                flatten(after.resolve(stack).resolve(PLAN_FILE)), showValues);
            lines.add("== " + stack + ": " + (changes.isEmpty() ? "unchanged" : "CHANGED"));
            changes.stream().map(change -> "   " + change).forEach(lines::add);
            differs |= !changes.isEmpty();
        }
        lines.add(differs ? "The plans differ" : "The plans are the same");
        return new Comparison(lines, differs);
    }

    private static List<String> compareStack(Map<String, String> before, Map<String, String> after, boolean showValues) {
        Set<String> beforeAddresses = addresses(before);
        Set<String> afterAddresses = addresses(after);
        List<String> changes = new ArrayList<>();
        beforeAddresses.stream().filter(address -> !afterAddresses.contains(address))
                       .forEach(address -> changes.add("- gone (tofu destroys it): " + address));
        afterAddresses.stream().filter(address -> !beforeAddresses.contains(address))
                      .forEach(address -> changes.add("+ new (tofu creates it):   " + address));
        Set<String> paths = new TreeSet<>(before.keySet());
        paths.addAll(after.keySet());
        for (String path : paths) {
            String address = address(path);
            if (!beforeAddresses.contains(address) || !afterAddresses.contains(address)) {
                continue;
            }
            String was = before.get(path);
            String now = after.get(path);
            if (was == null) {
                changes.add("  + " + path + (showValues ? " = " + now : ""));
            } else if (now == null) {
                changes.add("  - " + path + (showValues ? " = " + was : ""));
            } else if (!was.equals(now)) {
                changes.add("  ~ " + path + (showValues ? ": " + was + " -> " + now : ""));
            }
        }
        return changes;
    }

    private static Set<String> addresses(Map<String, String> leaves) {
        Set<String> addresses = new TreeSet<>();
        leaves.keySet().forEach(path -> addresses.add(address(path)));
        return addresses;
    }

    /**
     * @return the address of the resource or data source that the path is in, eg {@code aws_eip.natEip}, or its first element
     */
    private static String address(String path) {
        String[] parts = path.split("\\.", -1);
        if (parts.length >= 3 && ADDRESSED.contains(parts[0])) {
            return ("data".equals(parts[0]) ? "data." : "") + parts[1] + "." + parts[2];
        }
        return parts[0];
    }

    /**
     * @return the leaves of the plan, by their path, eg {@code resource.aws_eip.natEip.tags.Name}, the values shortened if long
     */
    static Map<String, String> flatten(Path plan) throws IOException {
        Map<String, String> leaves = new TreeMap<>();
        try {
            flatten("", MAPPER.readTree(Files.readString(plan)), leaves);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read " + plan, e);
        }
        return leaves;
    }

    private static void flatten(String path, JsonNode node, Map<String, String> leaves) {
        if (node.isObject()) {
            node.fields().forEachRemaining(field -> flatten(path.isEmpty() ? field.getKey() : path + "." + field.getKey(),
                                                            field.getValue(), leaves));
        } else if (node.isArray()) {
            boolean unordered = path.endsWith(".depends_on");
            List<String> elements = new ArrayList<>();
            node.forEach(element -> elements.add(element.isTextual() ? element.asText() : element.toString()));
            if (unordered) {
                elements.sort(null);
            }
            if (unordered || node.size() == 0 || allScalar(node)) {
                leaves.put(path, abbreviate(elements.toString()));
            } else {
                for (int i = 0; i < node.size(); i++) {
                    flatten(path + "[" + i + "]", node.get(i), leaves);
                }
            }
        } else {
            leaves.put(path, abbreviate(node.isTextual() ? node.asText() : node.toString()));
        }
    }

    private static boolean allScalar(JsonNode array) {
        for (JsonNode element : array) {
            if (element.isContainerNode()) {
                return false;
            }
        }
        return true;
    }

    private static String abbreviate(String value) {
        return value.length() <= MAX_VALUE ? value : String.format("<%d chars, sha256 %s>", value.length(), hash(value));
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)), 0, 8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
