package uk.gov.di.lambdas;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.nimbusds.jwt.SignedJWT;
import org.apache.http.client.utils.URIBuilder;
import uk.gov.di.entity.ClientRegistry;
import uk.gov.di.entity.Session;
import uk.gov.di.helpers.CookieHelper;
import uk.gov.di.services.ClientSessionService;
import uk.gov.di.services.ConfigurationService;
import uk.gov.di.services.DynamoClientService;
import uk.gov.di.services.SessionService;

import java.net.URI;
import java.net.URISyntaxException;
import java.text.ParseException;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import static java.lang.String.format;

public class LogoutHandler
        implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    private final ConfigurationService configurationService;
    private final SessionService sessionService;
    private final DynamoClientService dynamoClientService;
    private final ClientSessionService clientSessionService;

    public LogoutHandler() {
        this.configurationService = new ConfigurationService();
        this.sessionService = new SessionService(configurationService);
        this.dynamoClientService =
                new DynamoClientService(
                        configurationService.getAwsRegion(),
                        configurationService.getEnvironment(),
                        configurationService.getDynamoEndpointUri());
        this.clientSessionService = new ClientSessionService(configurationService);
    }

    public LogoutHandler(
            ConfigurationService configurationService,
            SessionService sessionService,
            DynamoClientService dynamoClientService,
            ClientSessionService clientSessionService) {
        this.configurationService = configurationService;
        this.sessionService = sessionService;
        this.dynamoClientService = dynamoClientService;
        this.clientSessionService = clientSessionService;
    }

    @Override
    public APIGatewayProxyResponseEvent handleRequest(
            APIGatewayProxyRequestEvent input, Context context) {
        Optional<String> state = Optional.ofNullable(input.getQueryStringParameters().get("state"));
        Optional<Session> sessionFromSessionCookie =
                sessionService.getSessionFromSessionCookie(input.getHeaders());
        return sessionFromSessionCookie
                .map(t -> processLogoutRequest(t, input, state))
                .orElse(generateDefaultLogoutResponse(state));
    }

    private APIGatewayProxyResponseEvent processLogoutRequest(
            Session session, APIGatewayProxyRequestEvent input, Optional<String> state) {
        Map<String, String> queryStringParameters = input.getQueryStringParameters();
        Optional<CookieHelper.SessionCookieIds> sessionCookieIds =
                CookieHelper.parseSessionCookie(input.getHeaders());

        if (!session.getClientSessions().contains(sessionCookieIds.get().getClientSessionId())) {
            throw new RuntimeException(
                    format(
                            "Client Session ID does not exist in Session: %s",
                            session.getSessionId()));
        }
        if (!queryStringParameters.containsKey("id_token_hint")
                || queryStringParameters.get("id_token_hint").isBlank()) {
            sessionService.deleteSessionFromRedis(session.getSessionId());
            return generateDefaultLogoutResponse(state);
        }
        if (!doesIDTokenExistInSession(queryStringParameters.get("id_token_hint"), session)) {
            throw new RuntimeException(
                    format("ID Token does not exist for Session: %s", session.getSessionId()));
        }
        if (!isIDTokenSignatureValid(
                queryStringParameters.get("id_token_hint"), session.getSessionId())) {
            throw new RuntimeException(
                    format(
                            "Unable to validate ID token signature for Session: %s",
                            session.getSessionId()));
        }
        try {
            String idTokenHint = queryStringParameters.get("id_token_hint");
            SignedJWT idToken = SignedJWT.parse(idTokenHint);
            Optional<String> audience =
                    idToken.getJWTClaimsSet().getAudience().stream().findFirst();
            sessionService.deleteSessionFromRedis(session.getSessionId());
            return audience.map(
                            a ->
                                    validateClientIDAgainstClientRegistry(
                                            queryStringParameters, a, state))
                    .orElse(generateDefaultLogoutResponse(state));
        } catch (ParseException e) {
            throw new RuntimeException();
        }
    }

    private boolean isIDTokenSignatureValid(String idTokenHint, String sessionID) {
        return true;
    }

    private boolean doesIDTokenExistInSession(String idTokenHint, Session session) {
        return session.getClientSessions().stream()
                .map(s -> clientSessionService.getClientSession(s))
                .filter(Objects::nonNull)
                .anyMatch(cs -> idTokenHint.equals(cs.getIdTokenHint()));
    }

    private APIGatewayProxyResponseEvent validateClientIDAgainstClientRegistry(
            Map<String, String> queryStringParameters, String clientID, Optional<String> state) {
        ClientRegistry clientRegistry =
                dynamoClientService
                        .getClient(clientID)
                        .orElseThrow(
                                () ->
                                        new RuntimeException(
                                                format(
                                                        "Client not found in ClientRegistry for ClientID: %s",
                                                        clientID)));
        String postLogoutRedirectUri = queryStringParameters.get("post_logout_redirect_uri");
        if (!queryStringParameters.get("post_logout_redirect_uri").isBlank()
                && clientRegistry.getPostLogoutRedirectUrls().contains(postLogoutRedirectUri)) {
            return generateLogoutResponse(URI.create(postLogoutRedirectUri), state);
        }
        return generateDefaultLogoutResponse(state);
    }

    private APIGatewayProxyResponseEvent generateDefaultLogoutResponse(Optional<String> state) {
        return generateLogoutResponse(configurationService.getDefaultLogoutURI(), state);
    }

    private APIGatewayProxyResponseEvent generateLogoutResponse(
            URI logoutUri, Optional<String> state) {
        URIBuilder uriBuilder = new URIBuilder(logoutUri);
        state.ifPresent(s -> uriBuilder.addParameter("state", s));
        URI uri;
        try {
            uri = uriBuilder.build();
        } catch (URISyntaxException e) {
            throw new RuntimeException("Unable to build URI");
        }
        return new APIGatewayProxyResponseEvent()
                .withStatusCode(302)
                .withHeaders(Map.of("Location", uri.toString()));
    }
}