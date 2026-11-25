#!/usr/bin/env bash
# Puts a new release live without downtime (see deploy/README.md). Installed on the server as
# ~/bin/taskz-release.sh; deploy.yml uploads the release and runs it through deploy-restricted.sh:
#   taskz-release.sh <dir with taskz.jar and dist/>
# The app runs as tasks@8081 or tasks@8082, and nginx sends requests to whichever answers. The new
# release starts on the free port; the old one only stops once the new one answers. If the new one
# doesn't come up, it is stopped again and the old one keeps running, frontend included.
set -euo pipefail

incoming="$1"
app_dir="$HOME/tasks"
web_dir=/var/www/spring
export XDG_RUNTIME_DIR="/run/user/$(id -u)"

if systemctl --user is-active --quiet tasks@8081; then
  old=8081 new=8082
elif systemctl --user is-active --quiet tasks@8082; then
  old=8082 new=8081
else
  old='' new=8081
fi

# The old port's jar stays, so going back is starting that port again (see README).
mv "$incoming/taskz.jar" "$app_dir/tasks-$new.jar"
# restart, not start: a failed earlier try may have left the unit running.
systemctl --user restart "tasks@$new"

echo "Waiting for the new release on port $new"
up=''
for _ in $(seq 1 90); do
  if curl -fsS -o /dev/null "http://127.0.0.1:$new/health"; then
    up=1
    break
  fi
  # Crashed on start (Restart=on-failure shows as "activating" then): no use waiting.
  systemctl --user is-active --quiet "tasks@$new" || break
  sleep 2
done
if [ -z "$up" ]; then
  echo "The new release didn't come up; the old one keeps running." >&2
  journalctl --user -u "tasks@$new" -n 60 --no-pager >&2 || true
  systemctl --user stop "tasks@$new"
  exit 1
fi

# After a reboot the current one starts.
systemctl --user enable --quiet "tasks@$new"
if [ -n "$old" ]; then
  systemctl --user disable --quiet "tasks@$old"
  # Graceful: running requests finish; nginx already sends new ones to the new release.
  systemctl --user stop "tasks@$old"
fi
echo "Port $new is live"

# Frontend only now, so it never talks to an older backend. Assets have hashed names and come
# first; index.html, which points at them, is swapped in at once.
cp -a "$incoming/dist/assets/." "$web_dir/assets/"
find "$incoming/dist" -maxdepth 1 -type f ! -name index.html -exec cp -a {} "$web_dir/" \;
cp "$incoming/dist/index.html" "$web_dir/.index.html.new"
mv "$web_dir/.index.html.new" "$web_dir/index.html"
# Every release copies all current assets anew (fresh mtime), so files older than a week belong
# to releases no open tab still loads chunks from.
find "$web_dir/assets" -type f -mtime +7 -delete

rm -rf "$incoming"
