#!/bin/bash
set -e

NEW_USER=${HOST_USER:-afon}
USER_ID=${HOST_UID:-1000}
GROUP_ID=${HOST_GID:-1000}

# Create group if needed
if ! getent group $GROUP_ID >/dev/null; then
    groupadd -g $GROUP_ID appgroup
fi

# Create user if needed (check by name, not UID)
if ! id -u $NEW_USER >/dev/null 2>&1; then
    useradd -m -u $USER_ID -g $GROUP_ID -s /bin/bash $NEW_USER
fi

# Ensure home directory exists with correct ownership
mkdir -p /home/$NEW_USER
chown $USER_ID:$GROUP_ID /home/$NEW_USER

# -m keeps the environment, including the PATH of the image
export HOME=/home/$NEW_USER
exec su -m $NEW_USER -c "exec $@"
