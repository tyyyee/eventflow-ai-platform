import assert from 'node:assert/strict'
import { mkdir, writeFile } from 'node:fs/promises'
import { resolve } from 'node:path'

const { chromium } = await import(process.env.PLAYWRIGHT_MODULE || 'playwright')
const origin = process.env.LIFE_URL || 'http://127.0.0.1:4178'
const output = resolve(process.env.LIFE_EVIDENCE || 'test-output/discovery')
await mkdir(output, { recursive: true })
const browser = await chromium.launch({ headless: true, channel: process.env.LIFE_BROWSER_CHANNEL || 'chrome' })
const context = await browser.newContext({ viewport: { width: 1440, height: 1000 } })
const page = await context.newPage()
const checks = [], errors = []
page.on('pageerror', error => errors.push(error.message))
async function check(name, action) { await action(); checks.push(name); console.log(`PASS ${name}`) }
async function assertLayout() {
  assert(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1), 'page has horizontal overflow')
  const overflow = await page.locator('button').evaluateAll(buttons => buttons.filter(button => button.checkVisibility() && (button.scrollWidth > button.clientWidth + 2 || button.scrollHeight > button.clientHeight + 2)).map(button => button.getAttribute('aria-label') || button.textContent))
  assert.deepEqual(overflow, [], 'button content overflows')
}
async function shot(name) { await assertLayout(); await page.screenshot({ path: resolve(output, `${name}.png`), fullPage: false }) }
try {
  await page.goto(origin)
  await page.getByRole('button', { name: '查看毕业季草坪音乐会', exact: true }).waitFor()
  await check('saved empty state and persistent collection', async () => {
    await page.getByRole('button', { name: '我的收藏', exact: true }).click()
    await page.getByRole('heading', { name: '还没有收藏活动' }).waitFor()
    await page.getByRole('button', { name: '去发现活动', exact: false }).click()
    await page.getByRole('button', { name: '收藏毕业季草坪音乐会', exact: true }).click()
    assert.equal(await page.locator('dialog').count(), 0, 'saving should not open event details')
    await page.getByRole('button', { name: '我的收藏', exact: true }).click()
    await page.getByRole('button', { name: '查看毕业季草坪音乐会', exact: true }).waitFor()
    assert.equal(await page.locator('.shop-card').count(), 1)
    await page.reload()
    await page.getByRole('button', { name: '我的收藏', exact: true }).click()
    await page.getByRole('button', { name: '取消收藏毕业季草坪音乐会', exact: true }).waitFor()
    await shot('saved-desktop')
  })
  await check('collection synchronizes between browser tabs', async () => {
    const second = await context.newPage()
    await second.goto(origin)
    await second.getByRole('button', { name: '收藏非遗手作体验课', exact: true }).click()
    await page.getByRole('button', { name: '查看非遗手作体验课', exact: true }).waitFor()
    await second.close()
  })
  await check('empty search reset clears filters and pagination', async () => {
    await page.getByRole('button', { name: '活动广场', exact: true }).click()
    await page.getByRole('button', { name: '下一页', exact: true }).click()
    await page.getByRole('textbox', { name: '搜索校园活动' }).fill('不存在的活动')
    await page.getByRole('heading', { name: '没有找到匹配的活动' }).waitFor()
    await page.getByRole('button', { name: '清除筛选条件', exact: true }).click()
    await page.getByRole('button', { name: '查看毕业季草坪音乐会', exact: true }).waitFor()
    assert.equal(await page.getByRole('button', { name: '上一页', exact: true }).isEnabled(), false)
    await page.getByRole('button', { name: '音乐演出', exact: true }).click()
    await page.getByRole('button', { name: '分类：音乐演出', exact: false }).waitFor()
    await page.getByRole('button', { name: '清除全部', exact: true }).click()
    await page.getByRole('button', { name: '查看非遗手作体验课', exact: true }).waitFor()
  })
  await check('responsive catalog and accessible detail ticket navigation', async () => {
    for (const width of [320, 390, 768, 1440]) {
      await page.setViewportSize({ width, height: 900 })
      await page.evaluate(() => window.scrollTo(0, 0))
      await shot(`home-${width}`)
      const event = page.getByRole('button', { name: '查看毕业季草坪音乐会', exact: true })
      await event.click()
      await page.getByRole('button', { name: '选择票档', exact: false }).click()
      await page.getByRole('heading', { name: '可选票档', exact: false }).waitFor()
      await page.waitForFunction(() => {
        const box = document.querySelector('.offer-section-title')?.getBoundingClientRect()
        return box && box.top >= 0 && box.bottom < innerHeight - 100
      })
      assert(await page.getByRole('button', { name: '登录后抢票', exact: false }).first().isVisible())
      await shot(`detail-${width}`)
      await page.keyboard.press('Escape')
      assert.equal(await page.locator('dialog').count(), 0)
      assert(await event.evaluate(element => element === document.activeElement), 'focus should return to event card')
    }
  })
  await check('storage failure explains result without claiming success', async () => {
    await page.evaluate(() => { window.restoreStorage = Storage.prototype.setItem; Storage.prototype.setItem = () => { throw new Error('blocked for test') } })
    await page.getByRole('button', { name: '收藏星河音乐节', exact: true }).click()
    await page.getByRole('status').filter({ hasText: '收藏未保存' }).waitFor()
    assert.equal(await page.getByRole('button', { name: '收藏星河音乐节', exact: true }).getAttribute('aria-pressed'), 'false')
    await page.getByRole('button', { name: '查看星河音乐节', exact: true }).click()
    await page.getByRole('dialog').getByRole('button', { name: '收藏', exact: true }).click()
    await page.getByRole('dialog').getByRole('status').filter({ hasText: '收藏未保存' }).waitFor()
    assert.equal(await page.getByRole('dialog').getByRole('button', { name: '收藏', exact: true }).getAttribute('aria-pressed'), 'false')
    await page.keyboard.press('Escape')
    await page.evaluate(() => { Storage.prototype.setItem = window.restoreStorage; delete window.restoreStorage })
  })
  await check('view navigation restores browsing position', async () => {
    await page.getByRole('button', { name: '活动广场', exact: true }).click()
    await page.evaluate(() => window.scrollTo(0, 600))
    const before = await page.evaluate(() => window.scrollY)
    await page.getByRole('button', { name: '我的收藏', exact: true }).click()
    assert.equal(await page.evaluate(() => window.scrollY), 0)
    await page.getByRole('button', { name: '活动广场', exact: true }).click()
    assert(Math.abs(await page.evaluate(() => window.scrollY) - before) <= 2)
  })
  assert.deepEqual(errors, [])
  await writeFile(resolve(output, 'results.json'), JSON.stringify({ passed: true, checks, errors }, null, 2))
} catch (error) {
  await page.screenshot({ path: resolve(output, 'failure.png'), fullPage: false }).catch(() => {})
  await writeFile(resolve(output, 'results.json'), JSON.stringify({ passed: false, checks, errors, failure: error.message }, null, 2))
  throw error
} finally { await browser.close() }
