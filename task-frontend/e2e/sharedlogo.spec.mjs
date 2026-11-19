// A guest on a shared board who clicks the Taskz logo lands on the login page, not on an empty board.
import { test } from '@playwright/test'
import { BASE, resetMock } from './support.mjs'

test('Taskz logo on a shared board, logged out', async ({ browser }) => {
  await resetMock()
  const { path } = await (await fetch(`${BASE}/api/boards/1/share`, { method: 'POST' })).json()

  const page = await browser.newPage()
  await page.route('**/api/auth/status', (route) => route.fulfill({ status: 401, body: '{}' }))
  await page.goto(`${BASE}/share/${path}`)
  await page.locator('[data-task-id="1"]').waitFor()

  await page.locator('header').getByRole('link', { name: 'Taskz' }).click()
  await page.waitForURL(`${BASE}/`)
  await page.screenshot({ path: `${test.info().outputDir}/logo-login.png` })
  await page.close()
})
