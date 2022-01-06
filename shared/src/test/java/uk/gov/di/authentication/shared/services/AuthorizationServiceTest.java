package uk.gov.di.authentication.shared.services;

import com.nimbusds.oauth2.sdk.AuthorizationCode;
import com.nimbusds.oauth2.sdk.ErrorObject;
import com.nimbusds.oauth2.sdk.OAuth2Error;
import com.nimbusds.oauth2.sdk.ResponseType;
import com.nimbusds.oauth2.sdk.Scope;
import com.nimbusds.oauth2.sdk.id.ClientID;
import com.nimbusds.oauth2.sdk.id.State;
import com.nimbusds.openid.connect.sdk.AuthenticationRequest;
import com.nimbusds.openid.connect.sdk.AuthenticationSuccessResponse;
import com.nimbusds.openid.connect.sdk.Nonce;
import com.nimbusds.openid.connect.sdk.OIDCClaimsRequest;
import com.nimbusds.openid.connect.sdk.OIDCScopeValue;
import com.nimbusds.openid.connect.sdk.claims.ClaimsSetRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import uk.gov.di.authentication.shared.entity.ClientRegistry;
import uk.gov.di.authentication.shared.entity.ClientSession;
import uk.gov.di.authentication.shared.entity.CustomScopeValue;
import uk.gov.di.authentication.shared.entity.Session;
import uk.gov.di.authentication.shared.entity.UserProfile;
import uk.gov.di.authentication.shared.entity.VectorOfTrust;
import uk.gov.di.authentication.shared.exceptions.ClientNotFoundException;
import uk.gov.di.authentication.shared.helpers.CookieHelper;
import uk.gov.di.authentication.shared.state.UserContext;
import uk.gov.di.authentication.sharedtest.logging.CaptureLoggingExtension;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static java.lang.String.format;
import static java.util.Collections.singletonList;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static uk.gov.di.authentication.sharedtest.helper.JsonArrayHelper.jsonArrayOf;
import static uk.gov.di.authentication.sharedtest.logging.LogEventMatcher.withMessageContaining;

class AuthorizationServiceTest {

    private static final URI REDIRECT_URI = URI.create("http://localhost/redirect");
    private static final ClientID CLIENT_ID = new ClientID();
    private static final State STATE = new State();
    private static final Nonce NONCE = new Nonce();
    private AuthorizationService authorizationService;
    private final DynamoClientService dynamoClientService = mock(DynamoClientService.class);
    private final DynamoService dynamoService = mock(DynamoService.class);

    @RegisterExtension
    public final CaptureLoggingExtension logging =
            new CaptureLoggingExtension(AuthorizationService.class);

    @BeforeEach
    void setUp() {
        authorizationService = new AuthorizationService(dynamoClientService, dynamoService);
    }

    @AfterEach
    public void tearDown() {
        assertThat(logging.events(), not(hasItem(withMessageContaining(CLIENT_ID.toString()))));
    }

    @Test
    void shouldThrowClientNotFoundExceptionWhenClientDoesNotExist() {
        when(dynamoClientService.getClient(CLIENT_ID.toString())).thenReturn(Optional.empty());

        ClientNotFoundException exception =
                Assertions.assertThrows(
                        ClientNotFoundException.class,
                        () ->
                                authorizationService.isClientRedirectUriValid(
                                        CLIENT_ID, REDIRECT_URI),
                        "Expected to throw exception");

        assertThat(
                exception.getMessage(),
                equalTo(format("No Client found for ClientID: %s", CLIENT_ID)));
    }

    @Test
    void shouldReturnFalseIfClientUriIsInvalid() throws ClientNotFoundException {
        when(dynamoClientService.getClient(CLIENT_ID.toString()))
                .thenReturn(
                        Optional.of(
                                generateClientRegistry(
                                        "http://localhost//", CLIENT_ID.toString())));
        assertFalse(authorizationService.isClientRedirectUriValid(CLIENT_ID, REDIRECT_URI));
    }

    @Test
    void shouldReturnTrueIfRedirectUriIsValid() throws ClientNotFoundException {
        when(dynamoClientService.getClient(CLIENT_ID.toString()))
                .thenReturn(
                        Optional.of(
                                generateClientRegistry(
                                        REDIRECT_URI.toString(), CLIENT_ID.toString())));
        assertTrue(authorizationService.isClientRedirectUriValid(CLIENT_ID, REDIRECT_URI));
    }

