// Admin page: all users with when they registered, deleting one after typing their email.
import { test } from '@playwright/test'
import { BASE, resetMock } from './support.mjs'

test('Admin', async ({ browser }) => {
  await resetMock()
  const page = await browser.newPage({ viewport: { width: 1200, height: 800 } })
  const errors = []
  page.on('pageerror', (e) => errors.push(e.message))
  const ensure = (cond, msg) => { if (!cond) throw new Error(msg) }
  const state = async () => (await fetch(`${BASE}/api/__state`)).json()
  const admin = () => page.getByTestId('admin')

  await test.step('admins reach it through the account menu', async () => {
    await page.goto(`${BASE}/boards`)
    await page.getByRole('button', { name: 'Account menu' }).click()
    await page.getByRole('menuitem', { name: 'Admin' }).click()
    await admin().getByRole('heading', { name: 'Users (2)' }).waitFor()
    ensure((await page.title()) === 'Admin · Taskz', await page.title())
  })

  await test.step('lists the users; no date for old accounts, no delete for yourself', async () => {
    const rows = admin().getByTestId('admin-user')
    ensure((await rows.count()) === 2, 'not two users')
    await rows.filter({ hasText: 'alice@example.com' }).getByText('Mar 2').waitFor()
    await rows.filter({ hasText: 'bob@example.com' }).getByText('–').waitFor()
    ensure((await page.getByRole('button', { name: 'Delete alice@example.com' }).count()) === 0, 'can delete self')
    await page.screenshot({ path: `${test.info().outputDir}/admin-1.png` })
  })

  await test.step('deleting needs the email typed', async () => {
    await page.getByRole('button', { name: 'Delete bob@example.com' }).click()
    const dialog = page.getByRole('dialog', { name: 'Delete user' })
    const confirm = dialog.getByRole('button', { name: 'Delete user' })
    await dialog.getByLabel(/To confirm/).fill('alice@example.com')
    ensure(await confirm.isDisabled(), 'enabled with the wrong email')
    await dialog.getByLabel(/To confirm/).fill('BOB@example.com ')
    await confirm.click()
    await dialog.waitFor({ state: 'hidden' })
    await admin().getByRole('heading', { name: 'Users (1)' }).waitFor()
    ensure((await state()).adminUsers.length === 1, 'not deleted on the server')
  })

  await test.step('phone width: nothing scrolls sideways', async () => {
    await page.setViewportSize({ width: 390, height: 844 })
    const wide = await page.evaluate(() => document.documentElement.scrollWidth > window.innerWidth)
    ensure(!wide, 'the page scrolls sideways')
  })

  ensure(errors.length === 0, errors.join('\n'))
})
