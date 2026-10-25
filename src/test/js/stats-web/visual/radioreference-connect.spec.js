const { expect, test } = require('@playwright/test');
const { readFileSync } = require('node:fs');
const { resolve } = require('node:path');

const application = readFileSync(resolve(__dirname, '../../../../..', 'stats-web/assets/app.js'), 'utf8');
const formField = application.slice(application.indexOf('function formField('),
  application.indexOf('function authenticationFailureMessage('));
const toggles = application.slice(application.indexOf('function uiToggle('),
  application.indexOf('function uiPill('));
const connected = { account: { state: 'VALID_PREMIUM', user_name: 'fixture-user' },
  country_id: 1, state_id: 2 };

async function installHarness(page, theme = 'light') {
  await page.goto(`/design-system.html?theme=${theme}&view=gallery`);
  await page.evaluate(async (source) => {
    const { createGalleryModalFoundation } = await import('/visual/modal-foundation-gallery.js');
    const { openRadioReferenceConnect } = await import('/assets/features/radioreference-connect.js');
    const shared = await createGalleryModalFoundation();
    document.body.replaceChildren();
    const node = (tag, className = '', text = null) => {
      const element = document.createElement(tag);
      element.className = className;
      if (text !== null) element.textContent = String(text);
      return element;
    };
    const controls = new Function('node', `${source.formField}\n${source.toggles}
      return { formField, uiToggleField };`)(node);
    const state = { writes: [], pending: [], continuations: 0, result: undefined };
    const signal = new AbortController();
    window.connectionState = state;
    window.abortConnection = () => signal.abort();
    window.finishConnection = (configuration, failure = false) => {
      const pending = state.pending.shift();
      (failure ? pending.reject : pending.resolve)(configuration);
    };
    const requestJson = (path, options) => {
      state.writes.push({ path, method: options.method, remember: options.body.remember,
        page: options.page, aborted: options.signal.aborted });
      return new Promise((resolve, reject) => state.pending.push({ resolve, reject }));
    };
    const trigger = node('button', 'ui-button', 'Configure search');
    trigger.type = 'button';
    trigger.addEventListener('click', () => {
      const parentForm = node('form', 'admin-form');
      const draft = node('input', 'ui-input');
      draft.value = 'Selected receiver';
      draft.setAttribute('aria-label', 'Receiver draft');
      const connect = node('button', 'ui-button ui-button-secondary', 'Connect account');
      connect.type = 'button';
      parentForm.append(draft, connect);
      const parent = shared.openReadOnlyModal('Find Trunked Systems', parentForm,
        { id: 'parent-search', className: 'test-parent' });
      draft.addEventListener('input', () => parent.setDirty(true));
      connect.addEventListener('click', async () => {
        const result = await openRadioReferenceConnect({ node, ...controls, ...shared, requestJson }, {
          introduction: 'Connect your account to look up directory names before searching.', signal: signal.signal
        });
        state.continuations += 1;
        state.result = result;
        if (result) parentForm.append(node('p', 'connection-result', 'Connected; directory names are available.'));
      });
    });
    document.body.append(trigger);
  }, { formField, toggles });
  await page.getByRole('button', { name: 'Configure search', exact: true }).click();
  const parent = page.locator('.test-parent');
  await parent.getByRole('textbox', { name: 'Receiver draft', exact: true }).fill('Unfinished receiver selection');
  await parent.getByRole('button', { name: 'Connect account', exact: true }).click();
  return page.getByRole('dialog', { name: 'Connect RadioReference', exact: true });
}

