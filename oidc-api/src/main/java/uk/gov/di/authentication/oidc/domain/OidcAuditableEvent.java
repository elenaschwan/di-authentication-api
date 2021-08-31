package uk.gov.di.authentication.oidc.domain;

import uk.gov.di.authentication.shared.domain.AuditableEvent;

public enum OidcAuditableEvent implements AuditableEvent {
    AUTHORISATION_REQUEST_ERROR,
    AUTHORISATION_REQUEST_RECEIVED
}