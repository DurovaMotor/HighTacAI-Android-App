import { expect, test, type Page } from '@playwright/test';

type BrowserError = { kind: 'console.error' | 'pageerror'; detail: string };

const browserErrors = new WeakMap<Page, BrowserError[]>();

test.beforeEach(async ({ page }) => {
  const errors: BrowserError[] = [];
  browserErrors.set(page, errors);
  page.on('pageerror', (error) => errors.push({ kind: 'pageerror', detail: error.message }));
  page.on('console', (entry) => {
    if (entry.type() === 'error') errors.push({ kind: 'console.error', detail: entry.text() });
  });
});

test.afterEach(async ({ page }) => {
  await page.waitForTimeout(250);
  expect(browserErrors.get(page) ?? []).toEqual([]);
});

async function login(page: Page) {
  await page.goto('/login');
  await expect(page.getByRole('heading', { name: '管理员登录' })).toBeVisible();
  await page.getByLabel('密码').fill('Adam');
  await page.getByRole('button', { name: /登\s*录/ }).click();
  await expect(page.getByRole('heading', { name: '仪表盘' })).toBeVisible();
}

async function openNavigation(page: Page) {
  const width = page.viewportSize()?.width || 1440;
  if (width <= 768) {
    await page.getByRole('button', { name: '打开导航' }).click();
  } else if (width <= 1024) {
    const expand = page.getByRole('button', { name: '展开导航' });
    if (await expand.isVisible().catch(() => false)) await expand.click();
  }
}

test('anonymous login handles the expected auth probe and serves the HighTac favicon', async ({ page }) => {
  await page.goto('/login');
  await expect(page.getByRole('heading', { name: '管理员登录' })).toBeVisible();
  const favicon = await page.request.get('/favicon.svg');
  expect(favicon.status()).toBe(200);
  expect(favicon.headers()['content-type']).toContain('image/svg+xml');
});

test('dashboard is visual-ready without page overflow', async ({ page }, testInfo) => {
  await login(page);
  await expect(page.getByText('24h 命令确认率')).toBeVisible();
  await expect(page.getByText('命令趋势')).toBeVisible();
  await page.waitForTimeout(500);
  const dimensions = await page.evaluate(() => ({ viewport: document.documentElement.clientWidth, page: document.documentElement.scrollWidth }));
  expect(dimensions.page).toBeLessThanOrEqual(dimensions.viewport + 1);
  await page.screenshot({ path: testInfo.outputPath(`dashboard-${page.viewportSize()?.width}x${page.viewportSize()?.height}.png`), fullPage: false });
});

test('initial password change is a hard navigation gate until completed', async ({ page }) => {
  await page.goto('/login');
  await page.evaluate(() => sessionStorage.setItem('hightac.mock.must_change', 'true'));
  await page.reload();
  await page.getByLabel('密码').fill('Adam');
  await page.getByRole('button', { name: /登\s*录/ }).click();
  await expect(page.getByRole('heading', { name: '管理员安全' })).toBeVisible();

  await openNavigation(page);
  await page.getByText('仪表盘', { exact: true }).first().click();
  await expect(page.getByRole('heading', { name: '管理员安全' })).toBeVisible();
  await page.getByLabel('当前密码').fill('Adam');
  await page.getByLabel('新密码', { exact: true }).fill('HighTacAdmin2026');
  await page.getByLabel('确认新密码').fill('HighTacAdmin2026');
  await page.getByRole('button', { name: '修改密码' }).click();
  await expect(page.getByRole('heading', { name: '仪表盘' })).toBeVisible();
  await expect(page).toHaveURL(/\/$/);

  await openNavigation(page);
  await page.getByText('管理员安全', { exact: true }).first().click();
  await expect(page.getByText('密码状态正常')).toBeVisible();
});

test('MQTT service exposes guarded controls and bounded logs', async ({ page }) => {
  await login(page);
  await openNavigation(page);
  await page.getByText('MQTT 服务', { exact: true }).first().click();
  await expect(page.getByRole('heading', { name: 'MQTT 服务' })).toBeVisible();
  await expect(page.getByText('链路检查')).toBeVisible();
  await expect(page.getByText('基站心跳', { exact: true })).toBeVisible();
  await expect(page.getByText('1 在线 · 1 超时 · 1 离线')).toBeVisible();
  await expect(page.getByLabel('Broker 日志', { exact: true })).toBeVisible();
  const pauseScroll = page.getByRole('switch', { name: '暂停 Broker 日志自动滚动' });
  await expect(pauseScroll).toBeVisible();
  await expect(page.getByRole('combobox', { name: 'Broker 日志显示行数' })).toBeVisible();
  await expect(page.getByRole('button', { name: /实时连接正常|实时通道重连中|网络离线/ })).toBeVisible();
  await expect(page.getByRole('button', { name: /打开用户菜单，当前用户/ })).toBeVisible();
  const stop = page.getByRole('button', { name: /停\s*止/ });
  await expect(stop).toBeEnabled();

  const logView = page.getByLabel('Broker 日志', { exact: true });
  await expect.poll(() => logView.evaluate((element) => element.scrollTop)).toBeGreaterThan(0);
  await pauseScroll.click();
  await logView.evaluate((element) => { element.scrollTop = 0; });
  await page.waitForTimeout(3200);
  expect(await logView.evaluate((element) => element.scrollTop)).toBe(0);

  await stop.click();
  const stopDialog = page.getByRole('dialog', { name: '确认停止 MQTT Broker？' });
  await expect(stopDialog).toBeVisible();
  await stopDialog.getByRole('button', { name: /取\s*消/ }).click();
});

