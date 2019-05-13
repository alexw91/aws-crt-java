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

import java.net.URI;
import java.util.concurrent.CompletableFuture;

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
    private static final int DEFAULT_HTTP_PORT = 80;
    private static final int DEFAULT_HTTPS_PORT = 443;

    private final ClientBootstrap clientBootstrap;
    private final SocketOptions socketOptions;
    private final TlsContext tlsContext;
    private final URI uri;
    private final int port;
    private final boolean useTls;

    private final CompletableFuture<HttpConnection> connectedFuture;
    private final CompletableFuture<Void> shutdownFuture;

    public static CompletableFuture<HttpConnection> createConnection(URI uri) throws CrtRuntimeException {
        HttpConnection conn = new HttpConnection(uri);
        return conn.connect();
    }

    public static CompletableFuture<HttpConnection> createConnection(URI uri, ClientBootstrap bootstrap,
                                                                     SocketOptions socketOptions, TlsContext tlsContext) throws CrtRuntimeException {
        HttpConnection conn = new HttpConnection(uri, bootstrap, socketOptions, tlsContext);
        return conn.connect();
    }

    /**
     * Constructs a new HttpConnection.
     * @param uri Must be non-null and contain a hostname
     * @throws CrtRuntimeException If unable to create SubResources
     */
    private HttpConnection(URI uri) throws CrtRuntimeException {
        this(uri, new ClientBootstrap(1), new SocketOptions(), new TlsContext());
        own(this.clientBootstrap);
        own(this.socketOptions);
        own(this.tlsContext);
    }

    /**
     * Constructs a new HttpConnection.
     * @param uri Must be non-null and contain a hostname
     * @throws HttpException If httpClient is null
     */
    private HttpConnection(URI uri, ClientBootstrap bootstrap, SocketOptions socketOptions, TlsContext tlsContext) {
        if (uri == null) {  throw new IllegalArgumentException("URI must not be null"); }
        if (uri.getScheme() == null) { throw new IllegalArgumentException("URI does not have a Scheme"); }
        if (!HTTP.equals(uri.getScheme()) && !HTTPS.equals(uri.getScheme())) { throw new IllegalArgumentException("URI has unknown Scheme"); }
        if (uri.getHost() == null) { throw new IllegalArgumentException("URI does not have a Host name"); }
        if (bootstrap == null || bootstrap.isNull()) {  throw new IllegalArgumentException("ClientBootstrap must not be null"); }
        if (socketOptions == null || socketOptions.isNull()) { throw new IllegalArgumentException("SocketOptions must not be null"); }
        if (HTTPS.equals(uri.getScheme()) && tlsContext == null) { throw new IllegalArgumentException("TlsContext must not be null if https is used"); }

        int port = uri.getPort();

        /* Pick a default port based on the scheme if one wasn't set in the URI */
        if (port == -1) {
            if (HTTP.equals(uri.getScheme()))  { port = DEFAULT_HTTP_PORT; }
            if (HTTPS.equals(uri.getScheme())) { port = DEFAULT_HTTPS_PORT; }
        }

        if (port <= 0 || 65535 < port) {
            throw new IllegalArgumentException("Invalid or missing port: " + uri);
        }

        this.uri = uri;
        this.port = port;
        this.useTls = HTTPS.equals(uri.getScheme());
        this.clientBootstrap = bootstrap;
        this.socketOptions = socketOptions;
        this.tlsContext = tlsContext;
        this.connectedFuture = new CompletableFuture<>();
        this.shutdownFuture = new CompletableFuture<>();
    }

    /**
     * Connects to the Http Endpoint
     * @return Future indicating when
     */
    private CompletableFuture<HttpConnection> connect() throws CrtRuntimeException {
        if (!isNull()) {
            return connectedFuture;
        }

        acquire(httpConnectionNew(this,
                clientBootstrap.native_ptr(),
                socketOptions.native_ptr(),
                useTls ? tlsContext.native_ptr() : 0,
                uri.getHost(),
                port));

        return connectedFuture;
    }

    /**
     * Shuts down Connection if necessary, and frees native resources associated with this connection
     */
    @Override
    public void close() {
        if (!isNull()) {
            System.out.println("Releasing Connection...");
            httpConnectionRelease(release());
            System.out.println("Connection Released");
        }

        System.out.println("Releasing SubResources...");
        super.close();
        System.out.println("SubResources Released");
    }

    /** Called from native when the connection is established the first time **/
    private void onConnectionComplete(int errorCode) {
        System.out.println("Native onConnectionComplete() : " + errorCode);
        if (errorCode == AWS_CRT_SUCCESS) {
            System.out.println("Completing connectedFuture...");
            connectedFuture.complete(this);
            System.out.println("Completed connectedFuture.");
        } else {

            System.out.println("Closing Ourself...");
            /* The user can't close this object since they never got a reference to it through the CompletableFuture,
                so we need to close ourself on Connection Error. */
            this.close();
            System.out.println("Closed Ourself...");

            System.out.println("Exceptionally closing connectedFuture...");
            connectedFuture.completeExceptionally(new HttpException(errorCode));
            System.out.println("Exceptionally Closed connectedFuture.");
        }
    }

    /** Called from native when the connection is shutdown. **/
    private void onConnectionShutdown(int errorCode) {
        System.out.println("Native onConnectionShutdown() : " + errorCode);
        if (errorCode == AWS_CRT_SUCCESS) {
           shutdownFuture.complete(null);
        } else {
            shutdownFuture.completeExceptionally(new HttpException(errorCode));
        }
    }

    /**
     * Shuts down the current connection
     * @return When this future completes, the shutdown is complete
     */
    public CompletableFuture<Void> shutdown() {
        if (isNull()) {
           return shutdownFuture;
        }
        try {
            httpConnectionShutdown(native_ptr());
        } catch (CrtRuntimeException e) {
            shutdownFuture.completeExceptionally(e);
        }

        return shutdownFuture;
    }

    /*******************************************************************************
     * Native methods
     ******************************************************************************/
    private static native long httpConnectionNew(HttpConnection thisObj,
                                                 long client_bootstrap,
                                                 long socketOptions,
                                                 long tlsContext,
                                                 String endpoint,
                                                 int port) throws CrtRuntimeException;

    private static native void httpConnectionShutdown(long connection) throws CrtRuntimeException;
    private static native void httpConnectionRelease(long connection);

}
