package uk.gov.di.authentication.frontendapi.validation;

import uk.gov.di.authentication.entity.CodeRequest;
import uk.gov.di.authentication.shared.entity.JourneyType;
import uk.gov.di.authentication.shared.entity.MFAMethodType;
import uk.gov.di.authentication.shared.services.AuditService;
import uk.gov.di.authentication.shared.services.AuthenticationService;
import uk.gov.di.authentication.shared.services.CodeStorageService;
import uk.gov.di.authentication.shared.services.ConfigurationService;
import uk.gov.di.authentication.shared.state.UserContext;

import java.util.Optional;

public class MfaCodeProcessorFactory {

    private final ConfigurationService configurationService;
    private final CodeStorageService codeStorageService;
    private final AuthenticationService authenticationService;
    private final AuditService auditService;

    public MfaCodeProcessorFactory(
            ConfigurationService configurationService,
            CodeStorageService codeStorageService,
            AuthenticationService authenticationService,
            AuditService auditService) {
        this.configurationService = configurationService;
        this.codeStorageService = codeStorageService;
        this.authenticationService = authenticationService;
        this.auditService = auditService;
    }

    public Optional<MfaCodeProcessor> getMfaCodeProcessor(
            MFAMethodType mfaMethodType, CodeRequest codeRequest, UserContext userContext) {

        switch (mfaMethodType) {
            case AUTH_APP:
                int codeMaxRetries =
                        codeRequest.getJourneyType().equals(JourneyType.REGISTRATION)
                                ? configurationService.getCodeMaxRetriesRegistration()
                                : configurationService.getCodeMaxRetries();
                return Optional.of(
                        new AuthAppCodeProcessor(
                                userContext,
                                codeStorageService,
                                configurationService,
                                authenticationService,
                                codeMaxRetries,
                                codeRequest,
                                auditService));
            case SMS:
                return Optional.of(
                        new PhoneNumberCodeProcessor(
                                codeStorageService,
                                userContext,
                                configurationService,
                                codeRequest,
                                authenticationService,
                                auditService));
            default:
                return Optional.empty();
        }
    }
}
