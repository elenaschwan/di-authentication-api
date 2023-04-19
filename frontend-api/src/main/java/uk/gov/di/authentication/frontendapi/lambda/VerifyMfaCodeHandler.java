package uk.gov.di.authentication.frontendapi.lambda;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import uk.gov.di.authentication.frontendapi.domain.FrontendAuditableEvent;
import uk.gov.di.authentication.frontendapi.entity.VerifyMfaCodeRequest;
import uk.gov.di.authentication.frontendapi.services.DynamoAccountRecoveryBlockService;
import uk.gov.di.authentication.shared.entity.ClientRegistry;
import uk.gov.di.authentication.shared.entity.CredentialTrustLevel;
import uk.gov.di.authentication.shared.entity.ErrorResponse;
import uk.gov.di.authentication.shared.entity.MFAMethodType;
import uk.gov.di.authentication.shared.entity.Session;
import uk.gov.di.authentication.shared.helpers.ApiGatewayResponseHelper;
import uk.gov.di.authentication.shared.helpers.IpAddressHelper;
import uk.gov.di.authentication.shared.lambda.BaseFrontendHandler;
import uk.gov.di.authentication.shared.services.AuditService;
import uk.gov.di.authentication.shared.services.AuthenticationService;
import uk.gov.di.authentication.shared.services.ClientService;
import uk.gov.di.authentication.shared.services.ClientSessionService;
import uk.gov.di.authentication.shared.services.CloudwatchMetricsService;
import uk.gov.di.authentication.shared.services.CodeStorageService;
import uk.gov.di.authentication.shared.services.ConfigurationService;
import uk.gov.di.authentication.shared.services.DynamoService;
import uk.gov.di.authentication.shared.services.SessionService;
import uk.gov.di.authentication.shared.state.UserContext;
import uk.gov.di.authentication.shared.validation.MfaCodeValidatorFactory;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import static java.util.Map.entry;
import static uk.gov.di.authentication.frontendapi.domain.FrontendAuditableEvent.CODE_MAX_RETRIES_REACHED;
import static uk.gov.di.authentication.frontendapi.domain.FrontendAuditableEvent.CODE_VERIFIED;
import static uk.gov.di.authentication.frontendapi.domain.FrontendAuditableEvent.INVALID_CODE_SENT;
import static uk.gov.di.authentication.shared.entity.LevelOfConfidence.NONE;
import static uk.gov.di.authentication.shared.helpers.ApiGatewayResponseHelper.generateApiGatewayProxyErrorResponse;
import static uk.gov.di.authentication.shared.helpers.PersistentIdHelper.extractPersistentIdFromHeaders;
import static uk.gov.di.authentication.shared.services.AuditService.MetadataPair.pair;
import static uk.gov.di.authentication.shared.services.CodeStorageService.CODE_BLOCKED_KEY_PREFIX;

