package uk.gov.di.authentication.frontendapi.lambda;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import uk.gov.di.authentication.frontendapi.domain.FrontendAuditableEvent;
import uk.gov.di.authentication.frontendapi.entity.UnblockEmailRequest;
import uk.gov.di.authentication.shared.entity.ClientRegistry;
import uk.gov.di.authentication.shared.entity.ErrorResponse;
import uk.gov.di.authentication.shared.helpers.IpAddressHelper;
import uk.gov.di.authentication.shared.helpers.PersistentIdHelper;
import uk.gov.di.authentication.shared.lambda.BaseFrontendHandler;
import uk.gov.di.authentication.shared.services.AuditService;
import uk.gov.di.authentication.shared.services.AuthenticationService;
import uk.gov.di.authentication.shared.services.ClientService;
import uk.gov.di.authentication.shared.services.ClientSessionService;
import uk.gov.di.authentication.shared.services.CodeStorageService;
import uk.gov.di.authentication.shared.services.ConfigurationService;
import uk.gov.di.authentication.shared.services.SessionService;
import uk.gov.di.authentication.shared.state.UserContext;

import static uk.gov.di.authentication.shared.helpers.ApiGatewayResponseHelper.generateApiGatewayProxyErrorResponse;
import static uk.gov.di.authentication.shared.helpers.ApiGatewayResponseHelper.generateEmptySuccessApiGatewayResponse;
import static uk.gov.di.authentication.shared.helpers.LogLineHelper.LogFieldName.CLIENT_ID;
import static uk.gov.di.authentication.shared.helpers.LogLineHelper.LogFieldName.PERSISTENT_SESSION_ID;
import static uk.gov.di.authentication.shared.helpers.LogLineHelper.attachLogFieldToLogs;
import static uk.gov.di.authentication.shared.helpers.LogLineHelper.attachSessionIdToLogs;
import static uk.gov.di.authentication.shared.helpers.PersistentIdHelper.extractPersistentIdFromHeaders;
import static uk.gov.di.authentication.shared.services.CodeStorageService.CODE_BLOCKED_KEY_PREFIX;

public class UnblockEmailHandler extends BaseFrontendHandler<UnblockEmailRequest>
        implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    private static final Logger LOG = LogManager.getLogger(UnblockEmailHandler.class);

    private final AuditService auditService;
    private final CodeStorageService codeStorageService;

    public UnblockEmailHandler(
            ConfigurationService configurationService,
            SessionService sessionService,
            ClientSessionService clientSessionService,
            ClientService clientService,
            AuthenticationService authenticationService,
            AuditService auditService,
            CodeStorageService codeStorageService) {
        super(
                UnblockEmailRequest.class,
                configurationService,
                sessionService,
                clientSessionService,
                clientService,
                authenticationService);
        this.auditService = auditService;
        this.codeStorageService = codeStorageService;
    }

    public UnblockEmailHandler() {
        this(ConfigurationService.getInstance());
    }

    public UnblockEmailHandler(ConfigurationService configurationService) {
        super(UnblockEmailRequest.class, configurationService);
        this.auditService = new AuditService(configurationService);
        this.codeStorageService = new CodeStorageService(configurationService);
    }

    @Override
    public APIGatewayProxyResponseEvent handleRequestWithUserContext(
            APIGatewayProxyRequestEvent input,
            Context context,
            UnblockEmailRequest request,
            UserContext userContext) {

        attachSessionIdToLogs(userContext.getSession());
        attachLogFieldToLogs(
                PERSISTENT_SESSION_ID, extractPersistentIdFromHeaders(input.getHeaders()));
        attachLogFieldToLogs(
                CLIENT_ID,
                userContext.getClient().map(ClientRegistry::getClientID).orElse("unknown"));

        LOG.info("Processing request");

        String emailAddress = request.getEmail().toLowerCase();
        String persistentSessionId =
                PersistentIdHelper.extractPersistentIdFromHeaders(input.getHeaders());

        if (userContext.getSession().getEmailAddress().equals(emailAddress)
                && codeStorageService.isBlockedForEmail(emailAddress, CODE_BLOCKED_KEY_PREFIX)) {
            codeStorageService.deleteBlockedForEmail(emailAddress, CODE_BLOCKED_KEY_PREFIX);
            LOG.info("Email unblocked");
            auditService.submitAuditEvent(
                    FrontendAuditableEvent.ADMIN_UNBLOCK_EMAIL_UNBLOCKED,
                    userContext.getClientSessionId(),
                    userContext.getSession().getSessionId(),
                    userContext
                            .getClient()
                            .map(ClientRegistry::getClientID)
                            .orElse(AuditService.UNKNOWN),
                    AuditService.UNKNOWN,
                    emailAddress,
                    IpAddressHelper.extractIpAddress(input),
                    AuditService.UNKNOWN,
                    persistentSessionId);
        } else {
            LOG.info("Unable to unblock email");
            return generateApiGatewayProxyErrorResponse(400, ErrorResponse.ERROR_1045);
        }
        LOG.info("Successfully processed request");
        return generateEmptySuccessApiGatewayResponse();
    }
}
