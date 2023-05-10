package uk.gov.di.authentication.shared.entity;

public enum JourneyType {
    ACCOUNT_RECOVERY("ACCOUNT_RECOVERY"),
    REGISTRATION("REGISTRATION"),
    SIGN_IN("SIGN_IN");

    private String value;

    JourneyType(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }
}