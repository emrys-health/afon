package health.emrys.afon.project;

import java.util.List;

/**
 * Thrown when a {@code project.yaml} is not valid, listing every problem found rather than the first.
 */
public class ProjectException extends RuntimeException {

    private final List<String> problems;

    public ProjectException(String source, List<String> problems) {
        super(String.format("%s is not a valid project:%n  %s", source, String.join(String.format("%n  "), problems)));
        this.problems = List.copyOf(problems);
    }

    /**
     * @return the problems, each naming the path of the key it is about, eg {@code dataProviders[1].audience}
     */
    public List<String> problems() {
        return problems;
    }
}
