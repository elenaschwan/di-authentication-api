package uk.gov.di.authentication.sharedtest.extensions;

import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.CreateTableRequest;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;
import uk.gov.di.authentication.app.entity.DocAppCredential;
import uk.gov.di.authentication.app.services.DynamoDocAppService;
import uk.gov.di.authentication.shared.services.ConfigurationService;
import uk.gov.di.authentication.sharedtest.basetest.DynamoTestConfiguration;

import java.util.List;
import java.util.Optional;

public class DocumentAppCredentialStoreExtension extends DynamoExtension
        implements AfterEachCallback {

    public static final String CREDENTIAL_REGISTRY_TABLE = "local-doc-app-credential";
    public static final String SUBJECT_ID_FIELD = "SubjectID";

    private DynamoDocAppService dynamoDocAppService;
    private final ConfigurationService configuration;

    public DocumentAppCredentialStoreExtension(long ttl) {
        createInstance();
        this.configuration =
                new DynamoTestConfiguration(REGION, ENVIRONMENT, DYNAMO_ENDPOINT) {
                    @Override
                    public long getAccessTokenExpiry() {
                        return ttl;
                    }
                };
        dynamoDocAppService = new DynamoDocAppService(configuration);
    }

    @Override
    public void beforeAll(ExtensionContext context) throws Exception {
        super.beforeAll(context);

        dynamoDocAppService = new DynamoDocAppService(configuration);
    }

    @Override
    public void afterEach(ExtensionContext context) throws Exception {
        clearDynamoTable(dynamoDB, CREDENTIAL_REGISTRY_TABLE, SUBJECT_ID_FIELD);
    }

    @Override
    protected void createTables() {
        if (!tableExists(CREDENTIAL_REGISTRY_TABLE)) {
            createCredentialRegistryTable(CREDENTIAL_REGISTRY_TABLE);
        }
    }

    private void createCredentialRegistryTable(String tableName) {
        CreateTableRequest request =
                CreateTableRequest.builder()
                        .tableName(tableName)
                        .keySchema(
                                KeySchemaElement.builder()
                                        .keyType(KeyType.HASH)
                                        .attributeName(SUBJECT_ID_FIELD)
                                        .build())
                        .billingMode(BillingMode.PAY_PER_REQUEST)
                        .attributeDefinitions(
                                AttributeDefinition.builder()
                                        .attributeName(SUBJECT_ID_FIELD)
                                        .attributeType(ScalarAttributeType.S)
                                        .build())
                        .build();
        dynamoDB.createTable(request);
    }

    public void addCredential(String subjectId, List<String> credentials) {
        dynamoDocAppService.addDocAppCredential(subjectId, credentials);
    }

    public Optional<DocAppCredential> getCredential(String subjectId) {
        return dynamoDocAppService.getDocAppCredential(subjectId);
    }
}
