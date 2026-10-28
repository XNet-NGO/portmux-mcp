/*
 ============================================================================
 Name        : portmux-jni.c
 Description : JVM JNI bridge embedding the portmux engine into portmux-mcp.
 *
 * Exposes to Kotlin (org.xnet.portmuxmcp.NativePortmuxEngine) as INSTANCE
 * methods:
 *
 *   nativeNew()                                                      -> long
 *   nativeAddRoute(h, proto, la, lp, ta, tp, egressKind, egressArg)  -> int
 *   nativeAddSocks(h, la, lp, egressKind, egressArg)                 -> int
 *   nativeAddTunnelClient(h, sa, sp, ta, tp, psk, cipher,
 *                         egressKind, egressArg,
 *                         rla, rlp, rta, rtp, rproto)                -> int
 *   nativeAddTunnelPeer(h, la, lp, psk, cipher, egressKind, egressArg) -> int
 *   nativeAddTunnelServer(h, la, lp, ba, bp, psk, cipher)             -> int
 *   nativeSetJumps(h, hopsAddrCsv, hopsPortCsv, ta, tp, proto)       -> int
 *   nativeRun(h)                                     (BLOCKS - worker thread)
 *   nativeStop(h)
 *   nativeDestroy(h)
 *
 * portmux_run() blocks and the hev-task scheduler is thread-local, so Kotlin
 * must call nativeRun on a dedicated thread, after all addRoute calls.
 *
 * Egress (DESKTOP subset): egressKind
 *   0 NONE, 1 SOURCE (egressArg = source IP, bind, no privilege),
 *   2 DEVICE (egressArg = interface name, SO_BINDTODEVICE, needs CAP_NET_RAW).
 * switch-mcp's Android CALLBACK (android_setsocknetwork) pin is intentionally
 * NOT built here — on desktop interface pinning is DEVICE or SOURCE.
 ============================================================================
 */

#include <jni.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>

#include "portmux.h"
#include "portmux-tunnel.h"

static const char *
jstr (JNIEnv *env, jstring s, char *buf, size_t n)
{
    if (!s) { buf[0] = '\0'; return buf; }
    const char *c = (*env)->GetStringUTFChars (env, s, NULL);
    if (!c) { buf[0] = '\0'; return buf; }
    strncpy (buf, c, n - 1);
    buf[n - 1] = '\0';
    (*env)->ReleaseStringUTFChars (env, s, c);
    return buf;
}

/* Build a PortmuxEgress from the desktop egress surface. *egp points at @eg
   (populated) or is NULL for PORTMUX_EGRESS_NONE. portmux keeps our string
   pointers by reference, so these small strdup()s are intentionally kept for
   the life of the forwarder.
   egressKind: 0 NONE, 1 SOURCE (arg = source IP), 2 DEVICE (arg = ifname). */
static void
build_egress (jint egressKind, const char *arg,
              PortmuxEgress *eg, PortmuxEgress **egp)
{
    memset (eg, 0, sizeof *eg);

    switch (egressKind) {
    case 1:
        eg->kind = PORTMUX_EGRESS_SOURCE;
        eg->source_ip = (arg && arg[0]) ? strdup (arg) : NULL;
        *egp = eg;
        break;
    case 2:
        eg->kind = PORTMUX_EGRESS_DEVICE;
        eg->device = (arg && arg[0]) ? strdup (arg) : NULL;
        *egp = eg;
        break;
    default:
        *egp = NULL; /* NONE */
    }
}

/* Parse a 64-hex-char string into 32 PSK bytes. Returns 0 on success. */
static int
parse_psk_hex (const char *hex, unsigned char out[PM_TUNNEL_PSK_LEN])
{
    if (!hex) return -1;
    size_t n = strlen (hex);
    if (n != PM_TUNNEL_PSK_LEN * 2) return -1;
    for (int i = 0; i < PM_TUNNEL_PSK_LEN; i++) {
        unsigned v;
        if (sscanf (hex + i * 2, "%2x", &v) != 1) return -1;
        out[i] = (unsigned char)v;
    }
    return 0;
}

