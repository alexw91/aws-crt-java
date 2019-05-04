package software.amazon.awssdk.crt.http;

import java.nio.ByteBuffer;

/**
 * Interface that Native code knows how to call.
 *
 * Maps 1-1 to the Native Http API here: https://github.com/awslabs/aws-c-http/blob/master/include/aws/http/request_response.h
 */
public interface JniHttpCallbackHandler {

    /**
     * Called from Native when new Http Headers have been received.
     * Note that this function may be called multiple times as HTTP headers are received.
     * @param stream The HttpStream object
     * @param responseStatusCode The HTTP Response Status Code
     * @param nextHeaders The headers received in the latest IO event.
     */
    void onHeaders(HttpStream stream, int responseStatusCode, HttpHeader[] nextHeaders);

    /**
     * Called from Native once all HTTP Headers are processed. Will not be called if there are no Http Headers in the
     * response. Guaranteed to be called exactly once if there is at least 1 Header.
     *
     * @param stream The HttpStream object
     * @param hasBody True if the HTTP Response had a Body, false otherwise.
     */
    void onHeadersDone(HttpStream stream, boolean hasBody);

    /**
     * Called when new Body bytes have been received.
     * Note that this function may be called multiple times as headers are received.
     * Do not keep a reference of the ByteBuffer after this method returns.
     * @param bodyBytesIn The HTTP Body Bytes received in the last IO Event.
     * @return The new IO Window Size
     */
    void onResponseBody(HttpStream stream, ByteBuffer bodyBytesIn);

    void onResponseComplete(HttpStream stream, int errorCode);
    boolean sendOutgoingBody(HttpStream stream, ByteBuffer bodyBytesOut);

}
