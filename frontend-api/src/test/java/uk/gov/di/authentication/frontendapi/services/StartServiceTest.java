package uk.gov.di.authentication.frontendapi.services;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.nimbusds.oauth2.sdk.ParseException;
import com.nimbusds.oauth2.sdk.ResponseType;
import com.nimbusds.oauth2.sdk.Scope;
import com.nimbusds.oauth2.sdk.id.ClientID;
import com.nimbusds.oauth2.sdk.id.State;
import com.nimbusds.openid.connect.sdk.AuthenticationRequest;
import com.nimbusds.openid.connect.sdk.Nonce;
import com.nimbusds.openid.connect.sdk.OIDCScopeValue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import uk.gov.di.authentication.shared.entity.ClientRegistry;
import uk.gov.di.authentication.shared.entity.ClientSession;
import uk.gov.di.authentication.shared.entity.ClientType;
import uk.gov.di.authentication.shared.entity.CredentialTrustLevel;
import uk.gov.di.authentication.shared.entity.CustomScopeValue;
import uk.gov.di.authentication.shared.entity.MFAMethod;
import uk.gov.di.authentication.shared.entity.MFAMethodType;
import uk.gov.di.authentication.shared.entity.Session;
import uk.gov.di.authentication.shared.entity.UserCredentials;
import uk.gov.di.authentication.shared.entity.UserProfile;
import uk.gov.di.authentication.shared.entity.VectorOfTrust;
import uk.gov.di.authentication.shared.helpers.IdGenerator;
import uk.gov.di.authentication.shared.helpers.NowHelper;
import uk.gov.di.authentication.shared.services.DynamoClientService;
import uk.gov.di.authentication.shared.services.DynamoService;
import uk.gov.di.authentication.shared.state.UserContext;

import java.net.URI;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

import static java.util.Collections.singletonList;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static uk.gov.di.authentication.sharedtest.helper.JsonArrayHelper.jsonArrayOf;

class StartServiceTest {

    private static final URI REDIRECT_URI = URI.create("http://localhost/redirect");
    private static final String EMAIL = "joe.bloggs@example.com";
    private static final ClientID CLIENT_ID = new ClientID("client-id");
    private static final String CLIENT_NAME = "test-client";
    private static final Session SESSION = new Session("a-session-id").setEmailAddress(EMAIL);
    private static final Scope SCOPES =
            new Scope(OIDCScopeValue.OPENID, OIDCScopeValue.EMAIL, OIDCScopeValue.OFFLINE_ACCESS);
    private static final String AUDIENCE = "oidc-audience";
    private static final Scope DOC_APP_SCOPES =
            new Scope(OIDCScopeValue.OPENID, CustomScopeValue.DOC_CHECKING_APP);
    private static final State STATE = new State();

    private final DynamoClientService dynamoClientService = mock(DynamoClientService.class);
    private final DynamoService dynamoService = mock(DynamoService.class);
    private StartService startService;

    @BeforeEach
    void setup() {
        startService = new StartService(dynamoClientService, dynamoService);
    }

    @Test
    void shouldCreateUserContextFromSessionAndClientSession() {
        when(dynamoClientService.getClient(CLIENT_ID.getValue()))
                .thenReturn(
                        Optional.of(
                                generateClientRegistry(
                                        REDIRECT_URI.toString(), CLIENT_ID.getValue(), false)));
        when(dynamoService.getUserProfileByEmailMaybe(EMAIL))
                .thenReturn(Optional.of(mock(UserProfile.class)));
        when(dynamoService.getUserCredentialsFromEmail(EMAIL))
                .thenReturn((mock(UserCredentials.class)));
        var authRequest =
                new AuthenticationRequest.Builder(
                                new ResponseType(ResponseType.Value.CODE),
                                SCOPES,
                                CLIENT_ID,
                                REDIRECT_URI)
                        .state(new State())
                        .nonce(new Nonce())
                        .build();
        var clientSession =
                new ClientSession(
                        authRequest.toParameters(),
                        LocalDateTime.now(),
                        mock(VectorOfTrust.class),
                        CLIENT_NAME);
        var userContext = startService.buildUserContext(SESSION, clientSession);

        assertThat(userContext.getSession(), equalTo(SESSION));
        assertThat(userContext.getClientSession(), equalTo(clientSession));
    }

    private static Stream<Arguments> userStartInfo() {
        return Stream.of(
                Arguments.of(jsonArrayOf("Cl"), "some-cookie-consent", null, false, false),
                Arguments.of(jsonArrayOf("Cl.Cm"), null, "ga-tracking-id", false, true));
    }

