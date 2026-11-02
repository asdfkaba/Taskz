import { defineStore } from 'pinia'
import { getCurrentUser, logoutBackend } from '../api/actions'

export const useAuthStore = defineStore('auth', {
  state: () => ({
    user: null,
    isAuthenticated: false,
  }),

  actions: {
    async logout() {
      logoutBackend();
      this.user = null;
      this.isAuthenticated = false;    
    },
    async checkLoginStatus() {
      const result = await getCurrentUser();
      if (result.status === 'ok') {
	this.user = result.user;
	this.isAuthenticated = true;
      }
      return result;
    }
  }
})

