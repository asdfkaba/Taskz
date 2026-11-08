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
// the user and only within its scopes, and may expire. A new token is shown once; tokens can be
// revoked any time.

// Same limit as ApiTokenService.MAX_NAME_LENGTH.
const MAX_NAME_LENGTH = 50

// The values of ApiScope, grouped as in the form.
const SCOPE_GROUPS = [
  {
    name: 'Tasks',
    scopes: [
      { value: 'tasks:read', label: 'Read' },
      { value: 'tasks:create', label: 'Create' },
      { value: 'tasks:update', label: 'Edit' },
      { value: 'tasks:move', label: 'Move' },
      { value: 'checklist:check', label: 'Tick checklists' },
    ],
  },
  {
    name: 'Attachments',
    scopes: [
      { value: 'attachments:read', label: 'Download' },
      { value: 'attachments:write', label: 'Upload' },
    ],
  },
  {
    name: 'Notes',
    scopes: [
      { value: 'notes:read', label: 'Read' },
      { value: 'notes:write', label: 'Write' },
    ],
  },
]

// Up to ApiTokenService.MAX_EXPIRY_DAYS.
const EXPIRY_OPTIONS = [
  { label: 'Expires in 30 days', value: 30 },
  { label: 'Expires in 90 days', value: 90 },
  { label: 'Expires in a year', value: 365 },
  { label: 'Never expires', value: null },
]

const store = useBoardStore()
const confirm = useConfirm()
const toast = useToast()

const tokens = ref([])
const loading = ref(true)
const busy = ref(false)
const form = ref({ boardId: null, name: '', scopes: ['tasks:read'], expiresInDays: 90 })
const created = ref(null) // { info, token }, only until the page is left
const tokenInput = ref()

const canCreate = computed(
  () => form.value.boardId != null && form.value.name.trim().length > 0 && form.value.scopes.length > 0 && !busy.value,
)
const scopeId = (value) => `token-scope-${value.replace(':', '-')}`

function lifetime(token) {
  const expires = fromApi(token.expiresAt)
  const until = !expires ? 'never expires' : expires <= new Date() ? 'expired' : `expires ${formatShort(expires)}`
  const used = token.lastUsedAt ? `last used ${formatShort(fromApi(token.lastUsedAt))}` : 'never used'
  return `Created ${formatShort(fromApi(token.created))} · ${until} · ${used}`
}

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
      For scripts and tools. A token acts as you, on one board only, and can do only what you tick
      below. Deleting and all settings need a login.
    </p>

    <i v-if="loading" class="pi pi-spin pi-spinner text-slate-400" />
    <ul v-else-if="tokens.length" class="flex flex-col divide-y divide-slate-100 dark:divide-slate-700">
      <li v-for="token in tokens" :key="token.id" class="flex items-center gap-3 py-2" data-testid="api-token">
        <span class="min-w-0 flex-1">
          <span class="block truncate text-sm text-slate-700 dark:text-slate-300">
            {{ token.name }} <span class="text-slate-400 dark:text-slate-500">· {{ token.boardName }}</span>
          </span>
          <span class="block text-xs break-words text-slate-500 dark:text-slate-400">{{ token.scopes.join(', ') }}</span>
          <span class="block text-xs text-slate-400 dark:text-slate-500">{{ lifetime(token) }}</span>
        </span>
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
        <code>GET /api/token</code> tells its board, scopes, columns and labels.
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
      <fieldset v-for="group in SCOPE_GROUPS" :key="group.name" class="flex flex-wrap items-center gap-x-4 gap-y-2">
        <legend class="mb-1 text-xs font-semibold text-slate-500 dark:text-slate-400">{{ group.name }}</legend>
        <div v-for="scope in group.scopes" :key="scope.value" class="flex items-center gap-2">
          <Checkbox v-model="form.scopes" :inputId="scopeId(scope.value)" :value="scope.value" />
          <label :for="scopeId(scope.value)" class="text-sm text-slate-700 dark:text-slate-300">{{ scope.label }}</label>
        </div>
      </fieldset>
      <div class="flex flex-wrap items-center gap-2">
        <Select
          v-model="form.expiresInDays"
          :options="EXPIRY_OPTIONS"
          optionLabel="label"
          optionValue="value"
          aria-label="Expiry"
          class="min-w-40"
        />
        <Button type="submit" label="Create token" icon="pi pi-key" :disabled="!canCreate" :loading="busy" class="ml-auto" />
      </div>
    </form>
  </div>
</template>
