// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
#include <jni.h>
#include <jvmti.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

static jvmtiEnv *jvmti;
static FILE *out;
static jrawMonitorID lock;
static _Atomic uint64_t sequence;
static char include_prefix[512] = "Lorg/springframework/";

static void json_string(FILE *stream, const char *value) {
    fputc('"', stream);
    for (const unsigned char *p = (const unsigned char *)(value ? value : ""); *p; ++p) {
        switch (*p) {
            case '"': fputs("\\\"", stream); break;
            case '\\': fputs("\\\\", stream); break;
            case '\n': fputs("\\n", stream); break;
            case '\r': fputs("\\r", stream); break;
            case '\t': fputs("\\t", stream); break;
            default: if (*p < 0x20) fprintf(stream, "\\u%04x", *p); else fputc(*p, stream);
        }
    }
    fputc('"', stream);
}

static int included(const char *signature) {
    char copy[512];
    snprintf(copy, sizeof(copy), "%s", include_prefix);
    for (char *save = NULL, *token = strtok_r(copy, "|", &save); token; token = strtok_r(NULL, "|", &save))
        if (strncmp(signature, token, strlen(token)) == 0) return 1;
    return 0;
}

static void JNICALL method_entry(jvmtiEnv *env, JNIEnv *jni, jthread thread, jmethodID method) {
    (void)jni;
    jclass declaring;
    char *class_signature = NULL, *name = NULL, *descriptor = NULL;
    if ((*env)->GetMethodDeclaringClass(env, method, &declaring) != JVMTI_ERROR_NONE ||
        (*env)->GetClassSignature(env, declaring, &class_signature, NULL) != JVMTI_ERROR_NONE) return;
    if (!included(class_signature)) { (*env)->Deallocate(env, (unsigned char *)class_signature); return; }
    if ((*env)->GetMethodName(env, method, &name, &descriptor, NULL) != JVMTI_ERROR_NONE) goto cleanup;
    jvmtiThreadInfo info;
    memset(&info, 0, sizeof(info));
    (*env)->GetThreadInfo(env, thread, &info);
    struct timespec mono, wall;
    clock_gettime(CLOCK_MONOTONIC, &mono);
    clock_gettime(CLOCK_REALTIME, &wall);
    uint64_t thread_id = 0;
    pthread_threadid_np(NULL, &thread_id);
    uint64_t seq = atomic_fetch_add(&sequence, 1) + 1;
    (*env)->RawMonitorEnter(env, lock);
    fprintf(out, "{\"sequence\":%llu,\"monotonicNanos\":%llu,\"wallClockNanos\":%llu,\"nativeThreadId\":%llu,\"threadName\":",
            (unsigned long long)seq,
            (unsigned long long)mono.tv_sec * 1000000000ULL + mono.tv_nsec,
            (unsigned long long)wall.tv_sec * 1000000000ULL + wall.tv_nsec,
            (unsigned long long)thread_id);
    json_string(out, info.name);
    fputs(",\"classSignature\":", out); json_string(out, class_signature);
    fputs(",\"methodName\":", out); json_string(out, name);
    fputs(",\"descriptor\":", out); json_string(out, descriptor);
    fputs("}\n", out);
    fflush(out);
    (*env)->RawMonitorExit(env, lock);
    if (info.name) (*env)->Deallocate(env, (unsigned char *)info.name);
cleanup:
    if (class_signature) (*env)->Deallocate(env, (unsigned char *)class_signature);
    if (name) (*env)->Deallocate(env, (unsigned char *)name);
    if (descriptor) (*env)->Deallocate(env, (unsigned char *)descriptor);
}

JNIEXPORT jint JNICALL Agent_OnLoad(JavaVM *vm, char *options, void *reserved) {
    (void)reserved;
    if ((*vm)->GetEnv(vm, (void **)&jvmti, JVMTI_VERSION_1_2) != JNI_OK) return JNI_ERR;
    char output[1024] = "task19-oracle-method-events.jsonl";
    if (options) {
        char *copy = strdup(options);
        for (char *save = NULL, *token = strtok_r(copy, ",", &save); token; token = strtok_r(NULL, ",", &save)) {
            if (strncmp(token, "output=", 7) == 0) snprintf(output, sizeof(output), "%s", token + 7);
            else if (strncmp(token, "include=", 8) == 0) snprintf(include_prefix, sizeof(include_prefix), "%s", token + 8);
        }
        free(copy);
    }
    out = fopen(output, "w");
    if (!out) return JNI_ERR;
    (*jvmti)->CreateRawMonitor(jvmti, "task19-oracle-output", &lock);
    jvmtiCapabilities capabilities;
    memset(&capabilities, 0, sizeof(capabilities));
    capabilities.can_generate_method_entry_events = 1;
    if ((*jvmti)->AddCapabilities(jvmti, &capabilities) != JVMTI_ERROR_NONE) return JNI_ERR;
    jvmtiEventCallbacks callbacks;
    memset(&callbacks, 0, sizeof(callbacks));
    callbacks.MethodEntry = &method_entry;
    (*jvmti)->SetEventCallbacks(jvmti, &callbacks, sizeof(callbacks));
    if ((*jvmti)->SetEventNotificationMode(jvmti, JVMTI_ENABLE, JVMTI_EVENT_METHOD_ENTRY, NULL) != JVMTI_ERROR_NONE) return JNI_ERR;
    return JNI_OK;
}

JNIEXPORT void JNICALL Agent_OnUnload(JavaVM *vm) {
    (void)vm;
    if (out) fclose(out);
}
