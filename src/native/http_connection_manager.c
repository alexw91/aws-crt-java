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

#include "http_connection_manager.h"

/* on 32-bit platforms, casting pointers to longs throws a warning we don't need */
#if UINTPTR_MAX == 0xffffffff
#    if defined(_MSC_VER)
#        pragma warning(push)
#        pragma warning(disable : 4305) /* 'type cast': truncation from 'jlong' to 'jni_tls_ctx_options *' */
#    else
#        pragma GCC diagnostic push
#        pragma GCC diagnostic ignored "-Wpointer-to-int-cast"
#        pragma GCC diagnostic ignored "-Wint-to-pointer-cast"
#    endif
#endif

/* methods of HttpConnectionPoolManager */
static struct {
    jmethodID onConnectionAcquired;
    jmethodID onShutdownComplete;
} s_http_connection_manager;


void s_cache_http_conn_manager(JNIEnv *env) {
    jclass cls = (*env)->FindClass(env, "software/amazon/awssdk/crt/http/HttpConnectionPoolManager");
    AWS_FATAL_ASSERT(cls);

    s_http_connection_manager.onConnectionAcquired = (*env)->GetMethodID(env, cls, "onConnectionAcquired", "(JI)V");
    AWS_FATAL_ASSERT(s_http_connection_manager.onConnectionAcquired);

    s_http_connection_manager.onShutdownComplete = (*env)->GetMethodID(env, cls, "onShutdownComplete", "()V");
    AWS_FATAL_ASSERT(s_http_connection_manager.onShutdownComplete);
}

static void s_on_http_conn_manager_shutdown_complete_callback(void *user_data) {

    struct jni_conn_manager *jni_conn_manager = (struct jni_conn_manager *)user_data;
    aws_mutex_lock(&jni_conn_manager->rw_lock);


    JNIEnv *env = aws_jni_get_thread_env(jni_conn_manager->jvm);

    AWS_LOGF_DEBUG(AWS_LS_HTTP_CONNECTION_MANAGER, "ConnManager Shutdown Complete");

    (*env)->CallVoidMethod(env, jni_conn_manager->java_conn_manager, s_http_connection_manager.onShutdownComplete);

    struct aws_allocator *allocator = aws_jni_get_allocator();
    size_t num_nodes = 0;
    while (!aws_linked_list_empty(&jni_conn_manager->idle_java_native_buf_pairs)) {
        struct aws_linked_list_node *node = aws_linked_list_pop_front(&jni_conn_manager->idle_java_native_buf_pairs);
        struct jni_byte_buf_pair *pair = AWS_CONTAINER_OF(node, struct jni_byte_buf_pair, list_handle);
        (*env)->DeleteGlobalRef(env, pair->java_buf);
        aws_byte_buf_clean_up(&pair->native_buf);
        aws_mem_release(allocator, node);
        num_nodes++;
    }

    /* Make sure we free'd the same number of Buffers that we allocated */
    AWS_FATAL_ASSERT(jni_conn_manager->max_connections == num_nodes);

    // We're done with this callback data, free it.
    (*env)->DeleteGlobalRef(env, jni_conn_manager->java_conn_manager);
    aws_mem_release(allocator, user_data);
    aws_mutex_clean_up(&jni_conn_manager->rw_lock);
}

void aws_push_new_native_java_buf_pair(JNIEnv *env, struct aws_allocator *allocator, struct aws_linked_list *list, size_t body_buf_size) {

    struct jni_byte_buf_pair *buf_pair = aws_mem_acquire(allocator, sizeof(struct jni_byte_buf_pair));

    AWS_FATAL_ASSERT(buf_pair != NULL);

    /* Pre-allocate a Native buffer and Java Direct ByteBuffer so that we don't create a new Java Object for each Http
     * Request.
     */

    int result = aws_byte_buf_init(&buf_pair->native_buf, allocator, body_buf_size);
    AWS_FATAL_ASSERT(result == AWS_OP_SUCCESS);

    jobject java_buf = aws_jni_direct_byte_buffer_from_byte_buf(env, &buf_pair->native_buf);
    AWS_FATAL_ASSERT(java_buf != NULL);

    /* Tell the JVM we have a reference to the Java ByteBuffer (so it's not GC'd) */
    buf_pair->java_buf = (*env)->NewGlobalRef(env, java_buf);

    aws_linked_list_push_front(list, &buf_pair->list_handle);
}

