// Archive: a task leaves the board from its menu, and comes back or is deleted for good in the archive view.
import { test } from '@playwright/test'
import { BASE, resetMock } from './support.mjs'

test('Archive', async ({ browser }) => {
  await resetMock()
  const page = await browser.newPage({ viewport: { width: 1400, height: 900 } })
  const errors = []
  page.on('pageerror', (e) => errors.push(e.message))
  const ensure = (cond, msg) => { if (!cond) throw new Error(msg) }
  const state = async () => (await fetch(`${BASE}/api/__state`)).json()
  const archive = () => page.getByTestId('board-archive')
  const archiveFromMenu = async (taskId) => {
    await page.locator(`[data-task-id="${taskId}"]`).getByRole('button', { name: 'Task actions' }).click()
    await page.getByRole('menuitem', { name: 'Archive' }).click()
    await page.locator(`[data-task-id="${taskId}"]`).waitFor({ state: 'detached' })
  }

  await page.goto(`${BASE}/boards`)
  await page.locator('[data-task-id="1"]').waitFor()

  await test.step('an empty archive says how to fill it', async () => {
    await page.getByRole('button', { name: 'Archive' }).click()
    await archive().getByText('No archived tasks.').waitFor()
    ensure(page.url().endsWith('/archive'), page.url())
    // The archive is one of the views: its button is pressed, and the toolbar stays where it is.
    const pressed = (name) => page.getByRole('button', { name, exact: true }).getAttribute('aria-pressed')
    ensure((await pressed('Archive')) === 'true', 'archive button not pressed')
    ensure((await pressed('Board view')) === 'false', 'board view pressed in the archive')
    ensure((await page.getByRole('button', { name: 'New task' }).count()) === 0, 'new task offered in the archive')
    await page.screenshot({ path: `${test.info().outputDir}/ar-0-toolbar.png` })
    const settings = page.getByRole('button', { name: 'Board settings' })
    const before = await settings.boundingBox()
    await page.getByRole('button', { name: 'Board view', exact: true }).click()
    await page.locator('[data-task-id="1"]').waitFor()
    const after = await settings.boundingBox()
    ensure(before.x === after.x && before.y === after.y, 'the toolbar jumped between archive and board')
    ensure((await pressed('Board view')) === 'true', 'board view not pressed again')
  })

  await test.step('archiving takes tasks off the board without asking', async () => {
    await archiveFromMenu(1)
    await archiveFromMenu(3)
    ensure((await page.locator('.p-dialog').count()) === 0, 'asked before archiving')
    const tasks = (await state()).tasks
    ensure(JSON.stringify(tasks.map((t) => [t.id, t.position])) === '[[2,0]]', JSON.stringify(tasks))
  })

  await test.step('the archive lists them, last archived first, and survives a reload', async () => {
    await page.getByRole('button', { name: 'Archive' }).click()
    await archive().locator('[data-archived-id]').first().waitFor()
    await page.reload()
    await archive().locator('[data-archived-id]').first().waitFor()
    const ids = await archive().locator('[data-archived-id]').evaluateAll((els) => els.map((e) => e.dataset.archivedId))
    ensure(JSON.stringify(ids) === '["3","1"]', JSON.stringify(ids))
    ensure((await archive().locator('[data-archived-id="1"]').innerText()).includes('To do'), 'column name')
    await page.screenshot({ path: `${test.info().outputDir}/ar-1-archive.png` })
  })

  await test.step('restore puts the task back at the end of its column', async () => {
    await archive().locator('[data-archived-id="1"]').getByRole('button', { name: 'Restore' }).click()
    await archive().locator('[data-archived-id="1"]').waitFor({ state: 'detached' })
    const tasks = (await state()).tasks
    ensure(JSON.stringify(tasks.map((t) => [t.id, t.position])) === '[[2,0],[1,1]]', JSON.stringify(tasks))
  })

  await test.step('deleting for good asks first', async () => {
    await archive().getByRole('button', { name: 'Delete Task C permanently' }).click()
    await page.getByRole('alertdialog').getByRole('button', { name: 'Delete' }).click()
    await archive().getByText('No archived tasks.').waitFor()
  })

  await test.step('the restored task is on the board again, with its history', async () => {
    await page.getByRole('button', { name: 'Board view', exact: true }).click()
    await page.locator('[data-task-id="1"]').waitFor()
    ensure((await page.locator('[data-task-id="3"]').count()) === 0, 'deleted task shown')
    ensure((await page.locator('[data-task-id]').evaluateAll((els) => els.map((e) => e.dataset.taskId))).join() === '2,1', 'order on the board')
  })

  await test.step('phone: the header stays put between board, archive and settings', async () => {
    await page.setViewportSize({ width: 390, height: 844 })
    const gear = page.getByRole('button', { name: 'Board settings' })
    const place = async () => JSON.stringify(await gear.boundingBox())
    const onBoard = await place()
    await page.getByRole('button', { name: 'Archive' }).click()
    await archive().waitFor()
    ensure((await place()) === onBoard, `header jumped in the archive: ${onBoard} -> ${await place()}`)
    await page.screenshot({ path: `${test.info().outputDir}/ar-2-phone.png` })
    await gear.click()
    await page.getByTestId('board-settings').waitFor()
    ensure((await place()) === onBoard, `header jumped in the settings: ${onBoard} -> ${await place()}`)
  })

  if (errors.length) throw new Error(`page errors: ${errors.join('; ')}`)
  for (const context of browser.contexts()) await context.close()
})