test('nested connection blocks busy dismissal and duplicate submits, retains failure drafts, and resumes once after success', async ({ page }) => {
  const dialog = await installHarness(page);
  const signup = dialog.getByRole('link', { name: 'Create a premium account', exact: true });
  await expect(signup).toHaveAttribute('href', 'https://www.radioreference.com/premium/');
  await expect(signup).toHaveAttribute('target', '_blank');
  await expect(signup).toHaveAttribute('rel', 'noopener noreferrer');
  await expect(signup.locator('..')).toHaveText('Need an account? Create a premium account');
  await expect(dialog.getByRole('textbox', { name: 'Username', exact: true })).toBeFocused();
  await expect(page.locator('.test-parent').locator('..')).toHaveJSProperty('inert', true);
  await dialog.getByRole('textbox', { name: 'Username', exact: true }).fill('fixture-user');
  await dialog.getByLabel('Password', { exact: true }).fill('synthetic-password');
  const submit = dialog.getByRole('button', { name: 'Connect account', exact: true });
  await submit.click();
  await expect(dialog).toHaveAttribute('aria-busy', 'true');
  await expect(dialog.getByRole('button', { name: 'Cancel', exact: true })).toBeDisabled();
  await page.keyboard.press('Escape');
  await expect(dialog).toBeVisible();
  await dialog.locator('form').evaluate(form => form.dispatchEvent(new Event('submit', { cancelable: true })));
  expect(await page.evaluate(() => window.connectionState.writes.length)).toBe(1);
  await page.evaluate(() => window.finishConnection({ account: { state: 'INVALID_CREDENTIALS' } }));
  await expect(dialog.getByRole('alert')).toHaveText(
    'RadioReference rejected the username or password. Check both and try again.');
  await expect(dialog.getByRole('textbox', { name: 'Username', exact: true })).toHaveValue('fixture-user');
  await expect(dialog.getByLabel('Password', { exact: true })).toHaveValue('synthetic-password');
  await expect(submit).toBeEnabled();
  await submit.click();
  await page.evaluate(() => window.finishConnection({ account: { state: 'EXPIRED_PREMIUM' } }));
  await expect(dialog.getByRole('alert')).toContainText('Renew or upgrade your account, then try again.');
  await submit.click();
  await page.evaluate(configuration => window.finishConnection(configuration), connected);
  await expect(dialog).toHaveCount(0);
  const parent = page.locator('.test-parent');
  await expect(parent).toBeVisible();
  await expect(parent.getByRole('textbox', { name: 'Receiver draft', exact: true }))
    .toHaveValue('Unfinished receiver selection');
  await expect(parent.locator('.connection-result')).toBeVisible();
  await expect(parent.getByRole('button', { name: 'Connect account', exact: true })).toBeFocused();
  expect(await page.evaluate(() => ({ count: window.connectionState.continuations,
    result: window.connectionState.result, writes: window.connectionState.writes }))).toEqual({
    count: 1, result: connected, writes: Array.from({ length: 3 }, () => ({
      path: '/api/v1/admin/radioreference/session', method: 'PUT', remember: false, page: false, aborted: false
    }))
  });
});

test('network failures preserve the account draft; Cancel discards credentials through the shared confirmation', async ({ page }) => {
  const dialog = await installHarness(page);
  await dialog.getByRole('textbox', { name: 'Username', exact: true }).fill('fixture-user');
  await dialog.getByLabel('Password', { exact: true }).fill('synthetic-password');
  await dialog.getByRole('button', { name: 'Connect account', exact: true }).click();
  await page.evaluate(() => window.finishConnection({ status: 503, message: 'Private service detail' }, true));
  await expect(dialog.getByRole('alert')).toHaveText(
    'RadioReference could not be connected. Check your account details and try again.');
  await dialog.getByRole('button', { name: 'Cancel', exact: true }).click();
  const confirmation = page.getByRole('alertdialog', { name: 'Discard unsaved changes', exact: true });
  await expect(confirmation.getByRole('button', { name: 'Keep editing', exact: true })).toBeFocused();
  await confirmation.getByRole('button', { name: 'Discard changes', exact: true }).click();
  await expect(dialog).toHaveCount(0);
  await expect(page.locator('.test-parent').getByRole('button', { name: 'Connect account', exact: true })).toBeFocused();
  expect(await page.evaluate(() => ({ count: window.connectionState.continuations,
    result: window.connectionState.result }))).toEqual({ count: 1, result: null });
});

