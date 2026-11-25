package health.emrys.afon.project;

import java.util.List;

/**
 * Thrown when a {@code project.yaml} is not valid, listing every problem found rather than the first.
 */
public class ProjectStackException extends RuntimeException {

    private final List<String> problems;

    public ProjectStackException(String source, List<String> problems) {
        super(String.format("%s is not a valid project stack:%n  %s", source, String.join(String.format("%n  "), problems)));
        this.problems = List.copyOf(problems);
    }

    /**
     * @return the problems, each naming the path of the key it is about, eg {@code dataProviders[1].audience}
     */
    public List<String> problems() {
        return problems;
    }
}
