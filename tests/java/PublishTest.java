/*
 * Copyright 2010-2018 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR connectionS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package crt.test;

import org.junit.Test;
import static org.junit.Assert.*;
import software.amazon.awssdk.crt.*;
import software.amazon.awssdk.crt.mqtt.*;
import java.util.function.*;
import java.nio.ByteBuffer;
import java.util.concurrent.Semaphore;

public class PublishTest {
    public PublishTest() {
    }

    static final String TEST_ENDPOINT = "localhost:1883";
    static final int TEST_TIMEOUT = 3000; /* ms */
    static final String TEST_TOPIC = "publish/me/senpai";
    static final String TEST_PAYLOAD = "PUBLISH ME! SHINY AND CHROME!";

    int pubsAcked = 0;
    boolean disconnecting = false;

    @Test
    public void testPublish() {
        try {
            MqttClient client = new MqttClient(1);
            assertNotNull(client);
            assertTrue(client.native_ptr() != 0);

            final Semaphore done = new Semaphore(0);

            MqttConnection.ConnectOptions options = new MqttConnection.ConnectOptions();
            options.clientId = "PublishTest";
            options.endpointUri = TEST_ENDPOINT;
            MqttConnection connection = new MqttConnection(client, options) {
                @Override
                public void onOnline() {
                    done.release();
                }

                @Override
                public void onOffline() {
                    if (!disconnecting) {
                        fail("Disconnected, server probably hung up");
                    }
                    done.release();
                }
            };

            connection.connect();
            done.acquire();
            assertEquals("Connected", MqttConnection.ConnectionState.Connected, connection.getState());

            MqttActionListener pubAck = new MqttActionListener() {
                @Override
                public void onSuccess() {
                    pubsAcked++;
                    done.release();
                }

                @Override
                public void onFailure(Throwable cause) {
                    fail("Publish failed: " + cause.getMessage());
                    done.release();
                }
            };

            ByteBuffer payload = ByteBuffer.allocateDirect(TEST_PAYLOAD.length());
            payload.put(TEST_PAYLOAD.getBytes());
            MqttMessage message = new MqttMessage(TEST_TOPIC, payload);
            connection.publish(message, MqttConnection.QOS.AT_LEAST_ONCE, false, pubAck);
            done.acquire();

            assertEquals("Published", 1, pubsAcked);

            disconnecting = true;
            connection.disconnect();
            done.acquire();
            assertEquals("Disconnected", MqttConnection.ConnectionState.Disconnected, connection.getState());
        } catch (CrtRuntimeException ex) {
            fail(ex.getMessage());
        } catch (InterruptedException interrupted) { /* wait() can be interrupted */
            fail(interrupted.getMessage());
        } catch (MqttException mqttEx) {
            fail(mqttEx.getMessage());
        }
    }
};
