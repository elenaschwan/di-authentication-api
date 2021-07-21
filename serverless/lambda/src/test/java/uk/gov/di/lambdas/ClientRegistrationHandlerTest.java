package uk.gov.di.lambdas;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.oauth2.sdk.id.ClientID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import uk.gov.di.entity.ClientRegistrationResponse;
import uk.gov.di.entity.ErrorResponse;
import uk.gov.di.services.ClientService;

import java.util.List;
import java.util.UUID;

import static java.util.Collections.singletonList;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;
import static uk.gov.di.matchers.APIGatewayProxyResponseEventMatcher.hasBody;
import static uk.gov.di.matchers.APIGatewayProxyResponseEventMatcher.hasStatus;

class ClientRegistrationHandlerTest {

    private final Context context = mock(Context.class);
    private final ClientService clientService = mock(ClientService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private ClientRegistrationHandler handler;

    @BeforeEach
    public void setup() {
        handler = new ClientRegistrationHandler(clientService);
    }

    @Test
    public void shouldReturn200IfClientRegistrationRequestIsSuccessful()
            throws JsonProcessingException {
        String clientId = UUID.randomUUID().toString();
        String clientName = "test-client";
        List<String> redirectUris = List.of("http://localhost:8080/redirect-uri");
        List<String> contacts = List.of("joe.bloggs@test.com");
        when(clientService.generateClientID()).thenReturn(new ClientID(clientId));

        APIGatewayProxyRequestEvent event = new APIGatewayProxyRequestEvent();

        event.setBody(
                "{ \"client_name\": \"test-client\", \"redirect_uris\": [\"http://localhost:8080/redirect-uri\"], \"contacts\": [\"joe.bloggs@test.com\"], \"scopes\": [\"openid\"],  \"public_key\": \"some-public-key\", \"post_logout_redirect_uris\": [\"http://localhost:8080/post-logout-redirect-uri\"]}");
        APIGatewayProxyResponseEvent result = handler.handleRequest(event, context);

        assertThat(result, hasStatus(200));
        ClientRegistrationResponse clientRegistrationResponseResult =
                objectMapper.readValue(result.getBody(), ClientRegistrationResponse.class);
        assertEquals(clientId, clientRegistrationResponseResult.getClientId());
        verify(clientService)
                .addClient(
                        clientId,
                        clientName,
                        redirectUris,
                        contacts,
                        singletonList("openid"),
                        "some-public-key",
                        singletonList("http://localhost:8080/post-logout-redirect-uri"));
    }

    @Test
    public void shouldReturn400IfAnyRequestParametersAreMissing() throws JsonProcessingException {
        APIGatewayProxyRequestEvent event = new APIGatewayProxyRequestEvent();
        event.setBody(
                "{\"redirect_uris\": [\"http://localhost:8080/redirect-uri\"], \"contacts\": [\"joe.bloggs@test.com\"] }");
        APIGatewayProxyResponseEvent result = handler.handleRequest(event, context);

        assertThat(result, hasStatus(400));
        String expectedResponse = new ObjectMapper().writeValueAsString(ErrorResponse.ERROR_1001);
        assertThat(result, hasBody(expectedResponse));
    }
}
