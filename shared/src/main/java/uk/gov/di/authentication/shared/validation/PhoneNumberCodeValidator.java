package uk.gov.di.authentication.shared.validation;

import uk.gov.di.authentication.shared.entity.ErrorResponse;
import uk.gov.di.authentication.shared.entity.MFAMethodType;
import uk.gov.di.authentication.shared.entity.NotificationType;
import uk.gov.di.authentication.shared.services.CodeStorageService;
import uk.gov.di.authentication.shared.services.ConfigurationService;
import uk.gov.di.authentication.shared.state.UserContext;

import java.util.Optional;

public class PhoneNumberCodeValidator extends MfaCodeValidator {

    private final ConfigurationService configurationService;
    private final UserContext userContext;
    private final boolean isRegistration;
    private final boolean isTestClient;

    PhoneNumberCodeValidator(
            CodeStorageService codeStorageService,
            UserContext userContext,
            ConfigurationService configurationService,
            boolean isRegistration,
            boolean isTestClient) {
        super(
                userContext.getSession().getEmailAddress(),
                codeStorageService,
                configurationService.getCodeMaxRetries());
        this.userContext = userContext;
        this.configurationService = configurationService;
        this.isRegistration = isRegistration;
        this.isTestClient = isTestClient;
    }

    @Override
    public Optional<ErrorResponse> validateCode(String code) {
        var notificationType =
                isRegistration ? NotificationType.VERIFY_PHONE_NUMBER : NotificationType.MFA_SMS;

        if (isCodeBlockedForSession()) {
            LOG.info("Code blocked for session");
            if (notificationType == NotificationType.MFA_SMS) {
                return Optional.of(ErrorResponse.ERROR_1027);
            }

            if (notificationType == NotificationType.VERIFY_PHONE_NUMBER) {
                return Optional.of(ErrorResponse.ERROR_1034);
            }
        }

        var storedCode =
                isTestClient
                        ? configurationService.getTestClientVerifyPhoneNumberOTP()
                        : codeStorageService.getOtpCode(emailAddress, notificationType);

        if (storedCode.filter(code::equals).isPresent()) {
            LOG.info("Phone OTP valid. Resetting code request count");
            resetCodeIncorrectEntryCount(MFAMethodType.SMS);
            return Optional.empty();
        }

        incrementRetryCount(MFAMethodType.SMS);

        if (hasExceededRetryLimit(MFAMethodType.SMS)) {
            LOG.info("Exceeded code retry limit");
            if (notificationType == NotificationType.MFA_SMS) {
                return Optional.of(ErrorResponse.ERROR_1027);
            }

            if (notificationType == NotificationType.VERIFY_PHONE_NUMBER) {
                return Optional.of(ErrorResponse.ERROR_1034);
            }
        }

        if (notificationType == NotificationType.MFA_SMS) {
            return Optional.of(ErrorResponse.ERROR_1035);
        }

        if (notificationType == NotificationType.VERIFY_PHONE_NUMBER) {
            return Optional.of(ErrorResponse.ERROR_1037);
        }

        LOG.error(
                "An unsupported notification type was passed to the validator: {}",
                notificationType);
        return Optional.of(ErrorResponse.ERROR_1002);
    }
}
