<script setup>
import { onMounted, ref } from 'vue'
import RadioButton from 'primevue/radiobutton'
import { useToast } from 'primevue/usetoast'
import { useNotificationStore } from '@/stores/notifications'
import ApiTokens from '@/components/ApiTokens.vue'

// The user's own settings: how notifications reach them besides the bell, and their API tokens.
const store = useNotificationStore()
const toast = useToast()

const OPTIONS = [
  { value: 'OFF', label: 'Off', hint: 'Only the bell.' },
  { value: 'IMMEDIATE', label: 'Right away', hint: 'One mail per notification.' },
  { value: 'DAILY', label: 'Daily summary', hint: 'Once a day, what is still unread. No mail if there is nothing new.' },
  { value: 'WEEKLY', label: 'Weekly summary', hint: 'Once a week, what is still unread.' },
]

const mail = ref(null) // { available, address, delivery }
const status = ref('loading') // 'loading' | 'ok' | 'error'
const saving = ref(false)

onMounted(async () => {
  try {
    mail.value = await store.fetchMailSettings()
    status.value = 'ok'
  } catch (err) {
    if (err.name !== 'AuthError') status.value = 'error'
  }
})

async function choose(delivery) {
  const before = mail.value.delivery
  mail.value.delivery = delivery
  saving.value = true
  try {
    mail.value = await store.setMailDelivery(delivery)
  } catch (err) {
    mail.value.delivery = before
    toast.add({ severity: 'error', summary: 'Could not save', detail: err.message, life: 5000 })
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <div class="mx-auto flex max-w-2xl flex-col gap-6" data-testid="account">
    <h1 class="text-2xl font-semibold text-slate-900 dark:text-slate-100">Account</h1>

    <section class="flex flex-col gap-3 rounded-lg border border-slate-200 bg-white p-4 dark:border-slate-700 dark:bg-slate-800">
      <h2 class="font-semibold text-slate-900 dark:text-slate-100">Notifications</h2>
      <p class="text-sm text-slate-600 dark:text-slate-400">The bell always shows them. You can also get them by mail.</p>

      <i v-if="status === 'loading'" class="pi pi-spin pi-spinner text-slate-400" />
      <p v-else-if="status === 'error'" class="text-sm text-red-600 dark:text-red-400">Could not load the settings.</p>
      <template v-else>
        <p v-if="!mail.available" class="text-sm text-slate-500 dark:text-slate-400" data-testid="mail-unavailable">
          Mail isn't set up on this server.
        </p>
        <fieldset class="flex flex-col gap-2" :disabled="!mail.available || saving">
          <legend class="mb-1 text-sm text-slate-700 dark:text-slate-300">
            Mail to <strong>{{ mail.address }}</strong>
          </legend>
          <div v-for="option in OPTIONS" :key="option.value" class="flex items-start gap-2">
            <RadioButton
              :modelValue="mail.delivery"
              :inputId="`mail-${option.value}`"
              name="mail-delivery"
              :value="option.value"
              :disabled="!mail.available || saving"
              @update:modelValue="choose"
            />
            <label :for="`mail-${option.value}`" class="text-sm">
              <span class="text-slate-800 dark:text-slate-200">{{ option.label }}</span>
              <span class="block text-xs text-slate-500 dark:text-slate-400">{{ option.hint }}</span>
            </label>
          </div>
        </fieldset>
      </template>
    </section>

    <section class="flex flex-col gap-3 rounded-lg border border-slate-200 bg-white p-4 dark:border-slate-700 dark:bg-slate-800">
      <h2 class="font-semibold text-slate-900 dark:text-slate-100">API tokens</h2>
      <ApiTokens />
    </section>
  </div>
</template>
