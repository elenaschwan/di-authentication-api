package uk.gov.di.authentication.shared.entity;

import uk.gov.di.authentication.shared.helpers.LocaleHelper.SupportedLanguage;
import uk.gov.di.authentication.shared.services.ConfigurationService;

public enum DeliveryReceiptsNotificationType implements TemplateAware {
    VERIFY_EMAIL("VERIFY_EMAIL", "VERIFY_EMAIL_TEMPLATE_ID", SupportedLanguage.EN),
    RESET_PASSWORD("RESET_PASSWORD_EMAIL", "RESET_PASSWORD_TEMPLATE_ID", SupportedLanguage.EN),
    PASSWORD_RESET_CONFIRMATION(
            "PASSWORD_RESET_CONFIRMATION_EMAIL",
            "PASSWORD_RESET_CONFIRMATION_TEMPLATE_ID",
            SupportedLanguage.EN),
    ACCOUNT_CREATED_CONFIRMATION(
            "ACCOUNT_CREATED_CONFIRMATION_EMAIL",
            "ACCOUNT_CREATED_CONFIRMATION_TEMPLATE_ID",
            SupportedLanguage.EN),
    RESET_PASSWORD_WITH_CODE(
            "RESET_PASSWORD_WITH_CODE_EMAIL",
            "RESET_PASSWORD_WITH_CODE_TEMPLATE_ID",
            SupportedLanguage.EN),
    EMAIL_UPDATED("EMAIL_UPDATED_EMAIL", "EMAIL_UPDATED_TEMPLATE_ID", SupportedLanguage.EN),
    DELETE_ACCOUNT("DELETE_ACCOUNT_EMAIL", "DELETE_ACCOUNT_TEMPLATE_ID", SupportedLanguage.EN),
    PHONE_NUMBER_UPDATED(
            "PHONE_NUMBER_UPDATED_EMAIL", "PHONE_NUMBER_UPDATED_TEMPLATE_ID", SupportedLanguage.EN),
    PASSWORD_UPDATED(
            "PASSWORD_UPDATED_EMAIL", "PASSWORD_UPDATED_TEMPLATE_ID", SupportedLanguage.EN),
    VERIFY_PHONE_NUMBER(
            "VERIFY_PHONE_NUMBER_SMS", "VERIFY_PHONE_NUMBER_TEMPLATE_ID", SupportedLanguage.EN),
    MFA_SMS("MFA_SMS", "MFA_SMS_TEMPLATE_ID", SupportedLanguage.EN),
    VERIFY_EMAIL_CY("VERIFY_EMAIL", "VERIFY_EMAIL_TEMPLATE_ID_CY", SupportedLanguage.CY),
    RESET_PASSWORD_CY(
            "RESET_PASSWORD_EMAIL", "RESET_PASSWORD_TEMPLATE_ID_CY", SupportedLanguage.CY),
    PASSWORD_RESET_CONFIRMATION_CY(
            "PASSWORD_RESET_CONFIRMATION_EMAIL",
            "PASSWORD_RESET_CONFIRMATION_TEMPLATE_ID_CY",
            SupportedLanguage.CY),
    ACCOUNT_CREATED_CONFIRMATION_CY(
            "ACCOUNT_CREATED_CONFIRMATION_EMAIL",
            "ACCOUNT_CREATED_CONFIRMATION_TEMPLATE_ID_CY",
            SupportedLanguage.CY),
    RESET_PASSWORD_WITH_CODE_CY(
            "RESET_PASSWORD_WITH_CODE_EMAIL",
            "RESET_PASSWORD_WITH_CODE_TEMPLATE_ID_CY",
            SupportedLanguage.CY),
    EMAIL_UPDATED_CY("EMAIL_UPDATED_EMAIL", "EMAIL_UPDATED_TEMPLATE_ID_CY", SupportedLanguage.CY),
    DELETE_ACCOUNT_CY(
            "DELETE_ACCOUNT_EMAIL", "DELETE_ACCOUNT_TEMPLATE_ID_CY", SupportedLanguage.CY),
    PHONE_NUMBER_UPDATED_CY(
            "PHONE_NUMBER_UPDATED_EMAIL",
            "PHONE_NUMBER_UPDATED_TEMPLATE_ID_CY",
            SupportedLanguage.CY),
    PASSWORD_UPDATED_CY(
            "PASSWORD_UPDATED_EMAIL", "PASSWORD_UPDATED_TEMPLATE_ID_CY", SupportedLanguage.CY),
    VERIFY_PHONE_NUMBER_CY(
            "VERIFY_PHONE_NUMBER_SMS", "VERIFY_PHONE_NUMBER_TEMPLATE_ID_CY", SupportedLanguage.CY),
    MFA_SMS_CY("MFA_SMS", "MFA_SMS_TEMPLATE_ID_CY", SupportedLanguage.CY);

    private final String templateAlias;
    private final String templateName;
    private final SupportedLanguage templateLanguage;

    DeliveryReceiptsNotificationType(
            String templateAlias, String templateName, SupportedLanguage templateLanguage) {
        this.templateAlias = templateAlias;
        this.templateName = templateName;
        this.templateLanguage = templateLanguage;
    }

    public String getTemplateName() {
        return templateName;
    }

    public String getTemplateId() {
        return System.getenv(templateName);
    }

    public SupportedLanguage getTemplateLanguage() {
        return templateLanguage;
    }

    @Override
    public String getTemplateId(
            SupportedLanguage language, ConfigurationService configurationService) {
        return null;
    }

    public String getTemplateAlias() {
        return templateAlias;
    }
}
