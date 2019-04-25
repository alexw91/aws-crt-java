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

import java.io.InputStream;
import java.util.List;
import java.util.Optional;

/**
 * Represents a single Request to be sent on a HTTP connection
 */
public class HttpRequest {
    private final String method;
    private final String encodedPath;
    private final List<HttpHeader> headers;
    private final InputStream body;

    /**
     * Simple Http Header Class
     */


    public HttpRequest(String method, String encodedPath) {
        this(method, encodedPath, null, null);
    }

    public HttpRequest(String method, String encodedPath, List<HttpHeader> headers) {
        this(method, encodedPath, headers, null);
    }

    public HttpRequest(String method, String encodedPath, List<HttpHeader> headers, InputStream body) {
        this.method = method;
        this.encodedPath = encodedPath;
        this.body = body;
        this.headers = headers;

    }

    public String getMethod() {
        return method;
    }

    public String getEncodedPath() {
        return encodedPath;
    }

    public List<HttpHeader> getHeaders() {
        return headers;
    }

    public Optional<InputStream> getBody() {
        return Optional.of(body);
    }
}