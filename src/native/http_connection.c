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

#include <jni.h>
#include <crt.h>

#include <aws/common/condition_variable.h>
#include <aws/common/mutex.h>
#include <aws/common/string.h>
#include <aws/common/thread.h>

#include <aws/io/channel.h>
#include <aws/io/channel_bootstrap.h>
#include <aws/io/event_loop.h>
#include <aws/io/host_resolver.h>
#include <aws/io/socket.h>
#include <aws/io/socket_channel_handler.h>
#include <aws/io/tls_channel_handler.h>

#include <aws/http/connection.h>
#include <aws/http/request_response.h>

/*******************************************************************************
 * JNI class field/method maps
 ******************************************************************************/

/* methods of HttpConnection.AsyncCallback */
static struct {
    jmethodID on_success;
    jmethodID on_failure;
} s_async_callback = {0};

void s_cache_http_async_callback(JNIEnv *env) {
    jclass cls = (*env)->FindClass(env, "software/amazon/awssdk/crt/AsyncCallback");
    assert(cls);
    s_async_callback.on_success = (*env)->GetMethodID(env, cls, "onSuccess", "()V");
    assert(s_async_callback.on_success);
    s_async_callback.on_failure = (*env)->GetMethodID(env, cls, "onFailure", "(Ljava/lang/Throwable;)V");
    assert(s_async_callback.on_failure);
}

/* methods of HttpConnection */
static struct {
    jmethodID on_connection_complete;
    jmethodID on_connection_shutdown;
} s_http_connection;

void s_cache_http_connection(JNIEnv *env) {
    jclass cls = (*env)->FindClass(env, "software/amazon/awssdk/crt/http/HttpConnection");
    assert(cls);
    s_http_connection.on_connection_complete = (*env)->GetMethodID(env, cls, "onConnectionComplete", "(I)V");
    assert(s_http_connection.on_connection_complete);

    s_http_connection.on_connection_shutdown = (*env)->GetMethodID(env, cls, "onConnectionShutdown", "(I)V");
    assert(s_http_connection.on_connection_shutdown);
}


/*******************************************************************************
 * http_jni_connection - represents an aws_http_connection to Java
 ******************************************************************************/
struct http_jni_connection {
    struct aws_http_connection *native_http_conn;
    struct aws_socket_options *socket_options;
    struct aws_tls_connection_options *tls_options;

    JavaVM *jvm;
    jobject java_http_conn; /* The Java HttpConnection instance */

    bool disconnect_requested;
};

/*******************************************************************************
 * http_request_jni_async_callback - carries an AsyncCallback around as user data to http
 * async ops, and is used to deliver callbacks. Also hangs on to JNI references
 * to buffers and strings that need to outlive the request
 ******************************************************************************/
struct http_request_jni_async_callback {
    struct http_jni_connection *connection;
    jobject jni_http_callback_handler;
    jobject java_byte_buffer;
};

static struct http_request_jni_async_callback *jni_http_request_async_callback_new(struct http_jni_connection *connection,
                                                                                   jobject java_callback_handler) {
    struct aws_allocator *allocator = aws_jni_get_allocator();
    struct http_request_jni_async_callback *callback = aws_mem_acquire(allocator, sizeof(struct http_request_jni_async_callback));
    if (!callback) {
        /* caller will throw when they get a null */
        return NULL;
    }

    JNIEnv *env = aws_jni_get_thread_env(connection->jvm);
    callback->connection = connection;

    // We need to call NewGlobalRef() on jobjects that we want to last after this native method returns to Java.
    // Otherwise Java's GC may free the jobject when Native still has a reference to it.
    callback->jni_http_callback_handler = java_callback_handler ? (*env)->NewGlobalRef(env, java_callback_handler) : NULL;

    return callback;
}

/* JniHttpCallbackHandler Java Methods */
static struct {
    jmethodID onHeaders;
    jmethodID onHeadersDone;
    jmethodID onResponseBody;
    jmethodID onResponseComplete;
    jmethodID sendOutgoingBody;
} s_jni_http_callback_handler;

