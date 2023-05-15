package uk.gov.di.authentication.shared.helpers;

import com.nimbusds.oauth2.sdk.id.Subject;
import com.nimbusds.openid.connect.sdk.SubjectType;
import uk.gov.di.authentication.shared.entity.ClientRegistry;
import uk.gov.di.authentication.shared.entity.UserProfile;

import java.io.File;
import java.io.FileNotFoundException;
import java.nio.ByteBuffer;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.Scanner;

import static uk.gov.di.authentication.shared.helpers.ClientSubjectHelper.getSubject;

public class ClientSubjectExtractor {

    public static void main(String[] args) {

        String inputFile = args[0];
        String redirectUrl = args[1];

        processSubjectIdFile(inputFile, redirectUrl);
    }

    private static void processSubjectIdFile(String inputFile, String redirectUrl) {
        try (Scanner scanner = new Scanner(new File(inputFile))) {
            while (scanner.hasNextLine()) {
                processSubjectLine(scanner.nextLine(), redirectUrl)
                        .ifPresent(
                                s -> {
                                    System.out.println(s.getValue());
                                });
            }
        } catch (FileNotFoundException e) {
            throw new RuntimeException(e);
        }
    }

    private static Optional<Subject> processSubjectLine(String line, String redirectUrl) {
        try (Scanner rowScanner = new Scanner(line)) {
            rowScanner.useDelimiter(",");
            if (rowScanner.hasNext()) {
                String subjectId = rowScanner.next();
                if (rowScanner.hasNext()) {
                    String saltBinary = rowScanner.next();
                    return Optional.of(getSubject(subjectId, saltBinary, redirectUrl));
                }
            }
        }
        return Optional.empty();
    }

    private static Subject getSubject(
            String argSubjectId, String argSaltBinary, String redirectUrl) {

        UserProfile userProfile =
                new UserProfile()
                        .withSubjectID(argSubjectId)
                        .withSalt(ByteBuffer.wrap(Base64.getDecoder().decode(argSaltBinary)));
        ClientRegistry clientRegistry =
                new ClientRegistry()
                        .withSubjectType(SubjectType.PAIRWISE.toString())
                        .withRedirectUrls(List.of(redirectUrl));

        Subject subject = ClientSubjectHelper.getSubject(userProfile, clientRegistry, null, null);
        return subject;
    }
}
