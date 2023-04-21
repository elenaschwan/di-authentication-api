package uk.gov.di.accountmanagement.testsupport.helpers;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import org.apache.commons.io.IOUtils;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;

import static org.mockito.Mockito.mock;

public class FakeAPI {

    public static void startServer(ArrayList<Injector> endpointList) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(5050), 0);
        endpointList.forEach((item) -> server.createContext(item.getEndpoint(), new WrapperHandler(item.getHandler())));
        server.setExecutor(null); // creates a default executor
        server.start();
    }
}

class WrapperHandler implements HttpHandler {
    private final RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> handler;
    public WrapperHandler(RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent>  h){
        this.handler = h;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {

        try {
            System.out.println("request method:" + exchange.getRequestMethod());
            String requestBody =
                    IOUtils.toString(exchange.getRequestBody(), StandardCharsets.UTF_8);
            System.out.println("BODY FROM ORIGINAL REQUEST");
            System.out.println(requestBody);

            Headers requestHeaders = exchange.getRequestHeaders();
            System.out.println(requestHeaders.containsKey("publicSubjectID"));

            String requestId = UUID.randomUUID().toString();

            APIGatewayProxyRequestEvent apiGatewayProxyRequestEvent =
                    new APIGatewayProxyRequestEvent()
                            .withBody(requestBody)
                            .withHeaders(getHeaderMap(requestHeaders))
                            .withHttpMethod(exchange.getRequestMethod())
                            .withRequestContext(
                                    new APIGatewayProxyRequestEvent.ProxyRequestContext()
                                            .withRequestId(requestId));

            apiGatewayProxyRequestEvent
                    .getRequestContext()
                    .setAuthorizer(Map.of("principalId", requestHeaders.get("publicSubjectID").get(0)));

            System.out.println("BODY FROM AG FORMED REQUEST");
            System.out.println(apiGatewayProxyRequestEvent.getBody());

            System.out.println("ATTEMPTING TO SET UP EMAIL-HANDLER");
            RequestHandler emailHandler = this.handler;

            Context context = mock(Context.class);

            System.out.println("ATTEMPTING TO SEND EVENT TO HANDLER");
            APIGatewayProxyResponseEvent response = (APIGatewayProxyResponseEvent) emailHandler.handleRequest(apiGatewayProxyRequestEvent, context);

            System.out.println("RESPONSE FROM HANDLER");
            System.out.println(response.toString());
            // need to translate response back to something that can be transmitted.
            System.out.println(response.toString().length());
            exchange.sendResponseHeaders(response.getStatusCode(), response.toString().length());
            OutputStream os = exchange.getResponseBody();
            os.write(response.toString().getBytes());
            os.flush();
            os.close();

        } catch (Exception e) {
            System.out.println("error was caught");
            System.out.println(e.getMessage());
            String err = "some error happened";
            exchange.sendResponseHeaders(500, err.length());
            OutputStream os = exchange.getResponseBody();
            os.write(err.getBytes());
            os.flush();
            os.close();
        }
    }

    private Map<String, String> getHeaderMap(Headers h){
        Map<String, String> tempMap = new HashMap<>();
        h.keySet().forEach(key -> tempMap.put(key, String.join(", ", h.get(key))));
        return tempMap;
    }
}
