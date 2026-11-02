import { onBeforeUnmount } from 'vue'

/**
 * Live updates of a board over server-sent events (see BoardEvents in the backend). The server
 * only says "changed", and the page loads the board again in the background with refresh();
 * refresh returns 'gone' when the board can't be opened anymore (link revoked, access removed).
 * Logged-in views also get "presence": the names of the others who have the board open.
 */
export function useBoardEvents({ refresh, onPresence = () => {} }) {
  let source = null
  let refreshTimer = null
  let retryTimer = null

  // Quick changes in a row (a drag renumbers a column) load the board once.
  function refreshSoon() {
    clearTimeout(refreshTimer)
    refreshTimer = setTimeout(refresh, 300)
  }

  function listen(url) {
    stop()
    source = new EventSource(url)
    let opened = false
    source.onopen = () => {
      if (opened) refresh() // reconnected: changes in between were missed
      opened = true
    }
    source.addEventListener('changed', refreshSoon)
    source.addEventListener('presence', (event) => onPresence(JSON.parse(event.data)))
    // The browser reconnects by itself, unless the reconnect was refused: the board can't be
    // opened anymore (refresh shows that) or the server was away, e.g. during a deploy (try
    // again). Delayed, as leaving the page also ends the stream, and its timers don't run anymore.
    source.onerror = () => {
      if (source?.readyState !== EventSource.CLOSED) return
      onPresence([])
      retryTimer = setTimeout(async () => {
        if ((await refresh()) !== 'gone') listen(url)
      }, 2000)
    }
  }

  function stop() {
    source?.close()
    source = null
    clearTimeout(refreshTimer)
    clearTimeout(retryTimer)
  }

  onBeforeUnmount(stop)
  return { listen, stop }
}
