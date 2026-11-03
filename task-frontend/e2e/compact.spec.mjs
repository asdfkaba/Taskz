// Compact cards: the board view's compact form, per board, hides descriptions and shows labels as dots.
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
  await api('PUT', '/tasks/1', {
    title: 'Task A',
    description: 'A longer description\n\n- [x] Cover letter\n- [ ] CV',
    due_date: null,
    labelIds: [remote.id, urgent.id],
  })

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

  await test.step('compact hides the description and shows labels as dots', async () => {
    await toggle().click()
    ensure((await toggle().getAttribute('aria-pressed')) === 'true', 'not pressed')
    ensure((await card().getByText('A longer description').count()) === 0, 'description still shown')
    ensure((await card().locator('[data-label-pill]').count()) === 0, 'pills still shown')
    const dots = card().getByTestId('label-dots').getByRole('img')
    ensure((await dots.count()) === 2, 'dots missing')
    ensure((await dots.first().getAttribute('title')) === 'Remote', 'no hover text')
    ensure((await dots.first().getAttribute('aria-label')) === 'Label Remote', 'no accessible name')
    ensure((await card().getByTestId('checklist-progress').innerText()).trim() === '1/2', 'progress missing')
    const titleSize = await card().locator('h3').evaluate((el) => getComputedStyle(el).fontSize)
    ensure(titleSize === '13px', titleSize)
    const compactHeight = (await card().boundingBox()).height
    ensure(compactHeight < fullHeight * 0.7, `${compactHeight} vs ${fullHeight}`)
    const taskC = page.locator('[data-task-id="3"]')
    ensure((await taskC.boundingBox()).height < 45, `card without details too high: ${(await taskC.boundingBox()).height}`)
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
