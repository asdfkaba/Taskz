// Board cards show when the task last changed, at the bottom right.
import { test } from '@playwright/test'
import { BASE, resetMock } from './support.mjs'

const ensure = (cond, msg) => { if (!cond) throw new Error(msg) }

test('Last change on board cards', async ({ browser }) => {
  await resetMock()
  // Task A gets edited now; Task C was only created (Aug 1); Task B has neither date.
  await fetch(`${BASE}/api/tasks/1`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ title: 'Task A', description: 'first', due_date: null, labelIds: [] }),
  })

  const page = await browser.newPage({ viewport: { width: 1400, height: 900 } })
  const errors = []
  page.on('pageerror', (e) => errors.push(e.message))
  const card = (id) => page.locator(`[data-task-id="${id}"]`)
  const changed = (id) => card(id).getByTestId('card-changed')
  // The visible date, without the "Last changed" that only screen readers get.
  const dateOf = async (id) => (await changed(id).innerText()).replace('Last changed', '').trim()
  const today = new Date().toLocaleDateString('en-US', { month: 'short', day: 'numeric' })

  await page.goto(`${BASE}/boards`)
  await card(1).waitFor()
  await page.mouse.move(5, 890)

  await test.step('edited, created-only and undated tasks', async () => {
    ensure((await dateOf(1)) === today, `Task A: ${await dateOf(1)}`)
    ensure((await changed(1).getAttribute('title')) === `Last changed ${today}`, 'no hover text')
    ensure(/^Aug 1( 2026)?$/.test(await dateOf(3)), `Task C: ${await dateOf(3)}`)
    ensure((await changed(2).count()) === 0, 'Task B has no date to show')
  })

  await test.step('right-aligned at the bottom', async () => {
    const cardBox = await card(3).boundingBox()
    const dateBox = await changed(3).boundingBox()
    ensure(cardBox.x + cardBox.width - (dateBox.x + dateBox.width) < 12, 'not at the right edge')
    ensure(cardBox.y + cardBox.height - (dateBox.y + dateBox.height) < 20, 'not at the bottom')
    await page.screenshot({ path: `${test.info().outputDir}/changed-1-board.png` })
  })

  await test.step('with a mouse the move arrows cover it on hover', async () => {
    const arrows = card(1).getByRole('button', { name: /^Move to/ })
    const opacity = (locator) => locator.evaluate((el) => getComputedStyle(el.parentElement).opacity)
    const dateOpacity = () => changed(1).evaluate((el) => getComputedStyle(el).opacity)
    ensure((await opacity(arrows)) === '0', 'arrows shown without hover')
    const before = await card(1).boundingBox()
    await card(1).hover()
    await page.waitForTimeout(300)
    ensure((await opacity(arrows)) === '1', 'arrows hidden on hover')
    ensure((await dateOpacity()) === '0', 'date still shown on hover')
    const after = await card(1).boundingBox()
    ensure(after.height === before.height, `card height jumps: ${before.height} -> ${after.height}`)
    const arrowBox = await arrows.last().boundingBox()
    ensure(after.x + after.width - (arrowBox.x + arrowBox.width) < 12, 'arrow not at the right edge')
    await page.screenshot({ path: `${test.info().outputDir}/changed-2-hover.png` })
    await page.mouse.move(5, 890)
    await page.waitForTimeout(300)
    ensure((await dateOpacity()) === '1', 'date not back')
  })

  await test.step('compact cards leave it out', async () => {
    await page.getByRole('button', { name: 'Compact cards' }).click()
    ensure((await changed(1).count()) === 0, 'date in compact mode')
    await page.getByRole('button', { name: 'Compact cards' }).click()
  })

  if (errors.length) throw new Error(`page errors: ${errors.join('; ')}`)
  await page.close()

  await test.step('touch screens show date and arrows side by side', async () => {
    const context = await browser.newContext({ viewport: { width: 1400, height: 900 }, hasTouch: true, isMobile: true })
    const touch = await context.newPage()
    await touch.goto(`${BASE}/boards`)
    const touchCard = touch.locator('[data-task-id="1"]')
    await touchCard.getByTestId('card-changed').waitFor()
    await touchCard.getByRole('button', { name: /^Move to/ }).waitFor()
    await touch.screenshot({ path: `${test.info().outputDir}/changed-3-touch.png` })
    await context.close()
  })
})
