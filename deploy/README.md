# Deploy without downtime

The app runs as one of two systemd user units, `tasks@8081` or `tasks@8082`. nginx knows both
ports and sends each request to whichever answers. A deploy (`.github/workflows/deploy.yml`, on
every push to `main`) uploads the release to `~/tasks/incoming` and runs `release.sh` on the
server. The deploy key may only do exactly that: its forced command `deploy-restricted.sh` allows
`taskz-prepare` (empty the upload folder), the upload into it, and `taskz-release`, which runs
`~/bin/taskz-release.sh` (a copy of `release.sh`, outside the upload folder so no upload can change
it). After a change to either script, copy it to the server again (see setup). The script:

1. starts the new release on the free port, next to the old one,
2. waits until its `/health` answers; if it doesn't come up, the script stops it again and the old
   release keeps running unchanged (the workflow fails, `journalctl` output is in its log),
3. stops the old release gracefully (running requests finish; open live views reconnect to the new
   one and reload),
4. only then copies the frontend: assets first, `index.html` swapped in at once.

## Rules for changes

For a few seconds the old and the new release run side by side, both on the new database schema.

- **Migrations only add**: new tables, new columns that are nullable or have a default. Renaming or
  dropping takes two deploys: first stop using it (and add the replacement), then drop it.
- **The API only adds** as well: the old frontend in open tabs talks to the new backend until it
  reloads.
- Known gap: a move by hand whose rules come due exactly in those seconds may be picked up by both
  releases. Hibernate most likely rolls the second one back (its delete of the `pending_move` row
  finds nothing), but this is not tested.

## One-time setup on the server

As the user that runs the app today (`tasks.service`). Needs `curl` on the server. Do it before
merging this into `main`; a deploy before that fails without harm (no `tasks@` unit yet, the old
`tasks` keeps running). Between steps 2 and 4 old and new run side by side, both with their
scheduled jobs; keep that to a few minutes, or an hourly mail summary may go out twice.

```bash
# 1. The template unit (from a checkout, or with scp from your machine). It starts the app like the
#    old tasks.service: settings from ~/tasks/tasks.properties, listening on 127.0.0.1 only.
#    Compare with `systemctl --user cat tasks` if that one was changed since.
cp deploy/tasks@.service ~/.config/systemd/user/
systemctl --user daemon-reload

# 2. The current release on 8081, next to the old unit on 8080
cp ~/tasks/tasks.jar ~/tasks/tasks-8081.jar
systemctl --user enable --now tasks@8081
curl http://127.0.0.1:8081/health   # "ok"
```

3. nginx: add the `upstream` block from `deploy/nginx-upstream.conf` (outside the `server` block)
   and change every `proxy_pass` to the app into `proxy_pass http://taskz;`. Then
   `sudo nginx -t && sudo systemctl reload nginx`. The site now runs on 8081.

```bash
# 4. The old unit off for good
systemctl --user disable --now tasks
```

```bash
# 5. Release script and the deploy key's forced command (from your machine, in the checkout)
scp deploy/release.sh SERVER:bin/taskz-release.sh
scp deploy/deploy-restricted.sh SERVER:deploy-restricted.sh
ssh SERVER 'chmod 755 ~/bin/taskz-release.sh ~/deploy-restricted.sh'
# ~/.ssh/authorized_keys: the deploy key's line starts with
#   command="/home/moritz/deploy-restricted.sh",no-port-forwarding,no-X11-forwarding,no-agent-forwarding,no-pty
```

The next push to `main` deploys to 8082 and stops 8081, and so on.

## By hand

```bash
systemctl --user status 'tasks@*'            # which port is live
journalctl --user -u tasks@8082 -n 100       # its log
```

Going back to the previous release: its jar is still there (`~/tasks/tasks-<other port>.jar`) until
the next deploy. Start that port, then stop the current one:

```bash
systemctl --user enable --now tasks@8081
curl http://127.0.0.1:8081/health            # repeat until it says "ok" (start takes a while)
systemctl --user disable --now tasks@8082
```

This only works while the database still fits the previous release, which the rules above ensure
for one step back. The frontend stays the newer one until the next deploy.
