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

#include <crt.h>
#include <jni.h>
#include <string.h>

#include <aws/common/condition_variable.h>
#include <aws/common/mutex.h>
#include <aws/common/string.h>
#include <aws/common/thread.h>

#include <aws/io/channel.h>
#include <aws/io/channel_bootstrap.h>
#include <aws/io/event_loop.h>
#include <aws/io/host_resolver.h>
#include <aws/io/logging.h>
#include <aws/io/socket.h>
#include <aws/io/socket_channel_handler.h>
#include <aws/io/tls_channel_handler.h>

#include <aws/http/connection.h>
#include <aws/http/connection_manager.h>
#include <aws/http/http.h>

/* A Native aws_byte_buf and a Java DirectByteBuffer that points to the aws_byte_buf */
struct jni_byte_buf_pair {
    struct aws_byte_buf native_buf;
    jobject java_buf;

    /* Only used if this jni_byte_buf_pair is currently in a linked list */
    struct aws_linked_list_node list_handle;
};

struct jni_conn_manager {
    struct aws_mutex rw_lock;
    JavaVM *jvm;
    jobject java_conn_manager;
    struct aws_http_connection_manager *native_conn_manager;
    size_t max_connections;

    /* Pre-allocated list of Java ByteBuffers to use for HttpStream Java callbacks */
    size_t buf_size;
    struct aws_linked_list idle_java_native_buf_pairs;
};
