package uk.gov.di.authentication.frontendapi.entity;

import uk.gov.di.authentication.shared.entity.BaseFrontendRequest;

public class UnblockEmailRequest extends BaseFrontendRequest {

    public UnblockEmailRequest() {}

    public UnblockEmailRequest(String email) {
        this.email = email;
    }

    @Override
    public String toString() {
        return "UnblockEmailRequest{" + "email='" + email + '\'' + '}';
    }
}
