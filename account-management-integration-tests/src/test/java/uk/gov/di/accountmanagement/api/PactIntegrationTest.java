package uk.gov.di.accountmanagement.api;

import au.com.dius.pact.provider.junit5.HttpTestTarget;
import au.com.dius.pact.provider.junit5.PactVerificationContext;
import au.com.dius.pact.provider.junit5.PactVerificationInvocationContextProvider;
import au.com.dius.pact.provider.junitsupport.Provider;
import au.com.dius.pact.provider.junitsupport.State;
import au.com.dius.pact.provider.junitsupport.loader.PactBroker;
import au.com.dius.pact.provider.junitsupport.loader.PactBrokerAuth;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.nimbusds.oauth2.sdk.id.Subject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestTemplate;
import org.junit.jupiter.api.extension.ExtendWith;
import uk.gov.di.accountmanagement.testsupport.helpers.FakeAPI;
import uk.gov.di.authentication.sharedtest.basetest.HandlerIntegrationTest;

import java.io.IOException;

import static com.google.i18n.phonenumbers.PhoneNumberUtil.PhoneNumberType.MOBILE;
import static uk.gov.di.authentication.sharedtest.basetest.HandlerIntegrationTest.userStore;

@Provider("Account Management API")
//@PactFolder("./pacts")
@PactBroker(
        host = "localhost",
        port = "8080",
        authentication = @PactBrokerAuth(username = "test", password = "test")
)
public class PactIntegrationTest extends HandlerIntegrationTest {

    private static final String TEST_EMAIL = "joe.bloggs+3@digital.cabinet-office.gov.uk";
    private static final Subject SUBJECT = new Subject();
    private static final int PORT = 5050;

    @BeforeAll
    static void startServer() throws IOException {
        String publicSubjectID = userStore.signUp(TEST_EMAIL, "password-1", SUBJECT);
        FakeAPI.startServer();
    }

    @BeforeEach
    void setup(PactVerificationContext context)  {
        context.setTarget(new HttpTestTarget("localhost", PORT));
    }

    @State("API server is healthy") //with existing user
    void setHealthyServer(){

    }

    @State("API server is healthy, email already assigned to another user")
    void setHealthyServerAndEmailAlreadyInUse(){

    }


    @State("API server is not healthy")
    void setBrokenApi(){

    }

    @TestTemplate
    @ExtendWith(PactVerificationInvocationContextProvider.class)
    void testMethod(PactVerificationContext context) {
        context.verifyInteraction();

    }
}