    @Test
    void shouldGenerateSuccessfulAuthResponse() throws URISyntaxException {
        AuthorizationCode authCode = new AuthorizationCode();
        Scope scope = new Scope();
        scope.add(OIDCScopeValue.OPENID);
        ResponseType responseType = new ResponseType(ResponseType.Value.CODE);
        AuthenticationRequest authRequest =
                generateAuthRequest(REDIRECT_URI.toString(), responseType, scope);

        AuthenticationSuccessResponse authSuccessResponse =
                authorizationService.generateSuccessfulAuthResponse(authRequest, authCode);
        assertThat(authSuccessResponse.getState(), equalTo(STATE));
        assertThat(authSuccessResponse.getAuthorizationCode(), equalTo(authCode));
        assertThat(authSuccessResponse.getRedirectionURI(), equalTo(REDIRECT_URI));
    }

    @Test
    void shouldSuccessfullyValidateAuthRequestWhenIdentityValuesAreIncludedInVtrAttribute() {
        Scope scope = new Scope();
        scope.add(OIDCScopeValue.OPENID);
        when(dynamoClientService.getClient(CLIENT_ID.toString()))
                .thenReturn(
                        Optional.of(
                                generateClientRegistry(
                                        REDIRECT_URI.toString(), CLIENT_ID.toString())));
        ResponseType responseType = new ResponseType(ResponseType.Value.CODE);
        AuthenticationRequest authRequest =
                generateAuthRequest(
                        REDIRECT_URI.toString(),
                        responseType,
                        scope,
                        jsonArrayOf("P2.Cl.Cm", "P2.Cl"),
                        Optional.empty());
        Optional<ErrorObject> errorObject = authorizationService.validateAuthRequest(authRequest);

        assertThat(errorObject, equalTo(Optional.empty()));
    }

    @Test
    void shouldReturnErrorWhenInvalidVtrAttributeIsSentInRequest() {
        Scope scope = new Scope();
        scope.add(OIDCScopeValue.OPENID);
        when(dynamoClientService.getClient(CLIENT_ID.toString()))
                .thenReturn(
                        Optional.of(
                                generateClientRegistry(
                                        REDIRECT_URI.toString(), CLIENT_ID.toString())));
        ResponseType responseType = new ResponseType(ResponseType.Value.CODE);
        AuthenticationRequest authRequest =
                generateAuthRequest(
                        REDIRECT_URI.toString(),
                        responseType,
                        scope,
                        jsonArrayOf("Cm.Cl.P1", "P1.Cl"),
                        Optional.empty());
        Optional<ErrorObject> errorObject = authorizationService.validateAuthRequest(authRequest);

        assertThat(
                errorObject.get(),
                equalTo(
                        new ErrorObject(
                                OAuth2Error.INVALID_REQUEST_CODE, "Request vtr not valid")));
    }

    @Test
    void shouldSuccessfullyValidateAuthRequest() {
        ResponseType responseType = new ResponseType(ResponseType.Value.CODE);
        Scope scope = new Scope();
        scope.add(OIDCScopeValue.OPENID);
        when(dynamoClientService.getClient(CLIENT_ID.toString()))
                .thenReturn(
                        Optional.of(
                                generateClientRegistry(
                                        REDIRECT_URI.toString(), CLIENT_ID.toString())));
        Optional<ErrorObject> errorObject =
                authorizationService.validateAuthRequest(
                        generateAuthRequest(REDIRECT_URI.toString(), responseType, scope));

        assertThat(errorObject, equalTo(Optional.empty()));
    }

    @Test
    void shouldSuccessfullyValidateAuthRequestWhenValidClaimsArePresent() {
        ResponseType responseType = new ResponseType(ResponseType.Value.CODE);
        Scope scope = new Scope();
        scope.add(OIDCScopeValue.OPENID);
        when(dynamoClientService.getClient(CLIENT_ID.toString()))
                .thenReturn(
                        Optional.of(
                                generateClientRegistry(
                                        REDIRECT_URI.toString(), CLIENT_ID.toString())));
        var claimsSetRequest = new ClaimsSetRequest().add("name").add("birthdate");
        var oidcClaimsRequest = new OIDCClaimsRequest().withUserInfoClaimsRequest(claimsSetRequest);
        AuthenticationRequest authRequest =
                generateAuthRequest(
                        REDIRECT_URI.toString(),
                        responseType,
                        scope,
                        jsonArrayOf("Cl.Cm", "Cl"),
                        Optional.of(oidcClaimsRequest));
        Optional<ErrorObject> errorObject = authorizationService.validateAuthRequest(authRequest);

        assertThat(errorObject, equalTo(Optional.empty()));
    }

