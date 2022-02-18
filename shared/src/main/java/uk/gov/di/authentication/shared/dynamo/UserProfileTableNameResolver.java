package uk.gov.di.authentication.shared.dynamo;

import com.amazonaws.services.dynamodbv2.datamodeling.DynamoDBMapperConfig;

public class UserProfileTableNameResolver extends DynamoDBMapperConfig.DefaultTableNameResolver {

    private static final String USER_PROFILE_TABLE = "user-profile";

    private String environment;

    public UserProfileTableNameResolver(String environment) {
        this.environment = environment;
    }

    @Override
    public String getTableName(Class<?> clazz, DynamoDBMapperConfig config) {
        return environment + "-" + USER_PROFILE_TABLE;
    }
}