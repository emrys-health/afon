spark-polaris-smoke

Smoke test that Spark writes a table through the Polaris of the stack to the stack's own catalog, and reads it back, on the
cloud of the stack hosting the TRE (TRE_CLOUD, aws by default).  It writes the table that spark-connect-smoke and trino-query-smoke read, so run
it first.  Skipped, and passing, for a project's TRE, whose Polaris admits only the tokens of the project.

Usage:
  afon-cli.sh spark-polaris-smoke