    @Test
    void shouldReturnErrorWhenValidatingAuthRequestWhichContainsInvalidClaims() {
        ResponseType responseType = new ResponseType(ResponseType.Value.CODE);
        Scope scope = new Scope();
        scope.add(OIDCScopeValue.OPENID);
        when(dynamoClientService.getClient(CLIENT_ID.toString()))
                .thenReturn(
                        Optional.of(
                                generateClientRegistry(
                                        REDIRECT_URI.toString(), CLIENT_ID.toString())));
        var claimsSetRequest = new ClaimsSetRequest().add("nickname").add("birthdate");
        var oidcClaimsRequest = new OIDCClaimsRequest().withUserInfoClaimsRequest(claimsSetRequest);
        AuthenticationRequest authRequest =
                generateAuthRequest(
                        REDIRECT_URI.toString(),
                        responseType,
                        scope,
                        jsonArrayOf("Cl.Cm", "Cl"),
                        Optional.of(oidcClaimsRequest));
        Optional<ErrorObject> errorObject = authorizationService.validateAuthRequest(authRequest);

        assertThat(
                errorObject,
                equalTo(
                        Optional.of(
                                new ErrorObject(
                                        OAuth2Error.INVALID_REQUEST_CODE,
                                        "Request contains invalid claims"))));
    }

    @Test
    void shouldSuccessfullyValidateAccountManagementAuthRequest() {
        ResponseType responseType = new ResponseType(ResponseType.Value.CODE);
        Scope scope = new Scope(OIDCScopeValue.OPENID, CustomScopeValue.ACCOUNT_MANAGEMENT);
        when(dynamoClientService.getClient(CLIENT_ID.toString()))
                .thenReturn(
                        Optional.of(
                                generateClientRegistry(
                                        REDIRECT_URI.toString(),
                                        CLIENT_ID.toString(),
                                        List.of("openid", "am"))));
        Optional<ErrorObject> errorObject =
                authorizationService.validateAuthRequest(
                        generateAuthRequest(REDIRECT_URI.toString(), responseType, scope));

        assertThat(errorObject, equalTo(Optional.empty()));
    }

    @Test
    void shouldReturnErrorForAccountManagementAuthRequestWhenScopeNotInClient() {
        ResponseType responseType = new ResponseType(ResponseType.Value.CODE);
        Scope scope = new Scope(OIDCScopeValue.OPENID, CustomScopeValue.ACCOUNT_MANAGEMENT);
        when(dynamoClientService.getClient(CLIENT_ID.toString()))
                .thenReturn(
                        Optional.of(
                                generateClientRegistry(
                                        REDIRECT_URI.toString(), CLIENT_ID.toString())));
        Optional<ErrorObject> errorObject =
                authorizationService.validateAuthRequest(
                        generateAuthRequest(REDIRECT_URI.toString(), responseType, scope));

        assertThat(errorObject, equalTo(Optional.of(OAuth2Error.INVALID_SCOPE)));
    }

    @Test
    void shouldReturnErrorWhenClientIdIsNotValidInAuthRequest() {
        ResponseType responseType = new ResponseType(ResponseType.Value.CODE);
        Scope scope = new Scope();
        scope.add(OIDCScopeValue.OPENID);
        when(dynamoClientService.getClient(CLIENT_ID.toString())).thenReturn(Optional.empty());
        Optional<ErrorObject> errorObject =
                authorizationService.validateAuthRequest(
                        generateAuthRequest(REDIRECT_URI.toString(), responseType, scope));

        assertThat(errorObject, equalTo(Optional.of(OAuth2Error.UNAUTHORIZED_CLIENT)));
    }

    @Test
    void shouldReturnErrorWhenResponseCodeIsNotValidInAuthRequest() {
        ResponseType responseType =
                new ResponseType(ResponseType.Value.TOKEN, ResponseType.Value.CODE);
        Scope scope = new Scope();
        scope.add(OIDCScopeValue.OPENID);
        when(dynamoClientService.getClient(CLIENT_ID.toString()))
                .thenReturn(
                        Optional.of(
                                generateClientRegistry(
                                        REDIRECT_URI.toString(), CLIENT_ID.toString())));
        Optional<ErrorObject> errorObject =
                authorizationService.validateAuthRequest(
                        generateAuthRequest(REDIRECT_URI.toString(), responseType, scope));

        assertThat(errorObject, equalTo(Optional.of(OAuth2Error.UNSUPPORTED_RESPONSE_TYPE)));
    }

