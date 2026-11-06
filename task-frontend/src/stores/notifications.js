import { defineStore } from 'pinia'
import { authFetch } from '@/api/actions.js'

async function call(url, method = 'GET') {
  const response = await authFetch(url, { method })
  if (!response.ok) {
    const error = new Error(`Notifications: HTTP ${response.status}`)
    error.status = response.status
    throw error
  }
  return response
}

/**
 * The bell: the user's newest notifications (see NotificationService in the backend).
 * Loaded when the bell appears, when the tab gets focus, and when an open board stream says
 * "notification" (useBoardEvents).
 */
export const useNotificationStore = defineStore('notifications', {
  // New (unseen) ones count on the bell until it is opened; unread ones keep their mark in the
  // list until opened or "Mark all read", so they can wait for later.
  state: () => ({
    items: [], // [{ id, text, boardName, boardSlug, taskId, taskTitle, created, read }], newest first
    unseen: 0,
  }),
  getters: {
    anyUnread: (state) => state.items.some((item) => !item.read),
  },
  actions: {
    async load() {
      const list = await (await call('/api/notifications')).json()
      this.items = list.items
      this.unseen = list.unseen
    },

    async markSeen() {
      if (!this.unseen) return
      await call('/api/notifications/seen', 'POST')
      this.unseen = 0
    },

    async markRead(item) {
      if (item.read) return
      await call(`/api/notifications/${item.id}/read`, 'POST')
      item.read = true
    },

    async markAllRead() {
      await call('/api/notifications/read-all', 'POST')
      this.items.forEach((item) => (item.read = true))
      this.unseen = 0
    },
  },
})
