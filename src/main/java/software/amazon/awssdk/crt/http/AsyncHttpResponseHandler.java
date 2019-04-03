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

import software.amazon.awssdk.crt.http.HttpRequest.Header;

import java.nio.ByteBuffer;

/**
 * Interface for Client Response Handlers to implement.
 */
public interface AsyncHttpResponseHandler {

    /**
     * Called when new Http Headers have been received. Note that this function may be called multiple times as
     * HTTP headers are received.
     * @param responseStatusCode The HTTP Response Status Code
     * @param nextHeaders The headers received in the latest IO event.
     */
    void receiveHeaders(int responseStatusCode, Header[] nextHeaders);

    /**
     * Called once all HTTP Headers are processed.
     * @param hasBody True if the HTTP Response had a Body, false otherwise.
     */
    void receiveHeadersDone(boolean hasBody);

    /**
     * Called when new Body bytes have been received.
     * Note that this function may be called multiple times as headers are received.
     * @param nextBodyBytes The HTTP Body Bytes received in the last IO Event.
     * @return The new IO Window Size
     */
    void receiveResponseBody(ByteBuffer nextBodyBytes);

    /**
     * Called when the ResponseBody has completed.
     * If successful, errorCode will be 0, otherwise the error String can be retrieved by calling
     * CRT.awsErrorString(errorCode);
     * @param errorCode The AWS CommonRuntime errorCode of the Response. Will be 0 if no error.
     */
    void receiveResponseBodyComplete(int errorCode);
}
