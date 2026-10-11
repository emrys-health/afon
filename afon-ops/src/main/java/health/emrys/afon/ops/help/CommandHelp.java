package health.emrys.afon.ops.help;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * The command-specific docs shown by {@code afon-cli <command> -h}, kept as {@code help/<command>.md} resources
 * named after the command, eg {@code help/show-env.md}.  A command without one handles {@code -h} itself, eg
 * {@code manage-stack}, whose usage lists the stacks, or is a script, whose own help is shown.
 */
public final class CommandHelp {

    public static final String RESOURCE_DIR = "help";
    public static final String GENERATED_DOCS_DIR = "docs";

    private CommandHelp() {
    }

    /**
     * @param command the command name in kebab-case, eg {@code show-env}
     * @return the docs of the command, if it has any
     */
    public static Optional<String> find(String command) {
        return read(resource(command));
    }

    /**
     * @param filename the file of a generated doc, see {@code health.emrys.afon.ops.docs}, eg {@code setup_aws_full_stack.md}, which
     *                 is bundled in the jar when it is packaged
     * @return the generated doc, if bundled
     */
    public static Optional<String> findGeneratedDoc(String filename) {
        return read(GENERATED_DOCS_DIR + "/" + filename);
    }

    private static Optional<String> read(String resource) {
        try (InputStream in = CommandHelp.class.getClassLoader().getResourceAsStream(resource)) {
            return in == null ? Optional.empty() : Optional.of(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read " + resource, e);
        }
    }

    static String resource(String command) {
        return RESOURCE_DIR + "/" + command + ".md";
    }

}
