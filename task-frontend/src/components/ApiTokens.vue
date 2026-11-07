<script setup>
import { computed, onMounted, ref } from 'vue'
import InputText from 'primevue/inputtext'
import Select from 'primevue/select'
import Checkbox from 'primevue/checkbox'
import Button from 'primevue/button'
import { useConfirm } from 'primevue/useconfirm'
import { useToast } from 'primevue/usetoast'
import { useBoardStore } from '@/stores/board'
import { fromApi, formatShort } from '@/utils/date'

// The user's personal API tokens, part of the account settings. Each works for one board, acts as
// the user and can read, write or both. A new token is shown once; tokens can be revoked any time.

// Same limit as ApiTokenService.MAX_NAME_LENGTH.
const MAX_NAME_LENGTH = 50

const store = useBoardStore()
const confirm = useConfirm()
const toast = useToast()

const tokens = ref([])
const loading = ref(true)
const busy = ref(false)
const form = ref({ boardId: null, name: '', read: true, write: false })
const created = ref(null) // { info, token }, only until the page is left
const tokenInput = ref()

const canCreate = computed(
  () => form.value.boardId != null && form.value.name.trim().length > 0 && (form.value.read || form.value.write) && !busy.value,
)
const rights = (t) => [t.read && 'read', t.write && 'write'].filter(Boolean).join(' + ')

function showError(summary, err) {
  toast.add({ severity: 'error', summary, detail: err.message, life: 5000 })
}

onMounted(async () => {
  try {
    if (!store.boardsLoaded) await store.fetchBoards()
    tokens.value = await store.fetchApiTokens()
  } catch (err) {
    if (err.name !== 'AuthError') showError('Could not load API tokens', err)
  } finally {
    loading.value = false
  }
})

async function create() {
  if (!canCreate.value) return
  busy.value = true
  try {
    created.value = await store.createApiToken({ ...form.value, name: form.value.name.trim() })
    tokens.value = [...tokens.value, created.value.info]
    form.value = { ...form.value, name: '' }
  } catch (err) {
    showError('Could not create the token', err)
  } finally {
    busy.value = false
  }
}

async function copyToken() {
  try {
    await navigator.clipboard.writeText(created.value.token)
    toast.add({ severity: 'success', summary: 'Token copied', life: 2000 })
  } catch {
    // Clipboard API needs https; let the user copy by hand.
    tokenInput.value?.$el?.select()
    toast.add({ severity: 'info', summary: 'Press Ctrl+C to copy the token', life: 4000 })
  }
}

function askRevoke(token) {
  confirm.require({
    header: `Revoke "${token.name}"?`,
    message: 'Scripts using it stop working right away.',
    icon: 'pi pi-ban',
    rejectProps: { label: 'Cancel', severity: 'secondary', text: true },
    acceptProps: { label: 'Revoke', severity: 'danger' },
    accept: async () => {
      try {
        await store.revokeApiToken(token.id)
        tokens.value = tokens.value.filter((t) => t.id !== token.id)
        if (created.value?.info.id === token.id) created.value = null
      } catch (err) {
        showError('Could not revoke the token', err)
      }
    },
  })
}
</script>

<template>
  <div class="flex flex-col gap-4" data-testid="api-tokens">
    <p class="text-sm text-slate-600 dark:text-slate-400">
      For scripts and tools. A token acts as you, on one board only. Read: the board and its tasks.
      Write: create and change tasks, labels included. Deleting and all settings need a login.
    </p>

    <i v-if="loading" class="pi pi-spin pi-spinner text-slate-400" />
    <ul v-else-if="tokens.length" class="flex flex-col divide-y divide-slate-100 dark:divide-slate-700">
      <li v-for="token in tokens" :key="token.id" class="flex items-center gap-3 py-2" data-testid="api-token">
        <span class="min-w-0 flex-1 truncate text-sm text-slate-700 dark:text-slate-300">
          {{ token.name }} <span class="text-slate-400 dark:text-slate-500">· {{ token.boardName }} · {{ rights(token) }}</span>
        </span>
        <span class="text-xs text-slate-400 dark:text-slate-500">{{ formatShort(fromApi(token.created)) }}</span>
        <Button
          icon="pi pi-ban"
          text
          rounded
          size="small"
          severity="danger"
          :aria-label="`Revoke ${token.name}`"
          @click="askRevoke(token)"
        />
      </li>
    </ul>

    <template v-if="created">
      <div class="flex gap-2">
        <InputText
          ref="tokenInput"
          :value="created.token"
          readonly
          aria-label="New API token"
          fluid
          class="font-mono !text-xs"
          @focus="$event.target.select()"
        />
        <Button label="Copy" icon="pi pi-copy" class="shrink-0" @click="copyToken" />
      </div>
      <p class="text-xs text-slate-500 dark:text-slate-400">
        Copy it now: it is shown only once. Send it as <code>Authorization: Bearer &lt;token&gt;</code>;
        <code>GET /api/token</code> tells its board, rights, columns and labels.
      </p>
    </template>

    <form v-if="!loading" class="flex flex-col gap-3" @submit.prevent="create">
      <div class="flex flex-wrap gap-2">
        <Select
          v-model="form.boardId"
          :options="store.boards"
          optionLabel="name"
          optionValue="id"
          placeholder="Board"
          aria-label="Board"
          class="min-w-40"
        />
        <InputText v-model="form.name" :maxlength="MAX_NAME_LENGTH" placeholder="Name, e.g. job search script" aria-label="Token name" class="min-w-0 flex-1" />
      </div>
      <div class="flex flex-wrap items-center gap-4">
        <div class="flex items-center gap-2">
          <Checkbox v-model="form.read" inputId="token-read" binary />
          <label for="token-read" class="text-sm text-slate-700 dark:text-slate-300">Read</label>
        </div>
        <div class="flex items-center gap-2">
          <Checkbox v-model="form.write" inputId="token-write" binary />
          <label for="token-write" class="text-sm text-slate-700 dark:text-slate-300">Write</label>
        </div>
        <Button type="submit" label="Create token" icon="pi pi-key" :disabled="!canCreate" :loading="busy" class="ml-auto" />
      </div>
    </form>
  </div>
</template>