JNIEXPORT jlong JNICALL
Java_org_xnet_portmuxmcp_NativePortmuxEngine_nativeNew (JNIEnv *env, jobject o)
{
    (void)env; (void)o;
    return (jlong)(intptr_t)portmux_new ();
}

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

    char la[128], ta[128], arg[128];
    jstr (env, listenAddr, la, sizeof la);
    jstr (env, targetAddr, ta, sizeof ta);
    jstr (env, egressArg, arg, sizeof arg);

    PortmuxEgress eg;
    PortmuxEgress *egp = NULL;
    build_egress (egressKind, arg, &eg, &egp);

    PortmuxProto p = (proto == 1) ? PORTMUX_PROTO_UDP : PORTMUX_PROTO_TCP;
    return portmux_add_route (pm, p,
                              la[0] ? la : "127.0.0.1", (int)listenPort,
                              ta[0] ? ta : "127.0.0.1", (int)targetPort,
                              egp);
}

/* SOCKS5 proxy listener. No fixed target; every upstream socket the proxy opens
   passes through this egress, so per-connection egress pinning applies. */
JNIEXPORT jint JNICALL
Java_org_xnet_portmuxmcp_NativePortmuxEngine_nativeAddSocks (
    JNIEnv *env, jobject o, jlong h,
    jstring listenAddr, jint listenPort,
    jint egressKind, jstring egressArg)
{
    (void)o;
    Portmux *pm = (Portmux *)(intptr_t)h;
    if (!pm) return -1;

    char la[128], arg[128];
    jstr (env, listenAddr, la, sizeof la);
    jstr (env, egressArg, arg, sizeof arg);

    PortmuxEgress eg;
    PortmuxEgress *egp = NULL;
    build_egress (egressKind, arg, &eg, &egp);

    return portmux_add_socks (pm, la[0] ? la : "127.0.0.1", (int)listenPort, egp);
}

/* Encrypted tunnel CLIENT: dial serverAddr:serverPort over NoisePSK_NN and
   splice to targetAddr:targetPort (e.g. 127.0.0.1:5555 adbd). pskHex is 64 hex
   chars (32 bytes). cipher: 0=AES-GCM, 1=ChaCha20-Poly1305. The outbound leg
   honors the egress.

   REVERSE-FORWARD (v0.4.0): rlistenAddr/rlistenPort open a REVERSE LISTENER on
   the client (when rlistenPort>0): connections there become client-originated
   reverse streams the server splices to rtargetAddr:rtargetPort. rproto sets the
   reverse listener protocol: 0=TCP, 1=UDP, 2=BOTH (dual-stack TCP+UDP). */
