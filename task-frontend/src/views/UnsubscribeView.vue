<script setup>
import { ref } from 'vue'
import { useRoute } from 'vue-router'
import Button from 'primevue/button'
import { useNotificationStore } from '@/stores/notifications'

// From the link in a notification mail. It asks first, as mail scanners open links on their own.
const route = useRoute()
const store = useNotificationStore()
const status = ref('ask') // 'ask' | 'busy' | 'done' | 'invalid' | 'error'

async function unsubscribe() {
  status.value = 'busy'
  try {
    await store.unsubscribe(route.params.token)
    status.value = 'done'
  } catch (err) {
    status.value = err.status === 404 ? 'invalid' : 'error'
  }
}
</script>

<template>
  <div class="flex min-h-[60vh] flex-col items-center justify-center gap-4 px-4 text-center" data-testid="unsubscribe">
    <template v-if="status === 'ask' || status === 'busy'">
      <p class="text-slate-700 dark:text-slate-300">Stop getting notifications by mail?</p>
      <Button label="Stop mails" icon="pi pi-bell-slash" :loading="status === 'busy'" @click="unsubscribe" />
    </template>
    <template v-else-if="status === 'done'">
      <i class="pi pi-check-circle text-2xl text-emerald-600" aria-hidden="true" />
      <p class="text-slate-700 dark:text-slate-300">Done. You get no more notification mails; the bell still shows them.</p>
      <RouterLink to="/account" class="text-sm text-emerald-700 underline dark:text-emerald-400">Change it in your account</RouterLink>
    </template>
    <template v-else>
      <i class="pi pi-exclamation-circle text-2xl text-slate-400" aria-hidden="true" />
      <p class="text-slate-700 dark:text-slate-300">
        {{ status === 'invalid' ? "This link doesn't work. Change your mail settings in your account." : 'Something went wrong. Try again later.' }}
      </p>
    </template>
  </div>
</template>
