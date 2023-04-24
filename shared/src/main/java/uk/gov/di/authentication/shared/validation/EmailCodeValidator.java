package uk.gov.di.authentication.shared.validation;

import uk.gov.di.authentication.shared.entity.ErrorResponse;
import uk.gov.di.authentication.shared.entity.MFAMethodType;
import uk.gov.di.authentication.shared.entity.NotificationType;
import uk.gov.di.authentication.shared.services.CodeStorageService;
import uk.gov.di.authentication.shared.services.ConfigurationService;

import java.util.Optional;

public class EmailCodeValidator extends MfaCodeValidator {
    private final NotificationType notificationType;
    private final boolean isTestClient;
    private final Optional<String> testClientOtp;

    public EmailCodeValidator(
            String emailAddress,
            CodeStorageService codeStorageService,
            ConfigurationService configurationService,
            NotificationType notificationType,
            boolean isTestClient) {
        super(emailAddress, codeStorageService, configurationService.getCodeMaxRetries());
        this.notificationType = notificationType;
        this.isTestClient = isTestClient;
        this.testClientOtp = configurationService.getTestClientVerifyEmailOTP();
    }

    @Override
    public Optional<ErrorResponse> validateCode(String inputCode) {
        if (isCodeBlockedForSession()) {
            LOG.info("Code blocked for session (email)");
            return Optional.of(ErrorResponse.ERROR_1033);
        }

        Optional<String> correctCode = getCorrectEmailOtp();

        if (correctCode.filter(inputCode::equals).isPresent()) {
            LOG.info("Email OTP valid. Resetting code request count");
            resetCodeIncorrectEntryCount(MFAMethodType.EMAIL);
            return Optional.empty();
        }
        // todo: double check this - increment before retry limit or after; I think before?
        incrementRetryCount(MFAMethodType.EMAIL);

        if (hasExceededRetryLimit(MFAMethodType.EMAIL)) {
            LOG.info("Exceeded code retry limit");
            if (notificationType == NotificationType.VERIFY_EMAIL) {
                return Optional.of(ErrorResponse.ERROR_1033);
            }

            if (notificationType == NotificationType.RESET_PASSWORD_WITH_CODE) {
                return Optional.of(ErrorResponse.ERROR_1039);
            }
        }

        if (notificationType == NotificationType.VERIFY_EMAIL) {
            return Optional.of(ErrorResponse.ERROR_1036);
        }

        if (notificationType == NotificationType.RESET_PASSWORD_WITH_CODE) {
            return Optional.of(ErrorResponse.ERROR_1021);
        }

        LOG.error(
                "An unsupported notification type was passed to the validator: {}",
                notificationType);
        return Optional.of(ErrorResponse.ERROR_1002);
    }

    public Optional<String> getCorrectEmailOtp() {
        return isTestClient
                ? testClientOtp
                : codeStorageService.getOtpCode(emailAddress, notificationType);
    }
}
