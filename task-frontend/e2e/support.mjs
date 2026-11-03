// Shared by the spec files. Each worker has its own mock server (see playwright.config.mjs), so
// spec files running at the same time don't share state.
export const BASE = `http://localhost:${4999 + Number(process.env.TEST_PARALLEL_INDEX ?? 0)}`

// Back to the mock's start state, so every spec file begins with the same three tasks.
export async function resetMock() {
  const response = await fetch(`${BASE}/api/__reset`, { method: 'POST' })
  if (!response.ok) throw new Error(`mock reset failed: ${response.status}`)
}
