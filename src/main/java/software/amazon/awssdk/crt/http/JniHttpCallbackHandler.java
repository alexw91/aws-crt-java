package software.amazon.awssdk.crt.http;

import software.amazon.awssdk.crt.CRT;
import software.amazon.awssdk.crt.utils.AwsCrtInputStreamEvent;
import software.amazon.awssdk.crt.utils.AwsCrtPipedInputStream;

import java.io.IOException;
import java.io.InputStream;
import java.io.PipedOutputStream;
import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;

/**
 * Receives calls from Native code and translates native data to/from Java Objects
 */
class JniHttpCallbackHandler {
    private final HttpRequest request;
    private final CompletableFuture<HttpResponse> responseHandler;
    private final HttpResponse.Builder responseBuilder = new HttpResponse.Builder();
    private final PipedOutputStream out;
    private final AwsCrtPipedInputStream in;

    JniHttpCallbackHandler(HttpRequest request, CompletableFuture<HttpResponse> responseHandler) throws IOException {
        this.request = request;
        this.responseHandler = responseHandler;
        out = new PipedOutputStream();
        AwsCrtInputStreamEvent callback = null; // TODO: wire up callback to update Window sizes on InputStream Read Events
        in = new AwsCrtPipedInputStream(out, callback);
    }

    /**
     * Called from Native when new Http Headers have been received.
     * Note that this function may be called multiple times as HTTP headers are received.
     * @param responseStatusCode The HTTP Response Status Code
     * @param nextHeaders The headers received in the latest IO event.
     */
    void onHeaders(int responseStatusCode, HttpHeader[] nextHeaders) {
        responseBuilder.withStatusCode(responseStatusCode);
        responseBuilder.withHeaders(nextHeaders);
    }

    /**
     * Called from Native once all HTTP Headers are processed.
     * @param hasBody True if the HTTP Response had a Body, false otherwise.
     */
    void onHeadersDone(boolean hasBody) {
        if (hasBody) {
            responseBuilder.withBodyStream(in);
        }
        responseHandler.complete(responseBuilder.build());
    }

    /**
     * Called when new Body bytes have been received.
     * Note that this function may be called multiple times as headers are received.
     * Do not keep a reference of the ByteBuffer after this method returns.
     * @param bodyBytesIn The HTTP Body Bytes received in the last IO Event.
     * @return The new IO Window Size
     */
    void onResponseBody(HttpStream stream, ByteBuffer bodyBytesIn) {
        if (!responseHandler.isDone()) {
            /* May happen if there were no Headers in Response */
            responseBuilder.withBodyStream(in);
            responseHandler.complete(responseBuilder.build());
        }

        try {
            /* Write the bytes, then update the Native Window Size*/
            out.write(bodyBytesIn.array());
            stream.updateWindowSize(in.writeSpaceAvailable());
        } catch (IllegalStateException|IOException e) {
            // TODO: What to do?
        }
    }


    /**
     * Called from Native when the ResponseBody has completed.
     * If successful, errorCode will be 0, otherwise the error String can be retrieved by calling
     * CRT.awsErrorString(errorCode);
     * @param errorCode The AWS CommonRuntime errorCode of the Response. Will be 0 if no error.
     */
    void onResponseComplete(int errorCode) {
//            System.err.println("JniHttpCallbackHandler.onResponseComplete(): err_code: " + errorCode);
        try {
            if (errorCode == CRT.AWS_CRT_SUCCESS) {
                out.close();
            } else {

            }

        } catch (IOException e) {
            // TODO:
        }
    }

    /**
     * Called from Native when the Http Request has a Body (Eg PUT/POST requests).
     * Do not keep a reference of the ByteBuffer after this method returns.
     * @param bodyBytesOut The Buffer to write the Body Bytes to.
     * @return True if body stream is finished, false otherwise.
     */
    boolean sendOutgoingBody(ByteBuffer bodyBytesOut) throws IOException {
//            System.err.println("JniHttpCallbackHandler.sendOutgoingBody()");
        boolean haveBody = request.getBody().isPresent();
        if (!haveBody) {
            return true;
        }

        InputStream bodyStream = request.getBody().get();

        int old_position = bodyBytesOut.position();

        // TODO: This is a Blocking read. Ideally this would be non-blocking.
        int amt_read = bodyStream.read(bodyBytesOut.array(), bodyBytesOut.position(), bodyBytesOut.remaining());

        if (amt_read <= 0) {
            return true;
        }

        bodyBytesOut.position(old_position + amt_read);

        return false;

    }
}
