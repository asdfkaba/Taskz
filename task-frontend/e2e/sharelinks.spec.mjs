// Readable share links.
import { test } from '@playwright/test'
import { BASE, resetMock } from './support.mjs'

test('Readable share links', async ({ browser }) => {
  const base = BASE
  const outDir = test.info().outputDir
  await resetMock()

  const host = new URL(base).hostname
  const owner = await browser.newContext({ viewport: { width: 1400, height: 900 } })
  await owner.grantPermissions(['clipboard-read', 'clipboard-write'], { origin: base })
  const page = await owner.newPage()
  const anon = await browser.newContext({ viewport: { width: 1400, height: 900 } })
  await anon.addCookies([{ name: 'mock_anon', value: '1', domain: host, path: '/' }])
  const guest = await anon.newPage()
  const errors = []
  for (const p of [page, guest]) p.on('pageerror', (e) => errors.push(e.message))
  async function check(...args) {
    const fn = args.pop()
    await test.step(args.pop(), fn)
  }
  const expect = (c, m) => { if (!c) throw new Error(m) }
  const state = async () => (await (await fetch(`${base}/api/__state`)).json())
  const shareInput = () => page.getByRole('textbox', { name: 'Share link' })
  const pathOf = (url) => new URL(url).pathname

  await page.goto(`${base}/boards`)
  await page.getByText('Task A', { exact: true }).waitFor()

  let link
  await check('settings show a link with board name and short key', async () => {
    await page.getByRole('button', { name: 'Board settings' }).click()
    await page.getByRole('button', { name: 'Create link' }).click()
    await shareInput().waitFor()
    link = await shareInput().inputValue()
    expect(/^\/share\/my-board-[A-Za-z0-9_-]{22}$/.test(pathOf(link)), link)
    await page.getByRole('button', { name: 'Copy' }).click()
    expect((await page.evaluate(() => navigator.clipboard.readText())) === link, 'clipboard')
    await page.screenshot({ path: `${outDir}/sl-1-settings.png` })
    await page.getByRole('button', { name: 'Board view', exact: true }).click()
  })

  await check('guest opens the link, address stays, loaded once', async () => {
    const before = (await state()).shareRequests.length
    await guest.goto(link)
    await guest.getByText('Task A', { exact: true }).waitFor()
    await guest.waitForTimeout(500)
    expect(pathOf(guest.url()) === pathOf(link), guest.url())
    expect((await state()).shareRequests.length - before === 1, 'loaded more than once')
  })

  await check('outdated name in the link is corrected without reloading', async () => {
    const key = pathOf(link).slice(-22)
    const before = (await state()).shareRequests.length
    await guest.goto(`${base}/share/old-name-${key}`)
    await guest.getByText('Task A', { exact: true }).waitFor()
    await guest.waitForURL((u) => u.pathname === pathOf(link))
    await guest.waitForTimeout(500)
    expect((await state()).shareRequests.length - before === 1, 'loaded more than once')
    expect((await guest.getByText('Read only').count()) === 1, 'view broken')
  })

  await check('old plain-token link is upgraded to the new form', async () => {
    await guest.goto(`${base}/share/13d72268-5904-431b-b410-f348df9ec414`)
    await guest.getByText('Task A', { exact: true }).waitFor()
    await guest.waitForURL((u) => u.pathname === pathOf(link))
  })

  await check('wrong key shows the not-found message', async () => {
    await guest.goto(`${base}/share/my-board-${'A'.repeat(22)}`)
    await guest.getByText("This link doesn't work anymore").waitFor()
    expect(pathOf(guest.url()) === `/share/my-board-${'A'.repeat(22)}`, 'address changed')
  })

  await check('replacing the link kills the old one', async () => {
    await page.getByRole('button', { name: 'Board settings' }).click()
    await shareInput().waitFor()
    expect((await shareInput().inputValue()) === link, 'reopened settings show another link')
    await page.getByRole('button', { name: 'Replace link' }).click()
    await page.locator('.p-confirmdialog').getByRole('button', { name: 'Replace link' }).click()
    await page.waitForFunction((old) => document.querySelector('input[aria-label="Share link"]')?.value !== old, link)
    const renewed = await shareInput().inputValue()
    expect(/\/share\/my-board-[A-Za-z0-9_-]{22}$/.test(renewed), renewed)
    await guest.goto(link)
    await guest.getByText("This link doesn't work anymore").waitFor()
    await guest.goto(renewed)
    await guest.getByText('Task A', { exact: true }).waitFor()
  })

  if (errors.length) throw new Error(`page errors: ${errors.join('; ')}`)
  for (const context of browser.contexts()) await context.close()
})