    @ParameterizedTest
    @MethodSource("userStartInfo")
    void shouldCreateUserStartInfo(
            String vtr,
            String cookieConsent,
            String gaTrackingId,
            boolean rpSupportsIdentity,
            boolean isAuthenticated) {
        var userContext =
                buildUserContext(
                        vtr,
                        false,
                        true,
                        ClientType.WEB,
                        null,
                        rpSupportsIdentity,
                        isAuthenticated,
                        Optional.empty(),
                        Optional.empty());
        var userStartInfo =
                startService.buildUserStartInfo(userContext, cookieConsent, gaTrackingId, true);

        assertThat(userStartInfo.isUpliftRequired(), equalTo(false));
        assertThat(userStartInfo.isIdentityRequired(), equalTo(false));
        assertThat(userStartInfo.isConsentRequired(), equalTo(false));
        assertThat(userStartInfo.getCookieConsent(), equalTo(cookieConsent));
        assertThat(userStartInfo.getGaCrossDomainTrackingId(), equalTo(gaTrackingId));
        assertThat(userStartInfo.isDocCheckingAppUser(), equalTo(false));
        assertThat(userStartInfo.isAuthenticated(), equalTo(isAuthenticated));
    }

    private static Stream<Arguments> userStartIdentityInfo() {
        return Stream.of(
                Arguments.of(jsonArrayOf("P2.Cl.Cm"), true, true, true),
                Arguments.of(jsonArrayOf("Cl.Cm"), false, true, true),
                Arguments.of(jsonArrayOf("P2.Cl.Cm"), false, false, true),
                Arguments.of(jsonArrayOf("P2.Cl.Cm"), true, true, true),
                Arguments.of(jsonArrayOf("P2.Cl.Cm"), false, true, false),
                Arguments.of(jsonArrayOf("P2.Cl.Cm"), false, false, false));
    }

    @ParameterizedTest
    @MethodSource("userStartIdentityInfo")
    void shouldCreateUserStartInfoWithCorrectIdentityRequiredValue(
            String vtr,
            boolean expectedIdentityRequiredValue,
            boolean rpSupportsIdentity,
            boolean identityEnabled) {
        var userContext =
                buildUserContext(
                        vtr,
                        false,
                        true,
                        ClientType.WEB,
                        null,
                        rpSupportsIdentity,
                        false,
                        Optional.empty(),
                        Optional.empty());
        var userStartInfo =
                startService.buildUserStartInfo(
                        userContext, "some-cookie-consent", "some-ga-tracking-id", identityEnabled);

        assertThat(userStartInfo.isUpliftRequired(), equalTo(false));
        assertThat(userStartInfo.isIdentityRequired(), equalTo(expectedIdentityRequiredValue));
        assertThat(userStartInfo.isConsentRequired(), equalTo(false));
        assertThat(userStartInfo.getCookieConsent(), equalTo("some-cookie-consent"));
        assertThat(userStartInfo.getGaCrossDomainTrackingId(), equalTo("some-ga-tracking-id"));
        assertThat(userStartInfo.isDocCheckingAppUser(), equalTo(false));
    }

    private static Stream<Boolean> userStartDocAppInfo() {
        return Stream.of(true, false);
    }

    @ParameterizedTest
    @MethodSource("userStartDocAppInfo")
    void shouldCreateUserStartInfoWithCorrectDocCheckingAppUserValue(boolean isAuthenticated)
            throws NoSuchAlgorithmException, JOSEException {
        var userContext =
                buildUserContext(
                        null,
                        false,
                        false,
                        ClientType.APP,
                        generateSignedJWT(),
                        true,
                        isAuthenticated,
                        Optional.empty(),
                        Optional.empty());
        var userStartInfo =
                startService.buildUserStartInfo(
                        userContext, "some-cookie-consent", "some-ga-tracking-id", true);

        assertThat(userStartInfo.isUpliftRequired(), equalTo(false));
        assertThat(userStartInfo.isIdentityRequired(), equalTo(false));
        assertThat(userStartInfo.isAuthenticated(), equalTo(false));
        assertThat(userStartInfo.isConsentRequired(), equalTo(false));
        assertThat(userStartInfo.getCookieConsent(), equalTo("some-cookie-consent"));
        assertThat(userStartInfo.getGaCrossDomainTrackingId(), equalTo("some-ga-tracking-id"));
        assertThat(userStartInfo.isDocCheckingAppUser(), equalTo(true));
    }

