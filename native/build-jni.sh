#!/usr/bin/env bash
# Build the embedded portmux JNI library (libportmuxjni) and place it in
# src/main/resources/native/<os>-<arch>/ so it is bundled into the JAR and
# loaded at runtime. No separate portmux install is required.
#
# The portmux core (v0.5.0+) is built from the vendored submodule via its own
# Makefile, which compiles the full library: port forwarding, the SOCKS5 proxy
# (portmux-socks.c), the encrypted NoisePSK_NN tunnel (portmux-tunnel.c +
# vendored noise-c), and hev-task-system. The JNI shim (native/portmux-jni.c)
# is then linked against libportmux.a + libhev-task-system.a.
#
# Usage:
#   JAVA_HOME=/path/to/jdk ./native/build-jni.sh [ARCH]
#     ARCH (optional): x86_64 (default: host arch) or aarch64.
#       aarch64 cross-builds via aarch64-linux-gnu-gcc when the host is x86_64.
#
# Requires: JAVA_HOME (for jni.h), a C compiler (plus aarch64-linux-gnu-gcc for
#           cross builds), make, and the portmux submodule checked out at a
#           version that has portmux-socks.c / portmux-tunnel.c / third-part/noise-c.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PORTMUX="$ROOT/native/portmux"
HTS="$PORTMUX/third-part/hev-task-system"

if [ -z "${JAVA_HOME:-}" ]; then
  echo "ERROR: set JAVA_HOME (needed for jni.h)" >&2
  exit 1
fi
if [ ! -f "$PORTMUX/include/portmux.h" ]; then
  echo "ERROR: portmux submodule missing; run: git submodule update --init --recursive" >&2
  exit 1
fi
if [ ! -f "$PORTMUX/src/portmux-tunnel.c" ] || [ ! -f "$PORTMUX/src/portmux-socks.c" ]; then
  echo "ERROR: portmux submodule too old (no socks/tunnel sources); check out v0.5.0+" >&2
  exit 1
fi

# OS detection (resource folder matches NativeLoader's lookup).
uname_s="$(uname -s)"
uname_m="$(uname -m)"
case "$uname_s" in
  Linux)  os=linux;  ext=so  ;;
  Darwin) os=macos;  ext=dylib ;;
  *) echo "unsupported OS: $uname_s" >&2; exit 1 ;;
esac

# Target arch: explicit arg, else host.
case "${1:-$uname_m}" in
  x86_64|amd64) arch=x86_64 ;;
  aarch64|arm64) arch=aarch64 ;;
  *) echo "unsupported arch: ${1:-$uname_m}" >&2; exit 1 ;;
esac

# Cross-compile prefix: when building aarch64 on an x86_64 host, use the
# gnu cross toolchain. Native builds use the default compiler.
CROSS_PREFIX=""
if [ "$arch" != "$( case "$uname_m" in x86_64|amd64) echo x86_64;; aarch64|arm64) echo aarch64;; *) echo "$uname_m";; esac )" ]; then
  if [ "$os" = "linux" ] && [ "$arch" = "aarch64" ]; then
    CROSS_PREFIX="aarch64-linux-gnu-"
  else
    echo "ERROR: no cross toolchain known for $os-$arch on host $uname_m" >&2
    exit 1
  fi
fi
CC="${CC:-${CROSS_PREFIX}cc}"
# cc may not be provided by the cross toolchain; fall back to gcc.
command -v "$CC" >/dev/null 2>&1 || CC="${CROSS_PREFIX}gcc"

echo "== target: $os-$arch  CC=$CC  CROSS_PREFIX='${CROSS_PREFIX}' =="

OUTDIR="$ROOT/src/main/resources/native/$os-$arch"
mkdir -p "$OUTDIR"

# Build everything (-fPIC) via the portmux Makefile: hev-task-system static,
# then libportmux.a (includes socks + tunnel + noise-c objects). A clean build
# avoids object-arch mismatches when switching between host and cross targets.
echo "== building portmux core (static, -fPIC) for $arch =="
make -C "$PORTMUX" clean >/dev/null 2>&1 || true
# hev-task-system static must itself be -fPIC (the portmux Makefile's hts rule
# does not forward CFLAGS), so build it directly first.
make -C "$HTS" static CROSS_PREFIX="$CROSS_PREFIX" CFLAGS="-fPIC"
make -C "$PORTMUX" bin/libportmux.a \
  CROSS_PREFIX="$CROSS_PREFIX" CFLAGS="-fPIC"

# JNI include dir for this OS.
JNI_INC_OS="$os"
[ "$os" = "macos" ] && JNI_INC_OS="darwin"
SHARED_FLAGS="-shared"
[ "$os" = "macos" ] && SHARED_FLAGS="-dynamiclib"

echo "== linking libportmuxjni.$ext =="
$CC $SHARED_FLAGS -fPIC -O2 \
  -I"$JAVA_HOME/include" -I"$JAVA_HOME/include/$JNI_INC_OS" \
  -I"$PORTMUX/include" -I"$HTS/include" \
  "$ROOT/native/portmux-jni.c" \
  "$PORTMUX/bin/libportmux.a" \
  "$HTS/bin/libhev-task-system.a" \
  -lpthread \
  -o "$OUTDIR/libportmuxjni.$ext"

echo "== done: $OUTDIR/libportmuxjni.$ext =="
ls -l "$OUTDIR/libportmuxjni.$ext"
file "$OUTDIR/libportmuxjni.$ext" || true
