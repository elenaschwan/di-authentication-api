package uk.gov.di.authentication.shared.validation;

import uk.gov.di.authentication.shared.entity.MFAMethodType;
import uk.gov.di.authentication.shared.entity.NotificationType;
import uk.gov.di.authentication.shared.services.AuthenticationService;
import uk.gov.di.authentication.shared.services.CodeStorageService;
import uk.gov.di.authentication.shared.services.ConfigurationService;
import uk.gov.di.authentication.shared.state.UserContext;

import java.util.Optional;

public class MfaCodeValidatorFactory {

    protected final ConfigurationService configurationService;
    private final CodeStorageService codeStorageService;
    private final AuthenticationService authenticationService;

    public MfaCodeValidatorFactory(
            ConfigurationService configurationService,
            CodeStorageService codeStorageService,
            AuthenticationService authenticationService) {
        this.configurationService = configurationService;
        this.codeStorageService = codeStorageService;
        this.authenticationService = authenticationService;
    }

    public Optional<MfaCodeValidator> getMfaCodeValidator(
            MFAMethodType mfaMethodType,
            boolean isRegistration,
            boolean isTestClient,
            UserContext userContext) {

        switch (mfaMethodType) {
            case AUTH_APP:
                int codeMaxRetries =
                        isRegistration
                                ? configurationService.getCodeMaxRetriesRegistration()
                                : configurationService.getCodeMaxRetries();
                return Optional.of(
                        new AuthAppCodeValidator(
                                userContext.getSession().getEmailAddress(),
                                codeStorageService,
                                configurationService,
                                authenticationService,
                                codeMaxRetries));
            case SMS:
                return Optional.of(
                        new PhoneNumberCodeValidator(
                                codeStorageService,
                                userContext,
                                configurationService,
                                isRegistration,
                                isTestClient));
            case EMAIL:
                return Optional.of(
                        new EmailCodeValidator(
                                userContext.getSession().getEmailAddress(),
                                codeStorageService,
                                configurationService,
                                NotificationType.VERIFY_EMAIL,
                                isTestClient));
            default:
                return Optional.empty();
        }
    }
}
