package uk.gov.di.authentication.frontendapi.lambda;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import uk.gov.di.authentication.frontendapi.domain.FrontendAuditableEvent;
import uk.gov.di.authentication.frontendapi.entity.VerifyCodeRequest;
import uk.gov.di.authentication.frontendapi.services.DynamoAccountRecoveryBlockService;
import uk.gov.di.authentication.shared.entity.ClientRegistry;
import uk.gov.di.authentication.shared.entity.ErrorResponse;
import uk.gov.di.authentication.shared.entity.MFAMethodType;
import uk.gov.di.authentication.shared.entity.NotificationType;
import uk.gov.di.authentication.shared.entity.Session;
import uk.gov.di.authentication.shared.exceptions.ClientNotFoundException;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import static java.util.Map.entry;
import static uk.gov.di.authentication.frontendapi.domain.FrontendAuditableEvent.CODE_MAX_RETRIES_REACHED;
import static uk.gov.di.authentication.frontendapi.domain.FrontendAuditableEvent.CODE_VERIFIED;
import static uk.gov.di.authentication.frontendapi.domain.FrontendAuditableEvent.INVALID_CODE_SENT;
import static uk.gov.di.authentication.shared.entity.LevelOfConfidence.NONE;
import static uk.gov.di.authentication.shared.entity.NotificationType.MFA_SMS;
import static uk.gov.di.authentication.shared.helpers.ApiGatewayResponseHelper.generateApiGatewayProxyErrorResponse;
import static uk.gov.di.authentication.shared.helpers.LogLineHelper.attachSessionIdToLogs;
import static uk.gov.di.authentication.shared.helpers.PersistentIdHelper.extractPersistentIdFromHeaders;
import static uk.gov.di.authentication.shared.helpers.TestClientHelper.isTestClientWithAllowedEmail;
import static uk.gov.di.authentication.shared.services.AuditService.MetadataPair.pair;
import static uk.gov.di.authentication.shared.services.CodeStorageService.CODE_BLOCKED_KEY_PREFIX;

