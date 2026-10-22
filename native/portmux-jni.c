/*
 ============================================================================
 Name        : portmux-jni.c
 Description : JVM JNI bridge embedding the portmux engine into portmux-mcp.
 *
 * Exposes to Kotlin (org.xnet.portmuxmcp.NativePortmuxEngine):
 *   nativeNew()                                   -> long handle
 *   nativeAddRoute(h, proto, la, lp, ta, tp, egressKind, egressArg) -> int
 *   nativeRun(h)                                  (BLOCKS - worker thread)
 *   nativeStop(h)
 *   nativeDestroy(h)
 *
 * portmux_run() blocks and the hev-task scheduler is thread-local, so Kotlin
 * must call nativeRun on a dedicated thread, after all addRoute calls.
 ============================================================================
 */

#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>

#include "portmux.h"

JNIEXPORT jlong JNICALL
Java_org_xnet_portmuxmcp_NativePortmuxEngine_nativeNew (JNIEnv *env, jobject o)
{
    (void)env; (void)o;
    return (jlong)(intptr_t)portmux_new ();
}

/* egressKind: 0 NONE, 1 SOURCE(egressArg=ip), 2 DEVICE(egressArg=ifname).
   CALLBACK is not exposed on the JVM backend (no fd-hook use case here). */
JNIEXPORT jint JNICALL
Java_org_xnet_portmuxmcp_NativePortmuxEngine_nativeAddRoute (
    JNIEnv *env, jobject o, jlong h, jint proto,
    jstring listenAddr, jint listenPort,
    jstring targetAddr, jint targetPort,
    jint egressKind, jstring egressArg)
{
    (void)o;
    Portmux *pm = (Portmux *)(intptr_t)h;
    if (!pm) return -1;

    const char *la = listenAddr ? (*env)->GetStringUTFChars (env, listenAddr, 0) : NULL;
    const char *ta = targetAddr ? (*env)->GetStringUTFChars (env, targetAddr, 0) : NULL;
    const char *arg = egressArg ? (*env)->GetStringUTFChars (env, egressArg, 0) : NULL;

    PortmuxEgress eg;
    PortmuxEgress *egp = NULL;
    memset (&eg, 0, sizeof eg);
    if (egressKind == 1 && arg) {
        eg.kind = PORTMUX_EGRESS_SOURCE;
        eg.source_ip = strdup (arg);  /* portmux keeps the pointer by reference */
        egp = &eg;
    } else if (egressKind == 2 && arg) {
        eg.kind = PORTMUX_EGRESS_DEVICE;
        eg.device = strdup (arg);
        egp = &eg;
    }

    PortmuxProto p = (proto == 1) ? PORTMUX_PROTO_UDP : PORTMUX_PROTO_TCP;
    int rc = portmux_add_route (pm, p,
                                la ? la : "127.0.0.1", (int)listenPort,
                                ta ? ta : "127.0.0.1", (int)targetPort,
                                egp);

    if (la) (*env)->ReleaseStringUTFChars (env, listenAddr, la);
    if (ta) (*env)->ReleaseStringUTFChars (env, targetAddr, ta);
    if (arg) (*env)->ReleaseStringUTFChars (env, egressArg, arg);
    return rc;
}

JNIEXPORT void JNICALL
Java_org_xnet_portmuxmcp_NativePortmuxEngine_nativeRun (JNIEnv *env, jobject o, jlong h)
{
    (void)env; (void)o;
    Portmux *pm = (Portmux *)(intptr_t)h;
    if (pm) portmux_run (pm); /* blocks until stop */
}

JNIEXPORT void JNICALL
Java_org_xnet_portmuxmcp_NativePortmuxEngine_nativeStop (JNIEnv *env, jobject o, jlong h)
{
    (void)env; (void)o;
    Portmux *pm = (Portmux *)(intptr_t)h;
    if (pm) portmux_stop (pm);
}

JNIEXPORT void JNICALL
Java_org_xnet_portmuxmcp_NativePortmuxEngine_nativeDestroy (JNIEnv *env, jobject o, jlong h)
{
    (void)env; (void)o;
    Portmux *pm = (Portmux *)(intptr_t)h;
    if (pm) portmux_destroy (pm);
}
