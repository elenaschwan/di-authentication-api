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
        endpointList.forEach((injector) -> server.createContext(injector.getEndpoint(), new WrapperHandler(injector)));
        server.setExecutor(null); // creates a default executor
        server.start();
    }
}

class WrapperHandler implements HttpHandler {
    private final RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> handler;
    private final Map<Integer, String> pathParamsFromInjector;

    public WrapperHandler(Injector injector){
        this.handler = injector.getHandler();
        this.pathParamsFromInjector = injector.getPathParams();

    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {

        try {
            APIGatewayProxyRequestEvent request = translateRequest(exchange);
            System.out.println("ATTEMPTING TO SET UP HANDLER");

            Context context = mock(Context.class);

            System.out.println("ATTEMPTING TO SEND EVENT TO HANDLER");
            APIGatewayProxyResponseEvent response = this.handler.handleRequest(request, context);

            System.out.println("RESPONSE FROM HANDLER");
            System.out.println(response);

            translateResponse(response, exchange);


        } catch (Exception e) {
            System.out.println("error was caught");
            System.out.println(e.getMessage());
            e.printStackTrace();
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

    private Map<String, String> getPathParameters(String requestURL){
        HashMap<String, String> stringStringHashMap = new HashMap<>();
        String[] pathArr = requestURL.split("/");
        System.out.println("CHECKING IF ANY PATH PARAMS: " + Arrays.toString(pathArr));
        if (pathParamsFromInjector.isEmpty() || pathArr.length <= 1) return stringStringHashMap;
        System.out.println("FOUND PATH PARAMS");
        pathParamsFromInjector.keySet()
                .forEach(key -> stringStringHashMap.put(pathParamsFromInjector.get(key), pathArr[key]));
        return stringStringHashMap;
    }

    private APIGatewayProxyRequestEvent translateRequest(HttpExchange request) throws IOException {

        String requestBody = IOUtils.toString(request.getRequestBody(), StandardCharsets.UTF_8);
        System.out.println("BODY FROM ORIGINAL REQUEST");
        System.out.println(requestBody);

        String requestPath = request.getRequestURI().getPath();

        System.out.println(requestPath);

        Headers requestHeaders = request.getRequestHeaders();

        String requestId = UUID.randomUUID().toString();

        APIGatewayProxyRequestEvent apiGatewayProxyRequestEvent =
                new APIGatewayProxyRequestEvent()
                        .withBody(requestBody)
                        .withHeaders(getHeaderMap(requestHeaders))
                        .withHttpMethod(request.getRequestMethod())
                        .withPathParameters(getPathParameters(requestPath))//need to add path params
                        .withRequestContext(
                                new APIGatewayProxyRequestEvent.ProxyRequestContext()
                                        .withRequestId(requestId));

        apiGatewayProxyRequestEvent
                .getRequestContext()
                .setAuthorizer(Map.of("principalId", requestHeaders.get("publicSubjectID").get(0), "clientId", "tester-client-id"));

        System.out.println("BODY FROM AG FORMED REQUEST");
        System.out.println(apiGatewayProxyRequestEvent.getBody());

        return apiGatewayProxyRequestEvent;
    }

    private void translateResponse(APIGatewayProxyResponseEvent response, HttpExchange exchange) throws IOException{
        Integer statusCode = response.getStatusCode();
        Map<String, String> apiResponseHeaders = response.getHeaders();
        apiResponseHeaders.put("Content-Type", "application/json");
        Headers serverResponseHeaders = exchange.getResponseHeaders();
        apiResponseHeaders.forEach(serverResponseHeaders::set);

        if (response.getBody().isEmpty()) {
            System.out.printf("empty body");
            exchange.sendResponseHeaders(statusCode, 0);
            OutputStream os = exchange.getResponseBody();
            //os.write(response.toString().getBytes());
            os.flush();
            os.close();
        }
        else {
            System.out.printf("getting response body");
            String body = response.getBody();
            exchange.sendResponseHeaders(statusCode, body.length());
            OutputStream os = exchange.getResponseBody();
            os.write(body.getBytes());
            os.flush();
            os.close();
        }
    }
}
