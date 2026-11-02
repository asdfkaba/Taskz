// Board members: the owner invites with a link, a member joins, sees whose board it is, and leaves.
import { test } from '@playwright/test'
import { BASE, resetMock } from './support.mjs'

test('Board members', async ({ browser }) => {
  await resetMock()
  const page = await browser.newPage({ viewport: { width: 1400, height: 900 } })
  const errors = []
  page.on('pageerror', (e) => errors.push(e.message))
  const ensure = (cond, msg) => { if (!cond) throw new Error(msg) }
  const out = (name) => `${test.info().outputDir}/${name}`
  const members = () => page.getByTestId('board-members')
  const sidebar = () => page.getByRole('navigation', { name: 'Boards' }).first()

  await test.step('the owner creates an invite link, shown once', async () => {
    await page.goto(`${BASE}/boards/my-board/settings`)
    await members().getByTestId('member').first().getByText('Owner').waitFor()
    await page.getByRole('heading', { name: 'Share link' }).waitFor()
    await members().getByRole('button', { name: 'Create invite link' }).click()
    const link = await page.getByRole('textbox', { name: 'Invite link' }).inputValue()
    ensure(/\/join\/[A-Za-z0-9_-]{22}$/.test(link), link)
    await members().getByText('shown only once').waitFor()
    await page.screenshot({ path: out('mb-1-invite.png') })

    // Opening it yourself only leads back to the board.
    await page.goto(link)
    await page.getByText('You can open').waitFor()
  })

  await test.step('joining a board shows whose it is', async () => {
    await page.goto(`${BASE}/join/team-invite`)
    await page.getByText('Bob invited you to the board').waitFor()
    await page.screenshot({ path: out('mb-2-join.png') })
    await page.getByRole('button', { name: 'Join board' }).click()
    await page.waitForURL(/\/boards\/team-board$/)
    const entry = sidebar().locator('li').filter({ hasText: 'Team board' })
    await entry.getByText('by Bob').waitFor()
    ensure((await entry.getByRole('button', { name: /Delete board/ }).count()) === 0, 'members can delete')
  })

  await test.step('a member sees the members, but no sharing or deleting, and can leave', async () => {
    await page.goto(`${BASE}/boards/team-board/settings`)
    await members().getByTestId('member').nth(1).getByText('Alice').waitFor()
    ensure((await page.getByRole('heading', { name: 'Share link' }).count()) === 0, 'share shown')
    ensure((await page.getByRole('heading', { name: 'Delete board' }).count()) === 0, 'delete shown')
    ensure((await members().getByRole('button', { name: /^Remove/ }).count()) === 0, 'remove shown')
    await page.screenshot({ path: out('mb-3-member-settings.png') })
    await members().getByRole('button', { name: 'Leave board' }).click()
    await page.locator('.p-confirmdialog').getByRole('button', { name: 'Leave board' }).click()
    await page.waitForURL(/\/boards\/my-board$/)
    await sidebar().locator('li').filter({ hasText: 'Team board' }).waitFor({ state: 'detached' })
  })

  await test.step('a used or unknown link says so', async () => {
    await page.goto(`${BASE}/join/no-such-link`)
    await page.getByText("This invite link doesn't work anymore").waitFor()
  })

  if (errors.length) throw new Error(`page errors: ${errors.join('; ')}`)
  await page.close()
})
