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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Interface for Client Response Handlers to implement.
 */
public class HttpResponse {
    private final int statusCode;
    private final List<HttpHeader> headers;
    private final InputStream body;

    public static class Builder {
        int statusCode = -1;
        List<HttpHeader> headers = new ArrayList<>();
        InputStream body = null;

        public Builder() {}

        public Builder withStatusCode(int statusCode) {
            this.statusCode = statusCode;
            return this;
        }

        public Builder withHeaders(HttpHeader[] h) {
            headers.addAll(Arrays.asList(h));
            return this;
        }

        public Builder withBodyStream(InputStream bodyStream) {
            this.body = bodyStream;
            return this;
        }

        public HttpResponse build() {
            return new HttpResponse(statusCode, headers, body);
        }
    }

    public HttpResponse(int statusCode, List<HttpHeader> headers, InputStream body) {
        this.statusCode = statusCode;
        this.headers = headers;
        this.body = body;
    }

    public int getStatusCode() {
        return statusCode;
    }

    public List<HttpHeader> getHeaders() {
        return headers;
    }

    public Optional<InputStream> getBody() {
        return Optional.ofNullable(body);
    }
}