JNIEXPORT jint JNICALL
Java_org_xnet_portmuxmcp_NativePortmuxEngine_nativeAddTunnelClient (
    JNIEnv *env, jobject o, jlong h,
    jstring serverAddr, jint serverPort,
    jstring targetAddr, jint targetPort,
    jstring pskHex, jint cipher,
    jint egressKind, jstring egressArg,
    jstring rlistenAddr, jint rlistenPort,
    jstring rtargetAddr, jint rtargetPort, jint rproto)
{
    (void)o;
    Portmux *pm = (Portmux *)(intptr_t)h;
    if (!pm) return -1;

    char sa[128], ta[128], arg[128], pk[128];
    char rla[128], rta[128];
    jstr (env, serverAddr, sa, sizeof sa);
    jstr (env, targetAddr, ta, sizeof ta);
    jstr (env, egressArg, arg, sizeof arg);
    jstr (env, pskHex, pk, sizeof pk);
    jstr (env, rlistenAddr, rla, sizeof rla);
    jstr (env, rtargetAddr, rta, sizeof rta);

    unsigned char psk[PM_TUNNEL_PSK_LEN];
    if (parse_psk_hex (pk, psk) < 0)
        return -1;

    PortmuxEgress eg;
    PortmuxEgress *egp = NULL;
    build_egress (egressKind, arg, &eg, &egp);

    PmTunnelCipher cph = (cipher == 1) ? PM_TUNNEL_CIPHER_CHACHAPOLY
                                       : PM_TUNNEL_CIPHER_AESGCM;

    /* Pass rlistenAddr/rlistenPort as the client listen_addr/listen_port when
       rlistenPort>0 (else NULL,0 for a traditional forward-only tunnel). */
    const char *clis_addr = (rlistenPort > 0 && rla[0]) ? rla : NULL;
    int clis_port = (rlistenPort > 0) ? (int)rlistenPort : 0;

    int rc = portmux_add_tunnel_client (pm, clis_addr, clis_port,
                                        sa, (int)serverPort,
                                        ta[0] ? ta : "127.0.0.1", (int)targetPort,
                                        psk, cph, egp);

    /* Configure reverse target and protocol if a reverse listener was requested. */
    if (rc == 0 && rtargetPort > 0) {
        int r2 = portmux_tunnel_set_reverse_target (pm, rta[0] ? rta : "127.0.0.1",
                                                    (int)rtargetPort);
        if (r2 != 0) rc = r2;
    }
    if (rc == 0 && rlistenPort > 0) {
        int r3 = portmux_tunnel_set_reverse_proto (pm, (int)rproto);
        if (r3 != 0) rc = r3;
    }

    memset (psk, 0, sizeof psk);
    return rc;
}

/* Encrypted tunnel PEER (v0.5.0): a single-port peek-demux tunnel server that
   relays OPENJ multi-hop streams to the next hop (honoring egress) or connects
   the terminal target when no hops remain. Accepts encrypted carriers on
   listenAddr:listenPort. Shares the single psk/cipher with every peer in the
   chain. cipher: 0=AES-GCM, 1=ChaCha20-Poly1305. */
JNIEXPORT jint JNICALL
Java_org_xnet_portmuxmcp_NativePortmuxEngine_nativeAddTunnelPeer (
    JNIEnv *env, jobject o, jlong h,
    jstring listenAddr, jint listenPort,
    jstring pskHex, jint cipher,
    jint egressKind, jstring egressArg)
{
    (void)o;
    Portmux *pm = (Portmux *)(intptr_t)h;
    if (!pm) return -1;

    char la[128], arg[128], pk[128];
    jstr (env, listenAddr, la, sizeof la);
    jstr (env, egressArg, arg, sizeof arg);
    jstr (env, pskHex, pk, sizeof pk);

    unsigned char psk[PM_TUNNEL_PSK_LEN];
    if (parse_psk_hex (pk, psk) < 0)
        return -1;

    PortmuxEgress eg;
    PortmuxEgress *egp = NULL;
    build_egress (egressKind, arg, &eg, &egp);

    PmTunnelCipher cph = (cipher == 1) ? PM_TUNNEL_CIPHER_CHACHAPOLY
                                       : PM_TUNNEL_CIPHER_AESGCM;

    int rc = portmux_add_tunnel_peer (pm, la[0] ? la : "127.0.0.1",
                                      (int)listenPort, psk, cph, egp);

    memset (psk, 0, sizeof psk);
    return rc;
}

/* Encrypted tunnel SERVER: accept encrypted clients on listenAddr:listenPort
   (the Noise responder side) and bridge each accepted client to the local
   plaintext service bridgeAddr:bridgePort. pskHex is 64 hex chars (32 bytes),
   must match the client. cipher: 0=AES-GCM, 1=ChaCha20-Poly1305. The server
   role has no egress parameter in the core (it bridges to a local target). */