void s_cache_http_response_handler(JNIEnv *env) {
    jclass cls = (*env)->FindClass(env, "software/amazon/awssdk/crt/http/HttpConnection$JniHttpCallbackHandler");
    assert(cls);
    s_jni_http_callback_handler.onHeaders = (*env)->GetMethodID(env, cls, "onHeaders", "(I;[Lsoftware/amazon/awssdk/crt/http/HttpRequest$Header)V");
    assert(s_jni_http_callback_handler.onHeaders);

    s_jni_http_callback_handler.onHeadersDone = (*env)->GetMethodID(env, cls, "onHeadersDone", "([B)V");
    assert(s_jni_http_callback_handler.onHeadersDone);

    s_jni_http_callback_handler.onResponseBody = (*env)->GetMethodID(env, cls, "onResponseBody", "([B)I");
    assert(s_jni_http_callback_handler.onResponseBody);

    s_jni_http_callback_handler.onResponseComplete = (*env)->GetMethodID(env, cls, "onResponseComplete", "([B)V");
    assert(s_jni_http_callback_handler.onResponseComplete);

    s_jni_http_callback_handler.sendOutgoingBody = (*env)->GetMethodID(env, cls, "sendOutgoingBody", "(Ljava/nio/ByteBuffer)Z");
    assert(s_jni_http_callback_handler.sendOutgoingBody);
}

static struct {
    jclass header_class;
    jmethodID constructor;
    jfieldID name;
    jfieldID value;
} s_http_header_handler;

void s_cache_http_header_handler(JNIEnv *env) {
    jclass cls = (*env)->FindClass(env, "software/amazon/awssdk/crt/http/HttpRequest$Header");
    assert(cls);
    s_http_header_handler.header_class = cls;

    s_http_header_handler.constructor = (*env)->GetMethodID(env, cls, "<init>", "()V");
    assert(s_http_header_handler.constructor);

    s_http_header_handler.name = (*env)->GetFieldID(env, cls, "name", "[B;");
    assert(s_http_header_handler.name);

    s_http_header_handler.value = (*env)->GetFieldID(env, cls, "value", "[B;");
    assert(s_http_header_handler.value);

    // FindClass() returns local JNI references that become eligible for GC once this native method returns to Java.
    // Call NewGlobalRef() so that this class reference doesn't get Garbage collected.
    s_http_header_handler.header_class = (*env)->NewGlobalRef(env, s_http_header_handler.header_class);
}

static void s_on_http_conn_setup(struct aws_http_connection *connection, int error_code, void *user_data) {
    struct http_jni_connection *http_jni_conn = (struct http_jni_connection *) user_data;

    // Save the native pointer
    http_jni_conn->native_http_conn = connection;

    // Call the Java Object's "onComplete" callback
    if (http_jni_conn->java_http_conn) {
        JNIEnv *env = aws_jni_get_thread_env(http_jni_conn->jvm);
        (*env)->CallVoidMethod(
            env, http_jni_conn->java_http_conn, s_http_connection.on_connection_complete, error_code);
    }

}

static void s_on_http_conn_shutdown(struct aws_http_connection *connection, int error_code, void *user_data) {
    struct http_jni_connection *http_jni_conn = (struct http_jni_connection *) user_data;

    // Call the Java Object's "onShutdown" callback
    if (http_jni_conn->java_http_conn) {
        JNIEnv *env = aws_jni_get_thread_env(http_jni_conn->jvm);
        (*env)->CallVoidMethod(
            env, http_jni_conn->java_http_conn, s_http_connection.on_connection_shutdown, error_code);
    }

}

/**
 * Create a new aws_http_request_options struct with default values
 */
