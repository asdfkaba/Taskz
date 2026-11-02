<script setup>
import UserIcon from '@/components/UserIcon.vue'
import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import Button from 'primevue/button'
import { useBoardStore } from '@/stores/board'

// Where an invite link leads: asks before joining, then opens the board. Needs a login; without
// one, App sends the user to the login and back here.
const route = useRoute()
const router = useRouter()
const store = useBoardStore()

const status = ref('loading') // 'loading' | 'ok' | 'invalid' | 'error'
const invite = ref(null) // { boardName, ownerName, ownerId, member }
const joining = ref(false)

onMounted(async () => {
  try {
    invite.value = await store.fetchInvite(route.params.token)
    status.value = 'ok'
  } catch (err) {
    // Not logged in: App takes care of it.
    if (err.name === 'AuthError' || err.status === 403) return
    status.value = err.status === 404 ? 'invalid' : 'error'
  }
})

async function join() {
  joining.value = true
  try {
    const board = await store.joinBoard(route.params.token)
    router.replace({ name: 'board', params: { slug: board.slug } })
  } catch (err) {
    status.value = err.status === 404 ? 'invalid' : 'error'
  } finally {
    joining.value = false
  }
}
</script>

<template>
  <div class="flex min-h-[60vh] flex-col items-center justify-center gap-4 px-4 text-center" data-testid="join">
    <i v-if="status === 'loading'" class="pi pi-spin pi-spinner text-2xl text-slate-400" />
    <template v-else-if="status === 'ok'">
      <!-- The owner's icon, the same one the joined board will show for them. -->
      <UserIcon :seed="invite.ownerId" :name="invite.ownerName" size="xl" data-testid="invite-owner" />
      <template v-if="invite.member">
        <p class="text-slate-700 dark:text-slate-300">You can open <strong>{{ invite.boardName }}</strong> already.</p>
        <Button label="Open board" icon="pi pi-arrow-right" :loading="joining" @click="join" />
      </template>
      <template v-else>
        <p class="text-slate-700 dark:text-slate-300">
          {{ invite.ownerName ?? 'Someone' }} invited you to the board <strong>{{ invite.boardName }}</strong>.
        </p>
        <p class="max-w-md text-sm text-slate-500 dark:text-slate-400">
          As a member you see and change its tasks, columns and labels.
        </p>
        <Button label="Join board" icon="pi pi-check" :loading="joining" @click="join" />
      </template>
    </template>
    <template v-else>
      <i class="pi pi-exclamation-circle text-2xl text-slate-400 dark:text-slate-500" aria-hidden="true" />
      <p class="font-medium text-slate-700 dark:text-slate-300">
        {{ status === 'invalid' ? "This invite link doesn't work anymore" : 'Could not load the invite' }}
      </p>
      <p v-if="status === 'invalid'" class="max-w-md text-sm text-slate-500 dark:text-slate-400">
        Invite links work once and for 7 days. Ask for a new one.
      </p>
      <Button label="Go to my boards" text @click="router.push('/boards')" />
    </template>
  </div>
</template>
