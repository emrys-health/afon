package health.emrys.afon.ops.project;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ProjectMembersTest {

    @TempDir
    Path dir;

    @Test
    void writesAndReadsTheMembersByUserIdWithACommentAtTheTop() throws IOException {
        Path file = dir.resolve("projects/p/members.csv");
        Map<String, String> members = new LinkedHashMap<>();
        members.put("id-1", "a@x.org");
        members.put("id-2", "b@x.org");
        ProjectMembers.write(file, members);

        assertThat(Files.readString(file)).startsWith("# ").endsWith("id-1,a@x.org\nid-2,b@x.org\n");
        assertThat(ProjectMembers.read(file)).containsExactly(Map.entry("id-1", "a@x.org"), Map.entry("id-2", "b@x.org"));
        assertThat(ProjectMembers.read(dir.resolve("none.csv"))).isEmpty();
    }

    @Test
    void readsALineWithTheUserIdAlone() throws IOException {
        Path file = Files.writeString(dir.resolve("m.csv"), "# c\n\nid-1\n  id-2 , b@x.org \n");
        assertThat(ProjectMembers.read(file)).containsExactly(Map.entry("id-1", ""), Map.entry("id-2", "b@x.org"));
    }

    @Test
    void sayswhatTheMembersFileHasMoreAndLessThanTheMembersTheProjectListsByEmailWhateverTheCase() {
        Map<String, String> inFile = new LinkedHashMap<>();
        inFile.put("id-1", "Alice@x.org");
        inFile.put("id-3", "carol@x.org");
        inFile.put("id-4", "");

        ProjectMembers.Drift drift = ProjectMembers.drift(inFile, List.of("alice@x.org", "BOB@x.org"));

        assertThat(drift.missing()).containsExactly("bob@x.org");
        assertThat(drift.extra()).containsExactly("carol@x.org");
        assertThat(drift.none()).isFalse();
        assertThat(ProjectMembers.drift(inFile, List.of("alice@x.org", "carol@x.org")).none()).isTrue();
    }
}