test('operation log filters are labelled and stay inside the viewport', async ({ page }) => {
  await login(page);
  await openNavigation(page);
  await page.getByText('操作记录', { exact: true }).first().click();
  await expect(page.getByRole('heading', { name: '操作记录' })).toBeVisible();

  await expect(page.getByLabel('开始时间')).toBeVisible();
  await expect(page.getByLabel('结束时间')).toBeVisible();
  await expect(page.getByRole('combobox', { name: '操作者类型' })).toBeVisible();
  await expect(page.getByRole('combobox', { name: '实体类型' })).toBeVisible();
  await expect(page.getByRole('textbox', { name: '动作类型' })).toBeVisible();
  await expect(page.getByRole('textbox', { name: '实体 ID' })).toBeVisible();

  const rangeBounds = await page.locator('.filter-range').evaluate((element) => {
    const bounds = element.getBoundingClientRect();
    return { left: bounds.left, right: bounds.right };
  });
  const viewportWidth = await page.evaluate(() => document.documentElement.clientWidth);
  expect(rangeBounds.left).toBeGreaterThanOrEqual(0);
  expect(rangeBounds.right).toBeLessThanOrEqual(viewportWidth + 1);
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(viewportWidth + 1);

  const csvDownload = page.waitForEvent('download');
  await page.getByRole('button', { name: '导出 CSV' }).click();
  expect((await csvDownload).suggestedFilename()).toMatch(/\.csv$/);

  await page.getByRole('button', { name: '选择操作记录导出格式' }).click();
  const xlsxDownload = page.waitForEvent('download');
  await page.getByText('导出 XLSX', { exact: true }).click();
  expect((await xlsxDownload).suggestedFilename()).toMatch(/\.xlsx$/);
});

test('product import, binding and per-tag light command complete end to end', async ({ page }) => {
  await login(page);
  await openNavigation(page);
  await page.getByText('产品', { exact: true }).first().click();
  await page.getByRole('button', { name: 'Excel 导入' }).click();
  await page.locator('.ant-modal input[type="file"]').setInputFiles({
    name: 'products.xlsx',
    mimeType: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
    buffer: Buffer.from('mock workbook'),
  });
  await page.getByRole('button', { name: '开始预检' }).click();
  await expect(page.getByText('预检通过，可整体提交')).toBeVisible();
  await page.getByRole('button', { name: '整体提交' }).click();
  await expect(page.getByRole('dialog', { name: 'Excel 导入产品' })).toBeHidden();

  await openNavigation(page);
  await page.getByText('绑定关系', { exact: true }).first().click();
  await page.getByRole('button', { name: '新建绑定' }).click();
  await page.getByLabel('产品编码', { exact: true }).fill('HT-E2E-9001');
  await page.getByLabel('灯条 ID（可输入多个）').fill('AD1FFFFFF001');
  await page.getByLabel('基站 SN', { exact: true }).fill('90A9F7301427');
  await page.getByRole('button', { name: '创建绑定' }).click();
  await expect(page.getByText('HT-E2E-9001', { exact: true })).toBeVisible();

  await openNavigation(page);
  await page.getByText('灯光控制', { exact: true }).first().click();
  await page.getByLabel('产品编码', { exact: true }).fill('HT-E2E-9001');
  await page.getByRole('button', { name: '发送亮灯命令' }).click();
  await expect(page.getByText('逐灯结果')).toBeVisible();
  await expect(page.getByText('AD1FFFFFF001')).toBeVisible();
  await expect(page.getByText('90A9F7301427')).toBeVisible();

  const viewportWidth = await page.evaluate(() => document.documentElement.clientWidth);
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(viewportWidth + 1);
});

test('navigation reaches every M4 work surface without viewport overflow', async ({ page }) => {
  await login(page);
  const destinations = [
    ['基站管理', '基站管理'], ['全部灯条', '灯条管理'], ['产品', '产品'], ['绑定关系', '绑定关系'],
    ['灯光控制', '灯光控制'], ['操作记录', '操作记录'], ['站点与网络', '站点与网络'],
  ];
  for (const [link, heading] of destinations) {
    await openNavigation(page);
    await page.getByText(link, { exact: true }).first().click();
    await expect(page.getByRole('heading', { name: heading, exact: true, level: 1 })).toBeVisible();
    const dimensions = await page.evaluate(() => ({ viewport: document.documentElement.clientWidth, page: document.documentElement.scrollWidth }));
    expect(dimensions.page).toBeLessThanOrEqual(dimensions.viewport + 1);
  }
});