JNIEXPORT jint JNICALL
Java_org_xnet_portmuxmcp_NativePortmuxEngine_nativeAddTunnelServer (
    JNIEnv *env, jobject o, jlong h,
    jstring listenAddr, jint listenPort,
    jstring bridgeAddr, jint bridgePort,
    jstring pskHex, jint cipher)
{
    (void)o;
    Portmux *pm = (Portmux *)(intptr_t)h;
    if (!pm) return -1;

    char la[128], ba[128], pk[128];
    jstr (env, listenAddr, la, sizeof la);
    jstr (env, bridgeAddr, ba, sizeof ba);
    jstr (env, pskHex, pk, sizeof pk);

    unsigned char psk[PM_TUNNEL_PSK_LEN];
    if (parse_psk_hex (pk, psk) < 0)
        return -1;

    PmTunnelCipher cph = (cipher == 1) ? PM_TUNNEL_CIPHER_CHACHAPOLY
                                       : PM_TUNNEL_CIPHER_AESGCM;

    int rc = portmux_add_tunnel_server (pm, la[0] ? la : "0.0.0.0",
                                        (int)listenPort,
                                        ba[0] ? ba : "127.0.0.1",
                                        (int)bridgePort, psk, cph);

    memset (psk, 0, sizeof psk);
    return rc;
}

/* Parse a decimal int from [s, len); returns the value, defaulting to 0. */
static int
parse_int_field (const char *s, size_t len)
{
    char tmp[16];
    size_t n = len < sizeof tmp - 1 ? len : sizeof tmp - 1;
    memcpy (tmp, s, n);
    tmp[n] = '\0';
    return (int)strtol (tmp, NULL, 10);
}

/* Multi-hop jumps (v0.5.0): configure the EDGE originator's jump-list on the
   most-recently-added tunnel route (call right after the edge
   nativeAddTunnelClient). hopsAddrCsv / hopsPortCsv are comma-separated lists
   (same arity) of the ordered next-hops; hops[0] MUST equal that client's
   carrier dest. targetAddr:targetPort is the final destination; proto is the
   final leg (0=TCP, 1=UDP). nhops must be 1..PM_MUX_MAX_HOPS. */
JNIEXPORT jint JNICALL
Java_org_xnet_portmuxmcp_NativePortmuxEngine_nativeSetJumps (
    JNIEnv *env, jobject o, jlong h,
    jstring hopsAddrCsv, jstring hopsPortCsv,
    jstring targetAddr, jint targetPort, jint proto)
{
    (void)o;
    Portmux *pm = (Portmux *)(intptr_t)h;
    if (!pm) return -1;

    char addrs[512], ports[256], ta[128];
    jstr (env, hopsAddrCsv, addrs, sizeof addrs);
    jstr (env, hopsPortCsv, ports, sizeof ports);
    jstr (env, targetAddr, ta, sizeof ta);

    PmJumpEndpoint hops[PM_MUX_MAX_HOPS];
    memset (hops, 0, sizeof hops);
    int nhops = 0;

    /* Walk the two CSVs in lockstep. */
    const char *ap = addrs;
    const char *pp = ports;
    while (*ap && nhops < PM_MUX_MAX_HOPS) {
        const char *acomma = strchr (ap, ',');
        size_t alen = acomma ? (size_t)(acomma - ap) : strlen (ap);
        if (alen == 0) break;
        size_t acopy = alen < sizeof hops[nhops].addr - 1
                       ? alen : sizeof hops[nhops].addr - 1;
        memcpy (hops[nhops].addr, ap, acopy);
        hops[nhops].addr[acopy] = '\0';

        const char *pcomma = pp ? strchr (pp, ',') : NULL;
        size_t plen = pp ? (pcomma ? (size_t)(pcomma - pp) : strlen (pp)) : 0;
        hops[nhops].port = pp ? parse_int_field (pp, plen) : 0;

        nhops++;
        ap = acomma ? acomma + 1 : ap + alen;
        if (pp) pp = pcomma ? pcomma + 1 : pp + plen;
    }

    if (nhops < 1)
        return -1;

    return portmux_tunnel_set_jumps (pm, hops, nhops,
                                     ta[0] ? ta : "127.0.0.1",
                                     (int)targetPort, (int)proto);
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
