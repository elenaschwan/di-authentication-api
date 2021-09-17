package uk.gov.di.authentication.frontendapi.lambda;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.core.exception.SdkClientException;
import uk.gov.di.authentication.frontendapi.entity.ResetPasswordRequest;
import uk.gov.di.authentication.frontendapi.services.AwsSqsClient;
import uk.gov.di.authentication.shared.entity.BaseAPIResponse;
import uk.gov.di.authentication.shared.entity.ErrorResponse;
import uk.gov.di.authentication.shared.entity.NotificationType;
import uk.gov.di.authentication.shared.entity.NotifyRequest;
import uk.gov.di.authentication.shared.entity.Session;
import uk.gov.di.authentication.shared.entity.SessionAction;
import uk.gov.di.authentication.shared.entity.SessionState;
import uk.gov.di.authentication.shared.entity.UserProfile;
import uk.gov.di.authentication.shared.services.AuthenticationService;
import uk.gov.di.authentication.shared.services.CodeGeneratorService;
import uk.gov.di.authentication.shared.services.CodeStorageService;
import uk.gov.di.authentication.shared.services.ConfigurationService;
import uk.gov.di.authentication.shared.services.DynamoService;
import uk.gov.di.authentication.shared.services.RedisConnectionService;
import uk.gov.di.authentication.shared.services.SessionService;
import uk.gov.di.authentication.shared.services.ValidationService;
import uk.gov.di.authentication.shared.state.StateMachine;

import java.util.Optional;

import static uk.gov.di.authentication.shared.entity.ErrorResponse.ERROR_1001;
import static uk.gov.di.authentication.shared.entity.ErrorResponse.ERROR_1017;
import static uk.gov.di.authentication.shared.entity.SessionAction.SYSTEM_HAS_SENT_RESET_PASSWORD_LINK;
import static uk.gov.di.authentication.shared.helpers.ApiGatewayResponseHelper.generateApiGatewayProxyErrorResponse;
import static uk.gov.di.authentication.shared.helpers.ApiGatewayResponseHelper.generateApiGatewayProxyResponse;
import static uk.gov.di.authentication.shared.helpers.WarmerHelper.isWarming;
import static uk.gov.di.authentication.shared.state.StateMachine.userJourneyStateMachine;

public class ResetPasswordRequestHandler
        implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    private static final Logger LOGGER = LoggerFactory.getLogger(ResetPasswordRequestHandler.class);

    private final ConfigurationService configurationService;
    private final ValidationService validationService;
    private final AwsSqsClient sqsClient;
    private final SessionService sessionService;
    private final CodeGeneratorService codeGeneratorService;
    private final CodeStorageService codeStorageService;
    private final AuthenticationService authenticationService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final StateMachine<SessionState, SessionAction, UserProfile> stateMachine =
            userJourneyStateMachine();

    public ResetPasswordRequestHandler(
            ConfigurationService configurationService,
            ValidationService validationService,
            AwsSqsClient sqsClient,
            SessionService sessionService,
            CodeGeneratorService codeGeneratorService,
            CodeStorageService codeStorageService,
            AuthenticationService authenticationService) {
        this.configurationService = configurationService;
        this.validationService = validationService;
        this.sqsClient = sqsClient;
        this.sessionService = sessionService;
        this.codeGeneratorService = codeGeneratorService;
        this.codeStorageService = codeStorageService;
        this.authenticationService = authenticationService;
    }

    public ResetPasswordRequestHandler() {
        this.configurationService = new ConfigurationService();
        this.sqsClient =
                new AwsSqsClient(
                        configurationService.getAwsRegion(),
                        configurationService.getEmailQueueUri(),
                        configurationService.getSqsEndpointUri());
        this.validationService = new ValidationService();
        sessionService = new SessionService(configurationService);
        this.codeGeneratorService = new CodeGeneratorService();
        this.codeStorageService =
                new CodeStorageService(new RedisConnectionService(configurationService));
        this.authenticationService = new DynamoService(configurationService);
    }

    @Override
    public APIGatewayProxyResponseEvent handleRequest(
            APIGatewayProxyRequestEvent input, Context context) {
        return isWarming(input)
                .orElseGet(
                        () -> {
                            Optional<Session> session =
                                    sessionService.getSessionFromRequestHeaders(input.getHeaders());
                            if (session.isEmpty()) {
                                return generateApiGatewayProxyErrorResponse(
                                        400, ErrorResponse.ERROR_1000);
                            } else {
                                LOGGER.info(
                                        "ResetPasswordRequestHandler processing request for session {}",
                                        session.get().getSessionId());
                            }
                            try {
                                ResetPasswordRequest resetPasswordRequest =
                                        objectMapper.readValue(
                                                input.getBody(), ResetPasswordRequest.class);
                                if (!session.get()
                                        .validateSession(resetPasswordRequest.getEmail())) {
                                    LOGGER.info(
                                            "Invalid session. Email {}",
                                            resetPasswordRequest.getEmail());
                                    return generateApiGatewayProxyErrorResponse(
                                            400, ErrorResponse.ERROR_1000);
                                }
                                SessionState nextState =
                                        stateMachine.transition(
                                                session.get().getState(),
                                                SYSTEM_HAS_SENT_RESET_PASSWORD_LINK);

                                Optional<ErrorResponse> emailErrorResponse =
                                        validationService.validateEmailAddress(
                                                resetPasswordRequest.getEmail());
                                if (emailErrorResponse.isPresent()) {
                                    LOGGER.error(
                                            "Encountered emailErrorResponse: {}",
                                            emailErrorResponse.get());
                                    return generateApiGatewayProxyErrorResponse(
                                            400, emailErrorResponse.get());
                                }
                                return handleNotificationRequest(
                                        resetPasswordRequest.getEmail(),
                                        NotificationType.RESET_PASSWORD,
                                        session.get(),
                                        nextState);
                            } catch (SdkClientException ex) {
                                LOGGER.error("Error sending message to queue", ex);
                                return generateApiGatewayProxyResponse(
                                        500, "Error sending message to queue");
                            } catch (JsonProcessingException e) {
                                LOGGER.error("Error parsing request", e);
                                return generateApiGatewayProxyErrorResponse(400, ERROR_1001);
                            } catch (StateMachine.InvalidStateTransitionException e) {
                                LOGGER.error("Invalid transition in user journey", e);
                                return generateApiGatewayProxyErrorResponse(400, ERROR_1017);
                            }
                        });
    }

    private APIGatewayProxyResponseEvent handleNotificationRequest(
            String email,
            NotificationType notificationType,
            Session session,
            SessionState nextState)
            throws JsonProcessingException {

        String subjectId = authenticationService.getSubjectFromEmail(email).getValue();
        String code = codeGeneratorService.twentyByteEncodedRandomCode();
        NotifyRequest notifyRequest = new NotifyRequest(email, notificationType, code);

        codeStorageService.savePasswordResetCode(
                subjectId,
                code,
                configurationService.getCodeExpiry(),
                NotificationType.RESET_PASSWORD);
        sessionService.save(session.setState(nextState));

        sqsClient.send(serialiseRequest(notifyRequest));
        LOGGER.info(
                "ResetPasswordRequestHandler successfully processed request for session {}",
                session.getSessionId());
        return generateApiGatewayProxyResponse(200, new BaseAPIResponse(session.getState()));
    }

    private String serialiseRequest(Object request) throws JsonProcessingException {
        return objectMapper.writeValueAsString(request);
    }
}