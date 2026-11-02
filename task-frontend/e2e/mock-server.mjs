// Serves the built frontend and an in-memory imitation of the board API, for the browser tests.
// It mimics the Spring backend closely enough for the UI; the backend has its own tests.
// Usage: node e2e/mock-server.mjs <dist-dir> <port>
// POST /api/__reset restores the start state; GET /api/__state shows the current state.
import http from 'node:http'
import fs from 'node:fs'
import path from 'node:path'

const [distDir, port] = process.argv.slice(2)

let nextId
const board = { id: 1, name: 'My board', slug: 'my-board' }
let columns
let labels
// History like the backend: newest first per task, only changed fields.
let events
let eventId
const colName = (id) => columns.find((c) => c.id === id)?.name
const labelNames = (ids) => labels.filter((l) => (ids ?? []).includes(l.id)).map((l) => l.name).sort()
// Like the backend: points in time as UTC with a zone (Instant), due dates without one.
const now = () => new Date().toISOString()
function record(task, type, changes) {
  events.unshift({ id: eventId++, taskId: task.id, type, at: now(), user: 'Alice', changes })
}
// Share link like the backend: a key (22 chars) plus the board slug in front; OLD_UUID imitates a pre-slug link.
const OLD_UUID = '13d72268-5904-431b-b410-f348df9ec414'
let share // { key, created }
let shareRequests
const newKey = () => Array.from({ length: 22 }, () => 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_'[Math.floor(Math.random() * 64)]).join('')
let boardDeleted
// Members of board 1 ([{ userId, name, joined }]) and its open invite keys. Board 2 is Bob's; Alice
// joins it with the fixed link /join/team-invite.
let members
let invites
let teamJoined
const TEAM_BOARD = { id: 2, name: 'Team board', slug: 'team-board', own: false, ownerName: 'Bob' }
// Open live views of shared boards (server-sent events), like BoardEvents in the backend.
const streams = new Set()
const endStreams = () => { for (const res of streams) res.end(); streams.clear() }
const shareOk = (ref) => ref === 'test-token' || (share && (ref === OLD_UUID || ref === share.key || (ref.length > 23 && ref.endsWith('-' + share.key))))
let tasks
let notes // [{ id, taskId, text, user, created, edited }]
let attachments // [{ id, taskId, name, contentType, size, image, user, created, data }]
let log

function reset() {
  endStreams()
  members = []
  invites = []
  teamJoined = false
  nextId = 100
  columns = [
    { id: 10, name: 'To do', color: '#64748b', position: 0 },
    { id: 11, name: 'In progress', color: '#3b82f6', position: 1 },
    { id: 12, name: 'Done', color: '#22c55e', position: 2 },
  ]
  labels = []
  Object.assign(board, { name: 'My board', slug: 'my-board', taskTemplate: null })
  boardDeleted = false
  events = []
  eventId = 1
  share = null
  shareRequests = []
  tasks = [
    { id: 1, title: 'Task A', description: 'first', due_date: null, created: null, edited: null, columnId: 10, position: 0 },
    { id: 2, title: 'Task B', description: null, due_date: [2026, 1, 1, 12, 0], created: null, edited: null, columnId: 10, position: 1 },
    { id: 3, title: 'Task C', description: null, due_date: null, created: '2026-08-01T10:00:00Z', edited: null, columnId: 11, position: 0 },
  ]
  notes = []
  attachments = []
  log = []
}
reset()

const inColumn = (id) => tasks.filter((t) => t.columnId === id).sort((a, b) => a.position - b.position)
const renumber = (list) => list.forEach((t, i) => (t.position = i))
const sortedColumns = () => [...columns].sort((a, b) => a.position - b.position)

function api(method, url, body) {
  const u = new URL(url, 'http://x')
  const p = u.pathname.replace(/^\/api/, '')
  let m
  if (method === 'GET' && p === '/auth/status') return [200, { email: 'alice@example.com', name: 'Alice' }]
  // Like the backend: the code "unverified" stands for an account without a confirmed email.
  if (method === 'POST' && p === '/auth/login') {
    if (body?.code === 'unverified') return [401, { message: 'Your account there has no confirmed email address. Confirm one and try again.' }]
    return [200, 'Login successful']
  }
  if (method === 'GET' && p === '/boards') {
    const own = boardDeleted ? [] : [{ id: 1, name: board.name, slug: board.slug, own: true, ownerName: null }]
    return [200, teamJoined ? [...own, TEAM_BOARD] : own]
  }
  if (method === 'GET' && p === '/boards/1/members') {
    return [200, [{ userId: 1, name: 'Alice', owner: true, joined: null }, ...members.map((m) => ({ ...m, owner: false }))]]
  }
  if (method === 'POST' && p === '/boards/1/invites') {
    const key = newKey()
    invites.push(key)
    return [201, { path: `/join/${key}`, expires: '2026-11-09T20:00:00Z' }]
  }
  if (method === 'DELETE' && (m = p.match(/^\/boards\/1\/members\/(\d+)$/))) {
    members = members.filter((x) => x.userId !== +m[1])
    return [204, null]
  }
  if ((m = p.match(/^\/invites\/([^/]+)$/))) {
    const key = decodeURIComponent(m[1])
    if (key === 'team-invite') {
      if (method === 'POST') teamJoined = true
      return [200, method === 'POST' ? TEAM_BOARD : { boardName: 'Team board', ownerName: 'Bob', member: teamJoined }]
    }
    // Alice owns board 1, so her own links only open it.
    if (invites.includes(key)) return [200, method === 'POST' ? { id: 1, name: board.name, slug: board.slug, own: true, ownerName: null } : { boardName: board.name, ownerName: 'Alice', member: true }]
    return [404, { message: "This invite link doesn't work anymore" }]
  }
  if (teamJoined && method === 'GET' && p === '/boards/2') {
    return [200, { id: 2, name: 'Team board', slug: 'team-board', columns: [{ id: 20, name: 'Ideas', color: null, position: 0 }], labels: [], tasks: [], taskTemplate: null }]
  }
  if (teamJoined && method === 'GET' && p === '/boards/2/members') {
    return [200, [{ userId: 2, name: 'Bob', owner: true, joined: null }, { userId: 1, name: 'Alice', owner: false, joined: '2026-11-02T20:00:00Z' }]]
  }
  if (teamJoined && method === 'DELETE' && p === '/boards/2/membership') { teamJoined = false; return [204, null] }
  if (method === 'DELETE' && p === '/boards/1') { boardDeleted = true; return [204, null] }
  if (method === 'GET' && p === '/boards/1') {
    const cols = sortedColumns()
    const ts = cols.flatMap((c) => inColumn(c.id))
    return [200, { ...board, columns: cols, labels, tasks: ts }]
  }
  if (method === 'PATCH' && p === '/boards/1') {
    const name = body.name?.trim()
    if (!name || name.length > 50) return [400, { message: 'Board name must not be empty' }]
    // Simplified slug rule of the backend (there is only one board, so no clashes).
    board.name = name
    board.slug = name.toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '') || 'board'
    return [200, { id: 1, name: board.name, slug: board.slug, own: true, ownerName: null }]
  }
  if (method === 'POST' && p === '/boards/1/columns') {
    const c = { id: nextId++, name: body.name.trim(), color: body.color ?? null, position: columns.length }
    columns.push(c)
    return [201, c]
  }
  if ((m = p.match(/^\/columns\/(\d+)$/))) {
    const col = columns.find((c) => c.id === +m[1])
    if (!col) return [404, {}]
    if (method === 'PATCH') {
      if ('name' in body) col.name = body.name
      if ('color' in body) col.color = body.color
      if ('position' in body) {
        const list = sortedColumns().filter((c) => c !== col)
        list.splice(Math.min(body.position, list.length), 0, col)
        list.forEach((c, i) => (c.position = i))
      }
      return [200, col]
    }
    if (method === 'DELETE') {
      const own = inColumn(col.id)
      const moveTo = u.searchParams.get('moveTo')
      const deleteTasks = u.searchParams.get('deleteTasks') === 'true'
      if (columns.length === 1) return [409, {}]
      if (own.length && !moveTo && !deleteTasks) return [409, {}]
      if (moveTo) {
        let next = inColumn(+moveTo).length
        own.forEach((t) => { t.columnId = +moveTo; t.position = next++ })
      } else if (deleteTasks) {
        tasks = tasks.filter((t) => t.columnId !== col.id)
      }
      columns = columns.filter((c) => c !== col)
      sortedColumns().forEach((c, i) => (c.position = i))
      return [204, null]
    }
  }
  if (method === 'POST' && p === '/boards/1/labels') {
    if (!body.name?.trim() || !/^#[0-9a-f]{6}$/i.test(body.color ?? '')) return [400, {}]
    const l = { id: nextId++, name: body.name.trim(), color: body.color.toLowerCase() }
    labels.push(l)
    return [201, l]
  }
  if ((m = p.match(/^\/labels\/(\d+)$/))) {
    const l = labels.find((x) => x.id === +m[1])
    if (!l) return [404, {}]
    if (method === 'PATCH') {
      if (body.name != null) l.name = body.name.trim()
      if (body.color != null) l.color = body.color.toLowerCase()
      return [200, l]
    }
    if (method === 'DELETE') {
      labels = labels.filter((x) => x !== l)
      tasks.forEach((t) => (t.labelIds = (t.labelIds ?? []).filter((id) => id !== l.id)))
      return [204, null]
    }
  }
  if (method === 'POST' && p === '/tasks') {
    if (!body.columnId) return [400, {}]
    const t = { id: nextId++, title: body.title, description: body.description, due_date: body.due_date, created: now(), edited: null, columnId: body.columnId, position: inColumn(body.columnId).length, labelIds: body.labelIds ?? [] }
    tasks.push(t)
    record(t, 'created', { title: t.title, column: colName(t.columnId) })
    return [201, t]
  }
  if ((m = p.match(/^\/tasks\/(\d+)\/move$/)) && method === 'PATCH') {
    const t = tasks.find((x) => x.id === +m[1])
    if (body.columnId !== t.columnId) record(t, 'moved', { column: [colName(t.columnId), colName(body.columnId)] })
    const source = inColumn(t.columnId).filter((x) => x !== t)
    const target = body.columnId === t.columnId ? source : inColumn(body.columnId)
    target.splice(Math.min(body.position, target.length), 0, t)
    t.columnId = body.columnId
    renumber(source)
    renumber(target)
    return [200, t]
  }
  if ((m = p.match(/^\/tasks\/(\d+)\/notes$/))) {
    const taskId = +m[1]
    if (!tasks.some((t) => t.id === taskId)) return [404, {}]
    if (method === 'GET') {
      return [200, notes.filter((n) => n.taskId === taskId).sort((a, b) => b.id - a.id).map(({ taskId, ...n }) => n)]
    }
    if (method === 'POST') {
      const text = (body.text ?? '').trim()
      if (!text || text.length > 5000) return [400, {}]
      const note = { id: nextId++, taskId, text, user: 'Alice', created: now(), edited: null, canEdit: true, canDelete: true }
      notes.push(note)
      const { taskId: _, ...out } = note
      return [201, out]
    }
  }
  if ((m = p.match(/^\/notes\/(\d+)$/))) {
    const note = notes.find((n) => n.id === +m[1])
    if (!note) return [404, {}]
    if (method === 'PATCH') {
      const text = (body.text ?? '').trim()
      if (!text || text.length > 5000) return [400, {}]
      if (text !== note.text) Object.assign(note, { text, edited: now() })
      const { taskId: _, ...out } = note
      return [200, out]
    }
    if (method === 'DELETE') {
      notes = notes.filter((n) => n !== note)
      return [204, null]
    }
  }
  if ((m = p.match(/^\/tasks\/(\d+)\/history$/)) && method === 'GET') {
    return [200, events.filter((e) => e.taskId === +m[1]).map(({ taskId, ...e }) => e)]
  }
  if (method === 'GET' && p === '/boards/1/share') return share ? [200, { path: `${board.slug}-${share.key}`, created: share.created }] : [204, null]
  if (method === 'POST' && p === '/boards/1/share') {
    share = { key: newKey(), created: '2026-10-28T20:00:00Z' }
    return [201, { path: `${board.slug}-${share.key}`, created: share.created }]
  }
  if (method === 'DELETE' && p === '/boards/1/share') { share = null; return [204, null] }
  if (method === 'GET' && (m = p.match(/^\/share\/(.+)$/)) && m[1] !== 'test-token') {
    const ref = decodeURIComponent(m[1])
    shareRequests.push(ref)
    if (!shareOk(ref)) return [404, {}]
    const cols = sortedColumns()
    // Like the backend: no attachments on shared boards, not even their number.
    return [200, { name: board.name, path: `${board.slug}-${share.key}`, columns: cols, labels, tasks: cols.flatMap((c) => inColumn(c.id)).map((t) => ({ ...t, attachments: null })) }]
  }
  if (method === 'GET' && p === '/share/test-token') {
    const cols = sortedColumns()
    return [200, { name: board.name, columns: cols, labels, tasks: cols.flatMap((c) => inColumn(c.id)) }]
  }
  if (method === 'PUT' && p === '/boards/1/task-template') {
    board.taskTemplate = body.template?.trim() ? body.template.trim() : null
    return [200, { template: board.taskTemplate }]
  }
  // Like the backend: only checkbox lines may differ, all the same way, including the clicked line; otherwise 409.
  if ((m = p.match(/^\/tasks\/(\d+)\/checkbox$/)) && method === 'PATCH') {
    const t = tasks.find((x) => x.id === +m[1])
    const before = (t.description ?? '').split('\n')
    const after = (body.description ?? '').split('\n')
    if (before.length !== after.length) return [409, {}]
    const itemRe = /^((?:[ \t]*>)*[ \t]*(?:[-*+]|\d{1,9}[.)])[ \t]+\[)([ xX])(\].*)$/
    const toggles = []
    for (let i = 0; i < before.length; i++) {
      if (before[i] === after[i]) continue
      const was = itemRe.exec(before[i])
      const is = itemRe.exec(after[i])
      if (!was || !is || was[1] !== is[1] || was[3] !== is[3] || (was[2] === ' ') === (is[2] === ' ')) return [409, {}]
      toggles.push({ line: i, item: is[3].slice(1).trim(), checked: is[2] !== ' ' })
    }
    const clicked = toggles.find((x) => (body.line == null ? toggles.length === 1 : x.line === body.line))
    if (!clicked || toggles.some((x) => x.checked !== clicked.checked)) return [409, {}]
    t.description = body.description
    t.edited = now()
    const changes = { item: clicked.item }
    const parents = toggles.filter((x) => x.line < clicked.line).map((x) => x.item)
    const subItems = toggles.filter((x) => x.line > clicked.line).length
    if (parents.length) changes.parents = parents
    if (subItems) changes.subItems = subItems
    record(t, clicked.checked ? 'checked' : 'unchecked', changes)
    return [200, t]
  }
  if ((m = p.match(/^\/tasks\/(\d+)$/)) && method === 'PUT') {
    const t = tasks.find((x) => x.id === +m[1])
    if (body.labelIds && body.labelIds.some((id) => !labels.some((l) => l.id === id))) return [400, {}]
    const before = { title: t.title, description: t.description || null, due_date: t.due_date ?? null, labels: labelNames(t.labelIds) }
    Object.assign(t, { title: body.title, description: body.description, due_date: body.due_date })
    if (body.labelIds) t.labelIds = [...body.labelIds].sort((a, b) => a - b)
    const after = { title: t.title, description: t.description || null, due_date: t.due_date ?? null, labels: labelNames(t.labelIds) }
    const changes = {}
    for (const f of ['title', 'description', 'due_date']) if (JSON.stringify(before[f]) !== JSON.stringify(after[f])) changes[f] = [before[f], after[f]]
    const added = after.labels.filter((n) => !before.labels.includes(n))
    const removed = before.labels.filter((n) => !after.labels.includes(n))
    if (added.length) changes.labelsAdded = added
    if (removed.length) changes.labelsRemoved = removed
    if (Object.keys(changes).length) record(t, 'updated', changes)
    t.edited = now()
    return [200, t]
  }
  if ((m = p.match(/^\/tasks\/(\d+)\/delete$/)) && method === 'DELETE') {
    tasks = tasks.filter((t) => t.id !== +m[1])
    return [200, null]
  }
  return [404, {}]
}

