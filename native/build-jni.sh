#!/usr/bin/env bash
# Build the embedded portmux JNI library (libportmuxjni) and place it in
# src/main/resources/native/<os>-<arch>/ so it is bundled into the JAR and
# loaded at runtime. No separate portmux install is required.
#
# Requires: JAVA_HOME (for jni.h), a C compiler, and the portmux submodule.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PORTMUX="$ROOT/native/portmux"
HTS="$PORTMUX/third-part/hev-task-system"
CC="${CC:-cc}"

if [ -z "${JAVA_HOME:-}" ]; then
  echo "ERROR: set JAVA_HOME (needed for jni.h)" >&2
  exit 1
fi
if [ ! -f "$PORTMUX/include/portmux.h" ]; then
  echo "ERROR: portmux submodule missing; run: git submodule update --init --recursive" >&2
  exit 1
fi

# OS/arch resource folder (matches NativeLoader's lookup)
uname_s="$(uname -s)"
uname_m="$(uname -m)"
case "$uname_s" in
  Linux)  os=linux;  ext=so  ;;
  Darwin) os=macos;  ext=dylib ;;
  *) echo "unsupported OS: $uname_s" >&2; exit 1 ;;
esac
case "$uname_m" in
  x86_64|amd64) arch=x86_64 ;;
  aarch64|arm64) arch=aarch64 ;;
  *) echo "unsupported arch: $uname_m" >&2; exit 1 ;;
esac

OUTDIR="$ROOT/src/main/resources/native/$os-$arch"
mkdir -p "$OUTDIR"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

echo "== building hev-task-system (static, -fPIC) =="
make -C "$HTS" clean >/dev/null 2>&1 || true
make -C "$HTS" static CC="$CC" AR="${AR:-ar}" CFLAGS="-fPIC" >/dev/null

echo "== building portmux objects (-fPIC) =="
PM_SRCS="$PORTMUX/src/portmux.c $PORTMUX/src/portmux-egress.c $PORTMUX/src/portmux-addr.c"
PM_OBJS=""
for s in $PM_SRCS; do
  o="$WORK/$(basename "${s%.c}").o"
  $CC -O2 -fPIC -std=gnu11 \
    -I"$PORTMUX/include" -I"$PORTMUX/src" -I"$HTS/include" \
    -c "$s" -o "$o"
  PM_OBJS="$PM_OBJS $o"
done

echo "== linking libportmuxjni.$ext =="
JNI_INC_OS="$os"
[ "$os" = "macos" ] && JNI_INC_OS="darwin"
SHARED_FLAGS="-shared"
[ "$os" = "macos" ] && SHARED_FLAGS="-dynamiclib"

$CC $SHARED_FLAGS -fPIC -O2 \
  -I"$JAVA_HOME/include" -I"$JAVA_HOME/include/$JNI_INC_OS" \
  -I"$PORTMUX/include" -I"$HTS/include" \
  "$ROOT/native/portmux-jni.c" $PM_OBJS \
  "$HTS/bin/libhev-task-system.a" \
  -lpthread \
  -o "$OUTDIR/libportmuxjni.$ext"

echo "== done: $OUTDIR/libportmuxjni.$ext =="
ls -l "$OUTDIR/libportmuxjni.$ext"
file "$OUTDIR/libportmuxjni.$ext" || true
