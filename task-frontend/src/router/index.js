import { createRouter, createWebHistory } from 'vue-router'
import { formatTitle } from '@/utils/title'

// Board and board settings share one view, so the sidebar and the loaded board stay when switching.
const BoardView = () => import('../views/BoardView.vue')

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    {
      path: '/',
      name: 'Login',
      component: () => import('../views/LoginView.vue'),
      meta: { requiresAuth: false, title: 'Log in' },
    },
    {
      // :slug is the readable name part, e.g. /boards/mein-board. Without it the view
      // opens the last used (or first) board.
      path: '/boards/:slug?',
      name: 'board',
      component: BoardView,
      // Sidebar and board use the full window width.
      meta: { requiresAuth: true, fullWidth: true },
    },
    {
      path: '/boards/:slug/settings',
      name: 'board-settings',
      component: BoardView,
      meta: { requiresAuth: true, fullWidth: true },
    },
    {
      path: '/account',
      name: 'account',
      component: () => import('../views/AccountView.vue'),
      meta: { requiresAuth: true, title: 'Account' },
    },
    {
      // The unsubscribe link in notification mails; no login needed.
      path: '/unsubscribe/:token',
      name: 'unsubscribe',
      component: () => import('../views/UnsubscribeView.vue'),
      meta: { requiresAuth: false, title: 'Unsubscribe' },
    },
    {
      path: '/due',
      name: 'due',
      component: () => import('../views/DueView.vue'),
      meta: { requiresAuth: true, title: "What's due" },
    },
    {
      // Read-only view of a board through its share link, no login needed.
      path: '/share/:token',
      name: 'shared-board',
      component: () => import('../views/SharedBoardView.vue'),
      meta: { requiresAuth: false, fullWidth: true },
    },
    {
      // Invite link of a board (see BoardMembers); joining needs a login.
      path: '/join/:token',
      name: 'join',
      component: () => import('../views/JoinView.vue'),
      meta: { requiresAuth: true, title: 'Join board' },
    },
    // Old URL of the task list.
    { path: '/tasks', redirect: '/boards' },
    {
      path: '/oauth2/callback',
      component: () => import('@/auth/OAuthCallback.vue'),
      meta: { requiresAuth: false, title: 'Signing in' },
    },
    // Old create/edit/detail pages are now dialogs on the board.
    { path: '/:pathMatch(.*)*', redirect: '/boards' },
  ],
})

// Pages with a fixed title set it here; boards set theirs in the view (usePageTitle),
// so this doesn't touch the title on their navigations (e.g. when the search changes the URL).
router.afterEach((to) => {
  if (to.meta.title) document.title = formatTitle(to.meta.title)
})

export default router
