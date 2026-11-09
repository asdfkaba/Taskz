<script setup>
import { ref } from 'vue'
import Button from 'primevue/button'
import ApiTokens from '@/components/ApiTokens.vue'
import AccountSessions from '@/components/AccountSessions.vue'
import AccountDeleteDialog from '@/components/AccountDeleteDialog.vue'

// The user's own settings: logins, API tokens, their data. Mail notifications stay out of here until
// mail is set up on the server; the backend for them (NotificationPreferenceController) is there.
const deleting = ref(false)
</script>

<template>
  <div class="mx-auto flex max-w-2xl flex-col gap-6" data-testid="account">
    <h1 class="text-2xl font-semibold text-slate-900 dark:text-slate-100">Account</h1>

    <section class="flex flex-col gap-3 rounded-lg border border-slate-200 bg-white p-4 dark:border-slate-700 dark:bg-slate-800">
      <h2 class="font-semibold text-slate-900 dark:text-slate-100">Logged in on</h2>
      <AccountSessions />
    </section>

    <section class="flex flex-col gap-3 rounded-lg border border-slate-200 bg-white p-4 dark:border-slate-700 dark:bg-slate-800">
      <h2 class="font-semibold text-slate-900 dark:text-slate-100">API tokens</h2>
      <ApiTokens />
    </section>

    <section class="flex flex-col gap-3 rounded-lg border border-slate-200 bg-white p-4 dark:border-slate-700 dark:bg-slate-800">
      <h2 class="font-semibold text-slate-900 dark:text-slate-100">Your data</h2>
      <p class="text-sm text-slate-600 dark:text-slate-400">
        The download has your profile, your boards with tasks and notes, and the boards you joined, as JSON.
        Attached files are listed, not included.
      </p>
      <div class="flex flex-wrap gap-2">
        <!-- A plain link, so the browser saves the file itself (the server sends it as attachment). -->
        <Button
          as="a"
          href="/api/account/export"
          download
          label="Download my data"
          icon="pi pi-download"
          severity="secondary"
          outlined
          class="no-underline"
          data-testid="export"
        />
        <Button label="Delete account" icon="pi pi-trash" severity="danger" outlined @click="deleting = true" />
      </div>
    </section>

    <AccountDeleteDialog v-model:visible="deleting" />
  </div>
</template>
