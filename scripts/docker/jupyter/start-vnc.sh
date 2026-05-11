#!/bin/bash
# Sourced by jupyter docker-stacks' start-notebook.sh from
# /usr/local/bin/before-notebook.d/, so DISPLAY=:1 is inherited by the
# Jupyter server (and the kernels it spawns).
#
# Brings up the GUI streaming pipeline that Apache Guacamole connects to:
#   Xvfb :1   ──►  fluxbox (decorations/dragging)
#                  x11vnc (rfbport 5901, password-protected)

set -e

VNC_DISPLAY=":1"
VNC_PORT="${JUPYTER_VNC_PORT:-5901}"
VNC_PASSWORD="${JUPYTER_VNC_PASSWORD:-afon}"

# Idempotent: skip if a previous source already started Xvfb.
if pgrep -x Xvfb >/dev/null 2>&1; then
    export DISPLAY="${VNC_DISPLAY}"
    return 0 2>/dev/null || exit 0
fi

Xvfb "${VNC_DISPLAY}" -screen 0 1280x800x24 -ac +extension GLX +render -noreset \
    > /tmp/xvfb.log 2>&1 &

# Wait for the X socket so dependents don't race the server.
for _ in $(seq 1 20); do
    [ -e "/tmp/.X11-unix/X${VNC_DISPLAY#:}" ] && break
    sleep 0.1
done

DISPLAY="${VNC_DISPLAY}" fluxbox > /tmp/fluxbox.log 2>&1 &

mkdir -p "${HOME}/.vnc"
x11vnc -storepasswd "${VNC_PASSWORD}" "${HOME}/.vnc/passwd" > /dev/null
x11vnc -display "${VNC_DISPLAY}" \
       -rfbport "${VNC_PORT}" \
       -rfbauth "${HOME}/.vnc/passwd" \
        -noclipboard -nosetclipboard \
       -forever -shared -bg -o /tmp/x11vnc.log

export DISPLAY="${VNC_DISPLAY}"