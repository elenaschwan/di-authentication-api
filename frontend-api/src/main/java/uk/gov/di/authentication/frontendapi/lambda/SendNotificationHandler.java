package uk.gov.di.authentication.frontendapi.lambda;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import software.amazon.awssdk.core.exception.SdkClientException;
import uk.gov.di.authentication.frontendapi.entity.SendNotificationRequest;
import uk.gov.di.authentication.shared.entity.BaseAPIResponse;
import uk.gov.di.authentication.shared.entity.ClientRegistry;
import uk.gov.di.authentication.shared.entity.ErrorResponse;
import uk.gov.di.authentication.shared.entity.NotificationType;
import uk.gov.di.authentication.shared.entity.NotifyRequest;
import uk.gov.di.authentication.shared.entity.Session;
import uk.gov.di.authentication.shared.entity.SessionAction;
import uk.gov.di.authentication.shared.entity.SessionState;
import uk.gov.di.authentication.shared.exceptions.ClientNotFoundException;
import uk.gov.di.authentication.shared.lambda.BaseFrontendHandler;
import uk.gov.di.authentication.shared.services.AuthenticationService;
import uk.gov.di.authentication.shared.services.AwsSqsClient;
import uk.gov.di.authentication.shared.services.ClientService;
import uk.gov.di.authentication.shared.services.ClientSessionService;
import uk.gov.di.authentication.shared.services.CodeGeneratorService;
import uk.gov.di.authentication.shared.services.CodeStorageService;
import uk.gov.di.authentication.shared.services.ConfigurationService;
import uk.gov.di.authentication.shared.services.RedisConnectionService;
import uk.gov.di.authentication.shared.services.SessionService;
import uk.gov.di.authentication.shared.services.ValidationService;
import uk.gov.di.authentication.shared.state.StateMachine;
import uk.gov.di.authentication.shared.state.UserContext;

import java.util.Optional;

import static uk.gov.di.authentication.shared.entity.ErrorResponse.ERROR_1001;
import static uk.gov.di.authentication.shared.entity.ErrorResponse.ERROR_1002;
import static uk.gov.di.authentication.shared.entity.ErrorResponse.ERROR_1011;
import static uk.gov.di.authentication.shared.entity.ErrorResponse.ERROR_1017;
import static uk.gov.di.authentication.shared.entity.NotificationType.ACCOUNT_CREATED_CONFIRMATION;
import static uk.gov.di.authentication.shared.entity.SessionAction.SYSTEM_HAS_SENT_EMAIL_VERIFICATION_CODE;
import static uk.gov.di.authentication.shared.entity.SessionAction.SYSTEM_HAS_SENT_PHONE_VERIFICATION_CODE;
import static uk.gov.di.authentication.shared.entity.SessionAction.SYSTEM_HAS_SENT_TOO_MANY_EMAIL_VERIFICATION_CODES;
import static uk.gov.di.authentication.shared.entity.SessionAction.SYSTEM_HAS_SENT_TOO_MANY_PHONE_VERIFICATION_CODES;
import static uk.gov.di.authentication.shared.entity.SessionAction.SYSTEM_IS_BLOCKED_FROM_SENDING_ANY_EMAIL_VERIFICATION_CODES;
import static uk.gov.di.authentication.shared.entity.SessionAction.SYSTEM_IS_BLOCKED_FROM_SENDING_ANY_PHONE_VERIFICATION_CODES;
import static uk.gov.di.authentication.shared.entity.SessionAction.USER_ENTERED_INVALID_EMAIL_VERIFICATION_CODE_TOO_MANY_TIMES;
import static uk.gov.di.authentication.shared.entity.SessionAction.USER_ENTERED_INVALID_PHONE_VERIFICATION_CODE_TOO_MANY_TIMES;
import static uk.gov.di.authentication.shared.helpers.ApiGatewayResponseHelper.generateApiGatewayProxyErrorResponse;
import static uk.gov.di.authentication.shared.helpers.ApiGatewayResponseHelper.generateApiGatewayProxyResponse;
import static uk.gov.di.authentication.shared.helpers.ApiGatewayResponseHelper.generateEmptySuccessApiGatewayResponse;
import static uk.gov.di.authentication.shared.helpers.LogLineHelper.*;
import static uk.gov.di.authentication.shared.helpers.LogLineHelper.LogFieldName.PERSISTENT_SESSION_ID;
import static uk.gov.di.authentication.shared.helpers.PersistentIdHelper.extractPersistentIdFromHeaders;
import static uk.gov.di.authentication.shared.services.CodeStorageService.CODE_BLOCKED_KEY_PREFIX;
import static uk.gov.di.authentication.shared.services.CodeStorageService.CODE_REQUEST_BLOCKED_KEY_PREFIX;
import static uk.gov.di.authentication.shared.state.StateMachine.userJourneyStateMachine;

