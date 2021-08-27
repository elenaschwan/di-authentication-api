package uk.gov.di.authentication.shared.services;

import com.nimbusds.oauth2.sdk.id.Subject;
import uk.gov.di.authentication.shared.entity.ClientConsent;
import uk.gov.di.authentication.shared.entity.UserProfile;

import java.util.List;
import java.util.Optional;

public interface AuthenticationService {
    boolean userExists(String email);

    void signUp(String email, String password, Subject subject);

    boolean login(String email, String password);

    boolean isEmailVerificationRequired();

    Subject getSubjectFromEmail(String email);

    void updatePhoneNumber(String email, String profileInformation);

    void updateConsent(String email, ClientConsent clientConsent);

    Optional<List<ClientConsent>> getUserConsents(String email);

    void updatePhoneNumberVerifiedStatus(String email, boolean verifiedStatus);

    Optional<String> getPhoneNumber(String email);

    UserProfile getUserProfileFromSubject(String subject);

    void updateTermsAndConditions(String email, String version);
}