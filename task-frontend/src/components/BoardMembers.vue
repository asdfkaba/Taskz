<script setup>
import UserIcon from '@/components/UserIcon.vue'
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import InputText from 'primevue/inputtext'
import Button from 'primevue/button'
import { useConfirm } from 'primevue/useconfirm'
import { useToast } from 'primevue/usetoast'
import { useBoardStore } from '@/stores/board'
import { fromApi, formatShort } from '@/utils/date'

// Who can open and change the board. The owner invites people with a link and removes them;
// members see who else is on the board and can leave. Part of the board settings.
const props = defineProps({ board: { type: Object, required: true } }) // { id, name, own }

const store = useBoardStore()
const router = useRouter()
const confirm = useConfirm()
const toast = useToast()

const members = ref([])
const loading = ref(true)
const busy = ref(false)
const invite = ref(null) // { path, expires }, only until the page is left
const urlInput = ref()

const inviteUrl = computed(() => (invite.value ? `${window.location.origin}${invite.value.path}` : ''))

function showError(summary, err) {
  toast.add({ severity: 'error', summary, detail: err.message, life: 5000 })
}

onMounted(async () => {
  try {
    members.value = await store.fetchMembers(props.board.id)
  } catch (err) {
    showError('Could not load members', err)
  } finally {
    loading.value = false
  }
})

async function createInvite() {
  busy.value = true
  try {
    invite.value = await store.createInvite(props.board.id)
  } catch (err) {
    showError('Could not create invite link', err)
  } finally {
    busy.value = false
  }
}

async function copyInvite() {
  try {
    await navigator.clipboard.writeText(inviteUrl.value)
    toast.add({ severity: 'success', summary: 'Link copied', life: 2000 })
  } catch {
    // Clipboard API needs https; let the user copy by hand.
    urlInput.value?.$el?.select()
    toast.add({ severity: 'info', summary: 'Press Ctrl+C to copy the link', life: 4000 })
  }
}

function askRemove(member) {
  confirm.require({
    header: `Remove ${member.name}?`,
    message: 'They can no longer open this board. Their tasks and notes stay.',
    icon: 'pi pi-user-minus',
    rejectProps: { label: 'Cancel', severity: 'secondary', text: true },
    acceptProps: { label: 'Remove', severity: 'danger' },
    accept: async () => {
      try {
        await store.removeMember(props.board.id, member.userId)
        members.value = members.value.filter((m) => m.userId !== member.userId)
      } catch (err) {
        showError('Could not remove member', err)
      }
    },
  })
}

function askLeave() {
  confirm.require({
    header: 'Leave board?',
    message: 'You can no longer open it. To come back, you need a new invite link.',
    icon: 'pi pi-sign-out',
    rejectProps: { label: 'Cancel', severity: 'secondary', text: true },
    acceptProps: { label: 'Leave board', severity: 'danger' },
    accept: async () => {
      try {
        await store.leaveBoard(props.board.id)
        router.replace({ name: 'board' })
        toast.add({ severity: 'success', summary: 'You left the board', life: 2500 })
      } catch (err) {
        showError('Could not leave the board', err)
      }
    },
  })
}
</script>

<template>
  <div class="flex flex-col gap-4" data-testid="board-members">
    <p v-if="loading" class="text-sm text-slate-400 dark:text-slate-500">Loading…</p>
    <ul v-else class="flex flex-col divide-y divide-slate-100 dark:divide-slate-800">
      <li v-for="member in members" :key="member.userId" class="flex items-center gap-3 py-2" data-testid="member">
        <UserIcon :seed="member.userId" />
        <span class="min-w-0 flex-1 truncate text-sm text-slate-700 dark:text-slate-300">{{ member.name ?? 'Unknown' }}</span>
        <span v-if="member.owner" class="text-xs text-slate-500 dark:text-slate-400">Owner</span>
        <span v-else class="text-xs text-slate-400 dark:text-slate-500">joined {{ formatShort(fromApi(member.joined)) }}</span>
        <Button
          v-if="board.own && !member.owner"
          icon="pi pi-user-minus"
          text
          rounded
          size="small"
          severity="danger"
          :aria-label="`Remove ${member.name}`"
          @click="askRemove(member)"
        />
      </li>
    </ul>

    <template v-if="board.own">
      <p class="text-sm text-slate-600 dark:text-slate-400">
        Members can change everything on the board except sharing, members and deleting it.
        An invite link works once and for 7 days; send it however you like.
      </p>
      <Button
        v-if="!invite"
        label="Create invite link"
        icon="pi pi-user-plus"
        :loading="busy"
        class="self-start"
        @click="createInvite"
      />
      <template v-else>
        <div class="flex gap-2">
          <InputText
            ref="urlInput"
            :value="inviteUrl"
            readonly
            aria-label="Invite link"
            fluid
            class="font-mono !text-xs"
            @focus="$event.target.select()"
          />
          <Button label="Copy" icon="pi pi-copy" class="shrink-0" @click="copyInvite" />
        </div>
        <p class="text-xs text-slate-500 dark:text-slate-400">
          Copy it now: it is shown only once. Works once, until {{ formatShort(fromApi(invite.expires)) }}.
        </p>
        <Button label="Another link" icon="pi pi-plus" text size="small" class="self-start" :loading="busy" @click="createInvite" />
      </template>
    </template>
    <Button
      v-else
      label="Leave board"
      icon="pi pi-sign-out"
      severity="danger"
      outlined
      class="self-start"
      @click="askLeave"
    />
  </div>
</template>
