package uk.gov.di.accountmanagement.testsupport.helpers;

import com.amazonaws.services.lambda.runtime.RequestHandler;

import java.io.Serializable;

public class Injector {
    private final RequestHandler<Serializable, Serializable> handler;
    private final String endpoint;

    public Injector(RequestHandler<Serializable, Serializable> handler, String endpoint){
        this.endpoint = endpoint;
        this.handler = handler;
    }

    public RequestHandler<Serializable, Serializable> getHandler() {
        return handler;
    }

    public String getEndpoint() {
        return endpoint;
    }

}
