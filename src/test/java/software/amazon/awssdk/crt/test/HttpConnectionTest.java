package software.amazon.awssdk.crt.test;

import org.junit.Assert;
import org.junit.Test;
import software.amazon.awssdk.crt.CRT;
import software.amazon.awssdk.crt.CrtResource;
import software.amazon.awssdk.crt.http.AsyncHttpResponseHandler;
import software.amazon.awssdk.crt.http.HttpConnection;
import software.amazon.awssdk.crt.http.HttpHeader;
import software.amazon.awssdk.crt.http.HttpRequest;

import javax.xml.bind.DatatypeConverter;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class HttpConnectionTest {
    private final static Charset UTF8 = StandardCharsets.UTF_8;
    private final static String TEST_DOC_SHA256 = "C7FDB5314B9742467B16BD5EA2F8012190B5E2C44A005F7984F89AAB58219534";


    public void testRequest(URI uri, String method, String reqPath, String reqBody,
                            int expStatus, String expBodySha) throws Exception {
        HttpConnection conn = new HttpConnection(uri);
        List<HttpHeader> headers = Arrays.asList(new HttpHeader("Host", uri.getHost()));

        HttpRequest req = new HttpRequest(method, reqPath, headers, new ByteArrayInputStream(reqBody.getBytes(UTF8)));

        final CompletableFuture<Boolean> requestFuture = new CompletableFuture<>();
        final AtomicInteger status = new AtomicInteger(0);
        MessageDigest digest = MessageDigest.getInstance("SHA-256");

        AsyncHttpResponseHandler responseHandler = new AsyncHttpResponseHandler() {

            @Override
            public void receiveHeaders(int responseStatusCode, HttpHeader[] nextHeaders) {
                System.out.println("HttpConnectionTest.receiveHeaders(), status: " + responseStatusCode
                        + ", Headers: " + Arrays.toString(nextHeaders));
                status.set(responseStatusCode);
            }

            @Override
            public void receiveHeadersDone(boolean hasBody) {
                System.out.println("HttpConnectionTest.receiveHeadersDone()");
            }

            @Override
            public void receiveResponseBody(ByteBuffer nextBodyBytes) {
                digest.update(nextBodyBytes);
            }

            @Override
            public void receiveResponseBodyComplete(int errorCode) {
                System.out.println("HttpConnectionTest.receiveResponseBodyComplete(), err_code: " + errorCode);
                if (errorCode == CRT.AWS_CRT_SUCCESS) {
                    requestFuture.complete(true);
                } else {
                    requestFuture.complete(false);
                }
            }
        };

        conn.getConnectedFuture().get(); // Wait for connection to connect
        conn.executeRequest(req, responseHandler);

        Boolean requestCompleted = requestFuture.get(30, TimeUnit.SECONDS);

        System.out.println("requestCompleted:" + requestCompleted);
        System.out.println("status:" + status.get());
        String hash = DatatypeConverter.printHexBinary(digest.digest());
        System.out.println("SHA256: " + hash);

        Assert.assertTrue(requestCompleted);
        Assert.assertEquals("Expected Http Response Status Mismatch", expStatus, status.get());

        if (expBodySha != null) {
            Assert.assertEquals("Sha256 Hash Mismatch", expBodySha, hash);
        }

        conn.disconnect().get(30, TimeUnit.SECONDS);
        conn.close();
        System.out.println("Curr Native Resources Count: " + CrtResource.getAllocatedNativeResourceCount());
        System.out.println("Curr Native Resources: " + CrtResource.getAllocatedNativeResources());
    }

//    @Test
//    public void testRequest() throws Exception {
//        testRequest(new URI("https://aws-crt-test-stuff.s3.amazonaws.com"), "GET", "/http_test_doc.txt", "", 200, TEST_DOC_SHA256);
//        testRequest(new URI("https://httpbin.org"), "GET", "/get", "", 200, null);
//        testRequest(new URI("https://httpbin.org"), "POST", "/post", "", 200, null);
//        testRequest(new URI("https://httpbin.org"), "GET", "/status/200", "", 200, null);
//        testRequest(new URI("https://httpbin.org"), "GET", "/status/300", "", 300, null);
//        testRequest(new URI("https://httpbin.org"), "GET", "/status/400", "", 400, null);
//        //testRequest(new URI("https://aws-crt-test-stuff.s3.amazonaws.com"), "GET", "/http_test_doc.txt", 200, TEST_DOC_SHA256);
//        //testRequest(new URI("https://aws-crt-test-stuff.s3.amazonaws.com"), "GET", "/http_test_doc.txt", 200, TEST_DOC_SHA256);
//
//    }
}
