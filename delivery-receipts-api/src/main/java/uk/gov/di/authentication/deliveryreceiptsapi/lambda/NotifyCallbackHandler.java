package uk.gov.di.authentication.deliveryreceiptsapi.lambda;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.i18n.phonenumbers.NumberParseException;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import uk.gov.di.authentication.deliveryreceiptsapi.entity.NotifyDeliveryReceipt;
import uk.gov.di.authentication.shared.services.CloudwatchMetricsService;
import uk.gov.di.authentication.shared.services.ConfigurationService;

import java.util.Map;
import java.util.Objects;

import static uk.gov.di.authentication.deliveryreceiptsapi.entity.DeliveryMetricStatus.SMS_DELIVERED;
import static uk.gov.di.authentication.deliveryreceiptsapi.entity.DeliveryMetricStatus.SMS_FAILURE;

public class NotifyCallbackHandler
        implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    private final ConfigurationService configurationService;
    private final CloudwatchMetricsService cloudwatchMetricsService;
    private static final Logger LOG = LogManager.getLogger(NotifyCallbackHandler.class);

    public NotifyCallbackHandler(
            CloudwatchMetricsService cloudwatchMetricsService,
            ConfigurationService configurationService) {
        this.cloudwatchMetricsService = cloudwatchMetricsService;
        this.configurationService = configurationService;
    }

    public NotifyCallbackHandler(ConfigurationService configurationService) {
        this.cloudwatchMetricsService = new CloudwatchMetricsService(configurationService);
        this.configurationService = configurationService;
    }

    public NotifyCallbackHandler() {
        this(ConfigurationService.getInstance());
    }

    @Override
    public APIGatewayProxyResponseEvent handleRequest(
            APIGatewayProxyRequestEvent input, Context context) {
        LOG.info("Received request");
        validateBearerToken(input.getHeaders());
        NotifyDeliveryReceipt deliveryReceipt;
        try {
            deliveryReceipt =
                    new ObjectMapper().readValue(input.getBody(), NotifyDeliveryReceipt.class);
            if (deliveryReceipt.getNotification_type().equals("sms")) {
                var countryCode = getCountryCodeFromNumber(deliveryReceipt.getTo());
                var deliveryStatus = getDeliveryStatus(deliveryReceipt.getStatus());
                LOG.info(
                        "SmsDeliveryStatus: {}, NotifyStatus: {}, CountryCode: {}",
                        deliveryStatus,
                        deliveryReceipt.getStatus(),
                        countryCode);
                cloudwatchMetricsService.incrementCounter(
                        deliveryStatus,
                        Map.of(
                                "CountryCode",
                                String.valueOf(countryCode),
                                "Environment",
                                configurationService.getEnvironment(),
                                "NotifyStatus",
                                deliveryReceipt.getStatus()));
                LOG.info("SMS callback request processed");
            }
        } catch (JsonProcessingException e) {
            LOG.error("Unable to parse Notify Delivery Receipt");
            throw new RuntimeException("Unable to parse Notify Delivery Receipt");
        }
        return null;
    }

    private void validateBearerToken(Map<String, String> headers) {
        var notifyCallbackBearerToken = configurationService.getNotifyCallbackBearerToken();
        if (Objects.isNull(headers.get("Authorization"))
                || !headers.get("Authorization").startsWith("Bearer ")) {
            LOG.error("No bearer token in request");
            throw new RuntimeException("No bearer token in request");
        }
        var token = headers.get("Authorization").substring(7);
        if (!token.equals(notifyCallbackBearerToken)) {
            LOG.error("Invalid bearer token in request");
            throw new RuntimeException("Invalid bearer token in request");
        }
    }

    private int getCountryCodeFromNumber(String number) {
        String defaultRegion = null;
        if (!number.startsWith("+")) {
            defaultRegion = "GB";
        }
        var phoneUtil = PhoneNumberUtil.getInstance();
        try {
            return phoneUtil.parse(number, defaultRegion).getCountryCode();
        } catch (NumberParseException e) {
            LOG.error("Unable to parse number");
            throw new RuntimeException("Unable to parse number");
        }
    }

    private String getDeliveryStatus(String notifyStatus) {
        var deliveryStatus = SMS_FAILURE.toString();
        if (notifyStatus.equals("delivered")) {
            deliveryStatus = SMS_DELIVERED.toString();
        }
        return deliveryStatus;
    }
}