    @Test
    void shouldReturnErrorWhenScopeIsNotValidInAuthRequest() {
        ResponseType responseType = new ResponseType(ResponseType.Value.CODE);
        Scope scope = new Scope();
        scope.add(OIDCScopeValue.OPENID);
        scope.add(OIDCScopeValue.EMAIL);
        when(dynamoClientService.getClient(CLIENT_ID.toString()))
                .thenReturn(
                        Optional.of(
                                generateClientRegistry(
                                        REDIRECT_URI.toString(), CLIENT_ID.toString())));
        Optional<ErrorObject> errorObject =
                authorizationService.validateAuthRequest(
                        generateAuthRequest(REDIRECT_URI.toString(), responseType, scope));

        assertThat(errorObject, equalTo(Optional.of(OAuth2Error.INVALID_SCOPE)));
    }

    @Test
    void shouldReturnErrorWhenStateIsNotIncludedInAuthRequest() {
        ResponseType responseType = new ResponseType(ResponseType.Value.CODE);
        Scope scope = new Scope();
        scope.add(OIDCScopeValue.OPENID);
        when(dynamoClientService.getClient(CLIENT_ID.toString()))
                .thenReturn(
                        Optional.of(
                                generateClientRegistry(
                                        REDIRECT_URI.toString(), CLIENT_ID.toString())));
        AuthenticationRequest authRequest =
                new AuthenticationRequest.Builder(
                                responseType, scope, new ClientID(CLIENT_ID), REDIRECT_URI)
                        .nonce(new Nonce())
                        .build();
        Optional<ErrorObject> errorObject = authorizationService.validateAuthRequest(authRequest);

        assertThat(
                errorObject,
                equalTo(
                        Optional.of(
                                new ErrorObject(
                                        OAuth2Error.INVALID_REQUEST_CODE,
                                        "Request is missing state parameter"))));
    }

    @Test
    void shouldReturnErrorWhenNonceIsNotIncludedInAuthRequest() {
        ResponseType responseType = new ResponseType(ResponseType.Value.CODE);
        Scope scope = new Scope();
        scope.add(OIDCScopeValue.OPENID);
        when(dynamoClientService.getClient(CLIENT_ID.toString()))
                .thenReturn(
                        Optional.of(
                                generateClientRegistry(
                                        REDIRECT_URI.toString(), CLIENT_ID.toString())));
        AuthenticationRequest authRequest =
                new AuthenticationRequest.Builder(
                                responseType, scope, new ClientID(CLIENT_ID), REDIRECT_URI)
                        .state(new State())
                        .build();
        Optional<ErrorObject> errorObject = authorizationService.validateAuthRequest(authRequest);

        assertThat(
                errorObject,
                equalTo(
                        Optional.of(
                                new ErrorObject(
                                        OAuth2Error.INVALID_REQUEST_CODE,
                                        "Request is missing nonce parameter"))));
    }

    @Test
    void shouldReturnErrorWhenInvalidVtrIsIncludedInAuthRequest() {
        ResponseType responseType = new ResponseType(ResponseType.Value.CODE);
        Scope scope = new Scope();
        scope.add(OIDCScopeValue.OPENID);
        when(dynamoClientService.getClient(CLIENT_ID.toString()))
                .thenReturn(
                        Optional.of(
                                generateClientRegistry(
                                        REDIRECT_URI.toString(), CLIENT_ID.toString())));
        AuthenticationRequest authRequest =
                new AuthenticationRequest.Builder(responseType, scope, CLIENT_ID, REDIRECT_URI)
                        .state(new State())
                        .nonce(new Nonce())
                        .customParameter("vtr", jsonArrayOf("Cm"))
                        .build();
        Optional<ErrorObject> errorObject = authorizationService.validateAuthRequest(authRequest);

        assertThat(
                errorObject,
                equalTo(
                        Optional.of(
                                new ErrorObject(
                                        OAuth2Error.INVALID_REQUEST_CODE,
                                        "Request vtr not valid"))));
    }

