const { expect, test } = require('@playwright/test');
const { install, mode, departure, navigate, stateWrites } = require('./fixtures/tuner-workspace.cjs');
const { expectContrast } = require('./fixtures/contrast-audit.cjs');

const center = (page) => page.getByRole('group', { name: 'Center frequency', exact: true });
const lockWrites = (state) => state.requests.filter(({ path, method }) =>
  path.endsWith('/settings/center_frequency_locked') && method === 'PUT');
const frequencyWrites = (state) => state.requests.filter(({ path, method }) =>
  path.endsWith('/settings/frequency_mhz') && method === 'PUT');

async function enterSetup(page, view) {
  await mode(page).getByRole('button', { name: 'Setup', exact: true }).click();
  const prompt = page.getByRole('alertdialog', { name: view === 'tuners' ? 'Enter Setup' : 'Stop channels to tune?', exact: true });
  await prompt.getByRole('button', { name: view === 'tuners' ? 'Enter Setup' : 'Stop channels and tune', exact: true }).click();
  await expect(mode(page).getByRole('button', { name: 'Setup', exact: true })).toHaveAttribute('aria-pressed', 'true');
  await expect(center(page)).toHaveAttribute('aria-disabled', 'false');
}

test('saved Lock center stays checked while both pages tune through the same Setup session', async ({ page }) => {
  const state = await install(page, { operatorState: 'live', savedCenterLocked: true, channelCount: 1 });
  await page.goto('/app.html?view=tuners');
  await expect(center(page)).toHaveAttribute('aria-disabled', 'true');
  await enterSetup(page, 'tuners');
  await expect(page.getByRole('checkbox', { name: /Lock center/ })).toBeChecked();
  await center(page).getByRole('button', { name: 'Increase 0.001 MHz place', exact: true }).click();
  await expect.poll(() => state.receiver.frequency_hz).toBe(851013500);

  await navigate(page, 'tuner-spectrum');
  await expect(page.getByRole('heading', { name: 'Browse Spectrum', exact: true })).toBeVisible();
  await expect(mode(page).getByRole('button', { name: 'Setup', exact: true })).toHaveAttribute('aria-pressed', 'true');
  await expect(center(page)).toHaveAttribute('aria-disabled', 'false');
  await center(page).getByRole('button', { name: 'Increase 0.001 MHz place', exact: true }).click();
  await expect.poll(() => state.receiver.frequency_hz).toBe(851014500);
  expect(frequencyWrites(state).at(-1).body.lease_id).toBe(state.lease);

  await navigate(page, 'tuners');
  await expect(center(page)).toHaveAttribute('aria-disabled', 'false');
  await expect(page.getByRole('checkbox', { name: /Lock center/ })).toBeChecked();
  expect(stateWrites(state).map(({ body }) => body.state)).toEqual(['setup']);
  expect(lockWrites(state)).toEqual([]);
  expect(state.savedCenterLocked).toBe(true);
});