public class VerifyMfaCodeHandler extends BaseFrontendHandler<VerifyMfaCodeRequest>
        implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    private static final Logger LOG = LogManager.getLogger(VerifyMfaCodeHandler.class);
    private final CodeStorageService codeStorageService;
    private final AuditService auditService;
    private final MfaCodeValidatorFactory mfaCodeValidatorFactory;
    private final CloudwatchMetricsService cloudwatchMetricsService;
    private final DynamoAccountRecoveryBlockService accountRecoveryBlockService;

    public VerifyMfaCodeHandler(
            ConfigurationService configurationService,
            SessionService sessionService,
            ClientSessionService clientSessionService,
            ClientService clientService,
            AuthenticationService authenticationService,
            CodeStorageService codeStorageService,
            AuditService auditService,
            MfaCodeValidatorFactory mfaCodeValidatorFactory,
            CloudwatchMetricsService cloudwatchMetricsService,
            DynamoAccountRecoveryBlockService accountRecoveryBlockService) {
        super(
                VerifyMfaCodeRequest.class,
                configurationService,
                sessionService,
                clientSessionService,
                clientService,
                authenticationService);
        this.codeStorageService = codeStorageService;
        this.auditService = auditService;
        this.mfaCodeValidatorFactory = mfaCodeValidatorFactory;
        this.cloudwatchMetricsService = cloudwatchMetricsService;
        this.accountRecoveryBlockService = accountRecoveryBlockService;
    }

    public VerifyMfaCodeHandler() {
        this(ConfigurationService.getInstance());
    }

    public VerifyMfaCodeHandler(ConfigurationService configurationService) {
        super(VerifyMfaCodeRequest.class, configurationService);
        this.codeStorageService = new CodeStorageService(configurationService);
        this.auditService = new AuditService(configurationService);
        this.mfaCodeValidatorFactory =
                new MfaCodeValidatorFactory(
                        configurationService,
                        codeStorageService,
                        new DynamoService(configurationService));
        this.cloudwatchMetricsService = new CloudwatchMetricsService(configurationService);
        this.accountRecoveryBlockService =
                new DynamoAccountRecoveryBlockService(configurationService);
    }

    @Override
    public APIGatewayProxyResponseEvent handleRequestWithUserContext(
            APIGatewayProxyRequestEvent input,
            Context context,
            VerifyMfaCodeRequest codeRequest,
            UserContext userContext) {

        LOG.info("Invoking verify MFA code handler");
        try {
            var session = userContext.getSession();
            var mfaMethodType = codeRequest.getMfaMethodType();
            var isRegistration = codeRequest.isRegistration();

            var mfaCodeValidator =
                    mfaCodeValidatorFactory
                            .getMfaCodeValidator(mfaMethodType, isRegistration, userContext)
                            .orElse(null);

            if (Objects.isNull(mfaCodeValidator)) {
                LOG.info("No MFA code validator found for this MFA method type");
                return generateApiGatewayProxyErrorResponse(400, ErrorResponse.ERROR_1002);
            }

            var errorResponse = mfaCodeValidator.validateCode(codeRequest.getCode());

            processCodeSession(
                    errorResponse, session, mfaMethodType, input, userContext, isRegistration);

            sessionService.save(session);

            return errorResponse
                    .map(response -> generateApiGatewayProxyErrorResponse(400, response))
                    .orElseGet(
                            () -> {
                                var clientSession = userContext.getClientSession();
                                var clientId = userContext.getClient().get().getClientID();
                                var levelOfConfidence =
                                        clientSession
                                                        .getEffectiveVectorOfTrust()
                                                        .containsLevelOfConfidence()
                                                ? clientSession
                                                        .getEffectiveVectorOfTrust()
                                                        .getLevelOfConfidence()
                                                : NONE;

                                LOG.info(
                                        "MFA code has been successfully verified for MFA type: {}. RegistrationJourney: {}",
                                        codeRequest.getMfaMethodType().getValue(),
                                        codeRequest.isRegistration());
                                accountRecoveryBlockService.deleteBlockIfPresent(
                                        session.getEmailAddress());
                                sessionService.save(
                                        session.setCurrentCredentialStrength(
                                                        CredentialTrustLevel.MEDIUM_LEVEL)
                                                .setVerifiedMfaMethodType(
                                                        codeRequest.getMfaMethodType()));
                                cloudwatchMetricsService.incrementAuthenticationSuccess(
                                        session.isNewAccount(),
                                        clientId,
                                        userContext.getClientName(),
                                        levelOfConfidence.getValue(),
                                        clientService.isTestJourney(
                                                clientId, session.getEmailAddress()),
                                        true);
                                return ApiGatewayResponseHelper
                                        .generateEmptySuccessApiGatewayResponse();
                            });

        } catch (Exception e) {
            LOG.error("Unexpected exception thrown");
            return generateApiGatewayProxyErrorResponse(400, ErrorResponse.ERROR_1001);
        }
    }

    private FrontendAuditableEvent errorResponseAsFrontendAuditableEvent(
            ErrorResponse errorResponse) {

        Map<ErrorResponse, FrontendAuditableEvent> map =
                Map.ofEntries(
                        entry(ErrorResponse.ERROR_1042, CODE_MAX_RETRIES_REACHED),
                        entry(ErrorResponse.ERROR_1043, INVALID_CODE_SENT),
                        entry(ErrorResponse.ERROR_1034, CODE_MAX_RETRIES_REACHED),
                        entry(ErrorResponse.ERROR_1037, INVALID_CODE_SENT));

        if (map.containsKey(errorResponse)) {
            return map.get(errorResponse);
        }

        return INVALID_CODE_SENT;
    }

    private void processCodeSession(
            Optional<ErrorResponse> errorResponse,
            Session session,
            MFAMethodType mfaMethodType,
            APIGatewayProxyRequestEvent input,
            UserContext userContext,
            boolean isRegistration) {

        var auditableEvent =
                errorResponse
                        .map(this::errorResponseAsFrontendAuditableEvent)
                        .orElse(CODE_VERIFIED);

        if (isRegistration
                && errorResponse.isEmpty()
                && mfaMethodType.equals(MFAMethodType.AUTH_APP)) {
            authenticationService.setAccountVerified(session.getEmailAddress());
            authenticationService.setMFAMethodVerifiedTrue(
                    session.getEmailAddress(), mfaMethodType);
        } else if (isRegistration
                && errorResponse.isEmpty()
                && mfaMethodType.equals(MFAMethodType.SMS)) {
            authenticationService.updatePhoneNumberAndAccountVerifiedStatus(
                    session.getEmailAddress(), true);
        }

        if (errorResponse
                .map(t -> List.of(ErrorResponse.ERROR_1034, ErrorResponse.ERROR_1042).contains(t))
                .orElse(false)) {
            blockCodeForSessionAndResetCountIfBlockDoesNotExist(session, mfaMethodType);
        }

        auditService.submitAuditEvent(
                auditableEvent,
                userContext.getClientSessionId(),
                session.getSessionId(),
                userContext
                        .getClient()
                        .map(ClientRegistry::getClientID)
                        .orElse(AuditService.UNKNOWN),
                session.getInternalCommonSubjectIdentifier(),
                session.getEmailAddress(),
                IpAddressHelper.extractIpAddress(input),
                AuditService.UNKNOWN,
                extractPersistentIdFromHeaders(input.getHeaders()),
                pair("mfa-type", mfaMethodType.getValue()));
    }

    private void blockCodeForSessionAndResetCountIfBlockDoesNotExist(
            Session session, MFAMethodType mfaMethodType) {
        if (!codeStorageService.isBlockedForEmail(
                session.getEmailAddress(), CODE_BLOCKED_KEY_PREFIX)) {
            codeStorageService.saveBlockedForEmail(
                    session.getEmailAddress(),
                    CODE_BLOCKED_KEY_PREFIX,
                    configurationService.getBlockedEmailDuration());
            codeStorageService.deleteIncorrectMfaCodeAttemptsCount(session.getEmailAddress());
            codeStorageService.deleteIncorrectMfaCodeAttemptsCount(
                    session.getEmailAddress(), mfaMethodType);
        }
    }
}
