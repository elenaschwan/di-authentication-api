package uk.gov.di.authentication.shared.validation;

import uk.gov.di.authentication.shared.entity.JourneyType;
import uk.gov.di.authentication.shared.entity.MFAMethodType;
import uk.gov.di.authentication.shared.services.AuthenticationService;
import uk.gov.di.authentication.shared.services.CodeStorageService;
import uk.gov.di.authentication.shared.services.ConfigurationService;
import uk.gov.di.authentication.shared.state.UserContext;

import java.util.Optional;

public class MfaCodeValidatorFactory {

    private final ConfigurationService configurationService;
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
            MFAMethodType mfaMethodType, JourneyType journeyType, UserContext userContext) {

        switch (mfaMethodType) {
            case AUTH_APP:
                int codeMaxRetries =
                        journeyType.equals(JourneyType.REGISTRATION)
                                ? configurationService.getCodeMaxRetriesRegistration()
                                : configurationService.getCodeMaxRetries();
                return Optional.of(
                        new AuthAppCodeValidator(
                                userContext.getSession().getEmailAddress(),
                                codeStorageService,
                                configurationService,
                                authenticationService,
                                codeMaxRetries,
                                journeyType));
            case SMS:
                return Optional.of(
                        new PhoneNumberCodeValidator(
                                codeStorageService,
                                userContext,
                                configurationService,
                                journeyType));
            default:
                return Optional.empty();
        }
    }
}
