// Shared board: changes of the owner show up while the link is open, without a page reload.
import { test } from '@playwright/test'
import { BASE, resetMock } from './support.mjs'

test('Shared board updates live', async ({ browser }) => {
  await resetMock()
  const page = await browser.newPage({ viewport: { width: 1400, height: 900 } })
  const errors = []
  page.on('pageerror', (e) => errors.push(e.message))
  const ensure = (cond, msg) => { if (!cond) throw new Error(msg) }
  const api = (url, method, body) => fetch(`${BASE}/api${url}`, { method, body: body && JSON.stringify(body) })
  const card = (id) => page.locator(`[data-task-id="${id}"]`)
  const view = () => page.getByTestId('task-view')

  const share = await (await api('/boards/1/share', 'POST')).json()
  await page.goto(`${BASE}/share/${share.path}`)
  await card(1).waitFor()
  // Gone after a page reload, so it shows the page stayed.
  await page.evaluate(() => { window.stillHere = true })

  await test.step('an edit changes the card and the open dialog in place', async () => {
    await card(1).locator('h3').click()
    await view().waitFor()
    await api('/tasks/1', 'PUT', { title: 'Renamed live', description: 'New text' })
    await card(1).getByText('Renamed live').waitFor()
    await view().getByText('New text').waitFor()
    ensure(await page.locator('.p-dialog').isVisible(), 'dialog closed')
    ensure(await page.evaluate(() => window.stillHere), 'page reloaded')
    await page.screenshot({ path: `${test.info().outputDir}/live-1-edited.png` })
  })

  await test.step('a moved task changes its column', async () => {
    await api('/tasks/1/move', 'PATCH', { columnId: 12, position: 0 })
    await page.waitForFunction(() =>
      document.querySelector('[data-task-id="1"]')?.closest('[data-column-id]')?.dataset.columnId === '12')
  })

  await test.step('deleting the open task closes the dialog', async () => {
    await api('/tasks/1/delete', 'DELETE')
    await page.waitForFunction(() => !document.querySelector('.p-dialog'))
    await card(1).waitFor({ state: 'detached' })
    ensure(await page.evaluate(() => window.stillHere), 'page reloaded')
  })

  await test.step('revoking the link tells the viewer', async () => {
    await api('/boards/1/share', 'DELETE')
    await page.getByText("This link doesn't work anymore").waitFor()
  })

  if (errors.length) throw new Error(`page errors: ${errors.join('; ')}`)
  await page.close()
})
