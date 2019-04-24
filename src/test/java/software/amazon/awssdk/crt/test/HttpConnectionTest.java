package software.amazon.awssdk.crt.test;

import org.junit.Assert;
import org.junit.Test;
import software.amazon.awssdk.crt.CRT;
import software.amazon.awssdk.crt.http.AsyncHttpResponseHandler;
import software.amazon.awssdk.crt.http.HttpConnection;
import software.amazon.awssdk.crt.http.HttpRequest;

import java.net.URI;
import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class HttpConnectionTest {

    static class TestHttpRequest {

    }

    @Test
    public void testSimpleGetRequest() throws Exception {
        HttpConnection conn = new HttpConnection(new URI("https://httpbin.org/get:443"));

        HttpRequest.Header[] headers = new HttpRequest.Header[]{new HttpRequest.Header("example-header-name", "example-header-value")};
        HttpRequest req = new HttpRequest("GET", "/get", headers);

        final AtomicBoolean recv200Response = new AtomicBoolean(false);
        final CompletableFuture<Boolean> requestFuture = new CompletableFuture<>();

        AsyncHttpResponseHandler responseHandler = new AsyncHttpResponseHandler() {

            @Override
            public void receiveHeaders(int responseStatusCode, HttpRequest.Header[] nextHeaders) {
                System.out.println("receiveHeaders");
                if (responseStatusCode != 200) {
                    recv200Response.set(true);
                }
            }

            @Override
            public void receiveHeadersDone(boolean hasBody) {
                System.out.println("receiveHeadersDone");
            }

            @Override
            public void receiveResponseBody(ByteBuffer nextBodyBytes) {
                System.out.println("receiveResponseBody");
            }

            @Override
            public void receiveResponseBodyComplete(int errorCode) {
                System.out.println("receiveResponseBodyComplete");
                if (errorCode == CRT.AWS_CRT_SUCCESS) {
                    requestFuture.complete(true);
                } else {
                    requestFuture.complete(false);
                }
            }
        };

        conn.executeRequest(req, responseHandler);

        Boolean requestCompleted = requestFuture.get(5, TimeUnit.SECONDS);
        if (requestCompleted) {
            Assert.assertTrue("Expected Http 200 Response", recv200Response.get());
            return;
        }
        Assert.fail();
    }
}
