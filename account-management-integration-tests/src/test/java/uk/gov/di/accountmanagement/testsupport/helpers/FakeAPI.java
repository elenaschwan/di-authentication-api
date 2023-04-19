package uk.gov.di.accountmanagement.testsupport.helpers;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.apache.commons.io.IOUtils;
import uk.gov.di.accountmanagement.lambda.UpdateEmailHandler;
import uk.gov.di.accountmanagement.lambda.UpdatePasswordHandler;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.SQLOutput;

import static org.mockito.Mockito.mock;
import static uk.gov.di.authentication.sharedtest.basetest.HandlerIntegrationTest.TXMA_ENABLED_CONFIGURATION_SERVICE;

public class FakeAPI {


    public static void startServer() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(5050), 0);
        server.createContext("/update-phone-number", new WrapperHandler());
        server.createContext("/update-password", new WrapperHandler());
        server.createContext("/update-email", new WrapperHandler());
        server.setExecutor(null); // creates a default executor
        server.start();
    }

}

class WrapperHandler implements HttpHandler {

    @Override
    public void handle(HttpExchange exchange)  {

        try {
            String requestBody = IOUtils.toString(exchange.getRequestBody(), StandardCharsets.UTF_8);
            System.out.println("BODY FROM ORIGINAL REQUEST");
            System.out.println(requestBody);
            // Headers h = exchange.getRequestHeaders(); headers is : HashMap<String,List<String>>
            APIGatewayProxyRequestEvent apiGatewayProxyRequestEvent = new APIGatewayProxyRequestEvent();
            apiGatewayProxyRequestEvent.setBody(requestBody);

            System.out.println("BODY FROM AG FORMED REQUEST");
            System.out.println(apiGatewayProxyRequestEvent.getBody());

            System.out.println("ATTEMPTING TO SET UP EMAIL-HANDLER");
            UpdatePasswordHandler emailHandler = new UpdatePasswordHandler(TXMA_ENABLED_CONFIGURATION_SERVICE);

            Context context = mock(Context.class);

            System.out.println("ATTEMPTING TO SEND EVENT TO HANDLER");
            APIGatewayProxyResponseEvent response = emailHandler.handleRequest(apiGatewayProxyRequestEvent, context);

            System.out.println("RESPONSE FROM HANDLER");
            System.out.println(response.toString());
            exchange.sendResponseHeaders(200, response.toString().length());
            OutputStream os = exchange.getResponseBody();
            os.write(response.toString().getBytes());
            os.flush();
            os.close();


        } catch (Exception e ){
            System.out.println("error was caught");
            System.out.println(e.getMessage());
        }

    }
}
