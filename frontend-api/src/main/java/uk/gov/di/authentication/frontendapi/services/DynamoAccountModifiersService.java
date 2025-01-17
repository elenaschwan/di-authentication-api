package uk.gov.di.authentication.frontendapi.services;

import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import uk.gov.di.authentication.frontendapi.entity.AccountModifiers;
import uk.gov.di.authentication.frontendapi.entity.AccountRecovery;
import uk.gov.di.authentication.shared.helpers.NowHelper;
import uk.gov.di.authentication.shared.services.ConfigurationService;

import java.util.Objects;
import java.util.Optional;

import static uk.gov.di.authentication.shared.dynamodb.DynamoClientHelper.createDynamoEnhancedClient;

public class DynamoAccountModifiersService {

    private static final String ACCOUNT_MODIFIERS_TABLE_NAME = "account-modifiers";
    private final DynamoDbTable<AccountModifiers> dynamoAccountModifiersTable;

    public DynamoAccountModifiersService(ConfigurationService configurationService) {
        var tableName = configurationService.getEnvironment() + "-" + ACCOUNT_MODIFIERS_TABLE_NAME;
        var dynamoDbEnhancedClient = createDynamoEnhancedClient(configurationService);
        dynamoAccountModifiersTable =
                dynamoDbEnhancedClient.table(
                        tableName, TableSchema.fromBean(AccountModifiers.class));
        warmUp();
    }

    public void setAccountRecoveryBlock(
            String internalCommonSubjectId, boolean accountRecoveryBlock) {
        var dateTime = NowHelper.toTimestampString(NowHelper.now());

        var optionalAccountModifiers =
                getAccountModifiers(internalCommonSubjectId)
                        .map(t -> t.withUpdated(dateTime))
                        .map(
                                t ->
                                        Objects.nonNull(t.getAccountRecovery())
                                                ? t.withAccountRecovery(
                                                        t.getAccountRecovery()
                                                                .withBlocked(accountRecoveryBlock)
                                                                .withUpdated(dateTime))
                                                : t.withAccountRecovery(
                                                        new AccountRecovery()
                                                                .withCreated(dateTime)
                                                                .withUpdated(dateTime)
                                                                .withBlocked(
                                                                        accountRecoveryBlock)));

        var accountModifiers =
                optionalAccountModifiers.orElse(
                        new AccountModifiers()
                                .withInternalCommonSubjectIdentifier(internalCommonSubjectId)
                                .withCreated(dateTime)
                                .withUpdated(dateTime)
                                .withAccountRecovery(
                                        new AccountRecovery()
                                                .withBlocked(accountRecoveryBlock)
                                                .withUpdated(dateTime)
                                                .withCreated(dateTime)));

        dynamoAccountModifiersTable.updateItem(accountModifiers);
    }

    public Optional<AccountModifiers> getAccountModifiers(String internalCommonSubjectId) {
        return Optional.ofNullable(
                dynamoAccountModifiersTable.getItem(
                        Key.builder().partitionValue(internalCommonSubjectId).build()));
    }

    public boolean isAccountRecoveryBlockPresent(String internalCommonSubjectId) {
        return getAccountModifiers(internalCommonSubjectId)
                .map(AccountModifiers::getAccountRecovery)
                .filter(AccountRecovery::isBlocked)
                .isPresent();
    }

    public void removeAccountRecoveryBlockIfPresent(String internalCommonSubjectId) {
        if (isAccountRecoveryBlockPresent(internalCommonSubjectId)) {
            setAccountRecoveryBlock(internalCommonSubjectId, false);
        }
    }

    private void warmUp() {
        dynamoAccountModifiersTable.describeTable();
    }
}