public class VerifyCodeHandler extends BaseFrontendHandler<VerifyCodeRequest>
        implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    private static final Logger LOG = LogManager.getLogger(VerifyCodeHandler.class);

    private final CodeStorageService codeStorageService;
    private final AuditService auditService;
    private final CloudwatchMetricsService cloudwatchMetricsService;
    private final DynamoAccountRecoveryBlockService accountRecoveryBlockService;
    private final MfaCodeValidatorFactory mfaCodeValidatorFactory;

    protected VerifyCodeHandler(
            ConfigurationService configurationService,
            SessionService sessionService,
            ClientSessionService clientSessionService,
            ClientService clientService,
            AuthenticationService authenticationService,
            CodeStorageService codeStorageService,
            AuditService auditService,
            CloudwatchMetricsService cloudwatchMetricsService,
            DynamoAccountRecoveryBlockService accountRecoveryBlockService,
            MfaCodeValidatorFactory mfaCodeValidatorFactory) {
        super(
                VerifyCodeRequest.class,
                configurationService,
                sessionService,
                clientSessionService,
                clientService,
                authenticationService);
        this.codeStorageService = codeStorageService;
        this.auditService = auditService;
        this.cloudwatchMetricsService = cloudwatchMetricsService;
        this.accountRecoveryBlockService = accountRecoveryBlockService;
        this.mfaCodeValidatorFactory = mfaCodeValidatorFactory;
    }

    public VerifyCodeHandler() {
        this(ConfigurationService.getInstance());
    }

    public VerifyCodeHandler(ConfigurationService configurationService) {
        super(VerifyCodeRequest.class, configurationService);
        this.codeStorageService = new CodeStorageService(configurationService);
        this.auditService = new AuditService(configurationService);
        this.cloudwatchMetricsService = new CloudwatchMetricsService();
        this.accountRecoveryBlockService =
                new DynamoAccountRecoveryBlockService(configurationService);
        this.mfaCodeValidatorFactory =
                new MfaCodeValidatorFactory(
                        configurationService,
                        codeStorageService,
                        new DynamoService(configurationService));
    }

    @Override
    public APIGatewayProxyResponseEvent handleRequestWithUserContext(
            APIGatewayProxyRequestEvent input,
            Context context,
            VerifyCodeRequest codeRequest,
            UserContext userContext) {

        attachSessionIdToLogs(userContext.getSession());

        try {
            LOG.info("Processing request");

            var session = userContext.getSession();

            NotificationType notificationType = codeRequest.getNotificationType();
            MFAMethodType mfaMethodType =
                    getMfaMethodTypeFromNotificationType(notificationType).orElse(null);

            if (Objects.isNull(mfaMethodType)) {
                LOG.info("No MFAMethodType for NotificationType: {}", notificationType);
                return generateApiGatewayProxyErrorResponse(400, ErrorResponse.ERROR_1002);
            }

            boolean isRegistration = isNotificationTypePartOfARegistrationJourney(notificationType);

            boolean isTestClient = isTestClientWithAllowedEmail(userContext, configurationService);
            var mfaCodeValidator =
                    mfaCodeValidatorFactory
                            .getMfaCodeValidator(
                                    mfaMethodType, isRegistration, isTestClient, userContext)
                            .orElse(null);

            if (Objects.isNull(mfaCodeValidator)) {
                LOG.info("No MFA code validator found for this MFA method type");
                return generateApiGatewayProxyErrorResponse(400, ErrorResponse.ERROR_1002);
            }

            LOG.info(codeRequest.getCode());

            var errorResponse = mfaCodeValidator.validateCode(codeRequest.getCode());

            processCodeSession(
                    errorResponse, session, notificationType, mfaMethodType, input, userContext);
            sessionService.save(session);

            return errorResponse
                    .map(response -> generateApiGatewayProxyErrorResponse(400, response))
                    .orElseGet(
                            () -> {
                                LOG.info(
                                        "Code has been successfully verified for notification type: {}",
                                        notificationType);

                                return ApiGatewayResponseHelper
                                        .generateEmptySuccessApiGatewayResponse();
                            });
        } catch (ClientNotFoundException e) {
            return generateApiGatewayProxyErrorResponse(400, ErrorResponse.ERROR_1015);
        }
    }

    private Optional<MFAMethodType> getMfaMethodTypeFromNotificationType(
            NotificationType notificationType) {
        switch (notificationType) {
            case VERIFY_EMAIL:
            case RESET_PASSWORD_WITH_CODE:
                return Optional.of(MFAMethodType.EMAIL);
            case MFA_SMS:
                return Optional.of(MFAMethodType.SMS);
            default:
                return Optional.empty();
        }
    }

    private FrontendAuditableEvent errorResponseAsFrontendAuditableEvent(
            ErrorResponse errorResponse) {

        Map<ErrorResponse, FrontendAuditableEvent> map =
                Map.ofEntries(
                        entry(ErrorResponse.ERROR_1021, INVALID_CODE_SENT),
                        entry(ErrorResponse.ERROR_1033, CODE_MAX_RETRIES_REACHED),
                        entry(ErrorResponse.ERROR_1036, INVALID_CODE_SENT),
                        entry(ErrorResponse.ERROR_1039, CODE_MAX_RETRIES_REACHED));

        if (map.containsKey(errorResponse)) {
            return map.get(errorResponse);
        }

        return INVALID_CODE_SENT;
    }

    private boolean isNotificationTypePartOfARegistrationJourney(
            NotificationType notificationType) {
        if (notificationType == NotificationType.VERIFY_EMAIL) {
            return true;
        }
        return false;
    }

    private void processCodeSession(
            Optional<ErrorResponse> errorResponse,
            Session session,
            NotificationType notificationType,
            MFAMethodType mfaMethodType,
            APIGatewayProxyRequestEvent input,
            UserContext userContext) {
        String emailAddress = session.getEmailAddress();
        var clientId = userContext.getClient().get().getClientID();
        var clientSession = userContext.getClientSession();
        var levelOfConfidence =
                clientSession.getEffectiveVectorOfTrust().containsLevelOfConfidence()
                        ? clientSession.getEffectiveVectorOfTrust().getLevelOfConfidence()
                        : NONE;

        var auditableEvent =
                errorResponse
                        .map(this::errorResponseAsFrontendAuditableEvent)
                        .orElse(CODE_VERIFIED);

        List<AuditService.MetadataPair> metadataPairs = new ArrayList<>();
        metadataPairs.add(pair("notification-type", notificationType.name()));

        if (errorResponse.isEmpty() && notificationType.equals(MFA_SMS)) {
            LOG.info(
                    "MFA code has been successfully verified for MFA type: {}. RegistrationJourney: {}",
                    MFAMethodType.SMS.getValue(),
                    false);
            sessionService.save(session.setVerifiedMfaMethodType(MFAMethodType.SMS));

            metadataPairs.add(pair("mfa-type", mfaMethodType.getValue()));

            accountRecoveryBlockService.deleteBlockIfPresent(emailAddress);

            cloudwatchMetricsService.incrementAuthenticationSuccess(
                    session.isNewAccount(),
                    clientId,
                    userContext.getClientName(),
                    levelOfConfidence.getValue(),
                    clientService.isTestJourney(clientId, session.getEmailAddress()),
                    true);
        }

        codeStorageService.deleteOtpCode(emailAddress, notificationType);

        if (errorResponse
                .map(
                        t ->
                                List.of(
                                                ErrorResponse.ERROR_1027,
                                                ErrorResponse.ERROR_1034,
                                                ErrorResponse.ERROR_1033,
                                                ErrorResponse.ERROR_1039)
                                        .contains(t))
                .orElse(false)) {
            blockCodeForEmailAndMfaTypeAndResetCountIfBlockDoesNotExist(
                    emailAddress, mfaMethodType);
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
                metadataPairs.toArray(new AuditService.MetadataPair[metadataPairs.size()]));
    }

    private void blockCodeForEmailAndMfaTypeAndResetCountIfBlockDoesNotExist(
            String emailAddress, MFAMethodType mfaMethodType) {

        if (codeStorageService.isBlockedForEmail(emailAddress, CODE_BLOCKED_KEY_PREFIX)) {
            return;
        }

        codeStorageService.saveBlockedForEmail(
                emailAddress,
                CODE_BLOCKED_KEY_PREFIX + mfaMethodType.getValue(),
                configurationService.getBlockedEmailDuration());

        codeStorageService.deleteIncorrectMfaCodeAttemptsCount(emailAddress, mfaMethodType);
    }
}
