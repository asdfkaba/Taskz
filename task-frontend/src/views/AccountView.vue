<script setup>
import ApiTokens from '@/components/ApiTokens.vue'

// The user's own settings: their API tokens. Mail notifications stay out of here until mail is
// set up on the server; the backend for them (NotificationPreferenceController) is there.
</script>

<template>
  <div class="mx-auto flex max-w-2xl flex-col gap-6" data-testid="account">
    <h1 class="text-2xl font-semibold text-slate-900 dark:text-slate-100">Account</h1>

    <section class="flex flex-col gap-3 rounded-lg border border-slate-200 bg-white p-4 dark:border-slate-700 dark:bg-slate-800">
      <h2 class="font-semibold text-slate-900 dark:text-slate-100">API tokens</h2>
      <ApiTokens />
    </section>
  </div>
</template>
