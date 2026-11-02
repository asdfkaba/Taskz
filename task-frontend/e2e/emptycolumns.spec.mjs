// Empty columns shrink to a narrow strip on wide screens and widen again when a task arrives.
import { test } from '@playwright/test'
import { BASE, resetMock } from './support.mjs'

test('Empty columns are narrow', async ({ browser }) => {
  await resetMock()
  const page = await browser.newPage({ viewport: { width: 1400, height: 900 } })
  const errors = []
  page.on('pageerror', (e) => errors.push(e.message))
  const ensure = (cond, msg) => { if (!cond) throw new Error(msg) }
  // Mock columns: To do (10) with A and B, In progress (11) with C, Done (12) empty.
  const width = async (id) => (await page.locator(`[data-column-id="${id}"]`).boundingBox()).width

  await page.goto(`${BASE}/boards`)
  await page.locator('[data-task-id="3"]').waitFor()

  await test.step('the empty column is a strip that still offers its actions', async () => {
    ensure((await width(12)) < 60, `Done is ${await width(12)}px`)
    ensure((await width(11)) > 250, `In progress is ${await width(11)}px`)
    const done = page.locator('[data-column-id="12"]')
    ensure(await done.getByRole('button', { name: 'Add task' }).isVisible(), 'no add button')
    ensure(await done.getByRole('button', { name: 'Column actions' }).isVisible(), 'no column menu')
    const name = await done.getByRole('heading', { name: 'Done' }).boundingBox()
    ensure(name.height > 20, `column name not shown (${name.height}px high)`)
    await page.mouse.move(5, 890)
    await page.screenshot({ path: `${test.info().outputDir}/empty-1-strip.png` })
  })

  await test.step('a task moved in widens it, and the column it left shrinks', async () => {
    const card = page.locator('[data-task-id="3"]')
    await card.hover()
    await card.getByRole('button', { name: 'Move to Done' }).click()
    await page.waitForFunction(() => document.querySelector('[data-column-id="12"]').getBoundingClientRect().width > 250)
    await page.waitForFunction(() => document.querySelector('[data-column-id="11"]').getBoundingClientRect().width < 60)
    await page.mouse.move(5, 890)
    await page.screenshot({ path: `${test.info().outputDir}/empty-2-moved.png` })
  })

  await test.step('a search that hides all tasks of a column keeps it wide', async () => {
    await page.getByRole('textbox', { name: /search/i }).fill('first') // only in Task A's description
    await page.locator('[data-column-id="12"]').getByText('No matches').waitFor()
    ensure((await width(12)) > 250, 'column shrank for the search')
    await page.getByRole('textbox', { name: /search/i }).fill('')
  })

  await test.step('small screens keep full-width columns', async () => {
    await page.setViewportSize({ width: 400, height: 800 })
    await page.waitForTimeout(200)
    ensure((await width(11)) > 300, `empty column on a phone is ${await width(11)}px`)
  })

  if (errors.length) throw new Error(`page errors: ${errors.join('; ')}`)
  await page.close()
})
