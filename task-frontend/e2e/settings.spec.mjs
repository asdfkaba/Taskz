// Board settings page: name, template, labels, share link and deleting the board.
import { test } from '@playwright/test'
import { BASE, resetMock } from './support.mjs'

test('Board settings', async ({ browser }) => {
  await resetMock()
  const page = await browser.newPage({ viewport: { width: 1400, height: 900 } })
  const errors = []
  page.on('pageerror', (e) => errors.push(e.message))
  const ensure = (cond, msg) => { if (!cond) throw new Error(msg) }
  const state = async () => (await fetch(`${BASE}/api/__state`)).json()
  const section = (name) => page.getByRole('region', { name })
  const sidebarLink = () => page.locator('aside nav[aria-label="Boards"] a')
  const out = (name) => `${test.info().outputDir}/${name}`

  await page.goto(`${BASE}/boards`)
  await page.locator('[data-task-id="1"]').waitFor()

  await test.step('the gear opens the settings of the board', async () => {
    await page.getByRole('button', { name: 'Board settings' }).click()
    await page.getByTestId('board-settings').waitFor()
    ensure(new URL(page.url()).pathname === '/boards/my-board/settings', page.url())
    ensure((await page.title()) === 'Settings · My board · Taskz', await page.title())
    ensure((await sidebarLink().getAttribute('aria-current')) === 'page', 'board not highlighted')
    ensure((await page.locator('[data-task-id]').count()) === 0, 'columns still shown')
    ensure((await page.getByRole('button', { name: 'New task' }).count()) === 0, 'board buttons still shown')
    for (const name of ['Name', 'Task template', 'Labels', 'Share link', 'Delete board']) {
      await section(name).waitFor()
    }
    await page.screenshot({ path: out('bs-1-settings.png'), fullPage: true })
  })

  await test.step('renaming changes name, address and sidebar', async () => {
    const rename = section('Name').getByRole('button', { name: 'Rename' })
    ensure(await rename.isDisabled(), 'rename enabled without change')
    await page.getByRole('textbox', { name: 'Board name' }).fill('  Job hunt ')
    await rename.click()
    await page.waitForURL((u) => u.pathname === '/boards/job-hunt/settings')
    await page.locator('.p-toast').getByText('Board renamed').waitFor()
    ensure((await page.locator('h1').innerText()).trim() === 'Job hunt', 'heading')
    ensure((await sidebarLink().innerText()).trim() === 'Job hunt', 'sidebar')
    ensure((await page.title()) === 'Settings · Job hunt · Taskz', await page.title())
    ensure((await state()).board.name === 'Job hunt', 'not saved')
    ensure(await rename.isDisabled(), 'rename enabled after saving')
  })

  await test.step('an empty name is not sent', async () => {
    await page.getByRole('textbox', { name: 'Board name' }).fill('   ')
    ensure(await section('Name').getByRole('button', { name: 'Rename' }).isDisabled(), 'rename enabled')
    await page.getByRole('textbox', { name: 'Board name' }).fill('Job hunt')
  })

  await test.step('the settings page opens directly after a reload', async () => {
    await page.reload()
    await page.getByTestId('board-settings').waitFor()
    ensure((await page.getByRole('textbox', { name: 'Board name' }).inputValue()) === 'Job hunt', 'name lost')
  })

  await test.step('the share link carries the new name', async () => {
    await section('Share link').getByRole('button', { name: 'Create link' }).click()
    const link = await page.getByRole('textbox', { name: 'Share link' }).inputValue()
    ensure(/\/share\/job-hunt-[A-Za-z0-9_-]{22}$/.test(link), link)
  })

  await test.step('labels are managed without picking them', async () => {
    const labels = section('Labels')
    ensure((await labels.locator('input[type="checkbox"]').count()) === 0, 'checkboxes shown')
    await labels.getByRole('button', { name: 'Create a label' }).click()
    await labels.getByRole('textbox', { name: 'Label name' }).fill('Remote')
    await labels.getByRole('button', { name: 'Create', exact: true }).click()
    await labels.getByText('Remote').waitFor()
    ensure((await state()).labels.map((l) => l.name).join() === 'Remote', 'not created')

    await labels.getByRole('button', { name: 'Edit label Remote' }).click()
    await labels.getByRole('textbox', { name: 'Label name' }).fill('Hybrid')
    await labels.getByRole('button', { name: 'Save' }).click()
    await labels.getByText('Hybrid').waitFor()
    ensure((await state()).labels[0].name === 'Hybrid', 'not renamed')
  })

  await test.step('back on the board the label is there', async () => {
    await page.getByRole('button', { name: 'Board view', exact: true }).click()
    await page.locator('[data-task-id="1"]').waitFor()
    ensure(new URL(page.url()).pathname === '/boards/job-hunt', page.url())
    await page.locator('[data-task-id="1"] h3').click()
    await page.locator('.p-dialog').getByRole('button', { name: 'Edit', exact: true }).click()
    await page.locator('.p-dialog').getByRole('button', { name: 'Edit labels' }).click()
    await page.getByTestId('label-picker').getByText('Hybrid').waitFor()
    ensure((await page.getByTestId('label-picker').locator('input[type="checkbox"]').count()) === 1, 'no checkbox in the task')
    await page.keyboard.press('Escape')
    await page.waitForFunction(() => !document.querySelector('.p-dialog'))
  })

  await test.step('dark mode and phone width', async () => {
    await page.getByRole('button', { name: 'Board settings' }).click()
    await page.getByTestId('board-settings').waitFor()
    await page.evaluate(() => document.documentElement.classList.add('dark'))
    await page.setViewportSize({ width: 390, height: 844 })
    await page.waitForTimeout(300)
    ensure(!(await page.evaluate(() => document.documentElement.scrollWidth > window.innerWidth)), 'page scrolls sideways')
    await page.screenshot({ path: out('bs-2-dark-phone.png'), fullPage: true })
    await page.evaluate(() => document.documentElement.classList.remove('dark'))
    await page.setViewportSize({ width: 1400, height: 900 })
  })

  await test.step('deleting asks for the name and leaves the settings', async () => {
    await section('Delete board').getByRole('button', { name: 'Delete board' }).click()
    const dialog = page.locator('.p-dialog').filter({ hasText: 'will be deleted' })
    await dialog.waitFor()
    const confirm = dialog.getByRole('button', { name: 'Delete board' })
    ensure(await confirm.isDisabled(), 'delete enabled without the name')
    await dialog.locator('#board-delete-confirm').fill('Job hunt')
    await confirm.click()
    await page.getByText('No boards yet').waitFor()
    ensure(new URL(page.url()).pathname === '/boards', page.url())
    ensure((await page.getByTestId('board-settings').count()) === 0, 'settings still shown')
  })

  if (errors.length) throw new Error(`page errors: ${errors.join('; ')}`)
  for (const context of browser.contexts()) await context.close()
})
