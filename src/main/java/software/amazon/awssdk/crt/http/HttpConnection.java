/*
 * Copyright 2010-2019 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package software.amazon.awssdk.crt.http;

import software.amazon.awssdk.crt.AsyncCallback;
import software.amazon.awssdk.crt.CrtResource;
import software.amazon.awssdk.crt.CrtRuntimeException;
import software.amazon.awssdk.crt.http.HttpRequest.Header;
import software.amazon.awssdk.crt.io.ClientBootstrap;
import software.amazon.awssdk.crt.io.EventLoopGroup;
import software.amazon.awssdk.crt.io.SocketOptions;
import software.amazon.awssdk.crt.io.TlsContext;
import software.amazon.awssdk.crt.io.TlsContextOptions;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;

import static software.amazon.awssdk.crt.CRT.AWS_CRT_SUCCESS;


/**
 * This class wraps aws-c-http to provide the basic HTTP request/response functionality via the AWS Common Runtime.
 * 
 * HttpConnection represents a single connection to a HTTP service endpoint.
 *
 * This class is not thread safe and should not be called from different threads.
 */
public class HttpConnection extends CrtResource implements Closeable {
    private static final String HTTPS = "https";
    private static final Charset UTF8 = StandardCharsets.UTF_8;

    private final ClientBootstrap clientBootstrap;
    private final SocketOptions socketOptions;
    private final TlsContext tlsContext;
    private final URI uri;
    private volatile ConnectionState connectionState = ConnectionState.DISCONNECTED;
    private HttpConnectionEvents userConnectionCallbacks;
    private AsyncCallback connectAck;
    private AsyncCallback disconnectAck;

    public enum ConnectionState {
        DISCONNECTED, CONNECTING, CONNECTED, DISCONNECTING,
    }

    /**
     * Receives calls from Native code and translates native data to/from Java Objects
     */
    private class JniHttpCallbackHandler {
        private final HttpRequest request;
        private final AsyncHttpResponseHandler responseHandler;

        private JniHttpCallbackHandler(HttpRequest request, AsyncHttpResponseHandler responseHandler) {
            this.request = request;
            this.responseHandler = responseHandler;
        }

        /**
         * Called from Native when new Http Headers have been received.
         * Note that this function may be called multiple times as HTTP headers are received.
         * @param responseStatusCode The HTTP Response Status Code
         * @param nextHeaders The headers received in the latest IO event.
         */
        void onHeaders(int responseStatusCode, Header[] nextHeaders){
            responseHandler.receiveHeaders(responseStatusCode, nextHeaders);
        }

        /**
         * Called from Native once all HTTP Headers are processed.
         * @param hasBody True if the HTTP Response had a Body, false otherwise.
         */
        void onHeadersDone(boolean hasBody){
            responseHandler.receiveHeadersDone(hasBody);
        }

        /**
         * Called when new Body bytes have been received.
         * Note that this function may be called multiple times as headers are received.
         * Do not keep a reference of the ByteBuffer after this method returns.
         * @param bodyBytesIn The HTTP Body Bytes received in the last IO Event.
         * @return The new IO Window Size
         */
        void onResponseBody(ByteBuffer bodyBytesIn){
            responseHandler.receiveResponseBody(bodyBytesIn);
        }


        /**
         * Called from Native when the ResponseBody has completed.
         * If successful, errorCode will be 0, otherwise the error String can be retrieved by calling
         * CRT.awsErrorString(errorCode);
         * @param errorCode The AWS CommonRuntime errorCode of the Response. Will be 0 if no error.
         */
        void onResponseComplete(int errorCode) {
            responseHandler.receiveResponseBodyComplete(errorCode);
        }

        /**
         * Called from Native when the Http Request has a Body (Eg PUT/POST requests).
         * Do not keep a reference of the ByteBuffer after this method returns.
         * @param bodyBytesOut The Buffer to write the Body Bytes to.
         * @return True if body stream is finished, false otherwise.
         */
        boolean sendOutgoingBody(ByteBuffer bodyBytesOut) throws IOException {
            boolean haveBody = request.getBody().isPresent();
            if (!haveBody) {
                return true;
            }

            InputStream bodyStream = request.getBody().get();
            // TODO: This is a Blocking read. Ideally this would be non-blocking.
            int old_position = bodyBytesOut.position();
            int amt_read = bodyStream.read(bodyBytesOut.array(), bodyBytesOut.position(), bodyBytesOut.remaining());

            if (amt_read <= 0) {
                return true;
            }

            bodyBytesOut.position(old_position + amt_read);

            return false;

        }
    }

    /**
     * Constructs a new HttpConnection. Connections are reusable after being disconnected.
     * @param uri Must be non-null
     * @throws HttpException If httpClient is null
     */
    public HttpConnection(URI uri) throws HttpException, CrtRuntimeException {
        this(uri, new ClientBootstrap(new EventLoopGroup(1)), new SocketOptions(), new TlsContext(new TlsContextOptions()), null);
    }