const types = { '.js': 'text/javascript', '.css': 'text/css', '.html': 'text/html', '.woff2': 'font/woff2', '.svg': 'image/svg+xml', '.ico': 'image/x-icon', '.png': 'image/png' }

// Attachments like the backend: allowed types by extension plus a look at the first bytes for
// images and PDFs; refused uploads say why in { message }.
const ATTACHMENT_TYPES = {
  png: ['image/png', '\x89PNG'], jpg: ['image/jpeg', '\xFF\xD8\xFF'], jpeg: ['image/jpeg', '\xFF\xD8\xFF'],
  gif: ['image/gif', 'GIF8'], webp: ['image/webp', 'RIFF'], pdf: ['application/pdf', '%PDF-'],
  txt: ['text/plain; charset=utf-8'], md: ['text/markdown; charset=utf-8'], csv: ['text/csv; charset=utf-8'],
}
const attachmentOut = ({ taskId, data, ...a }) => a

async function attachmentApi(method, url, raw, headers) {
  let m
  if (url === '/api/attachments/settings' && method === 'GET') {
    return [200, { maxFileSize: 10 * 1024 * 1024, types: 'PNG, JPEG, GIF, WebP, PDF, TXT, MD, CSV', extensions: Object.keys(ATTACHMENT_TYPES) }]
  }
  if ((m = url.match(/^\/api\/tasks\/(\d+)\/attachments$/))) {
    const task = tasks.find((t) => t.id === +m[1])
    if (!task) return [404, { message: 'Task not found' }]
    if (method === 'GET') {
      return [200, attachments.filter((a) => a.taskId === task.id).sort((a, b) => b.id - a.id).map(attachmentOut)]
    }
    if (method === 'POST') {
      log.push({ method, url })
      const form = await new Request('http://mock', { method, headers, body: raw }).formData()
      const file = form.get('file')
      const data = Buffer.from(await file.arrayBuffer())
      if (data.length > 10 * 1024 * 1024) return [413, { message: 'Files can be at most 10 MB' }]
      const [contentType, magic] = ATTACHMENT_TYPES[file.name.split('.').pop().toLowerCase()] ?? []
      if (!contentType || (magic && !data.toString('latin1').startsWith(magic)) || (!magic && data.includes(0))) {
        return [415, { message: 'Only PNG, JPEG, GIF, WebP, PDF, TXT, MD, CSV files can be attached, with an extension that fits the content' }]
      }
      const a = { id: nextId++, taskId: task.id, name: file.name, contentType, size: data.length, image: contentType.startsWith('image/'), user: 'Alice', created: now(), data }
      attachments.push(a)
      task.attachments = (task.attachments ?? 0) + 1
      record(task, 'attached', { file: a.name })
      return [201, attachmentOut(a)]
    }
  }
  if ((m = url.match(/^\/api\/attachments\/(\d+)$/))) {
    const a = attachments.find((x) => x.id === +m[1])
    if (!a) return [404, { message: 'Attachment not found' }]
    if (method === 'GET') return [200, a]
    if (method === 'DELETE') {
      attachments = attachments.filter((x) => x !== a)
      const task = tasks.find((t) => t.id === a.taskId)
      task.attachments -= 1
      record(task, 'detached', { file: a.name })
      return [204, null]
    }
  }
  return null
}

