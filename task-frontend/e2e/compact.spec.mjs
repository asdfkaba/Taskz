// Compact cards: the board view's compact form, per board, is one row: title, due date and menu.
import { test } from '@playwright/test'
import { BASE, resetMock } from './support.mjs'

async function api(method, path, body) {
  const response = await fetch(`${BASE}/api${path}`, {
    method,
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
  return response.json()
}

test('Compact cards', async ({ browser }) => {
  await resetMock()
  const remote = await api('POST', '/boards/1/labels', { name: 'Remote', color: '#3b82f6' })
  const urgent = await api('POST', '/boards/1/labels', { name: 'Urgent', color: '#ef4444' })
  const task = await api('PUT', '/tasks/1', {
    title: 'Task A',
    description: 'A longer description',
    due_date: null,
    labelIds: [remote.id, urgent.id],
    checklist: [{ text: 'Cover letter' }, { text: 'CV' }],
  })
  await api('PATCH', `/tasks/1/checklist/${task.checklist[0].id}`, { checked: true })
  const longTitle = 'Task C with a title far too long to fit into a single row of a compact card'
  await api('PUT', '/tasks/3', { title: longTitle, description: null, due_date: null })

  const page = await browser.newPage({ viewport: { width: 1400, height: 900 } })
  const errors = []
  page.on('pageerror', (e) => errors.push(e.message))
  const ensure = (cond, msg) => { if (!cond) throw new Error(msg) }
  const card = () => page.locator('[data-task-id="1"]')
  const toggle = () => page.getByRole('button', { name: 'Compact board view' })
  const out = (name) => `${test.info().outputDir}/${name}`

  await page.goto(`${BASE}/boards`)
  await card().waitFor()
  const fullHeight = (await card().boundingBox()).height

  await test.step('cards start with description and label pills', async () => {
    ensure((await toggle().getAttribute('aria-pressed')) === 'false', 'starts compact')
    await card().getByText('A longer description').waitFor()
    ensure((await card().locator('[data-label-pill]').count()) === 2, 'pills missing')
    await page.screenshot({ path: out('cc-1-normal.png') })
  })

  await test.step('compact shows only the title, the due date and the menu', async () => {
    await toggle().click()
    ensure((await toggle().getAttribute('aria-pressed')) === 'true', 'not pressed')
    ensure((await card().getByText('A longer description').count()) === 0, 'description still shown')
    ensure((await card().locator('[data-label-pill]').count()) === 0, 'pills still shown')
    ensure((await card().getByTestId('checklist-progress').count()) === 0, 'progress shown')
    const titleSize = await card().locator('h3').evaluate((el) => getComputedStyle(el).fontSize)
    ensure(titleSize === '13px', titleSize)
    const compactHeight = (await card().boundingBox()).height
    ensure(compactHeight < fullHeight * 0.7, `${compactHeight} vs ${fullHeight}`)
    const taskC = page.locator('[data-task-id="3"]')
    // One row whatever the card holds; the menu moves the task, there are no arrows.
    for (const c of [card(), taskC]) {
      ensure((await c.boundingBox()).height < 40, `more than one row: ${(await c.boundingBox()).height}`)
      ensure((await c.getByRole('button', { name: /^Move to / }).count()) === 0, 'arrows shown')
      ensure(await c.getByRole('button', { name: 'Task actions' }).isVisible(), 'menu missing')
    }
    const title = taskC.locator('h3')
    ensure(await title.evaluate((el) => el.scrollWidth > el.clientWidth), 'long title not cut off')
    ensure((await title.getAttribute('title')) === longTitle, 'full title not on hover')
    await page.mouse.move(700, 800)
    await page.screenshot({ path: out('cc-2-compact.png') })
  })

  await test.step('the choice survives a reload', async () => {
    await page.reload()
    await card().waitFor()
    ensure((await toggle().getAttribute('aria-pressed')) === 'true', 'forgotten')
    ensure((await card().getByText('A longer description').count()) === 0, 'description shown after reload')
  })

  await test.step('search in the description still says so', async () => {
    await page.getByRole('textbox', { name: /search/i }).fill('longer')
    await card().getByText('Match in description').waitFor()
    await page.getByRole('textbox', { name: /search/i }).fill('')
  })

  await test.step('dark mode', async () => {
    await page.evaluate(() => document.documentElement.classList.add('dark'))
    await page.waitForTimeout(200)
    await page.screenshot({ path: out('cc-3-dark.png') })
    await page.evaluate(() => document.documentElement.classList.remove('dark'))
  })

  await test.step('back to the board brings everything back', async () => {
    await page.getByRole('button', { name: 'Board view', exact: true }).click()
    ensure((await toggle().getAttribute('aria-pressed')) === 'false', 'still pressed')
    await card().getByText('A longer description').waitFor()
    ensure((await card().locator('[data-label-pill]').count()) === 2, 'pills missing')
  })

  if (errors.length) throw new Error(`page errors: ${errors.join('; ')}`)
  for (const context of browser.contexts()) await context.close()
})