    /**
     * Constructs a new HttpConnection. Connections are reusable after being disconnected.
     * @param uri Must be non-null
     * @param callbacks Optional handler for connection interruptions/resumptions
     * @throws HttpException If httpClient is null
     */
    public HttpConnection(URI uri, ClientBootstrap bootstrap, SocketOptions socketOptions, TlsContext tlsContext, HttpConnectionEvents callbacks) throws HttpException {
        if (uri == null) {  throw new IllegalArgumentException("URI must not be null"); }
        if (bootstrap == null) {  throw new IllegalArgumentException("ClientBootstrap must not be null"); }
        if (socketOptions == null) { throw new IllegalArgumentException("SocketOptions must not be null"); }
        if (HTTPS.equals(uri.getScheme()) && tlsContext == null) { throw new IllegalArgumentException("TlsContext must not be null"); }

        this.uri = uri;
        this.clientBootstrap = bootstrap;
        this.socketOptions = socketOptions;
        this.tlsContext = tlsContext;
        this.userConnectionCallbacks = callbacks;

        String endpoint = uri.getHost();
        int port = uri.getPort();

        CompletableFuture<Boolean> future = new CompletableFuture<>();
        connectAck = AsyncCallback.wrapFuture(future, null);

        try {
            acquire(httpConnectionNew(this,
                    clientBootstrap != null ? clientBootstrap.native_ptr() : 0,
                    socketOptions != null ? socketOptions.native_ptr() : 0,
                    tlsContext != null ? tlsContext.native_ptr() : 0,
                    endpoint,
                    (short) port));
        } catch (CrtRuntimeException ex) {
            throw new HttpException("Exception during httpConnectionNew: " + ex.getMessage());
        }
    }

    /**
     * Disconnects if necessary, and frees native resources associated with this connection
     */
    @Override
    public void close() {
        disconnect();
        httpConnectionRelease(release());
    }

    /**
     * Returns the current connection state. This function should not be used often, it is much better to respond
     * to events delivered via the HttpConnectionEvents interface provided at construction.
     * @return The current connection state
     */
    public ConnectionState getState() {
        return connectionState;
    }

    // Called from native when the connection is established the first time
    private void onConnectionComplete(int errorCode) {
        if (errorCode == AWS_CRT_SUCCESS) {
            connectionState = ConnectionState.CONNECTED;
            if (connectAck != null) {
                connectAck.onSuccess();
                connectAck = null;
            }
        } else {
            connectionState = ConnectionState.DISCONNECTED;
            if (connectAck != null) {
                connectAck.onFailure(new HttpException(errorCode));
                connectAck = null;
            }
        }
        if (userConnectionCallbacks != null) {
            userConnectionCallbacks.onConnectionComplete(errorCode);
        }
    }

    // Called from native when the connection is shutdown.
    private void onConnectionShutdown(int errorCode) {
        connectionState = ConnectionState.DISCONNECTED;
        if (disconnectAck != null) {
            if (errorCode == AWS_CRT_SUCCESS) {
                disconnectAck.onSuccess();
            } else {
                disconnectAck.onFailure(new HttpException(errorCode));
            }
            disconnectAck = null;
        }
        if (userConnectionCallbacks != null) {
            userConnectionCallbacks.onConnectionShutdown(errorCode);
        }
    }

    /**
     * Disconnects the current session
     * @return When this future completes, the disconnection is complete
     */
    public CompletableFuture<Void> disconnect() {
        CompletableFuture<Void> future = new CompletableFuture<>();
        if (this.isNull()) {
            future.complete(null);
            return future;
        }
        disconnectAck = AsyncCallback.wrapFuture(future, null);
        connectionState = ConnectionState.DISCONNECTING;
        try {
            httpConnectionClose(native_ptr());
        } catch (CrtRuntimeException e) {
            future.completeExceptionally(e);
        }

        return future;
    }

    public void executeRequest(HttpRequest request, AsyncHttpResponseHandler responseHandler) throws CrtRuntimeException {

        if (this.isNull()) {
            throw new HttpException("Invalid connection during executeRequest");
        }

        httpConnectionExecuteRequest(native_ptr(),
                                    request.getMethod(),
                                    request.getEncodedPath(),
                                    request.getHeaders(),
                                    new JniHttpCallbackHandler(request, responseHandler));
    }


    /*******************************************************************************
     * Native methods
     ******************************************************************************/
    private static native long httpConnectionNew(HttpConnection thisObj,
                                                 long client_bootstrap,
                                                 long socketOptions,
                                                 long tlsContext,
                                                 String endpoint,
                                                 short port) throws CrtRuntimeException;

    private static native void httpConnectionClose(long connection) throws CrtRuntimeException;
    private static native void httpConnectionRelease(long connection);


    private static native void httpConnectionExecuteRequest(long connection,
                                                           String method,
                                                           String uri,
                                                           Header[] headers,
                                                           JniHttpCallbackHandler jniHttpCallbackHandler) throws CrtRuntimeException;



};