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

import software.amazon.awssdk.crt.CrtResource;

/**
 * An HttpStream represents a Request/Response pair, and wraps the native resource from the aws-c-http library.
 */
public class HttpStream extends CrtResource {

    /* Native code can call this constructor */
    protected HttpStream(long ptr) {
        acquire(ptr);
    }

    @Override
    public void close() {
        httpStreamRelease(release());
    }

    /**
     * Updates the Window size of this HttpStream. This value determines the size of the next update.
     *
     * This can be used to temporarily pause the flow of data by setting the windowSize to 0, and then be used to resume
     * data flow by setting windowSize to a value greater than 0 when ready.
     *
     * @param windowSize
     */
    public void updateWindowSize(int windowSize) {
        if (windowSize < 0) {
            throw new IllegalArgumentException("windowSize must be >= 0. Actual value: " + windowSize);
        }
        httpStreamUpdateWindowSize(native_ptr(), windowSize);
    }

    private static native void httpStreamRelease(long http_stream);
    private static native void httpStreamUpdateWindowSize(long http_stream, int window_size);
}