    @Test
    void shouldThrowExceptionWhenRedirectUriIsInvalidInAuthRequest() {
        ResponseType responseType = new ResponseType(ResponseType.Value.CODE);
        String redirectURi = "http://localhost/redirect";
        Scope scope = new Scope();
        scope.add(OIDCScopeValue.OPENID);
        when(dynamoClientService.getClient(CLIENT_ID.toString()))
                .thenReturn(
                        Optional.of(
                                generateClientRegistry(
                                        "http://localhost/wrong-redirect", CLIENT_ID.toString())));

        RuntimeException exception =
                assertThrows(
                        RuntimeException.class,
                        () ->
                                authorizationService.validateAuthRequest(
                                        generateAuthRequest(redirectURi, responseType, scope)),
                        "Expected to throw exception");
        assertThat(
                exception.getMessage(),
                equalTo(format("Invalid Redirect in request %s", redirectURi)));
    }

    @Test
    void shouldCreateUserContextFromSessionAndClientSession() {
        String email = "joe.bloggs@example.com";
        Session session = new Session("a-session-id");
        session.setEmailAddress(email);
        ClientID clientId = new ClientID("client-id");
        when(dynamoClientService.getClient(clientId.getValue()))
                .thenReturn(
                        Optional.of(
                                generateClientRegistry(
                                        REDIRECT_URI.toString(), clientId.getValue())));
        when(dynamoService.getUserProfileByEmailMaybe(email))
                .thenReturn(Optional.of(mock(UserProfile.class)));
        Scope scopes =
                new Scope(
                        OIDCScopeValue.OPENID, OIDCScopeValue.EMAIL, OIDCScopeValue.OFFLINE_ACCESS);
        AuthenticationRequest authRequest =
                new AuthenticationRequest.Builder(
                                new ResponseType(ResponseType.Value.CODE),
                                scopes,
                                clientId,
                                REDIRECT_URI)
                        .state(new State())
                        .nonce(new Nonce())
                        .build();
        ClientSession clientSession =
                new ClientSession(
                        authRequest.toParameters(), LocalDateTime.now(), mock(VectorOfTrust.class));
        UserContext userContext = authorizationService.buildUserContext(session, clientSession);

        assertEquals(userContext.getSession(), session);
        assertEquals(userContext.getClientSession(), clientSession);
    }

    @Test
    void shouldGetPersistentCookieIdFromExistingCookie() {
        Map<String, String> requestCookieHeader =
                Map.of(
                        CookieHelper.REQUEST_COOKIE_HEADER,
                        "di-persistent-session-id=some-persistent-id;gs=session-id.456");

        String persistentSessionId =
                authorizationService.getExistingOrCreateNewPersistentSessionId(requestCookieHeader);

        assertEquals(persistentSessionId, "some-persistent-id");
    }

    @ParameterizedTest
    @MethodSource("cookieConsentValues")
    void shouldValidateCookieConsentValue(String cookieConsentValue, boolean expectedResult) {
        assertThat(
                authorizationService.isValidCookieConsentValue(cookieConsentValue),
                equalTo(expectedResult));
    }

    private static Stream<Arguments> cookieConsentValues() {
        return Stream.of(
                Arguments.of("accept", true),
                Arguments.of("reject", true),
                Arguments.of("not-engaged", true),
                Arguments.of("Accept", false),
                Arguments.of("some-value", false),
                Arguments.of("", false));
    }

    private ClientRegistry generateClientRegistry(String redirectURI, String clientID) {
        return generateClientRegistry(redirectURI, clientID, singletonList("openid"));
    }

    private ClientRegistry generateClientRegistry(
            String redirectURI, String clientID, List<String> scopes) {
        return new ClientRegistry()
                .setRedirectUrls(singletonList(redirectURI))
                .setClientID(clientID)
                .setContacts(singletonList("joe.bloggs@digital.cabinet-office.gov.uk"))
                .setPublicKey(null)
                .setScopes(scopes);
    }

    private AuthenticationRequest generateAuthRequest(
            String redirectUri, ResponseType responseType, Scope scope) {
        return generateAuthRequest(
                redirectUri, responseType, scope, jsonArrayOf("Cl.Cm", "Cl"), Optional.empty());
    }

    private AuthenticationRequest generateAuthRequest(
            String redirectUri,
            ResponseType responseType,
            Scope scope,
            String jsonArray,
            Optional<OIDCClaimsRequest> claimsRequest) {
        AuthenticationRequest.Builder authRequestBuilder =
                new AuthenticationRequest.Builder(
                                responseType, scope, CLIENT_ID, URI.create(redirectUri))
                        .state(STATE)
                        .nonce(NONCE)
                        .customParameter("vtr", jsonArray);
        claimsRequest.ifPresent(authRequestBuilder::claims);

        return authRequestBuilder.build();
    }
}
