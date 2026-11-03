import { createHash } from 'node:crypto';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { createRequire } from 'node:module';
import { join } from 'node:path';

const require = createRequire(
  'C:/Users/yohmi/AppData/Local/npm-cache/_npx/9833c18b2d85bc59/node_modules/playwright/package.json'
);
const { chromium } = require('playwright');

const baseUrl = process.env.IWS_ACCEPTANCE_BASE_URL ?? 'http://127.0.0.1:18000';
const yamlPath = 'C:/code/company/innolux-iws/iws-admin/src/main/resources/application-dev.yml';
const jarPath =
  process.env.IWS_ACCEPTANCE_JAR_PATH ??
  'C:/code/company/innolux-iws/iws-admin/target/iws-admin.jar';
const outputDir =
  process.env.IWS_ACCEPTANCE_OUTPUT_DIR ??
  'C:/code/company/innolux-iws/work/verification-current-20261103/browser-current-20261103';

await mkdir(outputDir, { recursive: true });

const yaml = await readFile(yamlPath, 'utf8');
const finalJarSha256 = createHash('sha256').update(await readFile(jarPath)).digest('hex').toUpperCase();
const passwordMatch = yaml.match(
  /spring\.boot\.admin\.client:[\s\S]*?username:\s*innolux\s*\r?\n\s*[^:\r\n]+:\s*(\S+)/
);
if (!passwordMatch) {
  throw new Error('未从隔离运行配置解析到普通验收账号密码');
}
const password = passwordMatch[1];

const diagnostics = {
  checkedAt: new Date().toISOString(),
  baseUrl,
  finalJarSha256,
  account: 'wmsacceptance',
  pageErrors: [],
  consoleErrors: [],
  failedRequests: [],
  apiResponses: [],
  checks: []
};

function assert(condition, message) {
  if (!condition) {
    throw new Error(message);
  }
  diagnostics.checks.push(message);
}

async function screenshot(page, name) {
  const path = join(outputDir, `${name}.png`);
  await page.screenshot({ path, fullPage: true });
  return path;
}

async function login(page) {
  await page.goto(`${baseUrl}/login`, { waitUntil: 'domcontentloaded' });
  await page.getByPlaceholder('用户名', { exact: true }).fill('wmsacceptance');
  await page.getByPlaceholder('密码', { exact: true }).fill(password);
  await page.locator('.submit-button').click();
  await page.waitForURL(url => !url.pathname.includes('/login'), { timeout: 30000 });
  await page.waitForFunction(() => Boolean(localStorage.getItem('Admin-Token')), null, {
    timeout: 30000
  });
  assert(true, '普通公司角色通过真实登录页进入系统');
}

await using browser = await chromium.launch({
  headless: true,
  executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe'
});
const context = await browser.newContext({
  viewport: { width: 1440, height: 1000 },
  locale: 'zh-CN'
});
const page = await context.newPage();

page.on('pageerror', error => diagnostics.pageErrors.push(String(error)));
page.on('console', message => {
  if (message.type() === 'error') {
    diagnostics.consoleErrors.push(message.text());
  }
});
page.on('requestfailed', request => {
  const url = new URL(request.url());
  diagnostics.failedRequests.push({
    method: request.method(),
    path: url.pathname,
    error: request.failure()?.errorText
  });
});
page.on('response', async response => {
  const url = new URL(response.url());
  if (!url.pathname.startsWith('/dev-api/')) {
    return;
  }
  let body;
  try {
    body = await response.json();
  } catch {
    body = null;
  }
  diagnostics.apiResponses.push({
    method: response.request().method(),
    path: url.pathname,
    status: response.status(),
    code: body?.code,
    msg: body?.msg
  });
});