    private static Stream<Arguments> userStartUpliftInfo() {
        var authAppUserCredentialsVerified =
                new UserCredentials()
                        .withEmail(EMAIL)
                        .setMfaMethod(
                                (new MFAMethod(
                                        MFAMethodType.AUTH_APP.getValue(),
                                        "rubbish-value",
                                        true,
                                        true,
                                        NowHelper.nowMinus(50, ChronoUnit.DAYS).toString())));
        var authAppUserProfileVerified = new UserProfile().withEmail(EMAIL).withAccountVerified(1);

        var authAppUserCredentialsUnverified =
                new UserCredentials()
                        .withEmail(EMAIL)
                        .setMfaMethod(
                                (new MFAMethod(
                                        MFAMethodType.AUTH_APP.getValue(),
                                        "rubbish-value",
                                        false,
                                        true,
                                        NowHelper.nowMinus(50, ChronoUnit.DAYS).toString())));
        var authAppUserProfileUnverified =
                new UserProfile().withEmail(EMAIL).withAccountVerified(0);

        var smsUserCredentialsVerified = new UserCredentials().withEmail(EMAIL);
        var smsUserProfileVerified =
                new UserProfile()
                        .withEmail(EMAIL)
                        .withAccountVerified(1)
                        .withPhoneNumber("+447316763843")
                        .withPhoneNumberVerified(true);

        var smsUserCredentialsUnverified = new UserCredentials().withEmail(EMAIL);
        var smsUserProfileUnverified =
                new UserProfile()
                        .withEmail(EMAIL)
                        .withAccountVerified(0)
                        .withPhoneNumber("+447316763843")
                        .withPhoneNumberVerified(false);

        var unverifiedUserCredentials = new UserCredentials().withEmail(EMAIL);
        var unverifiedUserProfile =
                new UserProfile()
                        .withEmail(EMAIL)
                        .withAccountVerified(0)
                        .withPhoneNumberVerified(false);

        return Stream.of(
                Arguments.of(
                        jsonArrayOf("Cl.Cm"),
                        false,
                        CredentialTrustLevel.LOW_LEVEL,
                        true,
                        authAppUserProfileVerified,
                        authAppUserCredentialsVerified,
                        MFAMethodType.AUTH_APP),
                Arguments.of(
                        jsonArrayOf("Cl.Cm"),
                        false,
                        null,
                        false,
                        authAppUserProfileUnverified,
                        authAppUserCredentialsUnverified,
                        null),
                Arguments.of(
                        jsonArrayOf("Cl.Cm"),
                        false,
                        CredentialTrustLevel.LOW_LEVEL,
                        true,
                        smsUserProfileVerified,
                        smsUserCredentialsVerified,
                        MFAMethodType.SMS),
                Arguments.of(
                        jsonArrayOf("Cl.Cm"),
                        false,
                        null,
                        false,
                        smsUserProfileUnverified,
                        smsUserCredentialsUnverified,
                        null),
                Arguments.of(
                        jsonArrayOf("Cl.Cm"),
                        false,
                        null,
                        false,
                        unverifiedUserProfile,
                        unverifiedUserCredentials,
                        null),
                Arguments.of(
                        jsonArrayOf("Cl"),
                        false,
                        CredentialTrustLevel.LOW_LEVEL,
                        false,
                        authAppUserProfileVerified,
                        authAppUserCredentialsVerified,
                        MFAMethodType.AUTH_APP),
                Arguments.of(
                        jsonArrayOf("Cl.Cm"),
                        false,
                        CredentialTrustLevel.MEDIUM_LEVEL,
                        false,
                        authAppUserProfileVerified,
                        authAppUserCredentialsVerified,
                        MFAMethodType.AUTH_APP),
                Arguments.of(
                        jsonArrayOf("P2.Cl.Cm"),
                        true,
                        CredentialTrustLevel.MEDIUM_LEVEL,
                        false,
                        authAppUserProfileVerified,
                        authAppUserCredentialsVerified,
                        MFAMethodType.AUTH_APP));
    }

