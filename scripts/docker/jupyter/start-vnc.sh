#!/bin/bash
# Sourced by jupyter docker-stacks' start-notebook.sh from
# /usr/local/bin/before-notebook.d/, so the resulting DISPLAY propagates into
# the notebook server.
#
# Brings up N independent GUI streaming pipelines that Apache Guacamole
# connects to — one per Guacamole user — so concurrent logins each get their
# own X session inside this single pod:
#
#   Xvfb :i   ──►  fluxbox (decorations/dragging)
#                  x11vnc (rfbport 5900+i, password-protected)
#
# N is controlled by JUPYTER_VNC_SESSIONS (default 3). Display indices and
# VNC ports match what GuacamoleConfig.SESSIONS hands to Guacamole.

set -e

VNC_SESSIONS="${JUPYTER_VNC_SESSIONS:-3}"
VNC_PASSWORD="${JUPYTER_VNC_PASSWORD:-afon}"

# Idempotent: if Xvfb is already running, this hook has been sourced before
# (e.g. by an earlier invocation of start-notebook.sh) — skip everything.
if pgrep -x Xvfb >/dev/null 2>&1; then
    export DISPLAY=":1"
    return 0 2>/dev/null || exit 0
fi

mkdir -p "${HOME}/.vnc"
x11vnc -storepasswd "${VNC_PASSWORD}" "${HOME}/.vnc/passwd" > /dev/null

for i in $(seq 1 "${VNC_SESSIONS}"); do
    display=":${i}"
    port=$((5900 + i))

    Xvfb "${display}" -screen 0 1280x800x24 -ac +extension GLX +render -noreset \
        > "/tmp/xvfb-${i}.log" 2>&1 &

    # Wait for the X socket so dependents don't race the server.
    for _ in $(seq 1 20); do
        [ -e "/tmp/.X11-unix/X${i}" ] && break
        sleep 0.1
    done

    DISPLAY="${display}" fluxbox > "/tmp/fluxbox-${i}.log" 2>&1 &

    # In-session clipboard bridge: xterm uses PRIMARY by default, Firefox uses
    # CLIPBOARD — without autocutsel keeping them in sync (via CUT_BUFFER0)
    # copy-from-terminal / paste-into-browser silently does nothing. This sync
    # is internal to the X session and does NOT bypass Guacamole's
    # disable-copy/disable-paste, which still block any leak to the user's
    # local browser clipboard.
    DISPLAY="${display}" autocutsel -fork -selection CLIPBOARD \
        > "/tmp/autocutsel-clip-${i}.log" 2>&1
    DISPLAY="${display}" autocutsel -fork -selection PRIMARY \
        > "/tmp/autocutsel-prim-${i}.log" 2>&1

    x11vnc -display "${display}" \
           -rfbport "${port}" \
           -rfbauth "${HOME}/.vnc/passwd" \
           -forever -shared -bg -o "/tmp/x11vnc-${i}.log"
done

# Jupyter inherits DISPLAY=:1; users that need a specific display can override
# in-notebook with `os.environ["DISPLAY"]=":2"`.
export DISPLAY=":1"