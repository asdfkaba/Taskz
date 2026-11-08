// Account settings: the menu behind your icon, the unsubscribe link from a mail, logging out.
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
    await menu.getByRole('menuitem', { name: 'Account settings' }).click()
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

  await test.step('the menu logs out', async () => {
    await page.getByRole('button', { name: 'Account menu' }).click()
    await page.locator('#user-menu').getByRole('menuitem', { name: 'Log out' }).click()
    await page.waitForURL(`${BASE}/`)
    await page.getByRole('button', { name: 'Account menu' }).waitFor({ state: 'detached' })
  })

  ensure(errors.length === 0, errors.join('\n'))
})
