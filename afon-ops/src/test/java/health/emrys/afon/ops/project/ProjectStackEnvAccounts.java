package health.emrys.afon.ops.project;

/** The account of the config a project stack is made from, on each cloud: the names of the project stack's resources depend on it. */
final class ProjectStackEnvAccounts {

    static final String ACCOUNTS = "AZURE_SUBSCRIPTION=sub-1\nAWS_ACCOUNT_ID=111111111111\nGCP_PROJECT_ID=gcp-proj\n";

    private ProjectStackEnvAccounts() {
    }
}
