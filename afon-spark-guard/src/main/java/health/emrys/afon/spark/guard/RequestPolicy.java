package health.emrys.afon.spark.guard;

import org.apache.spark.connect.proto.AddArtifactsRequest;
import org.apache.spark.connect.proto.ExecuteExternalCommand;
import org.apache.spark.connect.proto.JavaUDF;
import org.apache.spark.connect.proto.ScalarScalaUDF;
import org.apache.spark.connect.proto.StreamingQueryManagerCommand;
import org.sparkproject.connect.protobuf.Descriptors.FieldDescriptor;
import org.sparkproject.connect.protobuf.Message;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * What the Spark Connect server refuses of a request, before Spark reads it, and the user that a request is made as.
 *
 * <p>The users of the one server share its driver and its executors, so the code that one of them runs there can reach what
 * the others have: their sessions' settings, their tokens among them, in the driver, and their data and credentials in the
 * executors' memory.  Python runs in processes of its own, away from the JVM, but JVM code runs in it, so none is taken:
 * <ul>
 *   <li>no Scala or Java UDF, whose payload is a Java-serialised object, which the driver deserialises, which alone can run code,
 *       as it reads the plan;</li>
 *   <li>no listener of streaming queries written in Scala or Java, which is a Java-serialised object too;</li>
 *   <li>no external command, which runs a class that the client names;</li>
 *   <li>no artifact but those that hold no JVM code: the cached local relations, that a client sends of a large DataFrame, and
 *       Python files, archives and files.  Jars and classes, and anything else, are refused.</li>
 * </ul>
 * What Spark makes of a plan, once it has read it, eg {@code ADD JAR} in SQL, is refused by {@link PlanPolicy}.
 */
final class RequestPolicy {

    /** The artifacts that hold no JVM code, by the prefix of their names. */
    static final List<String> ALLOWED_ARTIFACTS = List.of("cache/", "pyfiles/", "archives/", "files/");

    static final String NO_JVM_CODE = "the Spark Connect server runs no JVM code of its clients: use Python UDFs instead of Scala or Java ones";

    private RequestPolicy() {
    }

    /**
     * @return why the request is refused, or nothing if it is not
     */
    static Optional<String> refusal(Message request) {
        if (request instanceof AddArtifactsRequest artifacts) {
            for (String name : artifactNames(artifacts)) {
                if (ALLOWED_ARTIFACTS.stream().noneMatch(name::startsWith)) {
                    return Optional.of("the artifact '" + name + "' is refused, as " + NO_JVM_CODE);
                }
            }
            return Optional.empty();
        }
        Deque<Message> pending = new ArrayDeque<>(List.of(request));
        while (!pending.isEmpty()) {
            Message message = pending.pop();
            Optional<String> refused = refusalOf(message);
            if (refused.isPresent()) {
                return refused;
            }
            for (Map.Entry<FieldDescriptor, Object> field : message.getAllFields().entrySet()) {
                if (field.getKey().getJavaType() != FieldDescriptor.JavaType.MESSAGE) {
                    continue;
                }
                if (field.getKey().isRepeated()) {
                    for (Object element : (List<?>) field.getValue()) {
                        pending.push((Message) element);
                    }
                } else {
                    pending.push((Message) field.getValue());
                }
            }
        }
        return Optional.empty();
    }

    private static Optional<String> refusalOf(Message message) {
        if (message instanceof ScalarScalaUDF || message instanceof JavaUDF) {
            return Optional.of(NO_JVM_CODE);
        }
        if (message instanceof StreamingQueryManagerCommand.StreamingQueryListenerCommand listener && !listener.getListenerPayload().isEmpty()) {
            return Optional.of("a listener of streaming queries written in Scala or Java is refused, as " + NO_JVM_CODE);
        }
        if (message instanceof ExecuteExternalCommand) {
            return Optional.of("external commands are refused, as " + NO_JVM_CODE);
        }
        return Optional.empty();
    }

    /** The names of the artifacts of the request; a chunk after the first names none, as it is of the artifact that the first began. */
    private static List<String> artifactNames(AddArtifactsRequest request) {
        List<String> names = new ArrayList<>();
        if (request.hasBatch()) {
            request.getBatch().getArtifactsList().forEach(artifact -> names.add(artifact.getName()));
        }
        if (request.hasBeginChunk()) {
            names.add(request.getBeginChunk().getName());
        }
        return names;
    }

    /**
     * @return the request, made as the given user, whoever the client said it was: the server keys a session by its user and its id,
     * so that no client can reach a session of another user's, even if it knows its id
     */
    @SuppressWarnings("unchecked")
    static <T extends Message> T asUser(T request, String userId) {
        FieldDescriptor contextField = request.getDescriptorForType().findFieldByName("user_context");
        if (contextField == null || contextField.getJavaType() != FieldDescriptor.JavaType.MESSAGE) {
            return request;
        }
        Message         context     = (Message) request.getField(contextField);
        FieldDescriptor userIdField = context.getDescriptorForType().findFieldByName("user_id");
        Message         asUser      = context.toBuilder().setField(userIdField, userId).build();
        return (T) request.toBuilder().setField(contextField, asUser).build();
    }
}
