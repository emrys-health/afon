#!/bin/bash
# Background a plain, token-less Jupyter Lab bound to the loopback interface only.
#
# This is the workspace the user actually works in. Under JupyterHub the hub-facing
# `jupyterhub-singleuser` server (started afterwards by start.sh on a different port) is
# wrapped in HubOAuth and a /jupyter/user/<name>/ base path, so it can't be opened cleanly
# from the browser inside the pod's VNC desktop. This server, by contrast, listens on
# 127.0.0.1:8888 with no token, so from the Guacamole-streamed desktop the user just visits
#
#     http://localhost:8888
#
# Because it is bound to loopback it is never reachable outside the pod, so the
# clipboard-disabled Guacamole stream remains the only way data can leave the notebook.
# It inherits the pod environment (incl. the injected OIDC_TOKEN / OIDC_ID_TOKEN), so the
# Spark Connector and Polaris still act as the logged-in user.
jupyter lab \
  --ip=127.0.0.1 \
  --port=8888 \
  --no-browser \
  --ServerApp.token='' \
  --ServerApp.password='' \
  --ServerApp.base_url=/ \
  --ServerApp.open_browser=False &
