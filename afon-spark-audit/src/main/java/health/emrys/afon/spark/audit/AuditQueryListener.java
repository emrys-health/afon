package health.emrys.afon.spark.audit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.spark.sql.catalyst.analysis.ResolvedIdentifier;
import org.apache.spark.sql.catalyst.analysis.ResolvedTable;
import org.apache.spark.sql.catalyst.plans.logical.LogicalPlan;
import org.apache.spark.sql.catalyst.plans.logical.V2WriteCommand;
import org.apache.spark.sql.connector.catalog.CatalogPlugin;
import org.apache.spark.sql.connector.catalog.Identifier;
import org.apache.spark.sql.execution.QueryExecution;
import org.apache.spark.sql.execution.datasources.v2.DataSourceV2Relation;
import org.apache.spark.sql.internal.SQLConf;
import org.apache.spark.sql.util.QueryExecutionListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scala.runtime.BoxedUnit;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static java.lang.String.format;

/**
 * Logs who ran what against the Iceberg (Polaris) tables, one line per query, to the logger
 * {@value #AUDIT_LOGGER}. Register it on the driver with
 * {@code spark.sql.queryExecutionListeners=health.emrys.afon.spark.audit.AuditQueryListener}. It works in classic Spark
 * as well as in Spark Connect, where each client's session is an ordinary server-side session that loads it too.
 *
 * <p>The user is the {@code preferred_username} (else {@code sub}) of the OIDC token the session gives the catalog in
 * {@code spark.sql.catalog.<catalog>.token}, which is the identity Polaris authorises, rather than the user id a Spark
 * Connect client asserts for itself. The token is decoded but not verified: Polaris verifies it, so a forged one only
 * shows up here against a FAILED query.
 *
 * <p>Only the text of SQL statements is logged. A DataFrame query has no SQL, and its plan is never logged as it can
 * embed rows of data, so it is recorded only by its operation and the tables it touches.
 */
public class AuditQueryListener implements QueryExecutionListener {

    public static final String AUDIT_LOGGER = "health.emrys.afon.spark.audit";

    static final String ICEBERG_CATALOG = "org.apache.iceberg.spark.SparkCatalog";

    private static final Logger logger = LoggerFactory.getLogger(AUDIT_LOGGER);
    private static final ObjectMapper mapper = new ObjectMapper();

    @Override
    public void onSuccess(String funcName, QueryExecution qe, long durationNs) {
        audit(funcName, qe, format("SUCCEEDED durationMs=%d", TimeUnit.NANOSECONDS.toMillis(durationNs)));
    }

    @Override
    public void onFailure(String funcName, QueryExecution qe, Exception exception) {
        audit(funcName, qe, format("FAILED error=%s", exception.getClass().getSimpleName()));
    }

    private void audit(String funcName, QueryExecution qe, String outcome) {
        try {
            record(funcName, qe, outcome).ifPresent(this::log);
        } catch (RuntimeException e) {
            logger.warn("Could not audit a {} query", funcName, e);
        }
    }

    protected void log(String record) {
        logger.info(record);
    }

    /**
     * @return the audit record of the query, or nothing when it touches no Iceberg table
     */
    Optional<String> record(String funcName, QueryExecution qe, String outcome) {
        SQLConf conf = qe.sparkSession().sessionState().conf();
        LogicalPlan plan = plan(qe);
        Set<String> tables = new TreeSet<>();
        Set<String> users = new TreeSet<>();
        plan.foreachWithSubqueries(node -> {
            table(node)
                .filter(table -> ICEBERG_CATALOG.equals(conf.getConfString("spark.sql.catalog." + table.catalog, null)))
                .ifPresent(table -> {
                    tables.add(table.toString());
                    users.add(user(conf, table.catalog));
                });
            return BoxedUnit.UNIT;
        });
        if (tables.isEmpty()) {
            return Optional.empty();
        }
        StringBuilder record = new StringBuilder()
            .append("user=").append(String.join(",", users))
            .append(" tables=").append(String.join(",", tables))
            .append(" operation=").append(plan.nodeName())
            .append(" action=").append(funcName)
            .append(" status=").append(outcome);
        sqlText(qe).ifPresent(sql -> record.append(" sql=").append(sql.replaceAll("\\s+", " ").trim()));
        return Optional.of(record.toString());
    }

    /** The analyzed plan names the tables, but a query that failed analysis only has its parsed plan. */
    private static LogicalPlan plan(QueryExecution qe) {
        try {
            return qe.analyzed();
        } catch (RuntimeException e) {
            return qe.logical();
        }
    }

    private record Table(String catalog, Identifier identifier) {
        @Override
        public String toString() {
            return catalog + "." + identifier;
        }
    }

    /**
     * A write names its table in a field rather than in a child, so it is not walked to. That field is a
     * {@code NamedRelation}, a Scala trait that extends {@link LogicalPlan} but which javac only sees as an interface.
     */
    private static Optional<Table> table(LogicalPlan node) {
        if (node instanceof V2WriteCommand write) {
            return table((LogicalPlan) write.table());
        } else if (node instanceof DataSourceV2Relation relation && relation.catalog().isDefined() && relation.identifier().isDefined()) {
            return Optional.of(table(relation.catalog().get(), relation.identifier().get()));
        } else if (node instanceof ResolvedTable resolved) {
            return Optional.of(table(resolved.catalog(), resolved.identifier()));
        } else if (node instanceof ResolvedIdentifier resolved) {
            return Optional.of(table(resolved.catalog(), resolved.identifier()));
        }
        return Optional.empty();
    }

    private static Table table(CatalogPlugin catalog, Identifier identifier) {
        return new Table(catalog.name(), identifier);
    }

    /**
     * The OIDC user of the catalog's token, or the client id of its client credentials when it has no token, as in a
     * Spark session of the client's own.
     */
    static String user(SQLConf conf, String catalog) {
        String token = conf.getConfString(format("spark.sql.catalog.%s.token", catalog), null);
        if (token != null) {
            return username(token);
        }
        String credential = conf.getConfString(format("spark.sql.catalog.%s.credential", catalog), null);
        if (credential != null) {
            return "client:" + credential.split(":", 2)[0];
        }
        return "unknown";
    }

    /**
     * @return the {@code preferred_username}, else the {@code sub}, of the JWT, which is decoded but not verified
     */
    static String username(String token) {
        String[] parts = token.split("\\.");
        if (parts.length < 2) {
            return "unreadable-token";
        }
        try {
            JsonNode claims = mapper.readTree(new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8));
            JsonNode username = claims.hasNonNull("preferred_username") ? claims.get("preferred_username") : claims.get("sub");
            return username == null ? "unreadable-token" : username.asText();
        } catch (Exception e) {
            return "unreadable-token";
        }
    }

    /**
     * The parser stamps the whole statement on the origin of each node it creates, and the analyzer keeps the origins
     * of the nodes it rewrites, so a query from SQL has its text there, where a DataFrame query has none.
     */
    private static Optional<String> sqlText(QueryExecution qe) {
        AtomicReference<String> sql = new AtomicReference<>();
        qe.logical().foreach(node -> {
            if (sql.get() == null && node.origin().sqlText().isDefined()) {
                sql.set(node.origin().sqlText().get());
            }
            return BoxedUnit.UNIT;
        });
        return Optional.ofNullable(sql.get());
    }
}
