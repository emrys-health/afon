package health.emrys.afon.cdk.component;

import health.emrys.afon.config.StackId;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static health.emrys.afon.config.AfonCloud.AWS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StackContextTest {

    private final StackId       stackId = new StackId(AWS, "full");
    private final StackContext  context = new StackContext(null, stackId, null, null);
    private final List<String>  deployed = new ArrayList<>();

    class First implements StackComponent {
        public void deploy(StackContext context) {
            deployed.add("first");
        }
    }

    class Second implements StackComponent {
        public void deploy(StackContext context) {
            deployed.add("second after " + context.require(First.class).getClass().getSimpleName());
        }
    }

    @Test
    void deploysComponentsInOrderAndFindsThoseDeployed() {
        First first = context.deploy(new First());
        context.deploy(new Second());

        assertThat(deployed).containsExactly("first", "second after First");
        assertThat(context.find(First.class)).containsSame(first);
    }

    @Test
    void cannotRequireComponentNotDeployed() {
        assertThat(context.find(First.class)).isEmpty();
        assertThatThrownBy(() -> context.deploy(new Second()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("First is required but not deployed to aws-full");
        assertThat(context.find(Second.class)).isEmpty();
    }

    @Test
    void cannotDeployComponentTwice() {
        context.deploy(new First());
        assertThatThrownBy(() -> context.deploy(new First()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("First is already deployed to aws-full");
    }
}
