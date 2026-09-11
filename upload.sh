#!/usr/bin/env bash
set -e

usage(){
  echo "usage:  $0 <file> [remote-dir]"
  echo ""
  echo "file        : local file to upload"
  echo "remote-dir  : destination dir, relative to the root folder.  Defaults to '/'."
  echo ""
  echo "examples:"
  echo "  $0  afon-distribution/target/afon-<version>-cli.zip"
  echo ""
  echo "Requires rclone configured with a remote named '$REMOTE', defaulting to `drive`."
  echo "Override the remote name via the `GDRIVE_REMOTE` env variable."
  echo ""
  echo "Uploads under the Google Drive folder id '$ROOT_FOLDER_ID' (the 'Afon Distribution' folder) by default."
  echo "Override it via the `GDRIVE_ROOT_FOLDER_ID` env variable."
  echo ""
  echo "To configure:"
  echo "First create a client_id: https://rclone.org/drive/#making-your-own-client-id"
  echo "Then run rclone config following instructions: https://rclone.org/drive"
  echo ""
  echo "To show the rclone config:"
  echo "rclone config show drive"
}

check_rclone(){
  if ! command -v rclone &> /dev/null ; then
    echo "rclone not found.  Install with: apt/brew install rclone"
    exit 1;
  fi
}

FILE=$1
REMOTE_DIR=${2:-/}
ROOT_FOLDER_ID=${GDRIVE_ROOT_FOLDER_ID:-1b0YkwVXC5ERvDavpeGe9L398cgnz_Bnt}
REMOTE="${GDRIVE_REMOTE:-drive},root_folder_id=$ROOT_FOLDER_ID"

if [ -z "$FILE" ] || [ ! -f "$FILE" ] ; then
  echo "File not found or not a file."
  usage; exit 1;
fi

check_rclone

echo "Uploading $FILE to $REMOTE:$REMOTE_DIR"
rclone copy "$FILE" "$REMOTE:$REMOTE_DIR" --progress

echo "Uploaded $FILE to $REMOTE:$REMOTE_DIR"
