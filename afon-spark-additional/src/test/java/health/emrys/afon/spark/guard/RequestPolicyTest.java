package health.emrys.afon.spark.guard;

import org.apache.spark.connect.proto.AddArtifactsRequest;
import org.apache.spark.connect.proto.AnalyzePlanRequest;
import org.apache.spark.connect.proto.Command;
import org.apache.spark.connect.proto.CommonInlineUserDefinedFunction;
import org.apache.spark.connect.proto.ExecuteExternalCommand;
import org.apache.spark.connect.proto.ExecutePlanRequest;
import org.apache.spark.connect.proto.Expression;
import org.apache.spark.connect.proto.JavaUDF;
import org.apache.spark.connect.proto.Plan;
import org.apache.spark.connect.proto.Project;
import org.apache.spark.connect.proto.PythonUDF;
import org.apache.spark.connect.proto.Relation;
import org.apache.spark.connect.proto.ScalarScalaUDF;
import org.apache.spark.connect.proto.StreamingQueryManagerCommand;
import org.apache.spark.connect.proto.UserContext;
import org.junit.jupiter.api.Test;
import org.sparkproject.connect.protobuf.ByteString;

import static org.assertj.core.api.Assertions.assertThat;

class RequestPolicyTest {

    static ExecutePlanRequest executing(Plan plan) {
        return ExecutePlanRequest.newBuilder().setSessionId("s").setUserContext(UserContext.newBuilder().setUserId("jovyan")).setPlan(plan).build();
    }

    static Plan projecting(CommonInlineUserDefinedFunction.Builder udf) {
        Expression expression = Expression.newBuilder().setCommonInlineUserDefinedFunction(udf.setFunctionName("f")).build();
        return Plan.newBuilder().setRoot(Relation.newBuilder().setProject(Project.newBuilder().addExpressions(expression))).build();
    }

    static Plan scalaUdf() {
        return projecting(CommonInlineUserDefinedFunction.newBuilder()
                                                         .setScalarScalaUdf(ScalarScalaUDF.newBuilder().setPayload(ByteString.copyFromUtf8("rO0AB"))));
    }

    static Plan pythonUdf() {
        return projecting(CommonInlineUserDefinedFunction.newBuilder()
                                                         .setPythonUdf(PythonUDF.newBuilder().setCommand(ByteString.copyFromUtf8("pickle"))));
    }

    static AddArtifactsRequest artifact(String name) {
        return AddArtifactsRequest.newBuilder()
                                  .setBatch(AddArtifactsRequest.Batch.newBuilder()
                                                                     .addArtifacts(AddArtifactsRequest.SingleChunkArtifact.newBuilder().setName(name)))
                                  .build();
    }

    @Test
    void aPlanWithAPythonUdfIsLetThrough() {
        assertThat(RequestPolicy.refusal(executing(pythonUdf()))).isEmpty();
    }

    @Test
    void aScalaOrJavaUdfIsRefusedWhereverItIsInThePlanAndWhetherItIsRunOrAnalysed() {
        Plan javaUdf = projecting(CommonInlineUserDefinedFunction.newBuilder().setJavaUdf(JavaUDF.newBuilder().setClassName("x.Y")));

        assertThat(RequestPolicy.refusal(executing(scalaUdf()))).hasValueSatisfying(why -> assertThat(why).contains("Python UDFs"));
        assertThat(RequestPolicy.refusal(executing(javaUdf))).isPresent();
        assertThat(RequestPolicy.refusal(AnalyzePlanRequest.newBuilder()
                                                           .setSchema(AnalyzePlanRequest.Schema.newBuilder().setPlan(scalaUdf()))
                                                           .build())).isPresent();
    }

    @Test
    void aStreamingListenerInScalaOrJavaIsRefusedButOneInPythonIsNot() {
        Plan scala = Plan.newBuilder().setCommand(Command.newBuilder().setStreamingQueryManagerCommand(
            StreamingQueryManagerCommand.newBuilder().setAddListener(
                StreamingQueryManagerCommand.StreamingQueryListenerCommand.newBuilder().setListenerPayload(ByteString.copyFromUtf8("rO0AB"))))).build();
        Plan python = Plan.newBuilder().setCommand(Command.newBuilder().setStreamingQueryManagerCommand(
            StreamingQueryManagerCommand.newBuilder().setAddListener(
                StreamingQueryManagerCommand.StreamingQueryListenerCommand.newBuilder().setId("l")))).build();

        assertThat(RequestPolicy.refusal(executing(scala))).isPresent();
        assertThat(RequestPolicy.refusal(executing(python))).isEmpty();
    }

    @Test
    void anExternalCommandIsRefused() {
        Plan external = Plan.newBuilder().setCommand(Command.newBuilder().setExecuteExternalCommand(
            ExecuteExternalCommand.newBuilder().setRunner("org.apache.spark.sql.execution.datasources.jdbc.JdbcExternalCommandRunner"))).build();

        assertThat(RequestPolicy.refusal(executing(external))).isPresent();
    }

    @Test
    void onlyArtifactsWithNoJvmCodeAreLetThrough() {
        assertThat(RequestPolicy.refusal(artifact("cache/0123abcd"))).isEmpty();
        assertThat(RequestPolicy.refusal(artifact("pyfiles/helpers.py"))).isEmpty();
        assertThat(RequestPolicy.refusal(artifact("jars/evil.jar"))).isPresent();
        assertThat(RequestPolicy.refusal(artifact("classes/Evil.class"))).isPresent();
        assertThat(RequestPolicy.refusal(artifact("forward_to_fs/tmp/x"))).isPresent();
        assertThat(RequestPolicy.refusal(artifact("cache"))).as("not a prefix of an allowed name").isPresent();
        assertThat(RequestPolicy.refusal(AddArtifactsRequest.newBuilder()
                                                            .setBeginChunk(AddArtifactsRequest.BeginChunkedArtifact.newBuilder().setName("jars/big.jar"))
                                                            .build())).isPresent();
    }

    @Test
    void theRequestIsMadeAsTheGivenUserWhoeverTheClientSaidItWas() {
        ExecutePlanRequest request = executing(pythonUdf());

        ExecutePlanRequest asUser = RequestPolicy.asUser(request, Tokens.SUBJECT);

        assertThat(asUser.getUserContext().getUserId()).isEqualTo(Tokens.SUBJECT);
        assertThat(asUser.getSessionId()).isEqualTo("s");
        assertThat(asUser.getPlan()).isEqualTo(request.getPlan());
    }

    @Test
    void aRequestWithNoUserContextGetsOne() {
        AddArtifactsRequest asUser = RequestPolicy.asUser(artifact("cache/x"), Tokens.SUBJECT);

        assertThat(asUser.getUserContext().getUserId()).isEqualTo(Tokens.SUBJECT);
    }
}