test('Leave stopped keeps Setup tuning available and going Live uses the chosen center with the saved lock', async ({ page }) => {
  const state = await install(page, { operatorState: 'live', savedCenterLocked: true, channelCount: 1 });
  await page.goto('/app.html?view=tuner-spectrum');
  await enterSetup(page, 'tuner-spectrum');
  await center(page).getByRole('button', { name: 'Increase 0.1 MHz place', exact: true }).click();
  await expect.poll(() => state.receiver.frequency_hz).toBe(851112500);
  await navigate(page, 'dashboard');
  await departure(page).getByRole('button', { name: 'Leave stopped', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Main', exact: true })).toBeVisible();
  expect(state.receiver.operator_state).toBe('setup');
  expect(state.savedCenterLocked).toBe(true);

  await navigate(page, 'tuners');
  await expect(center(page)).toHaveAttribute('aria-disabled', 'false');
  await expect(page.getByRole('checkbox', { name: /Lock center/ })).toBeChecked();
  await mode(page).getByRole('button', { name: 'Live', exact: true }).click();
  await expect(mode(page).getByRole('button', { name: 'Live', exact: true })).toHaveAttribute('aria-pressed', 'true');
  await expect(center(page)).toHaveAttribute('aria-disabled', 'true');
  await expect(page.getByRole('checkbox', { name: /Lock center/ })).toBeChecked();
  expect(state.receiver.frequency_hz).toBe(851112500);
  expect(stateWrites(state).map(({ body }) => body.state)).toEqual(['setup', 'live']);
  expect(lockWrites(state)).toEqual([]);
});

test('changing the saved Live lock during Setup is one explicit preference edit', async ({ page }) => {
  const state = await install(page, { operatorState: 'setup', savedCenterLocked: true });
  await page.goto('/app.html?view=tuners');
  const lock = page.getByRole('checkbox', { name: /Lock center/ });
  await expect(lock).toBeChecked();
  await expect(lock).toBeEnabled();
  await lock.focus();
  await lock.press('Space');
  await expect.poll(() => lockWrites(state).length).toBe(1);
  await expect(lock).not.toBeChecked();
  await navigate(page, 'tuner-spectrum');
  await expect(center(page)).toHaveAttribute('aria-disabled', 'false');
  await center(page).getByRole('button', { name: 'Increase 0.001 MHz place', exact: true }).click();
  await expect.poll(() => state.receiver.frequency_hz).toBe(851013500);
  await mode(page).getByRole('button', { name: 'Live', exact: true }).click();
  await expect(mode(page).getByRole('button', { name: 'Live', exact: true })).toHaveAttribute('aria-pressed', 'true');
  await navigate(page, 'tuners');
  await expect(page.getByRole('checkbox', { name: /Lock center/ })).not.toBeChecked();
  expect(lockWrites(state).map(({ body }) => body.value)).toEqual([false]);
});

for (const view of ['tuners', 'tuner-spectrum']) {
  test(`leaving ${view} waits for the last Setup retune before restoring Live`, async ({ page }) => {
    const state = await install(page, { operatorState: 'setup', savedCenterLocked: true });
    state.pauseFrequency = true;
    await page.goto(`/app.html?view=${view}`);
    await expect(center(page)).toHaveAttribute('aria-disabled', 'false');
    await center(page).getByRole('button', { name: 'Increase 0.001 MHz place', exact: true }).click();
    await expect.poll(() => typeof state.releaseFrequency).toBe('function');
    await navigate(page, 'dashboard');
    await expect(departure(page)).toHaveCount(0);
    expect(stateWrites(state)).toEqual([]);
    state.releaseFrequency();
    await departure(page).getByRole('button', { name: 'Go Live & leave', exact: true }).click();
    await expect(page.getByRole('heading', { name: 'Main', exact: true })).toBeVisible();
    expect(state.liveBeforeFrequencyCompleted).toBe(false);
    expect(state.receiver.frequency_hz).toBe(851013500);
    expect(stateWrites(state).map(({ body }) => body.state)).toEqual(['live']);
    expect(lockWrites(state)).toEqual([]);
  });
}

for (const theme of ['light', 'dark']) {
  for (const [size, width] of [['desktop', 1280], ['phone', 390]]) {
    test(`Setup saved-lock readouts remain editable and readable in ${theme} on ${size}`, async ({ page }) => {
      const state = await install(page, { operatorState: 'setup', savedCenterLocked: true, theme });
      await page.setViewportSize({ width, height: 900 });
      await page.goto('/app.html?view=tuners');
      const readouts = page.locator('.tuners-readouts');
      const lock = readouts.getByRole('checkbox', { name: 'Lock center in Live', exact: true });
      await expect(lock).toBeChecked();
      await expect(lock).toBeEnabled();
      await expect(readouts).toContainText('Temporarily unlocked in Setup.');
      await expect(center(page)).toHaveAttribute('aria-disabled', 'false');
      await readouts.scrollIntoViewIfNeeded();
      await center(page).focus();
      await expect(center(page)).toBeFocused();
      await expect(lock.locator('..')).toBeInViewport();
      expect(await readouts.evaluate((element) => element.scrollWidth <= element.clientWidth)).toBe(true);
      await expectContrast(page, { rootSelector: '.tuners-readouts', reportName: `setup-saved-lock-${theme}-${size}` });
      await expect(readouts).toHaveScreenshot(`setup-saved-lock-${theme}-${size}.png`);
      expect(lockWrites(state)).toEqual([]);
    });
  }
}
