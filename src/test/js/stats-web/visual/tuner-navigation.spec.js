const { expect, test } = require('@playwright/test');
const { install, mode, departure, navigate, stateWrites } = require('./fixtures/tuner-workspace.cjs');

test('leaving Setup uses the agreed wording and Stay here preserves the tuner and route', async ({ page }) => {
  const state = await install(page);
  await page.goto('/app.html?view=tuners');
  await expect(mode(page).getByRole('button', { name: 'Setup', exact: true })).toHaveAttribute('aria-pressed', 'true');
  await navigate(page, 'dashboard');
  const prompt = departure(page);
  await expect(prompt).toContainText('North antenna is in Setup. Some channels have been stopped. Go Live again?');
  await expect(prompt.locator('.ui-modal-footer').getByRole('button')).toHaveText(['Stay here', 'Leave stopped', 'Go Live & leave']);
  await expect(prompt.getByRole('button', { name: 'Stay here', exact: true })).toBeFocused();
  await prompt.getByRole('button', { name: 'Stay here', exact: true }).click();
  await expect(prompt).toHaveCount(0);
  await expect(page.getByRole('heading', { name: 'Tuners', exact: true })).toBeVisible();
  expect(new URL(page.url()).searchParams.get('view')).toBe('tuners');
  expect(state.receiver.operator_state).toBe('setup');
  expect(stateWrites(state)).toEqual([]);
});

