package uk.gov.di.accountmanagement.testsupport.helpers;

import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import software.amazon.awssdk.services.sqs.endpoints.internal.Value;

import java.util.HashMap;

public class Injector {
    private final RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> handler;
    private final String endpoint;

    private final String pathDescription;

    private HashMap<Integer, String> pathParams;

    private APIGatewayProxyRequestEvent.ProxyRequestContext requestContext;

    public Injector(RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> handler, String endpoint, String pathDescription){
        this.endpoint = endpoint;
        this.handler = handler;
        this.pathDescription = pathDescription;
        this.pathParams = new HashMap<>();
        this.findPathParams();

    }

    public Injector(RequestHandler<APIGatewayProxyRequestEvent,
            APIGatewayProxyResponseEvent> handler,
                    String endpoint, String pathDescription,
                    APIGatewayProxyRequestEvent.ProxyRequestContext requestContext){
        this.endpoint = endpoint;
        this.handler = handler;
        this.pathDescription = pathDescription;
        this.requestContext = requestContext;
        this.pathParams = new HashMap<>();
        this.findPathParams();
    }

    public RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> getHandler() {
        return handler;
    }

    public String getEndpoint() {
        return endpoint;
    }

    public String getPathDescription() { return pathDescription; }

    public HashMap<Integer, String> getPathParams(){
        return this.pathParams;
    }

    private void findPathParams(){
        String[] arr = pathDescription.split("/");
        for(int i = 0 ; i < arr.length; i++){
            if (arr[i].charAt(0) == '{'){
                pathParams.put(i,arr[i].substring(1, arr.length));
                System.out.println("added path param : " + pathParams.get(i) + " with key: " + i);
            }
        }
    }

}
