package uk.gov.di.authentication.frontendapi.lambda;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import uk.gov.di.authentication.frontendapi.entity.CheckUserExistsResponse;
import uk.gov.di.authentication.frontendapi.entity.UserWithEmailRequest;
import uk.gov.di.authentication.shared.entity.ErrorResponse;
import uk.gov.di.authentication.shared.entity.Session;
import uk.gov.di.authentication.shared.entity.SessionState;
import uk.gov.di.authentication.shared.helpers.StateMachine;
import uk.gov.di.authentication.shared.services.AuthenticationService;
import uk.gov.di.authentication.shared.services.ConfigurationService;
import uk.gov.di.authentication.shared.services.DynamoService;
import uk.gov.di.authentication.shared.services.SessionService;
import uk.gov.di.authentication.shared.services.ValidationService;

import java.util.Optional;

import static uk.gov.di.authentication.shared.helpers.ApiGatewayResponseHelper.generateApiGatewayProxyErrorResponse;
import static uk.gov.di.authentication.shared.helpers.ApiGatewayResponseHelper.generateApiGatewayProxyResponse;

public class CheckUserExistsHandler
        implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    private final ValidationService validationService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AuthenticationService authenticationService;
    private final SessionService sessionService;

    public CheckUserExistsHandler(
            ValidationService validationService,
            AuthenticationService authenticationService,
            SessionService sessionService) {
        this.validationService = validationService;
        this.authenticationService = authenticationService;
        this.sessionService = sessionService;
    }

    public CheckUserExistsHandler() {
        ConfigurationService configurationService = new ConfigurationService();
        this.validationService = new ValidationService();
        this.sessionService = new SessionService(configurationService);
        this.authenticationService =
                new DynamoService(
                        configurationService.getAwsRegion(),
                        configurationService.getEnvironment(),
                        configurationService.getDynamoEndpointUri());
    }

    @Override
    public APIGatewayProxyResponseEvent handleRequest(
            APIGatewayProxyRequestEvent input, Context context) {
        try {
            Optional<Session> session =
                    sessionService.getSessionFromRequestHeaders(input.getHeaders());
            if (session.isPresent()) {

                StateMachine.validateStateTransition(session.get(), SessionState.USER_NOT_FOUND);
                session.get().setState(SessionState.USER_NOT_FOUND);

                UserWithEmailRequest userExistsRequest =
                        objectMapper.readValue(input.getBody(), UserWithEmailRequest.class);
                String emailAddress = userExistsRequest.getEmail();
                Optional<ErrorResponse> errorResponse =
                        validationService.validateEmailAddress(emailAddress);
                if (errorResponse.isPresent()) {
                    return generateApiGatewayProxyErrorResponse(400, errorResponse.get());
                }
                boolean userExists = authenticationService.userExists(emailAddress);
                session.get().setEmailAddress(emailAddress);
                if (userExists) {
                    StateMachine.validateStateTransition(
                            session.get(), SessionState.AUTHENTICATION_REQUIRED);
                    session.get().setState(SessionState.AUTHENTICATION_REQUIRED);
                }
                CheckUserExistsResponse checkUserExistsResponse =
                        new CheckUserExistsResponse(
                                emailAddress, userExists, session.get().getState());
                sessionService.save(session.get());

                return generateApiGatewayProxyResponse(200, checkUserExistsResponse);
            }
            return generateApiGatewayProxyErrorResponse(400, ErrorResponse.ERROR_1000);
        } catch (JsonProcessingException e) {
            return generateApiGatewayProxyErrorResponse(400, ErrorResponse.ERROR_1001);
        } catch (StateMachine.InvalidStateTransitionException e) {
            return generateApiGatewayProxyErrorResponse(400, ErrorResponse.ERROR_1017);
        }
    }
}