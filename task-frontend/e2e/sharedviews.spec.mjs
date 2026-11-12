// Shared boards offer the same views as the owner: compact cards and a read-only table.
import { test } from '@playwright/test'
import { BASE, resetMock } from './support.mjs'

async function api(method, path, body) {
  const response = await fetch(`${BASE}/api${path}`, {
    method,
    headers: { 'Content-Type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body),
  })
  return response.json()
}

test('Views on a shared board', async ({ browser }) => {
  await resetMock()
  const remote = await api('POST', '/boards/1/labels', { name: 'Remote', color: '#3b82f6' })
  await api('PUT', '/tasks/1', { title: 'Task A', description: 'Some text', due_date: null, labelIds: [remote.id] })
  const { path } = await api('POST', '/boards/1/share')

  const page = await browser.newPage({ viewport: { width: 1400, height: 900 } })
  const errors = []
  page.on('pageerror', (e) => errors.push(e.message))
  const ensure = (cond, msg) => { if (!cond) throw new Error(msg) }
  const card = () => page.locator('[data-task-id="1"]')
  const table = () => page.getByTestId('task-table')
  const out = (name) => `${test.info().outputDir}/${name}`

  await page.goto(`${BASE}/share/${path}`)
  await card().waitFor()

  await test.step('empty columns are a narrow strip, with the name cut to fit', async () => {
    const width = async (id) => (await page.locator(`[data-column-id="${id}"]`).boundingBox()).width
    // Mock: Done (12) is empty, In progress (11) has Task C.
    ensure((await width(12)) < 60, `Done is ${await width(12)}px`)
    ensure((await width(11)) > 250, `In progress is ${await width(11)}px`)
    ensure((await page.locator('[data-column-id="12"] h2').getAttribute('title')) === 'Done', 'no full name on hover')
    await page.screenshot({ path: out('sv-0-narrow.png') })
  })

  await test.step('compact cards', async () => {
    await card().getByText('Some text').waitFor()
    await page.getByRole('button', { name: 'Compact board view' }).click()
    ensure((await card().getByText('Some text').count()) === 0, 'description still shown')
    ensure((await card().boundingBox()).height < 40, `more than one row: ${(await card().boundingBox()).height}`)
    await page.screenshot({ path: out('sv-1-compact.png') })
  })

  await test.step('the table is read-only but opens tasks', async () => {
    await page.getByRole('button', { name: 'Table view' }).click()
    await table().waitFor()
    ensure((await table().locator('tbody tr').count()) === 3, 'rows')
    ensure((await table().locator('tbody').getByRole('combobox').count()) === 0, 'status can be changed')
    ensure((await table().locator('tbody tr').first().innerText()).includes('To do'), 'status text')
    await table().locator('tbody tr').nth(2).locator('td').nth(3).click()
    await page.getByRole('dialog').getByText('Task C').waitFor()
    await page.screenshot({ path: out('sv-2-table.png') })
    await page.keyboard.press('Escape')
    await page.waitForFunction(() => !document.querySelector('.p-dialog'))
  })

  await test.step('the choice survives a reload, separately from the owner', async () => {
    await page.reload()
    await table().waitFor()
    const keys = await page.evaluate(() => Object.keys(localStorage).filter((k) => k.startsWith('boardView:')))
    ensure(JSON.stringify(keys) === `["boardView:share:${path.slice(-22)}"]`, JSON.stringify(keys))
  })

  await test.step('phone width', async () => {
    await page.setViewportSize({ width: 390, height: 844 })
    await page.waitForTimeout(300)
    ensure(!(await page.evaluate(() => document.documentElement.scrollWidth > window.innerWidth)), 'page scrolls sideways')
    await page.screenshot({ path: out('sv-3-phone.png') })
  })

  if (errors.length) throw new Error(`page errors: ${errors.join('; ')}`)
  for (const context of browser.contexts()) await context.close()
})
