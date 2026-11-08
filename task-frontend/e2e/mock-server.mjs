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
  events.unshift({ id: eventId++, taskId: task.id, type, at: now(), user: 'Alice', userId: 1, changes })
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
// Alice's bell ([{ id, text, boardName, boardSlug, taskId, taskTitle, created, seen, read }]), and what NOTIFY rules
// sent to others ([{ userId, text, taskId }]).
let notifications
let sentToOthers
// Alice's mail setting; her unsubscribe link is /unsubscribe/mail-token.
let mail
let invites
let teamJoined
// Alice's API tokens ([{ id, name, boardId, boardName, scopes, created, expiresAt, lastUsedAt }]).
let apiTokens
const TEAM_BOARD = { id: 2, name: 'Team board', slug: 'team-board', own: false, ownerName: 'Bob', ownerId: 2 }
// Open live views of shared boards (server-sent events), like BoardEvents in the backend.
const streams = new Set()
const endStreams = () => { for (const res of streams) res.end(); streams.clear() }
// The same for the owner's view of board 1 (/boards/1/events), which also gets "presence".
const boardStreams = new Set()
const sse = (res, name, data) => res.write(`event: ${name}\ndata: ${data}\n\n`)
let revisions = 0
const newRevision = () => `rev${++revisions}`
const shareOk = (ref) => ref === 'test-token' || (share && (ref === OLD_UUID || ref === share.key || (ref.length > 23 && ref.endsWith('-' + share.key))))
let tasks
let itemId // ids of checklist items
// Automations of board 1 ([{ id, columnId, action, items, text, days, removeOnLeave, userIds, enabled }]), applied like RuleEngine.
let rules
let notes // [{ id, taskId, text, user, created, edited, remindAt, ruleId }]; all of them Alice's
let attachments // [{ id, taskId, name, contentType, size, image, user, created, data }]
let log

function reset() {
  endStreams()
  for (const res of boardStreams) res.end()
  boardStreams.clear()
  members = []
  notifications = []
  sentToOthers = []
  mail = { available: true, address: 'alice@example.com', delivery: 'OFF' }
  invites = []
  teamJoined = false
  apiTokens = []
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
  tasks.forEach((t) => Object.assign(t, { revision: newRevision(), checklist: [] }))
  itemId = 1000
  rules = []
  notes = []
  attachments = []
  log = []
}
reset()

const inColumn = (id) => tasks.filter((t) => t.columnId === id).sort((a, b) => a.position - b.position)
const renumber = (list) => list.forEach((t, i) => (t.position = i))
const sortedColumns = () => [...columns].sort((a, b) => a.position - b.position)

// Checklist items like the backend: Markdown prefix dropped, blanks skipped, new ones unticked.
const cleanItem = (text) => (text ?? '').trim().replace(/^(?:(?:[-*+]|\d{1,9}[.)])\s+)?(?:\[[ xX]]\s*)?/, '').trim()
const newItem = (text, checked = false) => ({ id: itemId++, text, checked })
const itemTexts = (t) => (t.checklist ?? []).map((i) => i.text)

// Wall-clock time without a zone, like LocalDateTime.
const pad = (n) => String(n).padStart(2, '0')
const localTime = (d) => `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}:00`
const addNote = (taskId, text, remindAt, ruleId = null) => {
  const note = { id: nextId++, taskId, text, user: 'Alice', userId: 1, created: now(), edited: null, remindAt: remindAt ?? null, canEdit: true, canDelete: true, ruleId }
  notes.push(note)
  return note
}
// Like RuleEngine.taskLeft: notes of the column's enabled rules with removeOnLeave go.
function leaveRules(t, columnId) {
  const ids = rules.filter((r) => r.columnId === columnId && r.enabled && r.removeOnLeave).map((r) => r.id)
  notes = notes.filter((n) => !(n.taskId === t.id && ids.includes(n.ruleId)))
}

// Rules like RuleEngine: checklist items added or replaced (ticks kept), a note reminding in N days.
function runRules(t) {
  const before = { checklist: itemTexts(t), due_date: t.due_date }
  for (const r of rules.filter((x) => x.columnId === t.columnId && x.enabled)) {
    if (r.action === 'REPLACE_CHECKLIST') {
      t.checklist = r.items.map((text) => t.checklist.find((i) => i.text === text) ?? newItem(text))
    } else if (r.action === 'APPEND_CHECKLIST') {
      t.checklist = [...t.checklist, ...r.items.filter((text) => !itemTexts(t).includes(text)).map((text) => newItem(text))]
    } else if (r.action === 'NOTIFY') {
      // Alice moved it, so she isn't told; the others' bells aren't in the mock.
      for (const userId of r.userIds.filter((id) => id !== 1)) sentToOthers.push({ userId, text: r.text, taskId: t.id })
    } else {
      addNote(t.id, r.text, localTime(new Date(Date.now() + r.days * 86400000)), r.id)
    }
  }
  const changes = { automation: true }
  const added = itemTexts(t).filter((x) => !before.checklist.includes(x))
  const removed = before.checklist.filter((x) => !itemTexts(t).includes(x))
  if (added.length) changes.checklistAdded = added
  if (removed.length) changes.checklistRemoved = removed
  if (t.due_date !== before.due_date) changes.due_date = [before.due_date, t.due_date]
  if (Object.keys(changes).length > 1) {
    t.revision = newRevision()
    events.unshift({ id: eventId++, taskId: t.id, type: 'updated', at: now(), user: null, userId: null, changes })
  }
}

