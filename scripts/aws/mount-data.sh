set -euo pipefail

VOLUME=
VOLUME="${1:-}"
if [ -z "$VOLUME" ]; then
  echo "Mount a volume as /data"
  echo "Usage: ${BASH_SOURCE[0]} <volume>"
  echo "  e.g. ${BASH_SOURCE[0]} /dev/xvdd"
  echo "Check available volumes using command:"
  echo "$ lsblk
  NAME     MAJ:MIN RM  SIZE RO TYPE MOUNTPOINTS
  xvda     202:0    0    8G  0 disk
  ├─xvda1  202:1    0  6.9G  0 part /
  ├─xvda13 202:13   0 1023M  0 part /boot
  ├─xvda14 202:14   0    4M  0 part
  └─xvda15 202:15   0  106M  0 part /boot/efi
  xvdd     202:48   0   60G  0 disk "
  echo "To configure automatic mount on boot, add to the /etc/fstab file an entry in the format:"
  echo "UUID=<UUID> /data ext4 defaults,nofail 0 2"
  echo "where the UUID is found vai the command:"
  echo "sudo blkid /dev/xvdd"
  exit
fi

# create a filesystem on the attached volume
sudo mkfs -t ext4 $VOLUME
#	create a mount directory
sudo mkdir /data
# mount the volume
sudo mount $VOLUME /data

