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
import software.amazon.awssdk.crt.io.ClientBootstrap;
import software.amazon.awssdk.crt.io.SocketOptions;
import software.amazon.awssdk.crt.io.TlsContext;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import static software.amazon.awssdk.crt.CRT.AWS_CRT_SUCCESS;


/**
 * This class wraps aws-c-http to provide the basic HTTP request/response functionality via the AWS Common Runtime.
 *
 * HttpConnection represents a single connection to a HTTP service endpoint.
 *
 * This class is not thread safe and should not be called from different threads.
 */
public class HttpConnection extends CrtResource {
    private static final String HTTP = "http";
    private static final String HTTPS = "https";
    private static final Charset UTF8 = StandardCharsets.UTF_8;

    private boolean ownResources = false;
    private final ClientBootstrap clientBootstrap;
    private final SocketOptions socketOptions;
    private final TlsContext tlsContext;
    private final URI uri;
    private volatile ConnectionState connectionState = ConnectionState.DISCONNECTED;
    private HttpConnectionEvents userConnectionCallbacks;
    private CompletableFuture<Boolean> connectedFuture;
    private AsyncCallback connectAck;
    private AsyncCallback disconnectAck;


    public enum ConnectionState {
        DISCONNECTED, CONNECTING, CONNECTED, DISCONNECTING,
    }

    /**
     * Constructs a new HttpConnection. Connections are reusable after being disconnected.
     * @param uri Must be non-null
     * @throws HttpException If httpClient is null
     */
    public HttpConnection(URI uri) throws HttpException, CrtRuntimeException {
        this(uri, new ClientBootstrap(1), new SocketOptions(), new TlsContext(), null);
        ownResources = true;
    }

    public CompletableFuture<Boolean> getConnectedFuture() {
        return connectedFuture;
    }

    /**
     * Constructs a new HttpConnection. Connections are reusable after being disconnected.
     * @param uri Must be non-null
     * @param callbacks Optional handler for connection interruptions/resumptions
     * @throws HttpException If httpClient is null
     */
    public HttpConnection(URI uri, ClientBootstrap bootstrap, SocketOptions socketOptions, TlsContext tlsContext, HttpConnectionEvents callbacks) throws HttpException {
        if (uri == null) {  throw new IllegalArgumentException("URI must not be null"); }
        if (bootstrap == null || bootstrap.isNull()) {  throw new IllegalArgumentException("ClientBootstrap must not be null"); }
        if (socketOptions == null || socketOptions.isNull()) { throw new IllegalArgumentException("SocketOptions must not be null"); }
        if (HTTPS.equals(uri.getScheme()) && tlsContext == null) { throw new IllegalArgumentException("TlsContext must not be null"); }

        this.uri = uri;
        this.clientBootstrap = bootstrap;
        this.socketOptions = socketOptions;
        this.tlsContext = tlsContext;
        this.userConnectionCallbacks = callbacks;


        String endpoint = uri.getHost();
        int port = uri.getPort();

        if (port == -1) {
            if (HTTP.equals(uri.getScheme())) {
                port = 80;
            } else {
                port = 443;
            }
        }

        connectedFuture = new CompletableFuture<>();
        connectAck = AsyncCallback.wrapFuture(connectedFuture, null);

        try {
            System.err.println("Endpoint: " + endpoint);
            System.err.println("Port: " + port);
            acquire(httpConnectionNew(this,
                    clientBootstrap.native_ptr(),
                    socketOptions.native_ptr(),
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

        if (ownResources) {
            tlsContext.close();
            socketOptions.close();
            clientBootstrap.close();
        }
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
        System.err.println("HttpConnection.onConnectionComplete()");
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
        System.err.println("HttpConnection.onConnectionShutdown()");
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

    public CompletableFuture<HttpResponse> executeRequest(HttpRequest request) throws IOException, CrtRuntimeException {
        if (this.isNull()) {
            throw new HttpException("Invalid connection during executeRequest");
        }

        if (connectionState != ConnectionState.CONNECTED) {
            throw new HttpException("Http Connection is not established yet.");
        }

        HttpHeader[] headers;
        if (request.getHeaders() == null || request.getHeaders().size() == 0) {
            headers = new HttpHeader[]{};
        } else {
            headers = (HttpHeader[]) request.getHeaders().toArray();
        }
        System.err.println("Method: " + request.getMethod());
        System.err.println("Path: " + request.getEncodedPath());
        System.err.println("Headers: " + Arrays.toString(headers));

        CompletableFuture<HttpResponse> future = new CompletableFuture<>();

        httpConnectionExecuteRequest(native_ptr(),
                request.getMethod(),
                request.getEncodedPath(),
                headers,
                new JniHttpCallbackHandler(request, future));

        return future;
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
                                                            HttpHeader[] headers,
                                                            JniHttpCallbackHandler jniHttpCallbackHandler) throws CrtRuntimeException;



};