package uk.gov.di.accountmanagement.pacttesting;

import au.com.dius.pact.provider.junit5.HttpTestTarget;
import au.com.dius.pact.provider.junit5.PactVerificationContext;
import au.com.dius.pact.provider.junit5.PactVerificationInvocationContextProvider;
import au.com.dius.pact.provider.junitsupport.Provider;
import au.com.dius.pact.provider.junitsupport.State;
import au.com.dius.pact.provider.junitsupport.loader.PactBroker;
import au.com.dius.pact.provider.junitsupport.loader.PactBrokerAuth;
import com.nimbusds.oauth2.sdk.id.Subject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestTemplate;
import org.junit.jupiter.api.extension.ExtendWith;
import uk.gov.di.accountmanagement.lambda.UpdatePasswordHandler;
import uk.gov.di.accountmanagement.testsupport.helpers.FakeAPI;
import uk.gov.di.accountmanagement.testsupport.helpers.Injector;
import uk.gov.di.authentication.sharedtest.basetest.HandlerIntegrationTest;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.apache.http.HttpRequest;

import static uk.gov.di.authentication.sharedtest.basetest.HandlerIntegrationTest.userStore;

@Provider("Account Management API")
// @PactFolder("./pacts")
@PactBroker(
        host = "localhost",
        port = "8080",
        authentication = @PactBrokerAuth(username = "test", password = "test"))
public class PactIntegrationTest extends HandlerIntegrationTest {

    private static final String TEST_EMAIL = "testEmail@mail.com";

    private static final String CURRENT_PASSWORD = "password-1";

    private static final Subject SUBJECT = new Subject();
    private static final int PORT = 5050;

    @BeforeAll
    static void startServer() throws IOException {
        Injector passwordChangeInjector = new Injector(new UpdatePasswordHandler(TXMA_ENABLED_CONFIGURATION_SERVICE), "/update-password");
        Injector emailChangeInjector = new Injector(new UpdatePasswordHandler(TXMA_ENABLED_CONFIGURATION_SERVICE), "/update-email");
        Injector phoneNumberChangeInjector = new Injector(new UpdatePasswordHandler(TXMA_ENABLED_CONFIGURATION_SERVICE), "/update-phone-number");
        FakeAPI.startServer(new ArrayList<>(Arrays.asList(passwordChangeInjector, emailChangeInjector, phoneNumberChangeInjector)));
    }

    @BeforeEach
    void setup(PactVerificationContext context) {
        context.setTarget(new HttpTestTarget("localhost", PORT));
    }

    @State("API server is healthy") // with existing user
    void setHealthyServer() {

    }

//    @State("API server is not healthy") // with existing user
//    void setNotHealthyServer() {
//        // how can we achieve this?
//    }


    @TestTemplate
    @ExtendWith(PactVerificationInvocationContextProvider.class)
    void testMethod(PactVerificationContext context, HttpRequest request) {
        String publicSubjectID = userStore.signUp(TEST_EMAIL, CURRENT_PASSWORD, SUBJECT);
        System.out.println(publicSubjectID);
        request.addHeader("publicSubjectID", publicSubjectID);
        context.verifyInteraction();
    }
}
