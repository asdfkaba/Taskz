import { defineStore } from 'pinia'
import { authFetch } from '@/api/actions.js'

const JSON_HEADERS = {
  Accept: 'application/json',
  'Content-Type': 'application/json',
}

async function expectOk(response, action) {
  if (!response.ok) {
    const error = new Error(`${action} failed (HTTP ${response.status})`)
    error.status = response.status
    throw error
  }
  return response
}

function sendJson(url, method, body) {
  return authFetch(url, { method, headers: JSON_HEADERS, body: JSON.stringify(body) })
}

/**
 * The user's boards, and the open board: columns in order, and per column the tasks in order.
 * Drag & drop changes the arrays in tasksByColumn / columns directly; the persist* actions
 * then tell the backend. If that fails, the caller reloads the board to undo the local change.
 */
export const useBoardStore = defineStore('board', {
  state: () => ({
    boards: [], // [{ id, name }] in creation order
    boardsLoaded: false,
    board: null, // the open board: { id, name }
    columns: [], // [{ id, name, color, position }]
    labels: [], // [{ id, name, color }] of the open board, in creation order
    taskTemplate: null, // Markdown new tasks of the open board start with, or null
    attachmentSettings: null, // { maxFileSize, types, extensions } from the backend, loaded once
    tasksByColumn: {}, // { [columnId]: [task, ...] }
    loading: false,
    loaded: false,
    requestedBoardId: null, // to ignore late answers after switching boards quickly
  }),

  getters: {
    columnById: (state) => (id) => state.columns.find((c) => c.id === id) ?? null,
    // The task's labels in the board's label order.
    labelsOf: (state) => (task) => state.labels.filter((l) => task.labelIds?.includes(l.id)),
    taskCount: (state) =>
      Object.values(state.tasksByColumn).reduce((sum, tasks) => sum + tasks.length, 0),
  },

  actions: {
    reset() {
      this.boards = []
      this.boardsLoaded = false
      this.closeBoard()
    },

    closeBoard() {
      this.board = null
      this.columns = []
      this.labels = []
      this.taskTemplate = null
      this.tasksByColumn = {}
      this.loaded = false
    },

    async fetchBoards() {
      this.boards = await (await expectOk(await authFetch('/api/boards'), 'Loading boards')).json()
      this.boardsLoaded = true
    },

    // Tasks with a due date on all boards, soonest first, without those in a board's last column.
    async fetchDue() {
      return (await expectOk(await authFetch('/api/due'), 'Loading due tasks')).json()
    },

    async fetchBoard(id) {
      // Switching boards: don't show the previous board while the new one loads.
      if (this.board?.id !== id) this.closeBoard()
      this.requestedBoardId = id
      this.loading = true
      try {
        const detail = await (await expectOk(await authFetch(`/api/boards/${id}`), 'Loading board')).json()
        if (this.requestedBoardId !== id) return
        this.applyBoard(detail)
        this.loaded = true
      } finally {
        if (this.requestedBoardId === id) this.loading = false
      }
    },

    // Loads the open board again without the loading state, after someone changed it (see
    // useBoardEvents). Returns 'gone' if the user can't open it anymore.
    async refreshBoard() {
      const id = this.board?.id
      if (!id) return
      const response = await authFetch(`/api/boards/${id}`)
      if (response.status === 404) {
        this.boardsLoaded = false
        return 'gone'
      }
      if (!response.ok || this.board?.id !== id) return
      this.applyBoard(await response.json())
    },

    applyBoard(detail) {
      const tasksByColumn = Object.fromEntries(detail.columns.map((c) => [c.id, []]))
      for (const task of detail.tasks) {
        tasksByColumn[task.columnId]?.push(task)
      }
      this.board = { id: detail.id, name: detail.name }
      this.columns = detail.columns
      this.labels = detail.labels ?? []
      this.taskTemplate = detail.taskTemplate ?? null
      this.tasksByColumn = tasksByColumn
    },

    async createBoard(name) {
      const response = await expectOk(await sendJson('/api/boards', 'POST', { name }), 'Creating board')
      const board = await response.json()
      this.boards.push(board)
      return board
    },

    // Returns { id, name, slug }; the slug follows the new name, so the caller updates the URL.
    async renameBoard(id, name) {
      const response = await expectOk(await sendJson(`/api/boards/${id}`, 'PATCH', { name }), 'Renaming board')
      const board = await response.json()
      const index = this.boards.findIndex((b) => b.id === id)
      if (index !== -1) this.boards.splice(index, 1, board)
      if (this.board?.id === id) this.board = { ...this.board, name: board.name }
      return board
    },

    async deleteBoard(id) {
      await expectOk(await authFetch(`/api/boards/${id}`, { method: 'DELETE' }), 'Deleting board')
      this.boards = this.boards.filter((b) => b.id !== id)
      if (this.board?.id === id) this.closeBoard()
    },

    // ---- Share link (one read-only link per board) ----

    // The board's link as { path, created }, or null if it isn't shared.
    async fetchShare(boardId) {
      const response = await expectOk(await authFetch(`/api/boards/${boardId}/share`), 'Loading share link')
      return response.status === 204 ? null : response.json()
    },

    // Creates the link, or replaces it so the old one stops working.
    async createShare(boardId) {
      const response = await expectOk(
        await authFetch(`/api/boards/${boardId}/share`, { method: 'POST' }),
        'Creating share link',
      )
      return response.json()
    },

    async revokeShare(boardId) {
      await expectOk(await authFetch(`/api/boards/${boardId}/share`, { method: 'DELETE' }), 'Stopping sharing')
    },

    // ---- Rules (automations, owner only): [{ id, columnId, trigger, action, items, days, targetColumnId, enabled }] ----

    async fetchRules(boardId) {
      return (await expectOk(await authFetch(`/api/boards/${boardId}/rules`), 'Loading automations')).json()
    },

    async addRule(boardId, rule) {
      return (await expectOk(await sendJson(`/api/boards/${boardId}/rules`, 'POST', rule), 'Adding automation')).json()
    },

    // Sends the settings and enabled; column, trigger and action can't change.
    async updateRule(rule) {
      return (await expectOk(await sendJson(`/api/rules/${rule.id}`, 'PUT', rule), 'Updating automation')).json()
    },

    async deleteRule(id) {
      await expectOk(await authFetch(`/api/rules/${id}`, { method: 'DELETE' }), 'Deleting automation')
    },

    // ---- Members: the owner and whoever joined through an invite link ----

    // [{ userId, name, owner, joined }], the owner first.
    async fetchMembers(boardId) {
      return (await expectOk(await authFetch(`/api/boards/${boardId}/members`), 'Loading members')).json()
    },

    // { path, expires }. Works once; the server keeps only its hash, so it can't be shown again.
    async createInvite(boardId) {
      const response = await expectOk(
        await authFetch(`/api/boards/${boardId}/invites`, { method: 'POST' }),
        'Creating invite link',
      )
      return response.json()
    },

    // The user's own API tokens: [{ id, name, boardId, boardName, read, write, created }].
    async fetchApiTokens() {
      return (await expectOk(await authFetch('/api/account/tokens'), 'Loading API tokens')).json()
    },

    // { info, token }. The token is shown once; the server keeps only its hash.
    // scopes: e.g. ['tasks:read']; expiresInDays: null for a token that doesn't expire.
    async createApiToken({ boardId, name, scopes, expiresInDays }) {
      const response = await expectOk(await sendJson('/api/account/tokens', 'POST', { boardId, name, scopes, expiresInDays }), 'Creating API token')
      return response.json()
    },

    async revokeApiToken(id) {
      await expectOk(await authFetch(`/api/account/tokens/${id}`, { method: 'DELETE' }), 'Revoking API token')
    },

    // The user's logins, newest first; current marks this browser's.
    async fetchSessions() {
      return (await expectOk(await authFetch('/api/account/sessions'), 'Loading sessions')).json()
    },

    async endSession(id) {
      await expectOk(await authFetch(`/api/account/sessions/${id}`, { method: 'DELETE' }), 'Logging out the device')
    },

    async endOtherSessions() {
      await expectOk(await authFetch('/api/account/sessions', { method: 'DELETE' }), 'Logging out other devices')
    },

    // email: the account's address, as confirmation.
    async deleteAccount(email) {
      await expectOk(await sendJson('/api/account', 'DELETE', { email }), 'Deleting account')
    },

    async removeMember(boardId, userId) {
      await expectOk(await authFetch(`/api/boards/${boardId}/members/${userId}`, { method: 'DELETE' }), 'Removing member')
    },

    async leaveBoard(boardId) {
      await expectOk(await authFetch(`/api/boards/${boardId}/membership`, { method: 'DELETE' }), 'Leaving board')
      this.boards = this.boards.filter((b) => b.id !== boardId)
      if (this.board?.id === boardId) this.closeBoard()
    },

    // { boardName, ownerName, member }; 404 for unknown, used and expired links.
    async fetchInvite(token) {
      return (await expectOk(await authFetch(`/api/invites/${encodeURIComponent(token)}`), 'Loading invite')).json()
    },

    // Returns the board's summary { id, name, slug, own, ownerName }.
    async joinBoard(token) {
      const response = await expectOk(
        await authFetch(`/api/invites/${encodeURIComponent(token)}`, { method: 'POST' }),
        'Joining board',
      )
      // The list gets the board with the address it has for this user.
      this.boardsLoaded = false
      return response.json()
    },

    // ---- Tasks ----

    findTask(id) {
      for (const [columnId, tasks] of Object.entries(this.tasksByColumn)) {
        const index = tasks.findIndex((t) => t.id === id)
        if (index !== -1) return { columnId: Number(columnId), index, task: tasks[index] }
      }
      return null
    },

    async createTask(columnId, data) {
      const response = await expectOk(
        await sendJson('/api/tasks', 'POST', { ...data, columnId }),
        'Creating task',
      )
      const task = await response.json()
      this.tasksByColumn[columnId].push(task)
      return task
    },

    // PUT replaces all editable fields, so send the current values plus the changes.
    async updateTask(id, changes) {
      const found = this.findTask(id)
      if (!found) throw new Error('Task not found')
      const { task } = found

      const payload = {
        title: task.title,
        description: task.description,
        due_date: task.due_date,
        labelIds: task.labelIds,
        ...changes,
      }
      const response = await expectOk(await sendJson(`/api/tasks/${id}`, 'PUT', payload), 'Updating task')
      const updated = await response.json()
      this.tasksByColumn[found.columnId].splice(found.index, 1, updated)
      return updated
    },

    // Ticks or unticks one checklist item; 404 if the item is gone (someone changed the checklist).
    async setChecklistItem(taskId, itemId, checked) {
      const response = await expectOk(
        await sendJson(`/api/tasks/${taskId}/checklist/${itemId}`, 'PATCH', { checked }),
        'Updating checklist',
      )
      const updated = await response.json()
      const found = this.findTask(taskId)
      if (found && updated.columnId !== found.columnId) {
        // Completing the checklist made a rule move the task to the end of another column.
        this.tasksByColumn[found.columnId].splice(found.index, 1)
        this.tasksByColumn[updated.columnId]?.push(updated)
        this.renumberTasks()
      } else if (found) {
        this.tasksByColumn[found.columnId].splice(found.index, 1, updated)
      }
      return updated
    },

    // Also deletes an archived task for good.
    async deleteTask(id) {
      await expectOk(await authFetch(`/api/tasks/${id}/delete`, { method: 'DELETE' }), 'Deleting task')
      const found = this.findTask(id)
      if (found) this.tasksByColumn[found.columnId].splice(found.index, 1)
    },

    // ---- Archive: tasks off the board that can come back ----

    async archiveTask(id) {
      await expectOk(await authFetch(`/api/tasks/${id}/archive`, { method: 'PATCH' }), 'Archiving task')
      const found = this.findTask(id)
      if (found) this.tasksByColumn[found.columnId].splice(found.index, 1)
      this.renumberTasks()
    },

    // The open board's archived tasks, last archived first; each with archived (when).
    async fetchArchive() {
      return (await expectOk(await authFetch(`/api/boards/${this.board.id}/archive`), 'Loading archive')).json()
    },

    // The task comes back at the end of its column.
    async restoreTask(id) {
      const response = await expectOk(await authFetch(`/api/tasks/${id}/restore`, { method: 'PATCH' }), 'Restoring task')
      const task = await response.json()
      if (!this.findTask(id)) this.tasksByColumn[task.columnId]?.push(task)
      return task
    },

    // Moves a task locally (e.g. from a menu) and then saves it.
    async moveTask(id, columnId, index) {
      const found = this.findTask(id)
      if (!found) throw new Error('Task not found')
      this.tasksByColumn[found.columnId].splice(found.index, 1)
      this.tasksByColumn[columnId].splice(index, 0, found.task)
      await this.persistTaskMove(id)
    },

    // Saves the place the task now has in tasksByColumn (after drag & drop or moveTask).
    async persistTaskMove(id) {
      const found = this.findTask(id)
      if (!found) return
      found.task.columnId = found.columnId
      this.renumberTasks()
      const response = await expectOk(
        await sendJson(`/api/tasks/${id}/move`, 'PATCH', { columnId: found.columnId, position: found.index }),
        'Moving task',
      )
      // Rules of the new column may have changed these; the place stays as it is now locally.
      const { checklist, due_date, edited, revision } = await response.json()
      Object.assign(found.task, { checklist, due_date, edited, revision })
    },

    renumberTasks() {
      for (const tasks of Object.values(this.tasksByColumn)) {
        tasks.forEach((t, i) => (t.position = i))
      }
    },

    // Newest first: [{ id, type, at, user, changes }]
    async fetchTaskHistory(id) {
      return (await expectOk(await authFetch(`/api/tasks/${id}/history`), 'Loading history')).json()
    },

    // ---- Notes (plain text, newest first: [{ id, text, user, created, edited, remindAt }]) ----
    // remindAt: wall-clock time (toApi) the note shows in "What's due" for its author; null for none.

    async fetchNotes(taskId) {
      return (await expectOk(await authFetch(`/api/tasks/${taskId}/notes`), 'Loading notes')).json()
    },

    async addNote(taskId, text, remindAt = null) {
      return (await expectOk(await sendJson(`/api/tasks/${taskId}/notes`, 'POST', { text, remindAt }), 'Adding note')).json()
    },

    // Sets text and reminder; remindAt null removes the reminder.
    async updateNote(id, text, remindAt) {
      return (await expectOk(await sendJson(`/api/notes/${id}`, 'PATCH', { text, remindAt }), 'Saving note')).json()
    },

    async deleteNote(id) {
      await expectOk(await authFetch(`/api/notes/${id}`, { method: 'DELETE' }), 'Deleting note')
    },

    // ---- Attachments ----

    // The same for every task, so loaded once.
    async fetchAttachmentSettings() {
      if (!this.attachmentSettings) {
        const response = await expectOk(await authFetch('/api/attachments/settings'), 'Loading attachment settings')
        this.attachmentSettings = await response.json()
      }
      return this.attachmentSettings
    },

    // The content of a text attachment (TXT, MD), for the preview.
    async fetchAttachmentText(id) {
      return (await expectOk(await authFetch(`/api/attachments/${id}`), 'Loading attachment')).text()
    },

    async fetchAttachments(taskId) {
      return (await expectOk(await authFetch(`/api/tasks/${taskId}/attachments`), 'Loading attachments')).json()
    },

    // Rejected uploads (type, size) come with the backend's reason as the error message.
    async uploadAttachment(taskId, file) {
      const body = new FormData()
      body.append('file', file)
      const response = await authFetch(`/api/tasks/${taskId}/attachments`, { method: 'POST', body })
      if (!response.ok) {
        const reason = await response.json().then((b) => b.message, () => null)
        // A 413 without a reason comes from nginx (client_max_body_size), not from the app.
        const error = new Error(reason ?? (response.status === 413
          ? 'The server refused the file as too large (HTTP 413)'
          : `Upload failed (HTTP ${response.status})`))
        error.status = response.status
        throw error
      }
      this.countAttachments(taskId, +1)
      return response.json()
    },

    async deleteAttachment(taskId, id) {
      await expectOk(await authFetch(`/api/attachments/${id}`, { method: 'DELETE' }), 'Deleting attachment')
      this.countAttachments(taskId, -1)
    },

    // Keeps the paperclip count on the card in step, without reloading the task.
    countAttachments(taskId, change) {
      const found = this.findTask(taskId)
      if (found) found.task.attachments = Math.max(0, (found.task.attachments ?? 0) + change)
    },

    // ---- Labels ----

    async addLabel(name, color) {
      const response = await expectOk(
        await sendJson(`/api/boards/${this.board.id}/labels`, 'POST', { name, color }),
        'Adding label',
      )
      const label = await response.json()
      this.labels.push(label)
      return label
    },

    // changes: any of { name, color }
    async updateLabel(id, changes) {
      const response = await expectOk(await sendJson(`/api/labels/${id}`, 'PATCH', changes), 'Updating label')
      const updated = await response.json()
      const index = this.labels.findIndex((l) => l.id === id)
      if (index !== -1) this.labels.splice(index, 1, updated)
      return updated
    },

    // The backend takes the label off all tasks; do the same locally.
    async deleteLabel(id) {
      await expectOk(await authFetch(`/api/labels/${id}`, { method: 'DELETE' }), 'Deleting label')
      this.labels = this.labels.filter((l) => l.id !== id)
      for (const tasks of Object.values(this.tasksByColumn)) {
        for (const task of tasks) {
          if (task.labelIds?.includes(id)) task.labelIds = task.labelIds.filter((x) => x !== id)
        }
      }
    },

    // ---- Columns ----

    async setTaskTemplate(template) {
      const response = await expectOk(
        await sendJson(`/api/boards/${this.board.id}/task-template`, 'PUT', { template }),
        'Saving task template',
      )
      this.taskTemplate = (await response.json()).template
    },

    async addColumn(name, color) {
      const response = await expectOk(
        await sendJson(`/api/boards/${this.board.id}/columns`, 'POST', { name, color }),
        'Adding column',
      )
      const column = await response.json()
      this.columns.push(column)
      this.tasksByColumn[column.id] = []
      return column
    },

    // changes: any of { name, color, position }; color null removes the color.
    async updateColumn(id, changes) {
      const response = await expectOk(await sendJson(`/api/columns/${id}`, 'PATCH', changes), 'Updating column')
      const updated = await response.json()
      const index = this.columns.findIndex((c) => c.id === id)
      if (index !== -1) this.columns.splice(index, 1, { ...this.columns[index], ...updated })
      return updated
    },

    // Moves a column locally (e.g. from its menu) and then saves it.
    async moveColumn(id, index) {
      const from = this.columns.findIndex((c) => c.id === id)
      if (from === -1) return
      const [column] = this.columns.splice(from, 1)
      this.columns.splice(index, 0, column)
      await this.persistColumnMove(id)
    },

    // Saves the place the column now has in columns (after drag & drop or moveColumn).
    async persistColumnMove(id) {
      const index = this.columns.findIndex((c) => c.id === id)
      if (index === -1) return
      this.columns.forEach((c, i) => (c.position = i))
      await expectOk(await sendJson(`/api/columns/${id}`, 'PATCH', { position: index }), 'Moving column')
    },

    // choice: { moveTo: columnId } or { deleteTasks: true }; not needed for an empty column.
    async deleteColumn(id, choice = {}) {
      const params = new URLSearchParams()
      if (choice.moveTo != null) params.set('moveTo', choice.moveTo)
      if (choice.deleteTasks) params.set('deleteTasks', 'true')
      const query = params.toString() ? `?${params}` : ''

      await expectOk(await authFetch(`/api/columns/${id}${query}`, { method: 'DELETE' }), 'Deleting column')

      const moved = this.tasksByColumn[id] ?? []
      if (choice.moveTo != null) {
        moved.forEach((t) => (t.columnId = choice.moveTo))
        this.tasksByColumn[choice.moveTo].push(...moved)
      }
      delete this.tasksByColumn[id]
      this.columns = this.columns.filter((c) => c.id !== id)
      this.columns.forEach((c, i) => (c.position = i))
      this.renumberTasks()
    },
  },
})
