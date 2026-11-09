// Account settings: the menu behind your icon, the unsubscribe link from a mail, logins, the data
// download, deleting the account, logging out.
import fs from 'node:fs'
import { test } from '@playwright/test'
import { BASE, resetMock } from './support.mjs'

test('Account', async ({ browser }) => {
  await resetMock()
  const page = await browser.newPage({ viewport: { width: 1400, height: 900 } })
  const errors = []
  page.on('pageerror', (e) => errors.push(e.message))
  const ensure = (cond, msg) => { if (!cond) throw new Error(msg) }
  const state = async () => (await fetch(`${BASE}/api/__state`)).json()
  const account = () => page.getByTestId('account')

  await test.step('your icon opens a menu with the account settings', async () => {
    await page.goto(`${BASE}/boards`)
    await page.getByRole('button', { name: 'Account menu' }).click()
    const menu = page.locator('#user-menu')
    await menu.getByText('Alice · alice@example.com').waitFor()
    await menu.getByRole('menuitem', { name: 'Log out' }).waitFor()
    await page.waitForFunction(() => !document.querySelector('#user-menu').getAnimations().length)
    await page.screenshot({ path: `${test.info().outputDir}/user-menu.png` })
    await menu.getByRole('menuitem', { name: 'Settings' }).click()
    await account().getByRole('heading', { name: 'API tokens' }).waitFor()
    // Mail notifications aren't offered until mail is set up.
    ensure((await account().getByText(/mail/i).count()) === 0, 'mail settings shown')
    ensure((await page.title()).startsWith('Account'), await page.title())
  })

  await test.step('the link in a mail asks, then stops the mails', async () => {
    await fetch(`${BASE}/api/__mail`, { method: 'POST', body: JSON.stringify({ delivery: 'DAILY' }) })
    await page.goto(`${BASE}/unsubscribe/mail-token`)
    const view = page.getByTestId('unsubscribe')
    await view.getByText('Stop getting notifications by mail?').waitFor()
    ensure((await state()).mail.delivery === 'DAILY', 'stopped before asking')
    await view.getByRole('button', { name: 'Stop mails' }).click()
    await view.getByText(/^Done\./).waitFor()
    ensure((await state()).mail.delivery === 'OFF', 'not stopped')

    await page.goto(`${BASE}/unsubscribe/wrong`)
    await page.getByRole('button', { name: 'Stop mails' }).click()
    await page.getByText("This link doesn't work.", { exact: false }).waitFor()
  })

  await test.step('lists the logins and logs out other devices', async () => {
    await page.goto(`${BASE}/account`)
    const sessions = account().getByTestId('session')
    await sessions.first().waitFor()
    ensure((await sessions.allInnerTexts()).join('|').includes('Firefox on Linux'), 'browser not named')
    await sessions.filter({ hasText: 'Firefox on Linux' }).getByText('This device').waitFor()
    await account().getByRole('button', { name: 'Log out Safari on iOS' }).click()
    await page.getByRole('alertdialog').getByRole('button', { name: 'Log out' }).click()
    await sessions.filter({ hasText: 'Safari on iOS' }).waitFor({ state: 'detached' })
    await account().getByRole('button', { name: 'Log out all other devices' }).click()
    await page.getByRole('alertdialog').getByRole('button', { name: 'Log out others' }).click()
    await account().getByRole('button', { name: 'Log out all other devices' }).waitFor({ state: 'detached' })
    ensure((await state()).sessions.length === 1 && (await sessions.count()) === 1, 'other devices still logged in')
  })

  await test.step('downloads the data', async () => {
    const [download] = await Promise.all([page.waitForEvent('download'), account().getByTestId('export').click()])
    const data = JSON.parse(fs.readFileSync(await download.path(), 'utf8'))
    ensure(data.user.email === 'alice@example.com', 'export without the user')
  })

  await test.step('deleting the account names the shared boards and needs the email', async () => {
    await fetch(`${BASE}/api/__members`, { method: 'POST', body: JSON.stringify([{ userId: 2, name: 'Bob', joined: '2026-11-01T09:00:00Z' }]) })
    await account().getByRole('button', { name: 'Delete account' }).click()
    const dialog = page.getByRole('dialog', { name: 'Delete account' })
    await dialog.getByTestId('shared-boards').getByText('My board').waitFor()
    const confirmButton = dialog.getByRole('button', { name: 'Delete account' })
    await dialog.getByLabel('To confirm').fill('alice@example')
    ensure(await confirmButton.isDisabled(), 'enabled before the email matches')
    await page.waitForFunction(() => !document.querySelector('[role=dialog]').getAnimations().length)
    await page.screenshot({ path: `${test.info().outputDir}/delete-account.png` })
    await dialog.getByLabel('To confirm').fill('Alice@example.com')
    await confirmButton.click()
    await page.waitForURL(`${BASE}/`)
    ensure((await state()).accountDeleted, 'not deleted')
    await page.getByRole('button', { name: 'Account menu' }).waitFor({ state: 'detached' })
  })

  await test.step('the menu logs out', async () => {
    await resetMock()
    await page.goto(`${BASE}/boards`)
    await page.getByRole('button', { name: 'Account menu' }).click()
    await page.locator('#user-menu').getByRole('menuitem', { name: 'Log out' }).click()
    await page.waitForURL(`${BASE}/`)
    await page.getByRole('button', { name: 'Account menu' }).waitFor({ state: 'detached' })
  })

  ensure(errors.length === 0, errors.join('\n'))
})
