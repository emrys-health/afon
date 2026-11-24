package health.emrys.afon.spark.guard;

import org.apache.spark.connect.proto.ExecutePlanRequest;
import org.apache.spark.connect.proto.ExecutePlanResponse;
import org.apache.spark.connect.proto.SparkConnectServiceGrpc;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.sparkproject.connect.grpc.Metadata;
import org.sparkproject.connect.grpc.ServerCall;
import org.sparkproject.connect.grpc.ServerCallHandler;
import org.sparkproject.connect.grpc.Status;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SparkConnectGuardInterceptorTest {

    private final Tokens                        tokens      = new Tokens();
    private final SparkConnectGuardInterceptor  interceptor = new SparkConnectGuardInterceptor(tokens.verifier());

    private ServerCall<ExecutePlanRequest, ExecutePlanResponse>        call;
    private ServerCallHandler<ExecutePlanRequest, ExecutePlanResponse> next;
    private ServerCall.Listener<ExecutePlanRequest>                    server;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        call   = mock(ServerCall.class);
        next   = mock(ServerCallHandler.class);
        server = mock(ServerCall.Listener.class);
        when(call.getMethodDescriptor()).thenReturn(SparkConnectServiceGrpc.getExecutePlanMethod());
        when(next.startCall(any(), any())).thenReturn(server);
    }

    private static Metadata withToken(String token) {
        Metadata headers = new Metadata();
        if (token != null) {
            headers.put(SparkConnectGuardInterceptor.TOKEN, token);
        }
        return headers;
    }

    private Status closedWith() {
        ArgumentCaptor<Status> status = ArgumentCaptor.forClass(Status.class);
        verify(call).close(status.capture(), any());
        return status.getValue();
    }

    @Test
    void aCallWithNoTokenIsRefusedAndNeverReachesTheServer() {
        interceptor.interceptCall(call, withToken(null), next);

        assertThat(closedWith().getCode()).isEqualTo(Status.Code.UNAUTHENTICATED);
        assertThat(closedWith().getDescription()).contains(";x-afon-token=");
        verify(next, never()).startCall(any(), any());
    }

    @Test
    void aCallWithAForgedTokenIsRefused() {
        interceptor.interceptCall(call, withToken(tokens.signed(tokens.claims().build(), tokens.other)), next);

        assertThat(closedWith().getCode()).isEqualTo(Status.Code.UNAUTHENTICATED);
        verify(next, never()).startCall(any(), any());
    }

    @Test
    void aRequestOfAnAuthenticatedCallIsMadeAsTheTokensSubject() {
        ServerCall.Listener<ExecutePlanRequest> listener = interceptor.interceptCall(call, withToken(tokens.token()), next);

        listener.onMessage(RequestPolicyTest.executing(RequestPolicyTest.pythonUdf()));
        listener.onHalfClose();

        ArgumentCaptor<ExecutePlanRequest> request = ArgumentCaptor.forClass(ExecutePlanRequest.class);
        verify(server).onMessage(request.capture());
        assertThat(request.getValue().getUserContext().getUserId()).isEqualTo(Tokens.SUBJECT);
        verify(server).onHalfClose();
        verify(call, never()).close(any(), any());
    }

    @Test
    void aRequestWithJvmCodeIsRefusedAndTheServerHearsNothingOfIt() {
        ServerCall.Listener<ExecutePlanRequest> listener = interceptor.interceptCall(call, withToken(tokens.token()), next);

        listener.onMessage(RequestPolicyTest.executing(RequestPolicyTest.scalaUdf()));
        listener.onHalfClose();
        listener.onReady();

        assertThat(closedWith().getCode()).isEqualTo(Status.Code.PERMISSION_DENIED);
        verify(server, never()).onMessage(any());
        verify(server, never()).onHalfClose();
        verify(server, never()).onReady();
    }
}