JNIEXPORT jlong JNICALL Java_software_amazon_awssdk_crt_http_HttpConnectionPoolManager_httpConnectionManagerNew(
    JNIEnv *env,
    jclass jni_class,
    jobject conn_manager_jobject,
    jlong jni_client_bootstrap,
    jlong jni_socket_options,
    jlong jni_tls_ctx,
    jint jni_buf_size,
    jint jni_window_size,
    jstring jni_endpoint,
    jint jni_port,
    jint jni_max_conns) {

    (void)jni_class;

    struct aws_client_bootstrap *client_bootstrap = (struct aws_client_bootstrap *)jni_client_bootstrap;
    struct aws_socket_options *socket_options = (struct aws_socket_options *)jni_socket_options;
    struct aws_tls_ctx *tls_ctx = (struct aws_tls_ctx *)jni_tls_ctx;

    if (!client_bootstrap) {
        aws_jni_throw_runtime_exception(env, "ClientBootstrap can't be null");
        return (jlong)NULL;
    }

    if (!socket_options) {
        aws_jni_throw_runtime_exception(env, "SocketOptions can't be null");
        return (jlong)NULL;
    }

    struct aws_allocator *allocator = aws_jni_get_allocator();
    AWS_FATAL_ASSERT(allocator != NULL);
    struct aws_byte_cursor endpoint = aws_jni_byte_cursor_from_jstring(env, jni_endpoint);

    if (jni_port <= 0 || 65535 < jni_port) {
        aws_jni_throw_runtime_exception(env, "Port must be between 1 and 65535");
        return (jlong)NULL;
    }

    if (jni_window_size <= 0) {
        aws_jni_throw_runtime_exception(env, "Window Size must be > 0");
        return (jlong)NULL;
    }

    if (jni_max_conns <= 0) {
        aws_jni_throw_runtime_exception(env, "Max Connections must be > 0");
        return (jlong)NULL;
    }

    uint16_t port = (uint16_t)jni_port;

    int use_tls = (jni_tls_ctx != 0);

    struct aws_tls_connection_options tls_conn_options = {0};

    if (use_tls) {
        aws_tls_connection_options_init_from_ctx(&tls_conn_options, tls_ctx);
        aws_tls_connection_options_set_server_name(&tls_conn_options, allocator, &endpoint);
    }

    struct jni_conn_manager *jni_conn_manager = aws_mem_acquire(allocator, sizeof(struct jni_conn_manager));
    aws_mutex_init(&jni_conn_manager->rw_lock);
    aws_mutex_lock(&jni_conn_manager->rw_lock);

    jni_conn_manager->java_conn_manager = (*env)->NewGlobalRef(env, conn_manager_jobject);;
    jni_conn_manager->max_connections = (size_t)jni_max_conns;
    jni_conn_manager->buf_size = (size_t)jni_buf_size;

    /* Setup the Linked List */
    aws_linked_list_init(&jni_conn_manager->idle_java_native_buf_pairs);

    for (int i = 0; i < jni_conn_manager->max_connections; i++) {
        /* TODO: If/when Http2 support becomes available and we have >1 HttpStreams per HttpConnection, we'll need to
         * add another for loop here for the expected max number of Streams per Conn. */
        aws_push_new_native_java_buf_pair(env, allocator, &jni_conn_manager->idle_java_native_buf_pairs, (size_t)jni_buf_size);
    }

    jint jvmresult = (*env)->GetJavaVM(env, &jni_conn_manager->jvm);
    (void)jvmresult;
    AWS_FATAL_ASSERT(jvmresult == 0);

    struct aws_http_connection_manager_options manager_options = {0};
    manager_options.bootstrap = client_bootstrap;
    manager_options.initial_window_size = (size_t)jni_window_size;
    manager_options.socket_options = socket_options;
    manager_options.tls_connection_options = NULL;
    manager_options.host = endpoint;
    manager_options.port = port;
    manager_options.max_connections = (size_t)jni_max_conns;
    manager_options.shutdown_complete_callback = &s_on_http_conn_manager_shutdown_complete_callback;
    manager_options.shutdown_complete_user_data = jni_conn_manager;

    if (use_tls) {
        manager_options.tls_connection_options = &tls_conn_options;
    }


    struct aws_http_connection_manager *conn_manager = aws_http_connection_manager_new(allocator, &manager_options);

    jni_conn_manager->native_conn_manager = conn_manager;
    aws_mutex_unlock(&jni_conn_manager->rw_lock);


    if (use_tls) {
        aws_tls_connection_options_clean_up(&tls_conn_options);
    }

    return (jlong)jni_conn_manager;
}

