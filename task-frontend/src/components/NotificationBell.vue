<script setup>
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { formatDistanceToNow } from 'date-fns'
import Button from 'primevue/button'
import Popover from 'primevue/popover'
import { useToast } from 'primevue/usetoast'
import { useNotificationStore } from '@/stores/notifications'
import { fromApi } from '@/utils/date'

// The bell in the header: the count of new notifications, and the newest ones in a popover.
// Opening it makes them seen (the count goes); unread ones keep their dot until opened or
// "Mark all read". An entry links to its task or board when it has one and the user can open it.
const store = useNotificationStore()
const router = useRouter()
const toast = useToast()
const panel = ref()

async function load() {
  try {
    await store.load()
  } catch {
    // Not shown: the bell just keeps what it had; the next load tries again.
  }
}

function onVisible() {
  if (document.visibilityState === 'visible') load()
}

onMounted(() => {
  load()
  document.addEventListener('visibilitychange', onVisible)
})
onBeforeUnmount(() => document.removeEventListener('visibilitychange', onVisible))

function target(item) {
  if (!item.boardSlug) return null
  return { name: 'board', params: { slug: item.boardSlug }, query: item.taskId ? { task: item.taskId } : {} }
}

async function open(item) {
  panel.value.hide()
  try {
    await store.markRead(item)
  } catch (err) {
    toast.add({ severity: 'error', summary: 'Could not mark as read', detail: err.message, life: 5000 })
  }
  const to = target(item)
  if (to) router.push(to)
}

async function toggle(event) {
  panel.value.toggle(event)
  try {
    await store.markSeen()
  } catch {
    // The count stays; the next opening tries again.
  }
}

async function markAllRead() {
  try {
    await store.markAllRead()
  } catch (err) {
    toast.add({ severity: 'error', summary: 'Could not mark as read', detail: err.message, life: 5000 })
  }
}

const label = () => (store.unseen ? `Notifications, ${store.unseen} new` : 'Notifications')
const relative = (at) => formatDistanceToNow(fromApi(at), { addSuffix: true })
</script>

<template>
  <div class="relative">
    <Button
      icon="pi pi-bell"
      :aria-label="label()"
      :title="label()"
      severity="secondary"
      text
      rounded
      size="small"
      aria-haspopup="true"
      data-testid="notification-bell"
      @click="toggle"
    />
    <span
      v-if="store.unseen"
      class="pointer-events-none absolute -top-0.5 -right-0.5 min-w-4 rounded-full bg-red-600 px-1 text-center text-[0.625rem] leading-4 font-semibold text-white"
      aria-hidden="true"
      data-testid="notification-count"
    >
      {{ store.unseen > 99 ? '99+' : store.unseen }}
    </span>

    <Popover ref="panel">
      <div class="flex w-80 max-w-[calc(100vw-2rem)] flex-col gap-2" data-testid="notifications">
        <div class="flex items-center justify-between gap-2">
          <h2 class="text-sm font-semibold text-slate-900 dark:text-slate-100">Notifications</h2>
          <Button v-if="store.anyUnread" label="Mark all read" text size="small" class="!py-0.5" @click="markAllRead" />
        </div>
        <p v-if="!store.items.length" class="py-4 text-center text-sm text-slate-500 dark:text-slate-400">
          No notifications yet.
        </p>
        <ul v-else class="-mx-2 flex max-h-96 flex-col overflow-y-auto">
          <li v-for="item in store.items" :key="item.id">
            <button
              type="button"
              class="flex w-full items-start gap-2 rounded-md px-2 py-2 text-left hover:bg-slate-100 dark:hover:bg-slate-800"
              :data-read="item.read"
              data-testid="notification"
              @click="open(item)"
            >
              <span
                class="mt-1.5 h-2 w-2 shrink-0 rounded-full"
                :class="item.read ? 'bg-transparent' : 'bg-emerald-500'"
                aria-hidden="true"
              />
              <span class="min-w-0 flex-1">
                <span
                  class="block text-sm break-words text-slate-800 dark:text-slate-200"
                  :class="{ 'font-semibold': !item.read }"
                >
                  <span v-if="!item.read" class="sr-only">Unread: </span>{{ item.text }}
                </span>
                <span class="block truncate text-xs text-slate-500 dark:text-slate-400">
                  {{ [item.taskTitle, item.boardName].filter(Boolean).join(' · ') }}
                  <template v-if="item.taskTitle || item.boardName"> · </template>{{ relative(item.created) }}
                </span>
              </span>
            </button>
          </li>
        </ul>
      </div>
    </Popover>
  </div>
</template>
