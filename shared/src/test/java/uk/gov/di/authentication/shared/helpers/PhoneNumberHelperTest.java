package uk.gov.di.authentication.shared.helpers;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class PhoneNumberHelperTest {

    @Test
    public void testShouldFormatPhoneNumbersUsingE164() {
        final String phoneNumber = "07316763843";

        final String result = PhoneNumberHelper.formatPhoneNumber(phoneNumber);

        assertEquals("+447316763843", result);
    }

    @Test
    public void testShouldThrowExceptionIfInvalidPhoneNumber() {
        final String phoneNumber = "Invalid phone number";

        assertThrows(
                RuntimeException.class,
                () -> PhoneNumberHelper.formatPhoneNumber(phoneNumber),
                "Expected to throw exception");
    }



    @Test
    public void testShouldFormatPhoneNumberUKUsingE164NoRegion() {
        final String phoneNumber = "+447316763843";

        final String result = PhoneNumberHelper.formatPhoneNumberWithRegion(phoneNumber, null);

        assertEquals("+447316763843", result);
    }

    @Test
    public void testShouldFormatPhoneNumberUKWithLeadingZeroUsingE164NoRegion() {
        final String phoneNumber = "+4407316763843";

        final String result = PhoneNumberHelper.formatPhoneNumberWithRegion(phoneNumber, null);

        assertEquals("+447316763843", result);
    }


    @Test
    public void testShouldThrowExceptionIfNationalNumberWhenNoRegion() {
        final String phoneNumber = "07316763843";

        assertThrows(
                RuntimeException.class,
                () -> PhoneNumberHelper.formatPhoneNumberWithRegion(phoneNumber, null),
                "Expected to throw exception");
    }

    @Test
    public void testShouldFormatPhoneNumberFRUsingE164NoRegion() {
        final String phoneNumber = "+33645453322";

        final String result = PhoneNumberHelper.formatPhoneNumberWithRegion(phoneNumber, null);

        assertEquals("+33645453322", result);
    }

    @Test
    public void testShouldFormatPhoneNumberFRWithLeadingZeroUsingE164NoRegion() {
        final String phoneNumber = "+330645453322";

        final String result = PhoneNumberHelper.formatPhoneNumberWithRegion(phoneNumber, null);

        assertEquals("+33645453322", result);
    }

    @Test
    public void testShouldThrowExceptionIfNationalNumberFRWhenNoRegion() {
        final String phoneNumber = "0645453322";

        assertThrows(
                RuntimeException.class,
                () -> PhoneNumberHelper.formatPhoneNumberWithRegion(phoneNumber, null),
                "Expected to throw exception");
    }
}
