<script setup>
import UserIcon from '@/components/UserIcon.vue'
import NotificationBell from '@/components/NotificationBell.vue'
import { RouterView, useRoute, useRouter } from 'vue-router'
import { computed, onMounted, ref, watch } from 'vue'
import Toast from 'primevue/toast'
import ConfirmDialog from 'primevue/confirmdialog'
import Button from 'primevue/button'
import Menu from 'primevue/menu'
import { useToast } from 'primevue/usetoast'
import { useBoardStore } from '@/stores/board'
import { useAuthStore } from '@/stores/auth'
import { useToastStore } from '@/stores/toast'
import { useTheme } from '@/composables/useTheme'

const boardStore = useBoardStore()
const authStore = useAuthStore()
const router = useRouter()
const route = useRoute()
// Full-width pages (the board with its sidebar) handle their own padding.
const fullWidth = computed(() => route.meta.fullWidth === true)
const toast = useToast()
const toastStore = useToastStore()

const theme = useTheme()
const THEME_LABELS = {
  system: { icon: 'pi pi-desktop', label: 'Theme: system' },
  light: { icon: 'pi pi-sun', label: 'Theme: light' },
  dark: { icon: 'pi pi-moon', label: 'Theme: dark' },
}
const themeButton = computed(() => THEME_LABELS[theme.choice.value])

watch(
  () => toastStore.toasts,
  (newToasts) => {
    if (newToasts.length > 0) {
      toast.add(newToasts[0])
      toastStore.shiftToast()
    }
  },
  { deep: true },
)

function logout() {
  authStore.logout()
  boardStore.reset()
  router.push('/')
}

// Your icon opens this menu; its heading is who you are logged in as.
const userMenu = ref()
const userMenuItems = computed(() => [
  {
    label: [authStore.user?.name, authStore.user?.email].filter(Boolean).join(' · ') || 'Account',
    items: [
      { label: 'Settings', icon: 'pi pi-cog', command: () => router.push({ name: 'account' }) },
      { label: 'Help', icon: 'pi pi-question-circle', command: () => router.push({ name: 'help' }) },
      { separator: true },
      { label: 'Log out', icon: 'pi pi-sign-out', command: logout },
    ],
  },
])

onMounted(async () => {
  await router.isReady()
  const route = router.currentRoute.value
  if (route.path === '/oauth2/callback') return

  const result = await authStore.checkLoginStatus()
  if (result.status === 'ok') {
    if (route.path === '/') router.push('/boards')
  } else if (result.status === 'unauthenticated') {
    // Pages like a shared board work without login. Others (e.g. an invite link) are opened
    // again after the login, see OAuthCallback.
    if (route.meta.requiresAuth) {
      sessionStorage.setItem('returnTo', route.fullPath)
      router.push('/')
    }
  } else if (result.status === 'backend-unreachable') {
    toast.add({ severity: 'warn', summary: 'Offline', detail: 'Backend not reachable' })
  } else if (result.status === 'error') {
    toast.add({ severity: 'error', summary: 'Unexpected error', detail: `Code ${result.code}` })
  }
})
</script>

<template>
  <div class="min-h-screen">
    <header class="sticky top-0 z-10 border-b border-slate-200 dark:border-slate-800 bg-white/80 dark:bg-slate-900/80 backdrop-blur">
      <div class="mx-auto flex h-14 items-center justify-between gap-4 px-4" :class="fullWidth ? 'max-w-none' : 'max-w-6xl'">
        <RouterLink to="/boards" class="flex items-center gap-2 font-semibold text-slate-900 dark:text-slate-100">
          <img src="/favicon.svg" alt="" class="h-8 w-8" />
          Taskz
        </RouterLink>

        <div class="flex min-w-0 items-center gap-2">
          <NotificationBell v-if="authStore.isAuthenticated" />
          <!-- Your icon, the same one others see on a board; name and email on hover. It opens the
               menu with your account settings and logout. -->
          <button
            v-if="authStore.isAuthenticated"
            type="button"
            aria-label="Account menu"
            aria-haspopup="true"
            aria-controls="user-menu"
            class="cursor-pointer rounded-full"
            @click="userMenu.toggle($event)"
          >
            <UserIcon
              :seed="authStore.user?.id"
              :name="[authStore.user?.name, authStore.user?.email].filter(Boolean).join(' · ')"
              size="lg"
              class="ring-2 ring-emerald-500 dark:ring-emerald-400"
              data-testid="header-user"
            />
          </button>
          <Menu id="user-menu" ref="userMenu" :model="userMenuItems" popup />
          <Button
            :icon="themeButton.icon"
            :aria-label="themeButton.label"
            :title="`${themeButton.label} (click to change)`"
            severity="secondary"
            text
            rounded
            size="small"
            @click="theme.cycle"
          />
        </div>
      </div>
    </header>

    <main :class="fullWidth ? '' : 'mx-auto max-w-6xl px-4 py-8'">
      <RouterView />
    </main>

    <Toast position="bottom-right" />
    <ConfirmDialog :style="{ width: '26rem' }" :breakpoints="{ '640px': '95vw' }" />
  </div>
</template>
