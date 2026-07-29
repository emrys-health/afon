package health.emrys.afon.duckdb;

import java.util.List;

public class DuckDbConfig {

    public static final  String       DUCKDB_IMAGE = "davidgasquez/duckdb:latest";
    /**
     * DuckDB is embedded, so the container has no server to run; keep the pod alive so it can be
     * {@code kubectl exec}'d into. {@code sleep infinity} is the idiomatic keep-alive.
     */
    public static final List<String> KEEP_ALIVE   = List.of("sleep", "infinity");

}
