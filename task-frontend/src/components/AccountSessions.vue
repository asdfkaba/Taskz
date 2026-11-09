<script setup>
import { onMounted, ref } from 'vue'
import Button from 'primevue/button'
import Tag from 'primevue/tag'
import { useConfirm } from 'primevue/useconfirm'
import { useToast } from 'primevue/usetoast'
import { useBoardStore } from '@/stores/board'
import { fromApi, formatShort } from '@/utils/date'

// Where the user is logged in. A device logged out here keeps working for up to a minute, until
// its short-lived access token runs out.

const store = useBoardStore()
const confirm = useConfirm()
const toast = useToast()

const sessions = ref([])
const loading = ref(true)

// "Firefox on Linux" from the User-Agent header; order matters (Edge and Opera also say Chrome).
const BROWSERS = [['Edg/', 'Edge'], ['OPR/', 'Opera'], ['Firefox/', 'Firefox'], ['Chrome/', 'Chrome'], ['Safari/', 'Safari']]
const SYSTEMS = [['Windows', 'Windows'], ['Android', 'Android'], ['iPhone', 'iOS'], ['iPad', 'iOS'], ['Mac OS X', 'macOS'], ['Linux', 'Linux']]
function device(userAgent) {
  const find = (list) => list.find(([marker]) => userAgent?.includes(marker))?.[1]
  const browser = find(BROWSERS)
  const system = find(SYSTEMS)
  if (!browser && !system) return 'Unknown device'
  return [browser ?? 'Browser', system].filter(Boolean).join(' on ')
}

function details(session) {
  return [session.ipAddress, `logged in ${formatShort(fromApi(session.issuedAt))}`].filter(Boolean).join(' · ')
}

function showError(summary, err) {
  toast.add({ severity: 'error', summary, detail: err.message, life: 5000 })
}

async function load() {
  try {
    sessions.value = await store.fetchSessions()
  } catch (err) {
    if (err.name !== 'AuthError') showError('Could not load your logins', err)
  } finally {
    loading.value = false
  }
}

onMounted(load)

function askEnd(session) {
  confirm.require({
    header: `Log out ${device(session.userAgent)}?`,
    message: 'That device has to log in again within a minute.',
    icon: 'pi pi-sign-out',
    rejectProps: { label: 'Cancel', severity: 'secondary', text: true },
    acceptProps: { label: 'Log out', severity: 'danger' },
    accept: async () => {
      try {
        await store.endSession(session.id)
        sessions.value = sessions.value.filter((s) => s.id !== session.id)
      } catch (err) {
        showError('Could not log out the device', err)
      }
    },
  })
}

function askEndOthers() {
  confirm.require({
    header: 'Log out all other devices?',
    message: 'Only this browser stays logged in.',
    icon: 'pi pi-sign-out',
    rejectProps: { label: 'Cancel', severity: 'secondary', text: true },
    acceptProps: { label: 'Log out others', severity: 'danger' },
    accept: async () => {
      try {
        await store.endOtherSessions()
        sessions.value = sessions.value.filter((s) => s.current)
      } catch (err) {
        showError('Could not log out the other devices', err)
      }
    },
  })
}
</script>

<template>
  <div class="flex flex-col gap-3" data-testid="sessions">
    <i v-if="loading" class="pi pi-spin pi-spinner text-slate-400" />
    <template v-else>
      <ul class="flex flex-col divide-y divide-slate-100 dark:divide-slate-700">
        <li v-for="session in sessions" :key="session.id" class="flex items-center gap-3 py-2" data-testid="session">
          <span class="min-w-0 flex-1">
            <span class="block truncate text-sm text-slate-700 dark:text-slate-300" :title="session.userAgent">
              {{ device(session.userAgent) }}
            </span>
            <span class="block text-xs text-slate-400 dark:text-slate-500">{{ details(session) }}</span>
          </span>
          <Tag v-if="session.current" value="This device" severity="success" />
          <Button
            v-else
            icon="pi pi-sign-out"
            text
            rounded
            size="small"
            severity="danger"
            :aria-label="`Log out ${device(session.userAgent)}`"
            @click="askEnd(session)"
          />
        </li>
      </ul>
      <Button
        v-if="sessions.some((s) => !s.current)"
        label="Log out all other devices"
        icon="pi pi-sign-out"
        severity="secondary"
        outlined
        class="self-start"
        @click="askEndOthers"
      />
    </template>
  </div>
</template>