JNIEXPORT long JNICALL
    Java_software_amazon_awssdk_crt_http_HttpConnection_httpConnectionNew(JNIEnv *env,
                                                                          jclass jni_class,
                                                                          jobject http_conn_jobject,
                                                                          jlong jni_client_bootstrap,
                                                                          jlong jni_socket_options,
                                                                          jlong jni_tls_ctx,
                                                                          jstring jni_endpoint,
                                                                          jshort jni_port) {

    struct aws_client_bootstrap *client_bootstrap = (struct aws_client_bootstrap *) jni_client_bootstrap;
    struct aws_socket_options *socket_options = (struct aws_socket_options *) jni_socket_options;
    struct aws_tls_ctx *tls_ctx = (struct aws_tls_ctx *) jni_tls_ctx;

    if (!jni_client_bootstrap) {
        aws_jni_throw_runtime_exception(env, "HttpConnection.httpConnectionNew: jni_client_bootstrap must not be null");
        return (jlong) NULL;
    }

    if (!jni_socket_options) {
        aws_jni_throw_runtime_exception(env, "HttpConnection.httpConnectionNew: jni_socket_options must not be null");
        return (jlong) NULL;
    }

    struct aws_allocator *allocator = aws_jni_get_allocator();
    struct aws_byte_cursor endpoint = aws_jni_byte_cursor_from_jstring(env, jni_endpoint);
    uint16_t port = jni_port;

    if (port == 0) {
        aws_jni_throw_runtime_exception(env, "HttpConnection.httpConnectionNew: Port must be between 1 and 65535");
        return (jlong) NULL;
    }

    // TODO: Better TLS Detection?
    int use_tls = !(port == 80 || port == 8080);
    struct aws_tls_connection_options tls_conn_options = {0};
    if (use_tls) {
        if (!jni_tls_ctx) {
            aws_jni_throw_runtime_exception(env, "HttpConnection.httpConnectionNew: jni_tls_ctx_options must not be null");
            return (jlong) NULL;
        }

        aws_tls_connection_options_init_from_ctx(&tls_conn_options, tls_ctx);
        aws_tls_connection_options_set_server_name(&tls_conn_options, allocator, &endpoint);
    }

    struct http_jni_connection *http_jni_conn = aws_mem_acquire(allocator, sizeof(struct http_jni_connection));
    if (!http_jni_conn) {
        aws_jni_throw_runtime_exception(env, "HttpConnection.httpConnectionNew: Out of memory allocating JNI connection");
        goto error_cleanup;
    }

    // Create a new reference to the HttpConnection Object.
    http_jni_conn->java_http_conn = (*env)->NewGlobalRef(env, http_conn_jobject);

    // GetJavaVM() reference doesn't need a NewGlobalRef() call since it's global by default
    jint jvmresult = (*env)->GetJavaVM(env, &http_jni_conn->jvm);
    (void)jvmresult;
    assert(jvmresult == 0);

    struct aws_http_client_connection_options http_options = {
            .allocator = allocator,
            .bootstrap = client_bootstrap,
            .host_name = endpoint,
            .port = port,
            .socket_options = socket_options,
            .tls_options = &tls_conn_options,
            .user_data = http_jni_conn,
            .on_setup = s_on_http_conn_setup,
            .on_shutdown = s_on_http_conn_shutdown
    };
    aws_http_client_connect(&http_options);

    if (use_tls) {
        aws_tls_connection_options_clean_up(&tls_conn_options);
    }

    return (jlong) http_jni_conn;

 error_cleanup:
        if (http_jni_conn) {
            aws_mem_release(allocator, http_jni_conn);
        }

        return (jlong) NULL;
}

JNIEXPORT void JNICALL
    Java_software_amazon_awssdk_crt_http_HttpConnection_httpConnectionClose(JNIEnv *env,
                                                                            jclass jni_class,
                                                                            jlong jni_connection) {
    struct http_jni_connection *http_jni_conn = (struct http_jni_connection *) jni_connection;

    http_jni_conn->disconnect_requested = true;
    aws_http_connection_close(http_jni_conn->native_http_conn);
}

JNIEXPORT void JNICALL
    Java_software_amazon_awssdk_crt_http_HttpConnection_httpConnectionDestroy(JNIEnv *env,
                                                                              jclass jni_class,
                                                                              jlong jni_connection) {
    struct http_jni_connection *http_jni_conn = (struct http_jni_connection *) jni_connection;
    if (http_jni_conn->java_http_conn) {
        // Delete our reference to the HttpConnection Object from the JVM.
        (*env)->DeleteGlobalRef(env, http_jni_conn->java_http_conn);
        http_jni_conn->java_http_conn = NULL;
    }
    struct aws_allocator *allocator = aws_jni_get_allocator();
    aws_mem_release(allocator, http_jni_conn);
}

