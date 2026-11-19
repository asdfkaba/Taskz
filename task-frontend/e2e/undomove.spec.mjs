// Undoing a move: the toast after a move to another column, while the column's rules wait.
import { test } from '@playwright/test'
import { BASE, resetMock } from './support.mjs'

test('Undo a move', async ({ browser }) => {
  await resetMock()
  const page = await browser.newPage({ viewport: { width: 1400, height: 900 } })
  const errors = []
  page.on('pageerror', (e) => errors.push(e.message))
  const ensure = (cond, msg) => { if (!cond) throw new Error(msg) }
  const state = async () => (await fetch(`${BASE}/api/__state`)).json()
  const card = (id) => page.locator(`[data-task-id="${id}"]`)
  const toast = () => page.getByTestId('undo-toast')
  const moveTo = async (id, column) => {
    await card(id).hover()
    await card(id).getByRole('button', { name: `Move to ${column}` }).click()
  }
  const task1 = async () => (await state()).tasks.find((t) => t.id === 1)
  const moves = async () => (await state()).log.filter((l) => l.url.endsWith('/move')).length

  await page.goto(`${BASE}/boards`)
  await card(1).waitFor()

  await test.step('a move offers its undo; undo puts the task back in place, without a history entry', async () => {
    ensure((await task1()).columnId === 10 && (await task1()).position === 0, 'unexpected start')
    await moveTo(1, 'In progress')
    await toast().getByText('Moved to In progress').waitFor()
    await page.waitForFunction(() => !document.querySelector('[data-testid="undo-toast"]').closest('.p-toast').getAnimations({ subtree: true }).length)
    await page.screenshot({ path: `${test.info().outputDir}/undo-1-toast.png` })
    await toast().getByRole('button', { name: 'Undo' }).click()
    await page.locator('[data-column-id="10"]').locator('[data-task-id="1"]').waitFor()
    await toast().waitFor({ state: 'detached' })
    ensure((await task1()).columnId === 10 && (await task1()).position === 0, JSON.stringify(await task1()))
    await page.getByText('Move undone').waitFor()
  })

  await test.step('moving on keeps one undo back to where it came from', async () => {
    await moveTo(1, 'In progress')
    await toast().getByText('Moved to In progress').waitFor()
    await moveTo(1, 'Done')
    await toast().getByText('Moved to Done').waitFor()
    // The earlier toast goes (after its fade-out): one undo at a time.
    await page.waitForFunction(() => document.querySelectorAll('[data-testid="undo-toast"]').length === 1)
    await toast().getByRole('button', { name: 'Undo' }).click()
    await page.locator('[data-column-id="10"]').locator('[data-task-id="1"]').waitFor()
    ensure((await task1()).columnId === 10, 'not back in To do')
    await toast().waitFor({ state: 'detached' })
  })

  await test.step('moving back where it came from leaves nothing to undo', async () => {
    await moveTo(1, 'In progress')
    await toast().waitFor()
    const before = await moves()
    await moveTo(1, 'To do')
    await page.waitForFunction(async ([base, n]) => (await (await fetch(`${base}/api/__state`)).json()).log.filter((l) => l.url.endsWith('/move')).length > n, [BASE, before])
    await toast().waitFor({ state: 'detached' })
  })

  await test.step('reordering within a column offers no undo', async () => {
    await page.reload()
    await card(1).waitFor()
    await page.locator('[data-column-id="10"]').locator('[data-task-id="2"]').dragTo(card(1))
    await page.waitForTimeout(300)
    ensure((await toast().count()) === 0, 'undo offered for a reorder')
  })

  ensure(errors.length === 0, errors.join('\n'))
})