test('Leave stopped visits another screen without restarting and prompts again after returning', async ({ page }) => {
  const state = await install(page);
  await page.goto('/app.html?view=tuners');
  await expect(mode(page)).toBeVisible();
  await navigate(page, 'dashboard');
  await departure(page).getByRole('button', { name: 'Leave stopped', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Main', exact: true })).toBeVisible();
  expect(state.receiver.operator_state).toBe('setup');
  expect(state.receiver.stopped_channels).toHaveLength(1);
  expect(stateWrites(state)).toEqual([]);
  await navigate(page, 'tuners');
  await expect(mode(page).getByRole('button', { name: 'Setup', exact: true })).toHaveAttribute('aria-pressed', 'true');
  await navigate(page, 'dashboard');
  await expect(departure(page)).toBeVisible();
  await departure(page).getByRole('button', { name: 'Stay here', exact: true }).click();
});

test('Go Live and leave resumes once before visiting another screen', async ({ page }) => {
  const state = await install(page);
  await page.goto('/app.html?view=tuners');
  await expect(mode(page)).toBeVisible();
  await navigate(page, 'dashboard');
  await departure(page).getByRole('button', { name: 'Go Live & leave', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Main', exact: true })).toBeVisible();
  expect(stateWrites(state)).toHaveLength(1);
  expect(stateWrites(state)[0].body).toMatchObject({ state: 'live' });
  expect(state.receiver.operator_state).toBe('live');
  expect(state.receiver.channel_count).toBe(1);
});

test('Tuners and Browse Spectrum retain Setup as one workspace until leaving both', async ({ page }) => {
  const state = await install(page);
  await page.goto('/app.html?view=tuners');
  await expect(mode(page)).toBeVisible();
  await navigate(page, 'tuner-spectrum');
  await expect(page.getByRole('heading', { name: 'Browse Spectrum', exact: true })).toBeVisible();
  await expect(mode(page).getByRole('button', { name: 'Setup', exact: true })).toHaveAttribute('aria-pressed', 'true');
  await expect(departure(page)).toHaveCount(0);
  await navigate(page, 'tuners');
  await expect(page.getByRole('heading', { name: 'Tuners', exact: true })).toBeVisible();
  await expect(mode(page).getByRole('button', { name: 'Setup', exact: true })).toHaveAttribute('aria-pressed', 'true');
  expect(stateWrites(state)).toEqual([]);
  expect(state.receiver.operator_state).toBe('setup');
  await navigate(page, 'dashboard');
  await expect(departure(page)).toBeVisible();
  await departure(page).getByRole('button', { name: 'Leave stopped', exact: true }).click();
});

test('browser Back asks before leaving and cancellation restores the current tuner page', async ({ page }) => {
  const state = await install(page);
  await page.goto('/app.html?view=dashboard');
  await navigate(page, 'tuners');
  await expect(mode(page)).toBeVisible();
  await page.goBack();
  await expect(departure(page)).toBeVisible();
  await departure(page).getByRole('button', { name: 'Stay here', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Tuners', exact: true })).toBeVisible();
  expect(new URL(page.url()).searchParams.get('view')).toBe('tuners');
  expect(stateWrites(state)).toEqual([]);
  await page.goBack();
  await departure(page).getByRole('button', { name: 'Leave stopped', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Main', exact: true })).toBeVisible();
});

test('a newer Disabled decision is not undone by a stale tuner page', async ({ page }) => {
  const state = await install(page);
  await page.goto('/app.html?view=tuners');
  await expect(mode(page)).toBeVisible();
  state.receiver.operator_state = 'disabled';
  state.receiver.operator_generation += 1;
  state.receiver.available = false;
  await navigate(page, 'dashboard');
  await expect(page.getByRole('heading', { name: 'Main', exact: true })).toBeVisible();
  await expect(departure(page)).toHaveCount(0);
  expect(state.receiver.operator_state).toBe('disabled');
  expect(stateWrites(state)).toEqual([]);
});

test('a Disable made in another tab while the departure prompt is open wins over Go Live', async ({ page }) => {
  const state = await install(page);
  await page.goto('/app.html?view=tuners');
  await expect(mode(page)).toBeVisible();
  await navigate(page, 'dashboard');
  await expect(departure(page)).toBeVisible();
  state.receiver.operator_state = 'disabled';
  state.receiver.operator_generation += 1;
  state.receiver.available = false;
  await departure(page).getByRole('button', { name: 'Go Live & leave', exact: true }).click();
  await expect.poll(() => stateWrites(state).length > 0 ||
    new URL(page.url()).searchParams.get('view') === 'dashboard').toBe(true);
  expect(state.receiver.operator_state).toBe('disabled');
  expect(stateWrites(state).length).toBeLessThanOrEqual(1);
});

test('ordinary channel-screen navigation does not prompt for an unrelated tuner in Setup', async ({ page }) => {
  const state = await install(page);
  await page.goto('/app.html?view=channel-setup');
  await expect(page.getByRole('heading', { name: 'Channel Setup', exact: true })).toBeVisible();
  await navigate(page, 'dashboard');
  await expect(page.getByRole('heading', { name: 'Main', exact: true })).toBeVisible();
  await expect(departure(page)).toHaveCount(0);
  expect(stateWrites(state)).toEqual([]);
});

for (const [theme, width] of [['light', 1280], ['dark', 390]]) {
  test(`Setup departure choices fit and Escape keeps reception stopped in ${theme} at ${width}px`, async ({ page }, testInfo) => {
    const state = await install(page, { theme });
    await page.setViewportSize({ width, height: 900 });
    await page.goto('/app.html?view=tuners');
    await expect(mode(page)).toBeVisible();
    await navigate(page, 'dashboard');
    const prompt = departure(page);
    await expect(prompt).toBeVisible();
    for (const name of ['Stay here', 'Leave stopped', 'Go Live & leave'])
      await expect(prompt.getByRole('button', { name, exact: true })).toBeInViewport();
    expect(await prompt.evaluate((element) => element.scrollWidth <= element.clientWidth)).toBe(true);
    await prompt.screenshot({ path: testInfo.outputPath(`tuner-departure-${theme}-${width}.png`), animations: 'disabled' });
    await page.keyboard.press('Escape');
    await expect(prompt).toHaveCount(0);
    await expect(page.getByRole('heading', { name: 'Tuners', exact: true })).toBeVisible();
    expect(state.receiver.operator_state).toBe('setup');
    expect(stateWrites(state)).toEqual([]);
  });
}

for (const theme of ['light', 'dark']) {
  for (const [size, width] of [['desktop', 1280], ['phone', 390]]) {
    test(`shared tuner Setup confirmation gallery in ${theme} on ${size}`, async ({ page }) => {
      await page.setViewportSize({ width, height: 900 });
      await page.goto(`/design-system.html?theme=${theme}&view=modal&confirmation=tuner-setup`);
      const prompt = departure(page);
      await expect(prompt).toBeVisible();
      await expect(prompt.locator('.ui-modal-footer').getByRole('button'))
        .toHaveText(['Stay here', 'Leave stopped', 'Go Live & leave']);
      await expect(prompt.getByRole('button', { name: 'Stay here', exact: true })).toBeFocused();
      await expect(page).toHaveScreenshot(`tuner-setup-confirmation-${theme}-${size}.png`);
    });
  }
}