static jobjectArray s_java_headers_array_from_native(struct http_request_jni_async_callback *callback,
                                                     const struct aws_http_header *header_array,
                                                     size_t num_headers) {

    JNIEnv *env = aws_jni_get_thread_env(callback->connection->jvm);
    jobjectArray myArray = (*env)->NewObjectArray(env, (jsize) num_headers, s_http_header_handler.header_class, NULL);

    for (int i = 0; i < num_headers; i++) {
        jobject jHeader = (*env)->NewObject(env, s_http_header_handler.header_class, s_http_header_handler.constructor);

        jbyteArray actual_name = aws_jni_byte_array_from_cursor(env, &(header_array[i].name));
        jbyteArray actual_value = aws_jni_byte_array_from_cursor(env, &(header_array[i].value));

        // Overwrite with actual values
        (*env)->SetObjectField(env, jHeader, s_http_header_handler.name, actual_name);
        (*env)->SetObjectField(env, jHeader, s_http_header_handler.value, actual_value);
    }

    return myArray;
}

static void s_on_incoming_headers_fn(struct aws_http_stream *stream,
                                     const struct aws_http_header *header_array,
                                     size_t num_headers,
                                     void *user_data) {
    struct http_request_jni_async_callback *callback = (struct http_request_jni_async_callback *) user_data;
    JNIEnv *env = aws_jni_get_thread_env(callback->connection->jvm);
    jobjectArray jHeaders = s_java_headers_array_from_native(user_data, header_array, num_headers);

    int resp_status;
    int err_code = aws_http_stream_get_incoming_response_status(stream, &resp_status);

    if (err_code) {
        // TODO: Is this valid?
        (*env)->CallVoidMethod(env, callback->jni_http_callback_handler, s_jni_http_callback_handler.onResponseComplete, err_code);
    }

    (*env)->CallVoidMethod(env, callback->jni_http_callback_handler, s_jni_http_callback_handler.onHeaders, resp_status, jHeaders);
}

static void s_on_incoming_header_block_done_fn(struct aws_http_stream *stream, bool has_body, void *user_data) {
    struct http_request_jni_async_callback *callback = (struct http_request_jni_async_callback *) user_data;
    JNIEnv *env = aws_jni_get_thread_env(callback->connection->jvm);

    jboolean jHasBody = has_body;
    (*env)->CallVoidMethod(env, callback->jni_http_callback_handler, s_jni_http_callback_handler.onHeadersDone, jHasBody);

}

static void s_on_stream_complete_fn(struct aws_http_stream *stream, int error_code, void *user_data) {

    struct http_request_jni_async_callback *callback = (struct http_request_jni_async_callback *) user_data;
    JNIEnv *env = aws_jni_get_thread_env(callback->connection->jvm);

    jint jErrorCode = error_code;
    (*env)->CallVoidMethod(env, callback->jni_http_callback_handler, s_jni_http_callback_handler.onResponseComplete, jErrorCode);
}

static void s_on_incoming_body_fn(struct aws_http_stream *stream,
                                  const struct aws_byte_cursor *data,
                                  /* NOLINTNEXTLINE(readability-non-const-parameter) */
                                  size_t *out_window_update_size,
                                  void *user_data) {

    struct http_request_jni_async_callback *callback = (struct http_request_jni_async_callback *) user_data;
    JNIEnv *env = aws_jni_get_thread_env(callback->connection->jvm);

    // TODO: Use DirectByteBuffer instead of copy?
    jobject jByteBuffer = jni_byte_buffer_copy_from_cursor(env, data);


    (*env)->CallVoidMethod(env, callback->jni_http_callback_handler, s_jni_http_callback_handler.onResponseBody, jByteBuffer);

    // TODO: Check actual bytes read from ByteBuffer, so we know how to update out_window_update_size
}