http.createServer((req, res) => {
  if (req.url.startsWith('/api/')) {
    const chunks = []
    req.on('data', (c) => chunks.push(c))
    req.on('end', async () => {
      const stream = req.method === 'GET' && req.url.match(/^\/api\/share\/([^/]+)\/events$/)
      if (stream) {
        if (!shareOk(decodeURIComponent(stream[1]))) {
          res.writeHead(404)
          res.end()
          return
        }
        res.writeHead(200, { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-cache' })
        res.write(':open\n\n')
        streams.add(res)
        res.on('close', () => streams.delete(res))
        return
      }
      const handled = await attachmentApi(req.method, req.url, Buffer.concat(chunks), req.headers)
      if (handled) {
        const [status, out] = handled
        if (req.method === 'GET' && status === 200 && out.data) {
          res.writeHead(200, { 'Content-Type': out.contentType, 'Content-Disposition': `${out.image ? 'inline' : 'attachment'}; filename="${out.name}"` })
          res.end(out.data)
        } else {
          res.writeHead(status, { 'Content-Type': 'application/json' })
          res.end(out === null ? '' : JSON.stringify(out))
        }
        return
      }
      const raw = Buffer.concat(chunks).toString()
      const body = raw ? JSON.parse(raw) : null
      // Test hook: add an event as older backends wrote it (e.g. a priority change).
      if (req.url === '/api/__event' && req.method === 'POST') {
        events.unshift({ id: eventId++, at: '2026-09-01T10:00:00Z', user: 'Alice', ...body })
        res.writeHead(204)
        res.end()
        return
      }
      if (req.url === '/api/__reset' && req.method === 'POST') {
        reset()
        res.writeHead(204)
        res.end()
        return
      }
      if (req.url === '/api/__state') {
        res.writeHead(200, { 'Content-Type': 'application/json' })
        res.end(JSON.stringify({ board, share, shareRequests, notes, attachments: attachments.map(attachmentOut), columns: sortedColumns(), labels, tasks: sortedColumns().flatMap((c) => inColumn(c.id)), log }))
        return
      }
      if (req.method !== 'GET') log.push({ method: req.method, url: req.url, body })
      const [status, out] = api(req.method, req.url, body)
      if (req.method !== 'GET' && status < 400) {
        // A new or revoked link cuts off the viewers of the old one; anything else is a change.
        if (req.url === '/api/boards/1/share') endStreams()
        else for (const res of streams) res.write('event: changed\ndata: changed\n\n')
      }
      res.writeHead(status, { 'Content-Type': 'application/json' })
      res.end(out === null ? '' : JSON.stringify(out))
    })
    return
  }
  let file = path.join(distDir, decodeURIComponent(new URL(req.url, 'http://x').pathname))
  if (!fs.existsSync(file) || fs.statSync(file).isDirectory()) file = path.join(distDir, 'index.html')
  res.writeHead(200, { 'Content-Type': types[path.extname(file)] ?? 'application/octet-stream' })
  fs.createReadStream(file).pipe(res)
}).listen(+port, () => console.log(`mock on ${port}`))
