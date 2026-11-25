#!/bin/bash
# Forced command of the GitHub Actions deploy key on the server (in ~/.ssh/authorized_keys:
# command="/home/moritz/deploy-restricted.sh",no-port-forwarding,... ssh-ed25519 ...).
# Allows only what deploy.yml needs: emptying the upload folder, uploading into it, and releasing
# from it. The release script lives outside the upload folder, so an upload can't change it.
set -e

incoming=/home/moritz/tasks/incoming

case "$SSH_ORIGINAL_COMMAND" in
  taskz-prepare)
    rm -rf "$incoming"
    mkdir -p "$incoming"
    ;;
  # Exactly what `scp -O -r <several files> host:/home/moritz/tasks/incoming/` sends.
  "scp -r -d -t /home/moritz/tasks/incoming/")
    exec scp -r -d -t /home/moritz/tasks/incoming/
    ;;
  taskz-release)
    exec /home/moritz/bin/taskz-release.sh "$incoming"
    ;;
  *)
    echo "Rejected command: $SSH_ORIGINAL_COMMAND" >&2
    exit 1
    ;;
esac
