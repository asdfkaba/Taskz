<script setup>
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import Button from 'primevue/button'
import { useAuthStore } from '@/stores/auth'

// The provider sends the browser back here with a code; the backend exchanges it and sets the
// login cookies. A failed login (e.g. no confirmed email at the provider) shows the reason.
const router = useRouter()
const auth = useAuthStore()
const error = ref(null)

onMounted(async () => {
  const params = new URLSearchParams(window.location.search)
  const code = params.get('code')
  if (!code) {
    error.value = 'The login was cancelled or the provider sent no code.'
    return
  }
  try {
    const response = await fetch('/api/auth/login', {
      method: 'POST',
      headers: { Accept: 'application/json', 'Content-Type': 'application/json' },
      body: JSON.stringify({
        code,
        code_verifier: sessionStorage.getItem('pkce_code_verifier'),
        provider: params.get('provider'),
      }),
    })
    if (!response.ok) {
      const reason = await response.json().then((b) => b.message, () => null)
      error.value = reason ?? `The login failed (HTTP ${response.status}).`
      return
    }
    sessionStorage.removeItem('pkce_code_verifier')
    await auth.checkLoginStatus()
    // Back to the page that needed the login. Only paths within the app, never another site.
    const returnTo = sessionStorage.getItem('returnTo')
    sessionStorage.removeItem('returnTo')
    router.push(returnTo?.startsWith('/') && !returnTo.startsWith('//') ? returnTo : '/boards')
  } catch {
    error.value = 'The server could not be reached. Try again in a moment.'
  }
})
</script>

<template>
  <div class="flex min-h-[60vh] flex-col items-center justify-center gap-3 text-slate-500 dark:text-slate-400">
    <template v-if="error">
      <i class="pi pi-exclamation-circle text-2xl text-red-500" aria-hidden="true" />
      <p class="max-w-md text-center text-slate-700 dark:text-slate-300" data-testid="login-error">{{ error }}</p>
      <Button label="Back to login" icon="pi pi-arrow-left" size="small" @click="router.push('/login')" />
    </template>
    <template v-else>
      <i class="pi pi-spin pi-spinner text-2xl" />
      <p>Signing you in…</p>
    </template>
  </div>
</template>
