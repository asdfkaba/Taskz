// The page the login provider sends the browser back to: success goes to the boards, a refused
// login says why and offers the way back.
import { test } from '@playwright/test'
import { BASE, resetMock } from './support.mjs'

test('Login callback', async ({ browser }) => {
  await resetMock()
  const page = await browser.newPage({ viewport: { width: 1200, height: 800 } })
  const errors = []
  page.on('pageerror', (e) => errors.push(e.message))
  const ensure = (cond, msg) => { if (!cond) throw new Error(msg) }

  await test.step('a refused login shows the reason and the way back', async () => {
    await page.goto(`${BASE}/oauth2/callback?code=unverified&provider=discord`)
    const message = page.getByTestId('login-error')
    await message.waitFor()
    ensure((await message.innerText()).includes('no confirmed email'), await message.innerText())
    await page.screenshot({ path: `${test.info().outputDir}/login-refused.png` })
    await page.getByRole('button', { name: 'Back to login' }).click()
    await page.waitForURL(/\/(login|boards)/)
  })

  await test.step('without a code it says the login was cancelled', async () => {
    await page.goto(`${BASE}/oauth2/callback?error=access_denied`)
    await page.getByTestId('login-error').getByText('cancelled').waitFor()
  })

  await test.step('a good login goes to the boards', async () => {
    await page.goto(`${BASE}/oauth2/callback?code=good&provider=discord`)
    await page.waitForURL(/\/boards/)
  })

  if (errors.length) throw new Error(`page errors: ${errors.join('; ')}`)
  await page.close()
})