try {
  await login(page);

  await page.goto(`${baseUrl}/iws-inventory/space`, { waitUntil: 'domcontentloaded' });
  await page.locator('.space-tabs').waitFor({ timeout: 30000 });
  await page.waitForFunction(() => document.body.innerText.includes('WH-ACCEPT'), null, {
    timeout: 30000
  });
  assert(
    (await page.locator('.space-tabs').innerText()).includes('仓库') &&
      (await page.locator('.space-tabs').innerText()).includes('库区') &&
      (await page.locator('.space-tabs').innerText()).includes('储位'),
    '空间页显示仓库、库区、储位三个正式页签'
  );
  assert(
    (await page.locator('body').innerText()).includes('验收父仓'),
    '空间页仓库表格显示正式验收仓库'
  );
  diagnostics.spaceWarehouseScreenshot = await screenshot(page, 'space-warehouse');

  await page.locator('.space-tabs').getByText('库区', { exact: true }).click();
  await page.waitForFunction(
    () =>
      document.body.innerText.includes('ZM-HTTP') &&
      document.body.innerText.includes('ZD-HTTP'),
    null,
    { timeout: 30000 }
  );
  assert(true, '空间页库区表格显示 MATERIAL 与 DEVICE 两层正式库区');
  diagnostics.spaceZoneScreenshot = await screenshot(page, 'space-zones');

  await page.locator('.space-tabs').getByText('储位', { exact: true }).click();
  await page.waitForFunction(() => document.body.innerText.includes('S-HTTP-001'), null, {
    timeout: 30000
  });
  assert(true, '空间页储位表格显示正式 API 创建的 ACTIVE 储位');
  diagnostics.spaceStorageScreenshot = await screenshot(page, 'space-storages');

  await page.goto(`${baseUrl}/iws-inventory/rules`, { waitUntil: 'domcontentloaded' });
  await page.waitForFunction(
    () =>
      document.body.innerText.includes('HTTP-STORAGE-FILTER') &&
      document.body.innerText.includes('ACTIVE · 生效'),
    null,
    { timeout: 30000 }
  );
  assert(true, '规则页显示生产来源编译并激活的 STORAGE 规则');
  diagnostics.rulesScreenshot = await screenshot(page, 'rules-list');

  await page.getByRole('button', { name: '版本', exact: true }).first().click();
  const ruleDrawer = page.locator('.el-drawer').filter({ hasText: '规则版本' });
  await ruleDrawer.waitFor({ timeout: 30000 });
  await ruleDrawer.getByText('ACTIVE · 生效', { exact: true }).first().waitFor({
    timeout: 30000
  });
  await ruleDrawer.getByText('就绪', { exact: true }).first().waitFor({ timeout: 30000 });
  assert(true, '规则版本抽屉显示 ACTIVE 且执行就绪');
  diagnostics.ruleVersionScreenshot = await screenshot(page, 'rules-versions');
  await ruleDrawer.locator('.el-drawer__close-btn').click();

  await page.getByRole('button', { name: '规则包', exact: true }).click();
  const packageDrawer = page.locator('.el-drawer').filter({ hasText: '规则包' });
  await packageDrawer.waitFor({ timeout: 30000 });
  await packageDrawer
    .getByText(/HTTP-STORAGE-PACKAGE-/, { exact: false })
    .first()
    .waitFor({ timeout: 30000 });
  await packageDrawer.locator('.el-table__body tbody tr').first().click();
  await packageDrawer.getByText('ALLOCATION_CANDIDATE_FILTER', { exact: true }).waitFor({
    timeout: 30000
  });
  assert(true, '规则包抽屉显示生效包及成员用途');
  diagnostics.rulePackageScreenshot = await screenshot(page, 'rules-package');

  const errorAlerts = await page.locator('.el-alert--error').count();
  assert(errorAlerts === 0, '本轮页面操作未出现业务错误提示');
  assert(diagnostics.pageErrors.length === 0, '本轮页面操作未出现未捕获脚本异常');
  assert(diagnostics.consoleErrors.length === 0, '本轮页面操作未出现浏览器控制台错误');
  assert(diagnostics.failedRequests.length === 0, '本轮页面操作未出现失败网络请求');
  const failedApis = diagnostics.apiResponses.filter(
    item => item.status >= 400 || (item.code !== undefined && item.code !== 200)
  );
  assert(failedApis.length === 0, '本轮页面 API 调用未返回 HTTP 或业务错误');

  diagnostics.result = 'PASS';
} catch (error) {
  diagnostics.result = 'FAIL';
  diagnostics.failure = String(error?.stack || error);
  try {
    diagnostics.failureScreenshot = await screenshot(page, 'failure');
  } catch {
    // Preserve the primary failure when the page is already closed.
  }
  process.exitCode = 1;
} finally {
  await writeFile(join(outputDir, 'summary.json'), `${JSON.stringify(diagnostics, null, 2)}\n`, 'utf8');
  await context.close();
}