function api(method, url, body) {
  const u = new URL(url, 'http://x')
  const p = u.pathname.replace(/^\/api/, '')
  let m
  if (method === 'GET' && p === '/auth/status') return [200, { email: 'alice@example.com', name: 'Alice', id: 1 }]
  // Like the backend: the code "unverified" stands for an account without a confirmed email.
  if (method === 'POST' && p === '/auth/login') {
    if (body?.code === 'unverified') return [401, { message: 'Your account there has no confirmed email address. Confirm one and try again.' }]
    return [200, 'Login successful']
  }
  if (method === 'GET' && p === '/boards') {
    const own = boardDeleted ? [] : [{ id: 1, name: board.name, slug: board.slug, own: true, ownerName: null }]
    return [200, teamJoined ? [...own, TEAM_BOARD] : own]
  }
  // Like BoardService.dueTasksOf for board 1: tasks with a due date and notes with a reminder, not in
  // the last column, soonest first (tasks before notes on the same time).
  if (method === 'GET' && p === '/due') {
    const cols = sortedColumns()
    const open = (t) => cols.length === 1 || t.columnId !== cols.at(-1).id
    const time = (d) => (Array.isArray(d) ? new Date(d[0], d[1] - 1, d[2], d[3] ?? 0, d[4] ?? 0) : new Date(d)).getTime()
    const entry = (t, due_date, note) => {
      const c = columns.find((x) => x.id === t.columnId)
      return { id: t.id, title: t.title, due_date, boardName: board.name, boardSlug: board.slug, columnName: c.name, columnColor: c.color, noteId: note?.id ?? null, noteText: note?.text ?? null }
    }
    const due = [
      ...tasks.filter((t) => t.due_date && open(t)).map((t) => entry(t, t.due_date, null)),
      ...notes.filter((n) => n.remindAt).map((n) => [tasks.find((t) => t.id === n.taskId), n]).filter(([t]) => t && open(t)).map(([t, n]) => entry(t, n.remindAt, n)),
    ]
    return [200, due.sort((a, b) => time(a.due_date) - time(b.due_date))]
  }
  if (method === 'GET' && p === '/notifications') {
    const items = [...notifications].reverse().map(({ seen, ...n }) => n)
    return [200, { items, unseen: notifications.filter((n) => !n.seen).length }]
  }
  if (p === '/account/notifications/mail') {
    if (method === 'PUT') {
      if (!['OFF', 'IMMEDIATE', 'DAILY', 'WEEKLY'].includes(body.delivery)) return [400, {}]
      if (body.delivery !== 'OFF' && !mail.available) return [409, {}]
      mail.delivery = body.delivery
    }
    return [200, mail]
  }
  if (method === 'POST' && (m = p.match(/^\/mail\/unsubscribe\/([^/]+)$/))) {
    if (m[1] !== 'mail-token') return [404, {}]
    mail.delivery = 'OFF'
    return [204, null]
  }
  if (method === 'POST' && p === '/notifications/seen') {
    notifications.forEach((n) => (n.seen = true))
    return [204, null]
  }
  if (method === 'POST' && (m = p.match(/^\/notifications\/(\d+)\/read$/))) {
    const n = notifications.find((x) => x.id === +m[1])
    if (!n) return [404, {}]
    Object.assign(n, { seen: true, read: true })
    return [204, null]
  }
  if (method === 'POST' && p === '/notifications/read-all') {
    notifications.forEach((n) => Object.assign(n, { seen: true, read: true }))
    return [204, null]
  }
  if (method === 'GET' && p === '/account/tokens') return [200, apiTokens]
  if (method === 'POST' && p === '/account/tokens') {
    const name = body.name?.trim()
    if (body.boardId !== 1 || !name || !body.scopes?.length) return [400, { message: 'Bad token request' }]
    const expiresAt = body.expiresInDays ? new Date(Date.now() + body.expiresInDays * 86400000).toISOString() : null
    const info = { id: nextId++, name, boardId: 1, boardName: board.name, scopes: body.scopes, created: now(), expiresAt, lastUsedAt: null }
    apiTokens.push(info)
    return [201, { info, token: `taskz_${newKey()}${newKey()}` }]
  }
  if (method === 'DELETE' && (m = p.match(/^\/account\/tokens\/(\d+)$/))) {
    const before = apiTokens.length
    apiTokens = apiTokens.filter((t) => t.id !== +m[1])
    return apiTokens.length < before ? [204, null] : [404, {}]
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
      return [200, method === 'POST' ? TEAM_BOARD : { boardName: 'Team board', ownerName: 'Bob', ownerId: 2, member: teamJoined }]
    }
    // Alice owns board 1, so her own links only open it.
    if (invites.includes(key)) return [200, method === 'POST' ? { id: 1, name: board.name, slug: board.slug, own: true, ownerName: null } : { boardName: board.name, ownerName: 'Alice', ownerId: 1, member: true }]
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
    const t = { id: nextId++, title: body.title, description: body.description, due_date: body.due_date, created: now(), edited: null, columnId: body.columnId, position: inColumn(body.columnId).length, labelIds: body.labelIds ?? [], checklist: (body.checklist ?? []).map((i) => cleanItem(i.text)).filter(Boolean).map((text) => newItem(text)) }
    t.revision = newRevision()
    tasks.push(t)
    record(t, 'created', { title: t.title, column: colName(t.columnId) })
    runRules(t)
    return [201, t]
  }
  if ((m = p.match(/^\/tasks\/(\d+)\/move$/)) && method === 'PATCH') {
    const t = tasks.find((x) => x.id === +m[1])
    if (body.columnId !== t.columnId) record(t, 'moved', { column: [colName(t.columnId), colName(body.columnId)] })
    const source = inColumn(t.columnId).filter((x) => x !== t)
    const target = body.columnId === t.columnId ? source : inColumn(body.columnId)
    target.splice(Math.min(body.position, target.length), 0, t)
    const entered = t.columnId !== body.columnId
    if (entered) leaveRules(t, t.columnId)
    t.columnId = body.columnId
    renumber(source)
    renumber(target)
    if (entered) runRules(t)
    return [200, t]
  }
  if ((m = p.match(/^\/tasks\/(\d+)\/notes$/))) {
    const taskId = +m[1]
    if (!tasks.some((t) => t.id === taskId)) return [404, {}]
    if (method === 'GET') {
      return [200, notes.filter((n) => n.taskId === taskId).sort((a, b) => b.id - a.id).map(({ taskId, ruleId, ...n }) => n)]
    }
    if (method === 'POST') {
      const text = (body.text ?? '').trim()
      if (!text || text.length > 5000) return [400, {}]
      const note = addNote(taskId, text, body.remindAt)
      const { taskId: _, ruleId: __, ...out } = note
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
      note.remindAt = body.remindAt ?? null
      const { taskId: _, ruleId: __, ...out } = note
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
  if (method === 'GET' && p === '/boards/1/rules') return [200, rules]
  if (method === 'POST' && p === '/boards/1/rules') {
    const r = { id: nextId++, columnId: body.columnId, action: body.action, items: body.items ?? null, text: body.text ?? null, days: body.days ?? null, removeOnLeave: body.removeOnLeave ?? false, userIds: body.userIds ?? null, enabled: true }
    rules.push(r)
    return [201, r]
  }
  if ((m = p.match(/^\/rules\/(\d+)$/))) {
    const r = rules.find((x) => x.id === +m[1])
    if (!r) return [404, {}]
    if (method === 'PUT') { Object.assign(r, { items: body.items ?? r.items, text: body.text ?? r.text, days: body.days ?? r.days, removeOnLeave: body.removeOnLeave ?? r.removeOnLeave, userIds: body.userIds ?? r.userIds, enabled: body.enabled ?? r.enabled }); return [200, r] }
    if (method === 'DELETE') { rules = rules.filter((x) => x !== r); return [204, null] }
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
  if ((m = p.match(/^\/tasks\/(\d+)\/checklist\/(\d+)$/)) && method === 'PATCH') {
    const t = tasks.find((x) => x.id === +m[1])
    const item = t?.checklist.find((i) => i.id === +m[2])
    if (!item) return [404, { message: 'Checklist item not found' }]
    if (item.checked !== body.checked) {
      item.checked = body.checked
      record(t, body.checked ? 'checked' : 'unchecked', { item: item.text })
    }
    return [200, t]
  }
  if ((m = p.match(/^\/tasks\/(\d+)$/)) && method === 'PUT') {
    const t = tasks.find((x) => x.id === +m[1])
    // Like the backend: saving from an older revision is refused.
    if (body.revision && body.revision !== t.revision) return [409, { message: 'Someone else changed this task in the meantime' }]
    t.revision = newRevision()
    if (body.labelIds && body.labelIds.some((id) => !labels.some((l) => l.id === id))) return [400, {}]
    const before = { title: t.title, description: t.description || null, due_date: t.due_date ?? null, labels: labelNames(t.labelIds) }
    Object.assign(t, { title: body.title, description: body.description, due_date: body.due_date })
    if (body.labelIds) t.labelIds = [...body.labelIds].sort((a, b) => a - b)
    const itemsBefore = itemTexts(t)
    if (body.checklist) {
      t.checklist = body.checklist
        .map((i) => ({ id: i.id, text: cleanItem(i.text) }))
        .filter((i) => i.text)
        .map((i) => (i.id ? { ...t.checklist.find((x) => x.id === i.id), text: i.text } : newItem(i.text)))
    }
    const after = { title: t.title, description: t.description || null, due_date: t.due_date ?? null, labels: labelNames(t.labelIds) }
    const changes = {}
    for (const f of ['title', 'description', 'due_date']) if (JSON.stringify(before[f]) !== JSON.stringify(after[f])) changes[f] = [before[f], after[f]]
    const added = after.labels.filter((n) => !before.labels.includes(n))
    const removed = before.labels.filter((n) => !after.labels.includes(n))
    if (added.length) changes.labelsAdded = added
    if (removed.length) changes.labelsRemoved = removed
    const itemsAdded = itemTexts(t).filter((x) => !itemsBefore.includes(x))
    const itemsRemoved = itemsBefore.filter((x) => !itemTexts(t).includes(x))
    if (itemsAdded.length) changes.checklistAdded = itemsAdded
    if (itemsRemoved.length) changes.checklistRemoved = itemsRemoved
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
      const a = { id: nextId++, taskId: task.id, name: file.name, contentType, size: data.length, image: contentType.startsWith('image/'), user: 'Alice', userId: 1, created: now(), data }
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
      if (req.method === 'GET' && req.url === '/api/boards/1/events') {
        res.writeHead(200, { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-cache' })
        res.write(':open\n\n')
        boardStreams.add(res)
        res.on('close', () => boardStreams.delete(res))
        return
      }
      // Test hooks: a notification for Alice (like WebUiChannel: stored, then a nudge on her board
      // streams), and members of board 1.
      if (req.url === '/api/__notify' && req.method === 'POST') {
        const body = JSON.parse(Buffer.concat(chunks).toString())
        notifications.push({ id: nextId++, boardName: null, boardSlug: null, taskId: null, taskTitle: null, created: new Date().toISOString(), seen: false, read: false, ...body })
        for (const res of boardStreams) sse(res, 'notification', 'new')
        res.writeHead(204)
        res.end()
        return
      }
      if (req.url === '/api/__mail' && req.method === 'POST') {
        Object.assign(mail, JSON.parse(Buffer.concat(chunks).toString()))
        res.writeHead(204)
        res.end()
        return
      }
      if (req.url === '/api/__members' && req.method === 'POST') {
        members = JSON.parse(Buffer.concat(chunks).toString())
        res.writeHead(204)
        res.end()
        return
      }
      // Test hooks: who else has board 1 open, and a change by someone else (no own request).
      if (req.url === '/api/__presence' && req.method === 'POST') {
        for (const res of boardStreams) sse(res, 'presence', Buffer.concat(chunks).toString())
        res.writeHead(204)
        res.end()
        return
      }
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
          // Like TaskAttachmentController: only CSV is a download.
          const inline = !out.contentType.startsWith('text/csv')
          res.writeHead(200, { 'Content-Type': out.contentType, 'Content-Disposition': `${inline ? 'inline' : 'attachment'}; filename="${out.name}"` })
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
        res.end(JSON.stringify({ board, apiTokens, rules, notifications, sentToOthers, mail, share, shareRequests, notes, attachments: attachments.map(attachmentOut), columns: sortedColumns(), labels, tasks: sortedColumns().flatMap((c) => inColumn(c.id)), log }))
        return
      }
      if (req.method !== 'GET') log.push({ method: req.method, url: req.url, body })
      const [status, out] = api(req.method, req.url, body)
      // Test hook: x-mock-silent leaves open pages unaware of the change, like a live update that
      // hasn't arrived yet.
      if (req.method !== 'GET' && status < 400 && !req.headers['x-mock-silent']) {
        // A new or revoked link cuts off the viewers of the old one; anything else is a change.
        if (req.url === '/api/boards/1/share') endStreams()
        else for (const res of streams) res.write('event: changed\ndata: changed\n\n')
        for (const res of boardStreams) sse(res, 'changed', 'changed')
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
