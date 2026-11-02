// The own board updates live, shows who else has it open, and doesn't let one edit silently
// overwrite another.
import { test } from '@playwright/test'
import { BASE, resetMock } from './support.mjs'

test('Board updates live', async ({ browser }) => {
  await resetMock()
  const page = await browser.newPage({ viewport: { width: 1400, height: 900 } })
  const errors = []
  page.on('pageerror', (e) => errors.push(e.message))
  const ensure = (cond, msg) => { if (!cond) throw new Error(msg) }
  const out = (name) => `${test.info().outputDir}/${name}`
  const api = (url, method, body) => fetch(`${BASE}/api${url}`, { method, body: body && JSON.stringify(body) })
  const state = async () => (await (await fetch(`${BASE}/api/__state`)).json())
  const card = (id) => page.locator(`[data-task-id="${id}"]`)
  const dialog = () => page.locator('.p-dialog').first()

  await page.goto(`${BASE}/boards/my-board`)
  await card(1).waitFor()
  await page.evaluate(() => { window.stillHere = true })

  await test.step('who else has the board open shows next to its name', async () => {
    await api('/__presence', 'POST', ['Bob Builder', 'Carla'])
    const presence = page.getByTestId('presence')
    await presence.getByText('BB').waitFor()
    ensure((await presence.getAttribute('aria-label')) === 'Bob Builder, Carla have this board open too', await presence.getAttribute('aria-label'))
    await page.screenshot({ path: out('lb-1-presence.png') })
    await api('/__presence', 'POST', [])
    await presence.waitFor({ state: 'detached' })
  })

  await test.step("someone else's change shows up without a reload", async () => {
    const task = (await state()).tasks.find((t) => t.id === 1)
    await api('/tasks/1', 'PUT', { ...task, title: 'Changed elsewhere' })
    await card(1).getByText('Changed elsewhere').waitFor()
    ensure(await page.evaluate(() => window.stillHere), 'page reloaded')
  })

  await test.step('saving over a newer version asks first', async () => {
    await card(2).locator('h3').click()
    await dialog().getByRole('button', { name: 'Edit', exact: true }).click()
    await page.locator('#task-title').fill('Mine')
    // Meanwhile someone else saves task 2.
    const task = (await state()).tasks.find((t) => t.id === 2)
    await api('/tasks/2', 'PUT', { ...task, title: 'Theirs' })
    await card(2).getByText('Theirs').waitFor()
    // The open form keeps what was typed.
    ensure((await page.locator('#task-title').inputValue()) === 'Mine', 'form overwritten by the live update')

    await page.getByRole('button', { name: 'Save changes' }).click()
    await page.locator('.p-confirmdialog').getByText('Someone else saved this task').waitFor()
    await page.screenshot({ path: out('lb-2-conflict.png') })
    await page.locator('.p-confirmdialog').getByRole('button', { name: 'Save mine' }).click()
    await card(2).getByText('Mine').waitFor()
    ensure((await state()).tasks.find((t) => t.id === 2).title === 'Mine', 'not saved')
  })

  await test.step('or drops the own edit and shows theirs', async () => {
    await page.keyboard.press('Escape')
    await page.waitForFunction(() => !document.querySelector('.p-dialog'))
    await card(3).locator('h3').click()
    await dialog().getByRole('button', { name: 'Edit', exact: true }).click()
    await page.locator('#task-title').fill('Mine again')
    const task = (await state()).tasks.find((t) => t.id === 3)
    await api('/tasks/3', 'PUT', { ...task, title: 'Theirs again' })
    await page.getByRole('button', { name: 'Save changes' }).click()
    await page.locator('.p-confirmdialog').getByRole('button', { name: 'See their version' }).click()
    await page.getByTestId('task-view').getByText('Theirs again').waitFor()
    ensure((await state()).tasks.find((t) => t.id === 3).title === 'Theirs again', 'overwritten')
  })

  if (errors.length) throw new Error(`page errors: ${errors.join('; ')}`)
  await page.close()
})