test('abort during connection clears the child and ignores a late successful result', async ({ page }) => {
  const dialog = await installHarness(page);
  await dialog.getByRole('textbox', { name: 'Username', exact: true }).fill('fixture-user');
  await dialog.getByLabel('Password', { exact: true }).fill('synthetic-password');
  await dialog.getByRole('button', { name: 'Connect account', exact: true }).click();
  await page.evaluate(() => window.abortConnection());
  await expect(dialog).toHaveCount(0);
  await page.evaluate(configuration => window.finishConnection(configuration), connected);
  expect(await page.evaluate(() => ({ count: window.connectionState.continuations,
    result: window.connectionState.result }))).toEqual({ count: 1, result: null });
  await expect(page.locator('.connection-result')).toHaveCount(0);
});

for (const theme of ['light']) for (const [width, height] of [[1280, 720], [320, 568]]) {
  test(`connection form remains reachable ${theme} ${width}x${height}`, async ({ page }) => {
    await page.setViewportSize({ width, height });
    const dialog = await installHarness(page, theme);
    const remember = dialog.getByRole('checkbox', { name: 'Remember credentials on this receiver', exact: true });
    await expect(remember).not.toBeChecked();
    await expect(dialog).toBeInViewport();
    expect(await dialog.evaluate(element => element.scrollWidth <= element.clientWidth + 1)).toBe(true);
    for (const name of ['Cancel', 'Connect account']) {
      const button = dialog.getByRole('button', { name, exact: true });
      await expect(button).toBeInViewport();
      expect(await button.evaluate(element => {
        const r = element.getBoundingClientRect();
        const hit = document.elementFromPoint(r.x + r.width / 2, r.y + r.height / 2);
        return element === hit || element.contains(hit);
      })).toBe(true);
    }
    await test.info().attach(`connection-${theme}-${width}`, {
      body: await page.screenshot({ path: test.info().outputPath(`connection-${theme}-${width}.png`) }),
      contentType: 'image/png'
    });
    await page.keyboard.press('Escape');
    await expect(dialog).toHaveCount(0);
    await expect(page.locator('.test-parent').getByRole('button', { name: 'Connect account', exact: true })).toBeFocused();
  });
}

for (const theme of ['light']) for (const [width, height] of [[1280, 900], [390, 844]]) {
  test(`connection gallery ${theme} ${width}x${height}`, async ({ page }) => {
    await page.setViewportSize({ width, height });
    await page.goto(`/design-system.html?view=radioreference-connect&theme=${theme}`);
    const dialog = page.getByRole('dialog', { name: 'Connect RadioReference', exact: true });
    await expect(dialog.getByRole('textbox', { name: 'Username', exact: true })).toBeFocused();
    await expect(dialog).toHaveScreenshot(`connection-${theme}-${width}.png`);
  });
}

for (const state of ['error', 'busy']) {
  test(`connection gallery ${state}`, async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 844 });
    await page.goto(`/design-system.html?view=radioreference-connect&theme=dark&connection-state=${state}`);
    const dialog = page.getByRole('dialog', { name: 'Connect RadioReference', exact: true });
    await dialog.getByRole('textbox', { name: 'Username', exact: true }).fill('fixture-user');
    await dialog.getByLabel('Password', { exact: true }).fill('synthetic-password');
    await dialog.getByRole('button', { name: 'Connect account', exact: true }).click();
    if (state === 'error') await expect(dialog.getByRole('alert')).toContainText('Check both and try again.');
    else await expect(dialog).toHaveAttribute('aria-busy', 'true');
    await expect(dialog).toHaveScreenshot(`connection-${state}-dark-390.png`);
  });
}
