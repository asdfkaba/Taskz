class AuthError extends Error {
  constructor(message) {
    super(message)
    this.name = 'AuthError'
  }
}

export async function authFetch(input, init = {}) {
  const defaultInit = {
    credentials: 'include',  // always send cookies
    ...init,
    // After init, so the caller's headers add to the default instead of replacing it.
    headers: {
      // Form data (uploads) needs the browser's multipart type with its boundary.
      ...(init.body instanceof FormData ? {} : { 'Content-Type': 'application/json' }),
      // Board rules set due dates in the user's wall-clock time (RuleEngine in the backend).
      'X-Time-Zone': Intl.DateTimeFormat().resolvedOptions().timeZone,
      ...(init.headers || {}),
    },
  };

  const response = await fetch(input, defaultInit);

  if (response.status === 401) {
    throw new AuthError('Unauthorized');
  }

  return response;
}

export async function getCurrentUser() {
  let response;

  try {
    response = await authFetch('/api/auth/status');
  } catch (err) {
    if (err.name === 'AuthError') {
      return { status: 'unauthenticated' };
    }
    return { status: 'backend-unreachable' };
  }

  if ([502, 503, 504].includes(response.status)) {
    return { status: 'backend-unreachable' };
  }

  if (!response.ok) {
    return { status: 'error', code: response.status };
  }

  const user = await response.json();
  return { status: 'ok', user };
}


export async function logoutBackend() {
  const response = await authFetch('/api/auth/logout', {method: 'POST'});

  // 204 without a body.
  if (!response.ok) {
    throw new Error(`Logout failed: ${response.status}`);
  }
}
