<script setup>
import { computed, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import Dialog from 'primevue/dialog'
import InputText from 'primevue/inputtext'
import Button from 'primevue/button'
import { useToast } from 'primevue/usetoast'
import { useAuthStore } from '@/stores/auth'
import { useBoardStore } from '@/stores/board'

// Deleting the account deletes the own boards, also for their members, so those boards are named
// and the email has to be typed to confirm. What the user wrote on other boards stays, without author.
const visible = defineModel('visible', { type: Boolean, default: false })

const auth = useAuthStore()
const store = useBoardStore()
const toast = useToast()
const router = useRouter()

const typedEmail = ref('')
const busy = ref(false)
const sharedBoards = ref([]) // own boards with members, [{ name, members }]
const email = computed(() => auth.user?.email ?? '')
const emailMatches = computed(() => email.value !== '' && typedEmail.value.trim().toLowerCase() === email.value.toLowerCase())

watch(visible, async (open) => {
  if (!open) return
  typedEmail.value = ''
  sharedBoards.value = []
  try {
    if (!store.boardsLoaded) await store.fetchBoards()
    const own = store.boards.filter((b) => b.own)
    const counts = await Promise.all(own.map((b) => store.fetchMembers(b.id).then((m) => m.length - 1)))
    sharedBoards.value = own.map((b, i) => ({ name: b.name, members: counts[i] })).filter((b) => b.members > 0)
  } catch (err) {
    toast.add({ severity: 'error', summary: 'Could not load your boards', detail: err.message, life: 5000 })
  }
})

async function confirmDelete() {
  if (!emailMatches.value) return
  busy.value = true
  try {
    await store.deleteAccount(typedEmail.value.trim())
    visible.value = false
    // The server already removed the login cookies.
    auth.user = null
    auth.isAuthenticated = false
    store.reset()
    toast.add({ severity: 'success', summary: 'Account deleted', life: 4000 })
    router.push('/')
  } catch (err) {
    toast.add({ severity: 'error', summary: 'Could not delete the account', detail: err.message, life: 5000 })
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <Dialog
    v-model:visible="visible"
    modal
    header="Delete account"
    :style="{ width: '30rem' }"
    :breakpoints="{ '640px': '95vw' }"
    :draggable="false"
  >
    <form id="account-delete-form" class="flex flex-col gap-4 pt-1" @submit.prevent="confirmDelete">
      <p class="text-sm text-slate-600 dark:text-slate-400">
        Your boards are deleted with all their tasks, and so are your logins and API tokens. What you wrote on
        other people's boards stays there without your name. This cannot be undone.
      </p>
      <div v-if="sharedBoards.length" class="flex flex-col gap-1 text-sm" data-testid="shared-boards">
        <p class="text-slate-700 dark:text-slate-300">These boards are also gone for their members:</p>
        <ul class="list-disc pl-5 text-slate-600 dark:text-slate-400">
          <li v-for="board in sharedBoards" :key="board.name">
            <strong class="text-slate-900 dark:text-slate-100">{{ board.name }}</strong>
            · {{ board.members }} {{ board.members === 1 ? 'member' : 'members' }}
          </li>
        </ul>
      </div>
      <div class="flex flex-col gap-1.5">
        <label for="account-delete-confirm" class="text-sm text-slate-700 dark:text-slate-300">
          To confirm, type <strong class="font-semibold text-slate-900 dark:text-slate-100 select-all">{{ email }}</strong>
        </label>
        <InputText id="account-delete-confirm" v-model="typedEmail" autocomplete="off" fluid />
      </div>
    </form>

    <template #footer>
      <Button label="Cancel" severity="secondary" text @click="visible = false" />
      <Button
        type="submit"
        form="account-delete-form"
        label="Delete account"
        icon="pi pi-trash"
        severity="danger"
        :disabled="!emailMatches"
        :loading="busy"
      />
    </template>
  </Dialog>
</template>
