// Task template: one default description per board for new tasks.
import { test } from '@playwright/test'
import { BASE, dialogOpened, resetMock } from './support.mjs'

const TEMPLATE = '## Steps\n- [ ] Cover letter\n- [ ] CV'

test('Task template', async ({ browser }) => {
  await resetMock()
  const page = await browser.newPage({ viewport: { width: 1400, height: 900 } })
  const errors = []
  page.on('pageerror', (e) => errors.push(e.message))
  const ensure = (cond, msg) => { if (!cond) throw new Error(msg) }
  const state = async () => (await fetch(`${BASE}/api/__state`)).json()
  const templateSection = () => page.getByRole('region', { name: 'Task template' })
  const closed = () => page.waitForFunction(() => !document.querySelector('.p-dialog'))
  const openSettings = async () => {
    await page.getByRole('button', { name: 'Board settings' }).click()
    await templateSection().waitFor()
  }
  const backToBoard = async () => {
    await page.getByRole('button', { name: 'Back to board' }).click()
    await page.locator('[data-task-id="1"]').waitFor()
  }

  await page.goto(`${BASE}/boards`)
  await page.locator('[data-task-id="1"]').waitFor()

  await test.step('the board starts without a template; new tasks start empty', async () => {
    await page.getByRole('button', { name: 'New task' }).click()
    await page.locator('#task-title').waitFor()
    ensure((await page.locator('#task-description').inputValue()) === '', 'not empty')
    await page.keyboard.press('Escape')
    await closed()
  })

  await test.step('setting the template with preview', async () => {
    await openSettings()
    ensure((await page.locator('#task-template').inputValue()) === '', 'not empty')
    ensure(await templateSection().getByRole('button', { name: 'Save template' }).isDisabled(), 'save enabled without change')
    await page.locator('#task-template').fill(`\n${TEMPLATE}\n\n`)
    await templateSection().getByRole('button', { name: 'preview' }).click()
    ensure((await page.getByTestId('template-preview').locator('input[type="checkbox"]').count()) === 2, 'preview')
    await page.screenshot({ path: `${test.info().outputDir}/tp-1-settings.png` })
    await templateSection().getByRole('button', { name: 'Save template' }).click()
    await page.locator('.p-toast').getByText('Template saved').waitFor()
    ensure((await state()).board.taskTemplate === TEMPLATE, JSON.stringify((await state()).board.taskTemplate))
    await templateSection().getByRole('button', { name: 'write' }).click()
    ensure((await page.locator('#task-template').inputValue()) === TEMPLATE, 'not shown trimmed')
    ensure(await templateSection().getByRole('button', { name: 'Save template' }).isDisabled(), 'save enabled after saving')
    await backToBoard()
  })

  await test.step('a new task starts with the template and can change it', async () => {
    await page.getByRole('button', { name: 'New task' }).click()
    await dialogOpened(page)
    await page.locator('#task-title').fill('Firma XY')
    ensure((await page.locator('#task-description').inputValue()) === TEMPLATE, 'not prefilled')
    await page.locator('#task-description').fill(TEMPLATE + '\n- [ ] Portfolio')
    await page.getByRole('button', { name: 'Create task' }).click()
    await closed()
    const created = (await state()).tasks.find((t) => t.title === 'Firma XY')
    ensure(created.description === TEMPLATE + '\n- [ ] Portfolio', created.description)
    ensure((await page.locator(`[data-task-id="${created.id}"]`).getByTestId('checklist-progress').innerText()).trim() === '0/3', 'progress')
  })

  await test.step('closing an untouched new task does not ask about unsaved changes', async () => {
    await page.getByRole('button', { name: 'New task' }).click()
    await page.locator('#task-title').waitFor()
    await page.getByRole('button', { name: 'Cancel' }).click()
    await closed()
    ensure((await page.locator('.p-confirmdialog').count()) === 0, 'asked')
  })

  await test.step('editing an existing task keeps its description', async () => {
    await page.locator('[data-task-id="1"] h3').click()
    await page.getByTestId('task-view').waitFor()
    await page.locator('.p-dialog').getByRole('button', { name: 'Edit', exact: true }).click()
    ensure((await page.locator('#task-description').inputValue()) === 'first', 'replaced')
    await page.keyboard.press('Escape')
    await closed()
  })

  await test.step('the template survives a reload', async () => {
    await page.reload()
    await page.locator('[data-task-id="1"]').waitFor()
    await openSettings()
    ensure((await page.locator('#task-template').inputValue()) === TEMPLATE, 'lost')
  })

  await test.step('emptying it removes the template', async () => {
    await page.locator('#task-template').fill('   ')
    await templateSection().getByRole('button', { name: 'Save template' }).click()
    await page.locator('.p-toast').getByText('Template removed').waitFor()
    ensure((await state()).board.taskTemplate === null, 'still set')
    await backToBoard()
    await page.getByRole('button', { name: 'New task' }).click()
    ensure((await page.locator('#task-description').inputValue()) === '', 'still prefilled')
    await page.keyboard.press('Escape')
    await closed()
  })

  await test.step('dark mode', async () => {
    await page.evaluate(() => document.documentElement.classList.add('dark'))
    await openSettings()
    await page.locator('#task-template').fill(TEMPLATE)
    await page.waitForTimeout(300)
    await page.screenshot({ path: `${test.info().outputDir}/tp-2-dark.png` })
  })

  if (errors.length) throw new Error(`page errors: ${errors.join('; ')}`)
  for (const context of browser.contexts()) await context.close()
})
