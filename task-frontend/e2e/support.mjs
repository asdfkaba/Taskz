// Shared by the spec files. Each worker has its own mock server (see playwright.config.mjs), so
// spec files running at the same time don't share state.
export const BASE = `http://localhost:${4999 + Number(process.env.TEST_PARALLEL_INDEX ?? 0)}`

// Back to the mock's start state, so every spec file begins with the same three tasks.
export async function resetMock() {
  const response = await fetch(`${BASE}/api/__reset`, { method: 'POST' })
  if (!response.ok) throw new Error(`mock reset failed: ${response.status}`)
}

// PrimeVue moves the focus into a dialog only once it has finished opening; typing before that
// can end up in another field (e.g. the title, which has autofocus). Waits until it is open:
// the opening animation done and the focus inside. Endless animations (spinners) don't count.
export async function dialogOpened(page) {
  const dialog = page.locator('.p-dialog').first()
  await dialog.waitFor()
  await dialog.evaluate((el) => Promise.all(el.getAnimations({ subtree: true })
    .filter((a) => a.effect?.getTiming().iterations !== Infinity)
    .map((a) => a.finished)))
  await page.waitForFunction(() => document.querySelector('.p-dialog')?.contains(document.activeElement))
}
