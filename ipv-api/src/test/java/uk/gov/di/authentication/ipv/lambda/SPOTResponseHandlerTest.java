package uk.gov.di.authentication.ipv.lambda;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import uk.gov.di.authentication.ipv.domain.IPVAuditableEvent;
import uk.gov.di.authentication.shared.services.AuditService;
import uk.gov.di.authentication.shared.services.DynamoIdentityService;

import static java.util.Collections.singletonList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SPOTResponseHandlerTest {

    private static final String REQUEST_ID = "request-id";

    private SPOTResponseHandler handler;
    private final Context context = mock(Context.class);
    private final DynamoIdentityService dynamoIdentityService = mock(DynamoIdentityService.class);
    private final AuditService auditService = mock(AuditService.class);

    @BeforeEach
    void setup() {
        handler = new SPOTResponseHandler(dynamoIdentityService, auditService);

        when(context.getAwsRequestId()).thenReturn(REQUEST_ID);
    }

    @Test
    void shouldWriteToDynamoForSuccesssfulSPOTResponse() {
        String json =
                "{\"sub\":\"some-pairwise-identifier\",\"status\":\"ACCEPTED\","
                        + "\"claims\":{\"http://something/v1/verifiableIdentityJWT\":\"random-searalized-credential\"}}";

        handler.handleRequest(generateSQSEvent(json), context);

        verify(dynamoIdentityService)
                .addCoreIdentityJWT("some-pairwise-identifier", "random-searalized-credential");

        verify(auditService)
                .submitAuditEvent(
                        IPVAuditableEvent.SPOT_RESPONSE_RECEIVED,
                        REQUEST_ID,
                        AuditService.UNKNOWN,
                        AuditService.UNKNOWN,
                        AuditService.UNKNOWN,
                        AuditService.UNKNOWN,
                        AuditService.UNKNOWN,
                        AuditService.UNKNOWN,
                        AuditService.UNKNOWN);
    }

    @Test
    void shouldNotWriteToDynamoWhenLambdaReceivedInvalidSPOTResponse() {
        handler.handleRequest(generateSQSEvent("invalid-payload"), context);

        verifyNoInteractions(dynamoIdentityService);

        verify(auditService)
                .submitAuditEvent(
                        IPVAuditableEvent.SPOT_RESPONSE_RECEIVED,
                        REQUEST_ID,
                        AuditService.UNKNOWN,
                        AuditService.UNKNOWN,
                        AuditService.UNKNOWN,
                        AuditService.UNKNOWN,
                        AuditService.UNKNOWN,
                        AuditService.UNKNOWN,
                        AuditService.UNKNOWN);
    }

    @Test
    void shouldNotWriteToDynamoWhenSPOTResponseStatusIsNotOK() {
        String json =
                "{\"sub\":\"some-pairwise-identifier\",\"status\":\"REJECTED\","
                        + "\"claims\":{\"http://something/v1/verifiableIdentityJWT\":\"random-searalized-credential\"}}";

        handler.handleRequest(generateSQSEvent(json), context);

        verifyNoInteractions(dynamoIdentityService);

        verify(auditService)
                .submitAuditEvent(
                        IPVAuditableEvent.SPOT_RESPONSE_RECEIVED,
                        REQUEST_ID,
                        AuditService.UNKNOWN,
                        AuditService.UNKNOWN,
                        AuditService.UNKNOWN,
                        AuditService.UNKNOWN,
                        AuditService.UNKNOWN,
                        AuditService.UNKNOWN,
                        AuditService.UNKNOWN);
    }

    @Test
    void shouldNotWriteToDynamoWhenStatusIsOKButNoCredentialIsPresent() {
        String json =
                "{\"sub\":\"some-pairwise-identifier\",\"status\":\"ACCEPTED\"," + "\"claims\":{}}";

        handler.handleRequest(generateSQSEvent(json), context);

        verifyNoInteractions(dynamoIdentityService);

        verify(auditService)
                .submitAuditEvent(
                        IPVAuditableEvent.SPOT_RESPONSE_RECEIVED,
                        REQUEST_ID,
                        AuditService.UNKNOWN,
                        AuditService.UNKNOWN,
                        AuditService.UNKNOWN,
                        AuditService.UNKNOWN,
                        AuditService.UNKNOWN,
                        AuditService.UNKNOWN,
                        AuditService.UNKNOWN);
    }

    private SQSEvent generateSQSEvent(String messageBody) {
        SQSEvent.SQSMessage sqsMessage = new SQSEvent.SQSMessage();
        sqsMessage.setBody(messageBody);
        SQSEvent sqsEvent = new SQSEvent();
        sqsEvent.setRecords(singletonList(sqsMessage));
        return sqsEvent;
    }
}
