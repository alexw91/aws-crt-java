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
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * Represents a single Request to be sent on a HTTP connection
 */
public class HttpRequest {
    private final String method;
    private final String encodedPath;
    private final Header[] headers;
    private final Optional<InputStream> body;

    /**
     * Simple Http Header Class
     */
    public static class Header {
        private final static Charset UTF8 = StandardCharsets.UTF_8;
        public byte[] name;
        public byte[] value;

        public Header() {}

        public Header(String name, String value){
            // TODO: Header validation?
            this.name = name.getBytes(UTF8);
            this.value = value.getBytes(UTF8);
        }

        public String getName() {
            if (name == null) {
                return "";
            }
            return new String(name, UTF8);
        }

        public String getValue() {
            if (value == null) {
                return "";
            }
            return new String(value, UTF8);
        }
    }

    public HttpRequest(String method, String encodedPath, Header[] headers) {
        this(method, encodedPath, headers, Optional.empty());
    }

    public HttpRequest(String method, String encodedPath, Header[] headers, Optional<InputStream> body) {
        this.method = method;
        this.encodedPath = encodedPath;
        this.headers = headers;
        this.body = body;
    }

    public String getMethod() {
        return method;
    }

    public String getEncodedPath() {
        return encodedPath;
    }

    public Header[] getHeaders() {
        return headers;
    }

    public Optional<InputStream> getBody() {
        return body;
    }
}