    @ParameterizedTest
    @MethodSource("userStartUpliftInfo")
    void shouldCreateUserStartInfoWithCorrectUpliftRequiredValue(
            String vtr,
            boolean expectedIdentityRequiredValue,
            CredentialTrustLevel credentialTrustLevel,
            boolean expectedUpliftRequiredValue,
            UserProfile userProfile,
            UserCredentials userCredentials,
            MFAMethodType expectedMfaMethodType) {
        var userContext =
                buildUserContext(
                        vtr,
                        false,
                        true,
                        ClientType.WEB,
                        null,
                        true,
                        false,
                        Optional.of(userProfile),
                        Optional.of(userCredentials));
        userContext
                .getSession()
                .setCurrentCredentialStrength(credentialTrustLevel)
                .setEmailAddress(EMAIL);
        var userStartInfo =
                startService.buildUserStartInfo(
                        userContext, "some-cookie-consent", "some-ga-tracking-id", true);

        assertThat(userStartInfo.isUpliftRequired(), equalTo(expectedUpliftRequiredValue));
        assertThat(userStartInfo.isIdentityRequired(), equalTo(expectedIdentityRequiredValue));
        assertThat(userStartInfo.isConsentRequired(), equalTo(false));
        assertThat(userStartInfo.getCookieConsent(), equalTo("some-cookie-consent"));
        assertThat(userStartInfo.getGaCrossDomainTrackingId(), equalTo("some-ga-tracking-id"));
        assertThat(userStartInfo.isDocCheckingAppUser(), equalTo(false));
        assertThat(userStartInfo.getMfaMethodType(), equalTo(expectedMfaMethodType));
    }

    @ParameterizedTest
    @MethodSource("clientStartInfo")
    void shouldCreateClientStartInfo(
            boolean cookieConsentShared, ClientType clientType, SignedJWT signedJWT)
            throws ParseException {
        var userContext =
                buildUserContext(
                        jsonArrayOf("Cl.Cm"),
                        false,
                        cookieConsentShared,
                        clientType,
                        signedJWT,
                        false,
                        false,
                        Optional.empty(),
                        Optional.empty());

        var clientStartInfo = startService.buildClientStartInfo(userContext);

        assertThat(clientStartInfo.getCookieConsentShared(), equalTo(cookieConsentShared));
        assertThat(clientStartInfo.getClientName(), equalTo(CLIENT_NAME));
        assertThat(clientStartInfo.getRedirectUri(), equalTo(REDIRECT_URI));
        assertThat(clientStartInfo.getState().getValue(), equalTo(STATE.getValue()));

        var expectedScopes = SCOPES;
        if (Objects.nonNull(signedJWT)) {
            expectedScopes = DOC_APP_SCOPES;
        }
        assertThat(clientStartInfo.getScopes(), equalTo(expectedScopes.toStringList()));
    }

    @Test
    void shouldReturnGaTrackingIdWhenPresentInAuthRequest() {
        var gaTrackingId = IdGenerator.generate();
        var authRequest =
                new AuthenticationRequest.Builder(
                                new ResponseType(ResponseType.Value.CODE),
                                SCOPES,
                                CLIENT_ID,
                                REDIRECT_URI)
                        .state(new State())
                        .nonce(new Nonce())
                        .customParameter("_ga", gaTrackingId)
                        .build();

        assertThat(startService.getGATrackingId(authRequest.toParameters()), equalTo(gaTrackingId));
    }

    @Test
    void shouldReturnNullWhenGaTrackingIdIsNotPresentInAuthRequest() {
        var authRequest =
                new AuthenticationRequest.Builder(
                                new ResponseType(ResponseType.Value.CODE),
                                SCOPES,
                                CLIENT_ID,
                                REDIRECT_URI)
                        .state(new State())
                        .nonce(new Nonce())
                        .build();

        assertThat(startService.getGATrackingId(authRequest.toParameters()), equalTo(null));
    }

    @ParameterizedTest
    @MethodSource("cookieConsentValues")
    void shouldReturnCookieConsentValueWhenPresentAndValid(
            String cookieConsentValue, boolean cookieConsentShared, String expectedValue) {
        when(dynamoClientService.getClient(CLIENT_ID.getValue()))
                .thenReturn(
                        Optional.of(
                                generateClientRegistry(
                                        REDIRECT_URI.toString(),
                                        CLIENT_ID.getValue(),
                                        cookieConsentShared)));
        var authRequest =
                new AuthenticationRequest.Builder(
                                new ResponseType(ResponseType.Value.CODE),
                                SCOPES,
                                CLIENT_ID,
                                REDIRECT_URI)
                        .state(new State())
                        .nonce(new Nonce())
                        .customParameter("cookie_consent", cookieConsentValue)
                        .build();

        assertThat(
                startService.getCookieConsentValue(
                        authRequest.toParameters(), CLIENT_ID.getValue()),
                equalTo(expectedValue));
    }