public class SendNotificationHandler extends BaseFrontendHandler<SendNotificationRequest>
        implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    private static final Logger LOG = LogManager.getLogger(SendNotificationHandler.class);

    private final ValidationService validationService;
    private final AwsSqsClient sqsClient;
    private final CodeGeneratorService codeGeneratorService;
    private final CodeStorageService codeStorageService;
    private final StateMachine<SessionState, SessionAction, UserContext> stateMachine =
            userJourneyStateMachine();

    public SendNotificationHandler(
            ConfigurationService configurationService,
            SessionService sessionService,
            ClientSessionService clientSessionService,
            ClientService clientService,
            AuthenticationService authenticationService,
            ValidationService validationService,
            AwsSqsClient sqsClient,
            CodeGeneratorService codeGeneratorService,
            CodeStorageService codeStorageService) {
        super(
                SendNotificationRequest.class,
                configurationService,
                sessionService,
                clientSessionService,
                clientService,
                authenticationService);
        this.validationService = validationService;
        this.sqsClient = sqsClient;
        this.codeGeneratorService = codeGeneratorService;
        this.codeStorageService = codeStorageService;
    }

    public SendNotificationHandler() {
        super(SendNotificationRequest.class, ConfigurationService.getInstance());
        this.sqsClient =
                new AwsSqsClient(
                        configurationService.getAwsRegion(),
                        configurationService.getEmailQueueUri(),
                        configurationService.getSqsEndpointUri());
        this.validationService = new ValidationService();
        this.codeGeneratorService = new CodeGeneratorService();
        this.codeStorageService =
                new CodeStorageService(new RedisConnectionService(configurationService));
    }

    @Override
    public APIGatewayProxyResponseEvent handleRequestWithUserContext(
            APIGatewayProxyRequestEvent input,
            Context context,
            SendNotificationRequest request,
            UserContext userContext) {

        attachSessionIdToLogs(userContext.getSession());
        attachLogFieldToLogs(
                PERSISTENT_SESSION_ID, extractPersistentIdFromHeaders(input.getHeaders()));
        attachLogFieldToLogs(
                LogFieldName.CLIENT_ID,
                userContext.getClient().map(ClientRegistry::getClientID).orElse("unknown"));

        try {
            if (!userContext.getSession().validateSession(request.getEmail())) {
                return generateApiGatewayProxyErrorResponse(400, ErrorResponse.ERROR_1000);
            }
            if (request.getNotificationType().equals(ACCOUNT_CREATED_CONFIRMATION)) {
                LOG.info("Placing message on queue for AccountCreatedConfirmation");
                NotifyRequest notifyRequest =
                        new NotifyRequest(request.getEmail(), ACCOUNT_CREATED_CONFIRMATION);
                if (!isTestClientAndAllowedEmail(userContext, ACCOUNT_CREATED_CONFIRMATION)) {
                    sqsClient.send(objectMapper.writeValueAsString((notifyRequest)));
                    LOG.info("AccountCreatedConfirmation email placed on queue");
                }
                return generateEmptySuccessApiGatewayResponse();
            }
            boolean codeRequestValid =
                    isCodeRequestAttemptValid(
                            request.getEmail(),
                            userContext.getSession(),
                            request.getNotificationType(),
                            userContext);
            if (!codeRequestValid) {
                return generateApiGatewayProxyResponse(
                        400, new BaseAPIResponse(userContext.getSession().getState()));
            }
            SessionState nextState;
            switch (request.getNotificationType()) {
                case VERIFY_EMAIL:
                    nextState =
                            stateMachine.transition(
                                    userContext.getSession().getState(),
                                    SYSTEM_HAS_SENT_EMAIL_VERIFICATION_CODE,
                                    userContext);

                    Optional<ErrorResponse> emailErrorResponse =
                            validationService.validateEmailAddress(request.getEmail());
                    if (emailErrorResponse.isPresent()) {
                        return generateApiGatewayProxyErrorResponse(400, emailErrorResponse.get());
                    }
                    return handleNotificationRequest(
                            request.getEmail(),
                            request.getNotificationType(),
                            userContext.getSession(),
                            nextState,
                            userContext);
                case VERIFY_PHONE_NUMBER:
                    nextState =
                            stateMachine.transition(
                                    userContext.getSession().getState(),
                                    SYSTEM_HAS_SENT_PHONE_VERIFICATION_CODE,
                                    userContext);

                    if (request.getPhoneNumber() == null) {
                        return generateApiGatewayProxyResponse(400, ERROR_1011);
                    }
                    String phoneNumber = removeWhitespaceFromPhoneNumber(request.getPhoneNumber());
                    Optional<ErrorResponse> errorResponse =
                            validationService.validatePhoneNumber(phoneNumber);
                    if (errorResponse.isPresent()) {
                        return generateApiGatewayProxyErrorResponse(400, errorResponse.get());
                    }
                    return handleNotificationRequest(
                            phoneNumber,
                            request.getNotificationType(),
                            userContext.getSession(),
                            nextState,
                            userContext);
            }
            return generateApiGatewayProxyErrorResponse(400, ERROR_1002);
        } catch (SdkClientException ex) {
            LOG.error("Error sending message to queue");
            return generateApiGatewayProxyResponse(500, "Error sending message to queue");
        } catch (JsonProcessingException e) {
            return generateApiGatewayProxyErrorResponse(400, ERROR_1001);
        } catch (StateMachine.InvalidStateTransitionException e) {
            return generateApiGatewayProxyErrorResponse(400, ERROR_1017);
        } catch (ClientNotFoundException e) {
            return generateApiGatewayProxyErrorResponse(400, ErrorResponse.ERROR_1015);
        }
    }

    private String removeWhitespaceFromPhoneNumber(String phoneNumber) {
        return phoneNumber.replaceAll("\\s+", "");
    }

    private APIGatewayProxyResponseEvent handleNotificationRequest(
            String destination,
            NotificationType notificationType,
            Session session,
            SessionState nextState,
            UserContext userContext)
            throws JsonProcessingException, ClientNotFoundException {

        String code = codeGeneratorService.sixDigitCode();
        NotifyRequest notifyRequest = new NotifyRequest(destination, notificationType, code);
        codeStorageService.saveOtpCode(
                session.getEmailAddress(),
                code,
                configurationService.getCodeExpiry(),
                notificationType);
        sessionService.save(session.setState(nextState).incrementCodeRequestCount());
        if (!isTestClientAndAllowedEmail(userContext, notificationType)) {
            sqsClient.send(objectMapper.writeValueAsString((notifyRequest)));
            LOG.info("Successfully processed request");
        }
        return generateApiGatewayProxyResponse(200, new BaseAPIResponse(session.getState()));
    }

    private boolean isCodeRequestAttemptValid(
            String email,
            Session session,
            NotificationType notificationType,
            UserContext userContext) {
        if (session.getCodeRequestCount() == configurationService.getCodeMaxRetries()) {
            LOG.info("User has requested too many OTP codes");
            codeStorageService.saveBlockedForEmail(
                    email, CODE_REQUEST_BLOCKED_KEY_PREFIX, configurationService.getCodeExpiry());
            SessionState nextState =
                    stateMachine.transition(
                            session.getState(),
                            getSessionActionForCodeRequestLimitReached(notificationType),
                            userContext);
            sessionService.save(session.setState(nextState).resetCodeRequestCount());
            return false;
        }
        if (codeStorageService.isBlockedForEmail(email, CODE_REQUEST_BLOCKED_KEY_PREFIX)) {
            LOG.info("User is blocked from requesting any OTP codes");
            SessionState nextState =
                    stateMachine.transition(
                            session.getState(),
                            getSessionActionForMaxCodeRequests(notificationType),
                            userContext);
            sessionService.save(session.setState(nextState));
            return false;
        }
        if (codeStorageService.isBlockedForEmail(email, CODE_BLOCKED_KEY_PREFIX)) {
            LOG.info("User is blocked from requesting any OTP codes");
            SessionState nextState =
                    stateMachine.transition(
                            session.getState(),
                            getSessionActionForMaxCodeAttempts(notificationType),
                            userContext);
            sessionService.save(session.setState(nextState));
            return false;
        }
        return true;
    }

    private SessionAction getSessionActionForCodeRequestLimitReached(
            NotificationType notificationType) {
        switch (notificationType) {
            case VERIFY_EMAIL:
                return SYSTEM_HAS_SENT_TOO_MANY_EMAIL_VERIFICATION_CODES;
            case VERIFY_PHONE_NUMBER:
                return SYSTEM_HAS_SENT_TOO_MANY_PHONE_VERIFICATION_CODES;
            default:
                LOG.error("Invalid NotificationType sent");
                throw new RuntimeException("Invalid NotificationType sent");
        }
    }

    private SessionAction getSessionActionForMaxCodeRequests(NotificationType notificationType) {
        switch (notificationType) {
            case VERIFY_EMAIL:
                return SYSTEM_IS_BLOCKED_FROM_SENDING_ANY_EMAIL_VERIFICATION_CODES;
            case VERIFY_PHONE_NUMBER:
                return SYSTEM_IS_BLOCKED_FROM_SENDING_ANY_PHONE_VERIFICATION_CODES;
            default:
                LOG.error("Invalid NotificationType sent");
                throw new RuntimeException("Invalid NotificationType sent");
        }
    }

    private SessionAction getSessionActionForMaxCodeAttempts(NotificationType notificationType) {
        switch (notificationType) {
            case VERIFY_EMAIL:
                return USER_ENTERED_INVALID_EMAIL_VERIFICATION_CODE_TOO_MANY_TIMES;
            case VERIFY_PHONE_NUMBER:
                return USER_ENTERED_INVALID_PHONE_VERIFICATION_CODE_TOO_MANY_TIMES;
            default:
                LOG.error("Invalid NotificationType sent");
                throw new RuntimeException("Invalid NotificationType sent");
        }
    }

    private boolean isTestClientAndAllowedEmail(
            UserContext userContext, NotificationType notificationType)
            throws ClientNotFoundException {
        if (configurationService.isTestClientsEnabled()) {
            LOG.warn("TestClients are ENABLED");
        } else {
            return false;
        }
        String emailAddress = userContext.getSession().getEmailAddress();
        return userContext
                .getClient()
                .map(
                        clientRegistry -> {
                            if (clientRegistry.isTestClient()
                                    && clientRegistry
                                            .getTestClientEmailAllowlist()
                                            .contains(emailAddress)) {

                                LOG.info(
                                        "SendNotificationHandler not sending message on TestClientEmailAllowlist with NotificationType {}",
                                        notificationType);
                                return true;
                            } else {
                                return false;
                            }
                        })
                .orElseThrow(() -> new ClientNotFoundException(userContext.getSession()));
    }
}
