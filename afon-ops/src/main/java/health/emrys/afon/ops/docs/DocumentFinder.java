package health.emrys.afon.ops.docs;

import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.config.AfonUserProvider;
import health.emrys.afon.cdk.StackDefinitions;
import health.emrys.afon.config.AfonCloud;
import health.emrys.afon.ops.flow.StackFlows;
import health.emrys.afon.ops.plan.StackPlanFactory;
import health.emrys.afon.ops.steps.StackAction;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static health.emrys.afon.config.ConfigUtils.cloudPropertySet;
import static health.emrys.afon.config.ConfigUtils.cloudPropertyValue;
import static org.slf4j.LoggerFactory.getLogger;

public class DocumentFinder {
    private static final Logger logger = getLogger(DocumentFinder.class);
    private final AfonPropertyProvider properties;
    private final AfonUserProvider users;

    public DocumentFinder(AfonPropertyProvider properties, AfonUserProvider users) {
        this.properties = properties;
        this.users = users;
    }

    /**
     * @return the documents of every deploy and setup of a stack, and delete of a cloud, every flow, and every scenario of a stack, limited to those
     * of the configured cloud, if any, excluding those spanning clouds
     */
    public List<DocumentMetadata> findDocuments() {
        StackPlanFactory factory = new StackPlanFactory();
        List<DocumentMetadata> documents = new ArrayList<>();
        // deleting a stack is documented with deleting its cloud, which says how to delete just the stack
        factory.supportedActions().forEach((stackId, actions) -> actions.stream()
               .filter(action -> action != StackAction.DELETE || stackId.isAll())
               .forEach(action -> documents.add(new StackDocument(properties, users, action, stackId))));
        StackFlows.all().forEach(flow -> documents.add(new FlowDocument(properties, users, flow.name())));
        StackDefinitions.all().forEach(definition -> factory.scenarios(definition.id(), properties).keySet().forEach(
            scenario -> documents.add(new ScenarioDocument(properties, users, definition.id(), scenario))));
        if (cloudPropertySet()) {
            AfonCloud cloud = AfonCloud.valueOf(cloudPropertyValue().toUpperCase());
            logger.info("Keeping the documents of cloud {}", cloud);
            documents.removeIf(document -> !document.clouds().equals(Set.of(cloud)));
        }
        logger.info("Found {} documents", documents.size());
        return documents;
    }

}
