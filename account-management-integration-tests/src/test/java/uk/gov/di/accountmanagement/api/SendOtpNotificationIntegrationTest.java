package uk.gov.di.accountmanagement.api;

import com.google.i18n.phonenumbers.PhoneNumberUtil;
import org.apache.http.HttpStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import uk.gov.di.accountmanagement.entity.NotifyRequest;
import uk.gov.di.accountmanagement.entity.SendNotificationRequest;
import uk.gov.di.accountmanagement.lambda.SendOtpNotificationHandler;
import uk.gov.di.authentication.sharedtest.basetest.ApiGatewayHandlerIntegrationTest;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static com.google.i18n.phonenumbers.PhoneNumberUtil.PhoneNumberType.MOBILE;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static uk.gov.di.accountmanagement.domain.AccountManagementAuditableEvent.SEND_OTP;
import static uk.gov.di.accountmanagement.entity.NotificationType.VERIFY_EMAIL;
import static uk.gov.di.accountmanagement.entity.NotificationType.VERIFY_PHONE_NUMBER;
import static uk.gov.di.authentication.sharedtest.helper.AuditAssertionsHelper.assertEventTypesReceived;
import static uk.gov.di.authentication.sharedtest.matchers.APIGatewayProxyResponseEventMatcher.hasStatus;

public class SendOtpNotificationIntegrationTest extends ApiGatewayHandlerIntegrationTest {

    @BeforeEach
    void setup() {
        handler = new SendOtpNotificationHandler(TEST_CONFIGURATION_SERVICE);
    }

    @Test
    public void shouldSendNotificationAndReturn204ForVerifyEmailRequest() {
        String email = "joe.bloggs+3@digital.cabinet-office.gov.uk";
        String phoneNumber =
                PhoneNumberUtil.getInstance().getExampleNumberForType("GB", MOBILE).toString();

        var response =
                makeRequest(
                        Optional.of(new SendNotificationRequest(email, VERIFY_EMAIL, phoneNumber)),
                        Collections.emptyMap(),
                        Collections.emptyMap());

        assertThat(response, hasStatus(HttpStatus.SC_NO_CONTENT));

        List<NotifyRequest> requests = notificationsQueue.getMessages(NotifyRequest.class);

        assertThat(requests, hasSize(1));
        assertThat(requests.get(0).getDestination(), equalTo(email));
        assertThat(requests.get(0).getNotificationType(), equalTo(VERIFY_EMAIL));

        assertEventTypesReceived(auditTopic, List.of(SEND_OTP));
    }

    @Test
    public void shouldSendNotificationAndReturn204ForVerifyPhoneNumberRequest() {
        String email = "joe.bloggs+3@digital.cabinet-office.gov.uk";
        String phoneNumber =
                Long.toString(
                        PhoneNumberUtil.getInstance()
                                .getExampleNumberForType("GB", MOBILE)
                                .getNationalNumber());

        var response =
                makeRequest(
                        Optional.of(
                                new SendNotificationRequest(
                                        email, VERIFY_PHONE_NUMBER, phoneNumber)),
                        Collections.emptyMap(),
                        Collections.emptyMap());

        assertThat(response, hasStatus(HttpStatus.SC_NO_CONTENT));

        List<NotifyRequest> requests = notificationsQueue.getMessages(NotifyRequest.class);

        assertThat(requests, hasSize(1));
        assertThat(requests.get(0).getDestination(), equalTo(phoneNumber));
        assertThat(requests.get(0).getNotificationType(), equalTo(VERIFY_PHONE_NUMBER));

        assertEventTypesReceived(auditTopic, List.of(SEND_OTP));
    }
}