enum aws_http_outgoing_body_state s_stream_outgoing_body_fn(struct aws_http_stream *stream,
                                                            struct aws_byte_buf *buf,
                                                            void *user_data) {
    struct http_request_jni_async_callback *callback = (struct http_request_jni_async_callback *) user_data;
    JNIEnv *env = aws_jni_get_thread_env(callback->connection->jvm);

    struct aws_byte_cursor cursor = aws_byte_cursor_from_buf(buf);

    // TODO: Create new ByteBuffer instead of Direct?
    jobject jDirectByteBuffer = jni_direct_byte_buffer_from_cursor(env, &cursor);

    jboolean isDone = (*env)->CallBooleanMethod(env, callback->jni_http_callback_handler,
                                                s_jni_http_callback_handler.sendOutgoingBody, jDirectByteBuffer);

    // TODO: Make s_java_byte_buffer extern
    //jint position = (*env)->CallIntMethod(env, jDirectByteBuffer, s_java_byte_buffer.get_position);
   // buf->len = position;

    if (isDone) {
        return AWS_HTTP_OUTGOING_BODY_DONE;
    }

    return AWS_HTTP_OUTGOING_BODY_IN_PROGRESS;
}

JNIEXPORT void JNICALL
    Java_software_amazon_awssdk_crt_http_HttpConnection_httpConnectionExecuteRequest(JNIEnv *env,
                                                                                     jclass jni_class,
                                                                                     jlong jni_connection,
                                                                                     jstring jni_method,
                                                                                     jstring jni_uri,
                                                                                     jobjectArray jni_headers,
                                                                                     jobject jni_callback_handler) {

    struct http_jni_connection *http_jni_conn = (struct http_jni_connection *) jni_connection;

    if (!http_jni_conn) {
       aws_jni_throw_runtime_exception(env, "HttpConnection.ExecuteRequest: Invalid connection");
       return;
   }

    if (!jni_callback_handler) {
        aws_jni_throw_runtime_exception(env, "HttpConnection.ExecuteRequest: Invalid handler");
        return ;
    }

    struct http_request_jni_async_callback *callback_handler = jni_http_request_async_callback_new(http_jni_conn, jni_callback_handler);

    if (!callback_handler) {
        aws_jni_throw_runtime_exception(env, "HttpConnection.ExecuteRequest: Unable to allocate handler");
        return;
    }

    struct aws_byte_cursor method = aws_jni_byte_cursor_from_jstring(env, jni_method);
    struct aws_byte_cursor uri = aws_jni_byte_cursor_from_jstring(env, jni_uri);
    jsize num_headers = (*env)->GetArrayLength(env, jni_headers);

    struct aws_http_header headers[num_headers];
    AWS_ZERO_ARRAY(headers);

    for (int i = 0; i < num_headers; i++) {
        jobject jHeader = (*env)->GetObjectArrayElement(env, jni_headers, i);
        jbyteArray jname = (*env)->GetObjectField(env, jHeader, s_http_header_handler.name);
        jbyteArray jvalue = (*env)->GetObjectField(env, jHeader, s_http_header_handler.value);

        headers[i].name = aws_jni_byte_cursor_from_jbyteArray(env, jname);
        headers[i].value = aws_jni_byte_cursor_from_jbyteArray(env, jvalue);
    }

    struct aws_http_request_options request_options = AWS_HTTP_REQUEST_OPTIONS_INIT;
    request_options.method = method;
    request_options.uri = uri;
    request_options.header_array = headers;
    request_options.num_headers = num_headers;

    // Set Callbacks
    request_options.on_response_headers = s_on_incoming_headers_fn;
    request_options.on_response_header_block_done = s_on_incoming_header_block_done_fn;
    request_options.on_response_body = s_on_incoming_body_fn;
    request_options.stream_outgoing_body = s_stream_outgoing_body_fn;
    request_options.on_complete = s_on_stream_complete_fn;
    request_options.user_data = callback_handler;

    aws_http_stream_new_client_request(&request_options);

}

