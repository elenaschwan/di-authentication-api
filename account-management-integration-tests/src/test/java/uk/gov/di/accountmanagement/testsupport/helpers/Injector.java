package uk.gov.di.accountmanagement.testsupport.helpers;

import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import uk.gov.di.accountmanagement.lambda.UpdatePasswordHandler;

public class Injector {
    private final RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> handler;
    private final String endpoint;

    public Injector(UpdatePasswordHandler handler, String endpoint){
        this.endpoint = endpoint;
        this.handler = handler;
    }

    public RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> getHandler() {
        return handler;
    }

    public String getEndpoint() {
        return endpoint;
    }

}
