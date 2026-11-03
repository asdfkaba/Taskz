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

  await test.step('who else has the board open shows next to its name, you first', async () => {
    const icons = () => page.getByTestId('presence').locator('[data-testid="presence-me"], [data-testid="presence-other"]')
    const fills = async () => icons().evaluateAll((spans) => spans.map((s) => s.querySelector('rect')?.getAttribute('fill')))
    const shapes = async () => icons().evaluateAll((spans) => spans.map((s) => s.querySelector('svg').innerHTML))
    await api('/__presence', 'POST', [{ id: 2, name: 'Bob Builder' }, { id: 3, name: 'Carla' }])
    const presence = page.getByTestId('presence')
    await presence.waitFor()
    ensure((await presence.getAttribute('aria-label')) === 'You and Bob Builder, Carla have this board open', await presence.getAttribute('aria-label'))
    ensure((await icons().first().getAttribute('data-testid')) === 'presence-me', 'you not first')
    ensure((await icons().first().getAttribute('title')) === 'You (Alice)', await icons().first().getAttribute('title'))
    ensure((await icons().nth(1).getAttribute('title')) === 'Bob Builder', 'no name on hover')
    // One icon per person, each in its own pattern and color.
    const colors = await fills()
    ensure(new Set(colors).size === 3 && colors.every(Boolean), `colors ${colors}`)
    ensure(new Set(await shapes()).size === 3, 'two icons look the same')
    // The same person gets the same icon again.
    const before = await shapes()
    await api('/__presence', 'POST', [{ id: 3, name: 'Carla' }, { id: 2, name: 'Bob Builder' }])
    await page.waitForFunction(() => document.querySelectorAll('[data-testid="presence-other"]')[0]?.title === 'Carla')
    const after = await shapes()
    ensure(after[0] === before[0] && after[1] === before[2] && after[2] === before[1], 'icon changed for the same person')
    await page.screenshot({ path: out('lb-1-presence.png') })

    // Four others still fit; from five on, three are shown and the rest is a "+N" with their names.
    const people = (n) => Array.from({ length: n }, (_, i) => ({ id: 10 + i, name: `P${i + 1}` }))
    const others = () => page.getByTestId('presence-other')
    await api('/__presence', 'POST', people(4))
    await page.waitForFunction(() => document.querySelectorAll('[data-testid="presence-other"]').length === 4)
    ensure((await page.getByTestId('presence-more').count()) === 0, '+N with only four')
    await api('/__presence', 'POST', people(6))
    await page.getByTestId('presence-more').waitFor()
    ensure((await others().count()) === 3, `others shown: ${await others().count()}`)
    ensure((await page.getByTestId('presence-more').innerText()).trim() === '+3', await page.getByTestId('presence-more').innerText())
    ensure((await page.getByTestId('presence-more').getAttribute('title')) === 'P4, P5, P6', 'hidden names')
    await page.screenshot({ path: out('lb-1b-many.png') })

    // On a phone only the number, so the title and the toolbar keep their room.
    await page.setViewportSize({ width: 390, height: 800 })
    const count = page.getByTestId('presence-count')
    await count.waitFor()
    ensure((await count.innerText()).trim() === '6', await count.innerText())
    ensure(!(await page.getByTestId('presence-me').isVisible()), 'icons on a phone')
    const [presenceBox, searchBox] = [await page.getByTestId('presence').boundingBox(), await page.getByRole('button', { name: 'Search', exact: true }).boundingBox()]
    const overlap = (a, b) => a.x < b.x + b.width && b.x < a.x + a.width && a.y < b.y + b.height && b.y < a.y + a.height
    ensure(!overlap(presenceBox, searchBox), `presence overlaps search: ${JSON.stringify([presenceBox, searchBox])}`)
    // The toolbar moves to its own line, so the title isn't cut off either.
    ensure(await page.locator('h1').first().evaluate((h) => h.scrollWidth <= h.clientWidth), 'title cut off on a phone')
    await page.screenshot({ path: out('lb-1c-phone.png'), clip: { x: 0, y: 0, width: 390, height: 160 } })
    await page.setViewportSize({ width: 1400, height: 900 })
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
