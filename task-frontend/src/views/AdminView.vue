<script setup>
import { computed, onMounted, ref } from 'vue'
import Dialog from 'primevue/dialog'
import InputText from 'primevue/inputtext'
import Button from 'primevue/button'
import { useToast } from 'primevue/usetoast'
import { authFetch } from '@/api/actions.js'
import { useAuthStore } from '@/stores/auth'
import { formatShort } from '@/utils/date'

// For admins (set by hand in the database): all users, and deleting an account like its owner would.
const auth = useAuthStore()
const toast = useToast()

const users = ref([]) // [{ id, name, email, admin, createdAt }]
const state = ref('loading') // loading | ok | forbidden | error
const deleting = ref(null) // the user the delete dialog is open for
const typedEmail = ref('')
const busy = ref(false)
const emailMatches = computed(
  () => deleting.value && typedEmail.value.trim().toLowerCase() === deleting.value.email.toLowerCase(),
)

onMounted(async () => {
  try {
    const response = await authFetch('/api/admin/users')
    if (response.status === 403) {
      state.value = 'forbidden'
      return
    }
    if (!response.ok) throw new Error(`HTTP ${response.status}`)
    users.value = await response.json()
    state.value = 'ok'
  } catch {
    state.value = 'error'
  }
})

function askDelete(user) {
  deleting.value = user
  typedEmail.value = ''
}

async function confirmDelete() {
  if (!emailMatches.value) return
  busy.value = true
  const user = deleting.value
  try {
    const response = await authFetch(`/api/admin/users/${user.id}`, {
      method: 'DELETE',
      body: JSON.stringify({ email: typedEmail.value.trim() }),
    })
    if (!response.ok) throw new Error(`HTTP ${response.status}`)
    users.value = users.value.filter((u) => u.id !== user.id)
    deleting.value = null
    toast.add({ severity: 'success', summary: `Deleted ${user.email}`, life: 4000 })
  } catch (err) {
    toast.add({ severity: 'error', summary: 'Could not delete the account', detail: err.message, life: 5000 })
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <div class="mx-auto flex max-w-4xl flex-col gap-6" data-testid="admin">
    <h1 class="text-2xl font-semibold text-slate-900 dark:text-slate-100">Admin</h1>

    <p v-if="state === 'loading'" class="text-slate-600 dark:text-slate-400">Loading users…</p>
    <p v-else-if="state === 'forbidden'" class="text-slate-600 dark:text-slate-400">This page is for admins only.</p>
    <p v-else-if="state === 'error'" class="text-red-700 dark:text-red-400">Could not load the users.</p>

    <section
      v-else
      class="flex flex-col gap-3 rounded-lg border border-slate-200 bg-white p-4 dark:border-slate-700 dark:bg-slate-800"
    >
      <h2 class="font-semibold text-slate-900 dark:text-slate-100">Users ({{ users.length }})</h2>
      <div class="overflow-x-auto">
        <table class="w-full text-left text-sm">
          <thead class="text-slate-500 dark:text-slate-400">
            <tr>
              <th class="py-2 pr-4 font-medium">Name</th>
              <th class="py-2 pr-4 font-medium">Email</th>
              <th class="py-2 pr-4 font-medium">Registered</th>
              <th class="py-2"><span class="sr-only">Actions</span></th>
            </tr>
          </thead>
          <tbody>
            <tr
              v-for="user in users"
              :key="user.id"
              class="border-t border-slate-100 dark:border-slate-700"
              data-testid="admin-user"
            >
              <td class="py-2 pr-4 text-slate-900 dark:text-slate-100">
                {{ user.name }}
                <span v-if="user.admin" class="ml-1 rounded bg-emerald-100 px-1.5 text-xs text-emerald-800 dark:bg-emerald-900 dark:text-emerald-200">admin</span>
              </td>
              <td class="py-2 pr-4 break-all text-slate-700 dark:text-slate-300">{{ user.email }}</td>
              <!-- Accounts from before the date was recorded have none. -->
              <td class="py-2 pr-4 whitespace-nowrap text-slate-700 dark:text-slate-300">
                {{ user.createdAt ? formatShort(new Date(user.createdAt)) : '–' }}
              </td>
              <td class="py-2 text-right">
                <Button
                  v-if="user.id !== auth.user?.id"
                  icon="pi pi-trash"
                  severity="danger"
                  text
                  rounded
                  size="small"
                  :aria-label="`Delete ${user.email}`"
                  @click="askDelete(user)"
                />
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>

    <Dialog
      :visible="deleting !== null"
      modal
      header="Delete user"
      :style="{ width: '30rem' }"
      :breakpoints="{ '640px': '95vw' }"
      :draggable="false"
      @update:visible="(open) => { if (!open) deleting = null }"
    >
      <form v-if="deleting" id="admin-delete-form" class="flex flex-col gap-4 pt-1" @submit.prevent="confirmDelete">
        <p class="text-sm text-slate-600 dark:text-slate-400">
          Their boards are deleted with all their tasks, also for the boards' members, and so are their logins
          and API tokens. What they wrote on other people's boards stays without their name. This cannot be undone.
        </p>
        <div class="flex flex-col gap-1.5">
          <label for="admin-delete-confirm" class="text-sm text-slate-700 dark:text-slate-300">
            To confirm, type <strong class="font-semibold text-slate-900 dark:text-slate-100 select-all">{{ deleting.email }}</strong>
          </label>
          <InputText id="admin-delete-confirm" v-model="typedEmail" autocomplete="off" fluid />
        </div>
      </form>

      <template #footer>
        <Button label="Cancel" severity="secondary" text @click="deleting = null" />
        <Button
          type="submit"
          form="admin-delete-form"
          label="Delete user"
          icon="pi pi-trash"
          severity="danger"
          :disabled="!emailMatches"
          :loading="busy"
        />
      </template>
    </Dialog>
  </div>
</template>
