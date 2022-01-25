package uk.gov.di.authentication.sharedtest.extensions;

import com.amazonaws.client.builder.AwsClientBuilder;
import com.amazonaws.services.sns.AmazonSNS;
import com.amazonaws.services.sns.AmazonSNSClientBuilder;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import uk.gov.di.authentication.sharedtest.httpstub.HttpStubExtension;

import static java.text.MessageFormat.format;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;

public class SnsTopicExtension extends HttpStubExtension
        implements BeforeAllCallback, BeforeEachCallback {

    protected static final String REGION = System.getenv().getOrDefault("AWS_REGION", "eu-west-2");
    protected static final String LOCALSTACK_ENDPOINT =
            System.getenv().getOrDefault("LOCALSTACK_ENDPOINT", "http://localhost:45678");

    private final String topicNameSuffix;
    private final AmazonSNS snsClient;

    private String topicArn;

    public SnsTopicExtension(String topicNameSuffix) {
        super();
        this.topicNameSuffix = topicNameSuffix;
        this.snsClient =
                AmazonSNSClientBuilder.standard()
                        .withEndpointConfiguration(
                                new AwsClientBuilder.EndpointConfiguration(
                                        LOCALSTACK_ENDPOINT, REGION))
                        .build();
    }

    @Override
    public void beforeAll(ExtensionContext context) {
        startStub();
        var topicName =
                format(
                        "{0}-{1}",
                        context.getTestClass().map(Class::getSimpleName).orElse("unknown"),
                        topicNameSuffix);

        topicArn = createTopic(topicName);
        initSubscriber();
        subscribeToTopic(topicArn);

        // Wait for topic subscription message to be received, so that it doesn't pollute the tests.
        await().atMost(5, SECONDS)
                .untilAsserted(() -> assertThat(getCountOfRequests(), greaterThan(0)));
    }

    @Override
    public void beforeEach(ExtensionContext context) throws Exception {
        clearRequests();
    }

    public String getTopicArn() {
        return topicArn;
    }

    private String createTopic(String topicName) {
        var result = snsClient.createTopic(topicName);
        return result.getTopicArn();
    }

    private void subscribeToTopic(String topicArn) {
        String url = format("http://subscriber.internal:{0,number,#}/subscriber", getHttpPort());

        snsClient.subscribe(topicArn, "http", url);
    }

    private void initSubscriber() {
        register("/subscriber", 200);
    }
}
