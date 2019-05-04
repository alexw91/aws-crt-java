package software.amazon.awssdk.crt.http;

import java.nio.ByteBuffer;

public class BlockingHttpResponseHandler  implements JniHttpCallbackHandler {


    @Override
    public void onHeaders(HttpStream stream, int responseStatusCode, HttpHeader[] nextHeaders) {

    }

    @Override
    public void onHeadersDone(HttpStream stream, boolean hasBody) {

    }

    @Override
    public void onResponseBody(HttpStream stream, ByteBuffer bodyBytesIn) {

    }

    @Override
    public void onResponseComplete(HttpStream stream, int errorCode) {

    }

    @Override
    public boolean sendOutgoingBody(HttpStream stream, ByteBuffer bodyBytesOut) {
        return false;
    }
}
