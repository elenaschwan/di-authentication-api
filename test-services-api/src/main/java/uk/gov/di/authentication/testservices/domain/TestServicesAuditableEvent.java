package uk.gov.di.authentication.testservices.domain;

import uk.gov.di.authentication.shared.domain.AuditableEvent;

public enum TestServicesAuditableEvent implements AuditableEvent {
    SYNTHETICS_USER_DELETED,
    SYNTHETICS_USER_NOT_FOUND_FOR_DELETION;

    public AuditableEvent parseFromName(String name) {
        return valueOf(name);
    }
}