    private static Stream<Arguments> cookieConsentValues() {
        return Stream.of(
                Arguments.of("accept", true, "accept"),
                Arguments.of("reject", true, "reject"),
                Arguments.of("not-engaged", true, "not-engaged"),
                Arguments.of("accept", false, null),
                Arguments.of("reject", false, null),
                Arguments.of("not-engaged", false, null),
                Arguments.of("Accept", true, null),
                Arguments.of("Accept", false, null),
                Arguments.of("", true, null),
                Arguments.of(null, true, null),
                Arguments.of("some-value", true, null));
    }

    private static Stream<Arguments> clientStartInfo()
            throws NoSuchAlgorithmException, JOSEException {
        return Stream.of(
                Arguments.of(false, ClientType.WEB, null),
                Arguments.of(true, ClientType.WEB, null),
                Arguments.of(true, ClientType.APP, generateSignedJWT()));
    }

    private ClientRegistry generateClientRegistry(
            String redirectURI, String clientID, boolean cookieConsentShared) {
        return new ClientRegistry()
                .withRedirectUrls(singletonList(redirectURI))
                .withClientID(clientID)
                .withContacts(singletonList("joe.bloggs@digital.cabinet-office.gov.uk"))
                .withPublicKey(null)
                .withScopes(singletonList("openid"))
                .withCookieConsentShared(cookieConsentShared);
    }

    private UserContext buildUserContext(
            String vtrValue,
            boolean consentRequired,
            boolean cookieConsentShared,
            ClientType clientType,
            SignedJWT requestObject,
            boolean identityVerificationSupport,
            boolean isAuthenticated,
            Optional<UserProfile> userProfile,
            Optional<UserCredentials> userCredentials) {
        AuthenticationRequest authRequest;
        var clientSessionVTR = VectorOfTrust.getDefaults();
        if (Objects.nonNull(requestObject)) {
            authRequest =
                    new AuthenticationRequest.Builder(
                                    new ResponseType(ResponseType.Value.CODE),
                                    DOC_APP_SCOPES,
                                    CLIENT_ID,
                                    REDIRECT_URI)
                            .state(STATE)
                            .nonce(new Nonce())
                            .requestObject(requestObject)
                            .build();
        } else {
            clientSessionVTR =
                    VectorOfTrust.parseFromAuthRequestAttribute(
                            Collections.singletonList(vtrValue));
            authRequest =
                    new AuthenticationRequest.Builder(
                                    new ResponseType(ResponseType.Value.CODE),
                                    SCOPES,
                                    CLIENT_ID,
                                    REDIRECT_URI)
                            .state(STATE)
                            .nonce(new Nonce())
                            .customParameter("vtr", vtrValue)
                            .build();
        }
        var clientSession =
                new ClientSession(
                        authRequest.toParameters(),
                        LocalDateTime.now(),
                        clientSessionVTR,
                        CLIENT_NAME);
        var clientRegistry =
                new ClientRegistry()
                        .withClientID(CLIENT_ID.getValue())
                        .withClientName(CLIENT_NAME)
                        .withConsentRequired(consentRequired)
                        .withCookieConsentShared(cookieConsentShared)
                        .withClientType(clientType.getValue())
                        .withIdentityVerificationSupported(identityVerificationSupport);
        return UserContext.builder(SESSION.setAuthenticated(isAuthenticated))
                .withClientSession(clientSession)
                .withClient(clientRegistry)
                .withUserCredentials(userCredentials)
                .withUserProfile(userProfile)
                .build();
    }

    private static SignedJWT generateSignedJWT() throws NoSuchAlgorithmException, JOSEException {
        var jwtClaimsSet =
                new JWTClaimsSet.Builder()
                        .audience(AUDIENCE)
                        .claim("redirect_uri", REDIRECT_URI.toString())
                        .claim("response_type", ResponseType.CODE.toString())
                        .claim("scope", DOC_APP_SCOPES.toString())
                        .claim("client_id", CLIENT_ID.getValue())
                        .claim("state", STATE.getValue())
                        .issuer(CLIENT_ID.getValue())
                        .build();
        var keyPair = KeyPairGenerator.getInstance("RSA").generateKeyPair();
        var jwsHeader = new JWSHeader(JWSAlgorithm.RS256);
        var signedJWT = new SignedJWT(jwsHeader, jwtClaimsSet);
        var signer = new RSASSASigner(keyPair.getPrivate());
        signedJWT.sign(signer);
        return signedJWT;
    }
}
