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

package software.amazon.awssdk.crt.test;

import org.junit.Assert;
import org.junit.Test;
import software.amazon.awssdk.crt.CrtResource;
import software.amazon.awssdk.crt.http.HttpConnection;
import software.amazon.awssdk.crt.http.HttpConnectionEvents;

import java.net.URI;
import java.util.concurrent.atomic.AtomicBoolean;

import static software.amazon.awssdk.crt.CRT.AWS_CRT_SUCCESS;

public class HttpConnectionTest {

    protected void testConnection(URI uri, boolean expectConnected, String exceptionMsg) {
        boolean actuallyConnected = false;
        boolean exceptionThrown = false;

        final AtomicBoolean connCompleteCalled = new AtomicBoolean(false);
        final AtomicBoolean connShutdownCalled = new AtomicBoolean(false);

        HttpConnectionEvents notifications = new HttpConnectionEvents() {
            @Override
            public void onConnectionComplete(int errorCode) {
                if (errorCode == AWS_CRT_SUCCESS) {
                    connCompleteCalled.set(true);
                }
            }

            @Override
            public void onConnectionShutdown(int errorCode) {
                if (errorCode == AWS_CRT_SUCCESS) {
                    connShutdownCalled.set(true);
                }
            }
        };

        try (HttpConnection conn = new HttpConnection(uri, notifications)){
            Assert.assertEquals(false, connCompleteCalled.get());
            Assert.assertEquals(false, connShutdownCalled.get());
            actuallyConnected = conn.connect().get();

            Assert.assertEquals(true, connCompleteCalled.get());

            conn.shutdown().get();
            Assert.assertEquals(true, connShutdownCalled.get());

        } catch (Exception e) {
            exceptionThrown = true;
            Assert.assertTrue(e.getMessage(), e.getMessage().contains(exceptionMsg));
        }

        Assert.assertEquals("URI: " + uri.toString(), expectConnected, actuallyConnected);
        Assert.assertEquals("URI: " + uri.toString(), expectConnected, !exceptionThrown);
        Assert.assertEquals(0, CrtResource.getAllocatedNativeResourceCount());
    }

    @Test
    public void testHttpConnection() throws Exception {
        testConnection(new URI("https://aws-crt-test-stuff.s3.amazonaws.com"), true, null);
        testConnection(new URI("http://aws-crt-test-stuff.s3.amazonaws.com"), true, null);
        testConnection(new URI("http://aws-crt-test-stuff.s3.amazonaws.com:80"), true, null);
        testConnection(new URI("http://aws-crt-test-stuff.s3.amazonaws.com:443"), true, null);
        testConnection(new URI("https://aws-crt-test-stuff.s3.amazonaws.com:443"), true, null);
        testConnection(new URI("https://rsa2048.badssl.com/"), true, null);
        testConnection(new URI("http://http.badssl.com/"), true, null);
        testConnection(new URI("https://expired.badssl.com/"), false, "TLS (SSL) negotiation failed");
        testConnection(new URI("https://self-signed.badssl.com/"), false, "TLS (SSL) negotiation failed");
    }
}