JNIEXPORT void JNICALL Java_software_amazon_awssdk_crt_http_HttpConnectionPoolManager_httpConnectionManagerRelease(
    JNIEnv *env,
    jclass jni_class,
    jlong jni_conn_manager_ptr) {

    (void)jni_class;

    struct jni_conn_manager *jni_conn_manager = (struct jni_conn_manager *)jni_conn_manager_ptr;

    if (!jni_conn_manager) {
        aws_jni_throw_runtime_exception(env, "Connection Manager can't be null");
        return;
    }

    AWS_LOGF_DEBUG(AWS_LS_HTTP_CONNECTION, "Releasing ConnManager: id: %p", (void *)jni_conn_manager_ptr);

    aws_mutex_lock(&jni_conn_manager->rw_lock);

    struct aws_http_connection_manager *native_conn_manager = jni_conn_manager->native_conn_manager;
    jni_conn_manager->native_conn_manager = NULL;
    aws_http_connection_manager_release(native_conn_manager);

    aws_mutex_unlock(&jni_conn_manager->rw_lock);

}

static void s_on_http_conn_acquisition_callback(
    struct aws_http_connection *connection,
    int error_code,
    void *user_data) {

    struct jni_conn_manager *jni_conn_manager = (struct jni_conn_manager *)user_data;
    JNIEnv *env = aws_jni_get_thread_env(jni_conn_manager->jvm);
    jlong jni_connection = (jlong)connection;
    jint jni_error_code = (jint)error_code;

    AWS_LOGF_DEBUG(
        AWS_LS_HTTP_CONNECTION,
        "ConnManager Acquired Conn: conn: %p, err_code: %d,  err_str: %s",
        (void *)connection,
        error_code,
        aws_error_str(error_code));

    (*env)->CallVoidMethod(
        env,
        jni_conn_manager->java_conn_manager,
        s_http_connection_manager.onConnectionAcquired,
        jni_connection,
        jni_error_code);
}

JNIEXPORT void JNICALL
    Java_software_amazon_awssdk_crt_http_HttpConnectionPoolManager_httpConnectionManagerAcquireConnection(
        JNIEnv *env,
        jclass jni_class,
        jobject conn_manager_jobject,
        jlong jni_conn_manager_ptr) {

    (void)jni_class;

    if (!jni_conn_manager_ptr) {
        aws_jni_throw_runtime_exception(env, "Connection Manager can't be null");
        return;
    }

    struct jni_conn_manager *jni_conn_manager = (struct jni_conn_manager *)jni_conn_manager_ptr;
    struct aws_http_connection_manager *conn_manager = jni_conn_manager->native_conn_manager;

    AWS_LOGF_DEBUG(AWS_LS_HTTP_CONNECTION, "Requesting a new connection from conn_manager: %p", (void *)conn_manager);



    aws_http_connection_manager_acquire_connection(
        conn_manager, &s_on_http_conn_acquisition_callback, (void *)jni_conn_manager);
}

JNIEXPORT void JNICALL
    Java_software_amazon_awssdk_crt_http_HttpConnectionPoolManager_httpConnectionManagerReleaseConnection(
        JNIEnv *env,
        jclass jni_class,
        jlong jni_conn_manager,
        jlong jni_conn) {

    (void)jni_class;

    struct aws_http_connection_manager *conn_manager = (struct aws_http_connection_manager *)jni_conn_manager;
    struct aws_http_connection *conn = (struct aws_http_connection *)jni_conn;

    if (!conn_manager) {
        aws_jni_throw_runtime_exception(env, "Connection Manager can't be null");
        return;
    }

    if (!conn) {
        aws_jni_throw_runtime_exception(env, "Connection can't be null");
        return;
    }

    AWS_LOGF_DEBUG(
        AWS_LS_HTTP_CONNECTION,
        "ConnManager Releasing Conn: manager: %p, conn: %p",
        (void *)conn_manager,
        (void *)conn);

    aws_http_connection_manager_release_connection(conn_manager, conn);
}

#if UINTPTR_MAX == 0xffffffff
#    if defined(_MSC_VER)
#        pragma warning(pop)
#    else
#        pragma GCC diagnostic pop
#    endif
#endif
