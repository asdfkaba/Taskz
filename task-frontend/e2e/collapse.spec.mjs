// Collapsing a column with tasks: a narrow strip like an empty column, remembered in this browser.
import { test } from '@playwright/test'
import { BASE, resetMock } from './support.mjs'

test('Columns can be collapsed', async ({ browser }) => {
  await resetMock()
  const context = await browser.newContext({ viewport: { width: 1400, height: 900 } })
  const page = await context.newPage()
  const errors = []
  page.on('pageerror', (e) => errors.push(e.message))
  const ensure = (cond, msg) => { if (!cond) throw new Error(msg) }
  // Mock columns: To do (10) with A and B, In progress (11) with C, Done (12) empty.
  const column = (id) => page.locator(`[data-column-id="${id}"]`)
  const width = async (id) => (await column(id).boundingBox()).width
  async function columnMenu(id, item) {
    await column(id).getByRole('button', { name: 'Column actions' }).click()
    await page.getByRole('menuitem', { name: item }).click()
  }

  await page.goto(`${BASE}/boards`)
  await page.locator('[data-task-id="1"]').waitFor()

  await test.step('"Collapse" turns a column with tasks into a strip with its count', async () => {
    await columnMenu(10, 'Collapse')
    await page.waitForFunction(() => document.querySelector('[data-column-id="10"]').getBoundingClientRect().width < 60)
    ensure(!(await page.locator('[data-task-id="1"]').isVisible()), 'cards still shown')
    ensure((await column(10).getByText('2', { exact: true }).count()) === 1, 'count missing')
    ensure(!(await column(10).getByRole('button', { name: 'Add task' }).isVisible()), 'add button shown')
    await page.mouse.move(5, 890)
    await page.screenshot({ path: `${test.info().outputDir}/collapse-1-strip.png` })
  })

  await test.step('a card dragged onto the strip goes into the column, which stays collapsed', async () => {
    const zone = await column(10).locator('[class*="border-dashed"]').boundingBox()
    const card = await page.locator('[data-task-id="3"]').boundingBox()
    await page.mouse.move(card.x + 60, card.y + 20)
    await page.mouse.down()
    await page.mouse.move(zone.x + zone.width / 2, zone.y + zone.height / 2, { steps: 20 })
    await page.mouse.up()
    await page.waitForFunction(async () => (await (await fetch('/api/__state')).json()).tasks.find((t) => t.id === 3).columnId === 10)
    ensure((await width(10)) < 60, 'column widened')
    await column(10).getByText('3', { exact: true }).waitFor()
  })

  await test.step('remembered after a reload, and "Expand" brings it back', async () => {
    await page.reload()
    await column(10).getByRole('heading', { name: 'To do' }).waitFor()
    ensure((await width(10)) < 60, 'not remembered')
    await columnMenu(10, 'Expand')
    await page.waitForFunction(() => document.querySelector('[data-column-id="10"]').getBoundingClientRect().width > 250)
    await page.locator('[data-task-id="1"]').waitFor({ state: 'visible' })
    await page.reload()
    await page.locator('[data-task-id="1"]').waitFor({ state: 'visible' })
  })

  await test.step('phones show columns full width and offer no "Collapse"', async () => {
    await columnMenu(10, 'Collapse')
    await page.setViewportSize({ width: 390, height: 844 })
    await page.locator('[data-task-id="1"]').waitFor({ state: 'visible' })
    await column(10).getByRole('button', { name: 'Column actions' }).click()
    await page.getByRole('menuitem', { name: 'Edit' }).waitFor()
    ensure(!(await page.getByRole('menuitem', { name: 'Expand' }).isVisible()), 'menu item shown on phone')
  })

  if (errors.length) throw new Error(`page errors: ${errors.join('; ')}`)
  await context.close()
})
