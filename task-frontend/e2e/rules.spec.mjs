// Automations in the board settings, and what they do when a task enters the column.
import { test } from '@playwright/test'
import { BASE, resetMock } from './support.mjs'

test('Board automations', async ({ browser }) => {
  await resetMock()
  const page = await browser.newPage({ viewport: { width: 1400, height: 900 } })
  const errors = []
  page.on('pageerror', (e) => errors.push(e.message))
  const ensure = (cond, msg) => { if (!cond) throw new Error(msg) }
  const state = async () => (await fetch(`${BASE}/api/__state`)).json()
  const rules = () => page.getByTestId('board-rules')
  const card = (id) => page.locator(`[data-task-id="${id}"]`)
  async function choose(label, option) {
    await rules().getByRole('combobox', { name: label }).click()
    await page.getByRole('option', { name: option, exact: true }).click()
  }

  await page.goto(`${BASE}/boards/my-board/settings`)
  await rules().getByText('No automations yet.').waitFor()

  await test.step('a checklist and a due date rule are added', async () => {
    await choose('Column', 'In progress')
    await rules().getByRole('textbox', { name: 'Checklist items' }).fill('Prepare\n\n  Thank-you mail ')
    await rules().getByRole('button', { name: 'Add automation' }).click()
    await rules().getByText('Add checklist items: Prepare, Thank-you mail').waitFor()

    await choose('Action', 'Set the due date')
    await rules().getByRole('textbox', { name: 'Days from now' }).fill('14')
    await rules().getByRole('button', { name: 'Add automation' }).click()
    await rules().getByText('Set the due date to 14 days from now').waitFor()
    ensure((await rules().getByText('When a task enters In progress').count()) === 1, 'not grouped by column')

    const saved = (await state()).rules
    ensure(saved.length === 2 && saved.every((r) => r.columnId === 11), JSON.stringify(saved))
    ensure(JSON.stringify(saved[0].items) === '["Prepare","Thank-you mail"]', JSON.stringify(saved[0]))
  })

  await test.step('a rule can be switched off and deleted', async () => {
    await rules().getByRole('checkbox').nth(1).click()
    await page.waitForFunction(async (base) => (await (await fetch(`${base}/api/__state`)).json()).rules[1].enabled === false, BASE)
    await rules().getByRole('button', { name: /^Delete automation: Set the due date/ }).click()
    await rules().getByTestId('rule').nth(1).waitFor({ state: 'detached' })
    ensure((await state()).rules.length === 1, 'not deleted')
  })

  await test.step('a rule is edited in place; cancel keeps it as it was', async () => {
    await rules().getByRole('button', { name: /^Edit automation: Add checklist/ }).click()
    await rules().getByRole('textbox', { name: 'Edit checklist items' }).fill('Nothing')
    await rules().getByRole('button', { name: 'Cancel' }).click()
    await rules().getByText('Add checklist items: Prepare, Thank-you mail').waitFor()

    await rules().getByRole('button', { name: /^Edit automation: Add checklist/ }).click()
    const field = rules().getByRole('textbox', { name: 'Edit checklist items' })
    ensure((await field.inputValue()) === 'Prepare\nThank-you mail', await field.inputValue())
    await field.fill('')
    ensure(await rules().getByRole('button', { name: 'Save' }).isDisabled(), 'saving an empty list allowed')
    await field.fill('Research\nPrepare\nThank-you mail')
    await rules().getByRole('button', { name: 'Save' }).click()
    await rules().getByText('Add checklist items: Research, Prepare, Thank-you mail').waitFor()
    ensure(JSON.stringify((await state()).rules[0].items) === '["Research","Prepare","Thank-you mail"]', 'not saved')
  })

  await test.step('moving a task into the column adds the checklist', async () => {
    await page.goto(`${BASE}/boards/my-board`)
    await card(1).hover()
    await card(1).getByRole('button', { name: 'Move to In progress' }).click()
    await card(1).getByTestId('checklist-progress').getByText('0/3').waitFor()
    const task = (await state()).tasks.find((t) => t.id === 1)
    ensure(task.description === 'first\n- [ ] Research\n- [ ] Prepare\n- [ ] Thank-you mail', JSON.stringify(task.description))
  })

  await test.step('the history names the automation', async () => {
    await card(1).locator('h3').click()
    await page.getByTestId('task-view').waitFor()
    const history = page.getByTestId('task-history')
    await history.getByRole('button', { name: 'History' }).click()
    const latest = history.locator('li').first()
    await latest.getByText('Changed the description').waitFor()
    ensure((await latest.getByTestId('event-user').innerText()).includes('Automation'), await latest.innerText())
  })

  ensure(errors.length === 0, errors.join('\n'))
})
