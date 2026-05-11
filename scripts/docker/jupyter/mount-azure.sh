#!/bin/bash
# Sourced by jupyter docker-stacks' start-notebook.sh from
# /usr/local/bin/before-notebook.d/, so the mount is live by the time the
# notebook server starts.
#
# Mounts an Azure Blob Storage container at /mnt/azure via rclone. Credentials
# are read from environment variables (typically projected from a Kubernetes
# secret by the deployment):
#   AZURE_STORAGE_ACCOUNT    - storage account name
#   AZURE_STORAGE_KEY        - storage account key
#   AZURE_STORAGE_CONTAINER  - container to mount

#set -e
#
#: "${AZURE_STORAGE_ACCOUNT:?AZURE_STORAGE_ACCOUNT not set}"
#: "${AZURE_STORAGE_KEY:?AZURE_STORAGE_KEY not set}"
#: "${AZURE_STORAGE_CONTAINER:?AZURE_STORAGE_CONTAINER not set}"
#
#mkdir -p /tmp/azure
#
#RCLONE_CONFIG_AZURE_TYPE=azureblob \
#RCLONE_CONFIG_AZURE_ACCOUNT="${AZURE_STORAGE_ACCOUNT}" \
#RCLONE_CONFIG_AZURE_KEY="${AZURE_STORAGE_KEY}" \
#rclone mount "azure:${AZURE_STORAGE_CONTAINER}" /tmp/azure \
#       --daemon \
#       --allow-other \
#       --vfs-cache-mode writes \
#       --log-file /tmp/rclone.log

mkdir -p /tmp/azure
rclone mount :azureblob:$AZURE_BUCKET /tmp/azure/ --azureblob-connection-string $AZURE_CONNECTION_STRING --vfs-cache-mode writes --daemon || echo "rclone failed"