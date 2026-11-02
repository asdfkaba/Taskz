<script setup>
import { reactive, ref, computed, onMounted } from 'vue'
import { useAuthStore } from '@/stores/auth'
import Button from 'primevue/button';

import { useRouter, useRoute } from 'vue-router'
import { useToast } from 'primevue/usetoast';


const toast = useToast();
const router = useRouter()

const authStore = useAuthStore()

onMounted(() => {
  if (authStore.isAuthenticated) {
    router.push('/boards')
  }
})

function base64url(buffer) {
  let binary = "";
  for (let i = 0; i < buffer.length; i++) {
    binary += String.fromCharCode(buffer[i]);
  }
  return btoa(binary)
    .replace(/\+/g, "-")
    .replace(/\//g, "_")
    .replace(/=+$/, "");
}

async function generatePKCE() {
  const codeVerifierBytes = crypto.getRandomValues(new Uint8Array(32));
  const code_verifier = base64url(codeVerifierBytes);

  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(code_verifier));
  const code_challenge = base64url(new Uint8Array(digest));

  return { code_verifier, code_challenge };
}

async function loginWithGithub() {
  const { code_verifier, code_challenge } = await generatePKCE();

  // Store the code_verifier temporarily
  sessionStorage.setItem("pkce_code_verifier", code_verifier);

  const clientId = 'Ov23liqqiVqq1lqxD5AV';
  const redirectUri = `${window.location.origin}/oauth2/callback?provider=github`;
  // Only the email addresses (read-only); the login needs nothing else. "user" would allow
  // changing the profile. access_type/prompt are Google parameters that GitHub doesn't know.
  const scope = 'user:email';
  const responseType = 'code';

  const authUrl =
    `https://github.com/login/oauth/authorize?` +
    `client_id=${clientId}&` +
    `redirect_uri=${encodeURIComponent(redirectUri)}&` +
    `response_type=${responseType}&` +
    `scope=${encodeURIComponent(scope)}&` +
    `code_challenge=${code_challenge}&` +
    `code_challenge_method=S256`;

  window.location.href = authUrl;
}

async function loginWithDiscord() {
  const { code_verifier, code_challenge } = await generatePKCE();

  // Store the code_verifier temporarily
  sessionStorage.setItem("pkce_code_verifier", code_verifier);

  const clientId = '1390322077785133067';
  const redirectUri = `${window.location.origin}/oauth2/callback?provider=discord`;
  // identify for /users/@me, email for the address and its "verified" flag; nothing else.
  // No prompt=consent, which made Discord ask again at every login.
  const scope = 'identify email';
  const responseType = 'code';

  const authUrl =
    `https://discord.com/oauth2/authorize?` +
    `client_id=${clientId}&` +
    `redirect_uri=${encodeURIComponent(redirectUri)}&` +
    `response_type=${responseType}&` +
    `scope=${encodeURIComponent(scope)}&` +
    `code_challenge=${code_challenge}&` +
    `code_challenge_method=S256`;

  window.location.href = authUrl;
}

async function loginWithGoogle() {
  const { code_verifier, code_challenge } = await generatePKCE();

  // Store the code_verifier temporarily
  sessionStorage.setItem("pkce_code_verifier", code_verifier);

  const clientId = '462857227500-spe4l3v5mgjb63u0kbb9tpr87n09tkbq.apps.googleusercontent.com';
  const redirectUri = `${window.location.origin}/oauth2/callback?provider=google`;
  // The ID token's email and email_verified are all the login needs; no profile, no offline
  // access (Google's refresh tokens aren't used), and no prompt=consent asking again every time.
  const scope = 'openid email';
  const responseType = 'code';

  const authUrl =
    `https://accounts.google.com/o/oauth2/v2/auth?` +
    `client_id=${clientId}&` +
    `redirect_uri=${encodeURIComponent(redirectUri)}&` +
    `response_type=${responseType}&` +
    `scope=${encodeURIComponent(scope)}&` +
    `code_challenge=${code_challenge}&` +
    `code_challenge_method=S256`;

  window.location.href = authUrl;
}
</script>

<template>
  <div class="flex min-h-[70vh] items-center justify-center">
    <div class="w-full max-w-sm rounded-2xl border border-slate-200 dark:border-slate-800 bg-white dark:bg-slate-900 p-8 shadow-sm">
      <div class="mb-8 flex flex-col items-center gap-3 text-center">
        <img src="/favicon.svg" alt="" class="h-12 w-12" />
        <h1 class="text-xl font-semibold text-slate-900 dark:text-slate-100">Welcome to Taskz</h1>
        <p class="text-sm text-slate-500 dark:text-slate-400">Sign in to see and manage your tasks.</p>
      </div>

      <p
        class="mb-6 flex gap-2 rounded-lg border border-amber-200 bg-amber-50 p-3 text-xs text-amber-900 dark:border-amber-900/60 dark:bg-amber-950/40 dark:text-amber-200"
        role="note"
      >
        <i class="pi pi-exclamation-triangle mt-0.5 shrink-0" aria-hidden="true" />
        <span>
          Taskz is an experimental hobby project. There is no guarantee for your data: it may be lost,
          and the service may change or go away. Don't keep anything here you can't afford to lose.
        </span>
      </p>

      <div class="flex flex-col gap-3">
        <Button
          label="Continue with Google"
          icon="pi pi-google"
          severity="secondary"
          outlined
          fluid
          @click="loginWithGoogle"
        />
        <Button
          label="Continue with GitHub"
          icon="pi pi-github"
          severity="secondary"
          outlined
          fluid
          @click="loginWithGithub"
        />
        <Button
          label="Continue with Discord"
          icon="pi pi-discord"
          severity="secondary"
          outlined
          fluid
          @click="loginWithDiscord"
        />
      </div>
    </div>
  </div>
</template>
