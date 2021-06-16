package uk.gov.di.lambdas;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import software.amazon.awssdk.core.exception.SdkClientException;
import uk.gov.di.entity.NotificationType;
import uk.gov.di.entity.NotifyRequest;
import uk.gov.di.entity.UserWithEmailRequest;
import uk.gov.di.services.AwsSqsClient;
import uk.gov.di.services.ConfigurationService;
import uk.gov.di.services.ValidationService;
import uk.gov.di.validation.EmailValidation;

import java.util.Set;

import static uk.gov.di.helpers.ApiGatewayResponseHelper.generateApiGatewayProxyResponse;

public class SendUserEmailHandler
        implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    private final ConfigurationService configurationService;
    private final ValidationService validationService;
    private final AwsSqsClient sqsClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public SendUserEmailHandler(
            ConfigurationService configurationService,
            ValidationService validationService,
            AwsSqsClient sqsClient) {
        this.configurationService = configurationService;
        this.validationService = validationService;
        this.sqsClient = sqsClient;
    }

    public SendUserEmailHandler() {
        this.configurationService = new ConfigurationService();
        this.sqsClient =
                new AwsSqsClient(
                        configurationService.getAwsRegion(),
                        configurationService.getEmailQueueUri(),
                        configurationService.getSqsEndpointUri());
        this.validationService = new ValidationService();
    }

    @Override
    public APIGatewayProxyResponseEvent handleRequest(
            APIGatewayProxyRequestEvent input, Context context) {
        LambdaLogger logger = context.getLogger();

        try {
            UserWithEmailRequest userRequest =
                    objectMapper.readValue(input.getBody(), UserWithEmailRequest.class);
            Set<EmailValidation> emailErrors =
                    validationService.validateEmailAddress(userRequest.getEmail());
            if (!emailErrors.isEmpty()) {
                return generateApiGatewayProxyResponse(400, emailErrors.toString());
            }

            NotifyRequest notifyRequest =
                    new NotifyRequest(userRequest.getEmail(), NotificationType.VERIFY_EMAIL);
            sqsClient.send(serialiseRequest(notifyRequest));
            return generateApiGatewayProxyResponse(200, "");
        } catch (SdkClientException ex) {
            logger.log("Error sending message to queue: " + ex.getMessage());
            return generateApiGatewayProxyResponse(500, "Error sending message to queue");
        } catch (JsonProcessingException e) {
            logger.log("Error parsing request: " + e.getMessage());
            return generateApiGatewayProxyResponse(400, "Request is missing parameters");
        }
    }

    private String serialiseRequest(Object request) throws JsonProcessingException {
        return objectMapper.writeValueAsString(request);
    }
}
