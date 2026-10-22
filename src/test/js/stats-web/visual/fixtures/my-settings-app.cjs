'use strict';

const { expect } = require('@playwright/test');

function session(username = 'listener') {
  return { configured: true, authenticated: username !== null, username,
    tier: username === null ? 'public' : 'user', primary: false,
    csrf_token: username === null ? null : 'fixture-only-token', capabilities: {
      'web-access': true, dashboard: true, credits: true, 'user-settings': username !== null,
      'receiver-health': username !== null, radio: true, live: true,
      'call-audio': false, recordings: false, 'admin-settings': false } };
}

async function openSettingsApp(page, options = {}) {
  // App navigation keeps canonical root URLs; reloading one must still serve the app, not the gallery.
  await page.route((url) => url.pathname === '/', route => route.fulfill({
    path: require('node:path').resolve(__dirname, '../../../../../../stats-web/index.html')
  }));
  const defaults = (await import(require('node:url').pathToFileURL(require('node:path').resolve(__dirname,
    '../../../../../../stats-web/assets/core/preference-schema.js')).href)).defaults;
  const listener = structuredClone(defaults);
  listener.appearance = { theme: options.theme || 'light', hue: options.hue ?? null };
  if (options.preferences) Object.assign(listener, options.preferences);
  const second = structuredClone(defaults);
  second.appearance = { theme: 'dark', hue: 285 };
  const state = { session: session(), profiles: { listener, second }, revisions: { listener: 1, second: 1 },
    writes: [], pending: [], holdSaves: options.holdSaves === true, preferenceLoadFailures: 0 };
  await page.route('**/api/v1/**', async (route) => {
    const request = route.request();
    const pathname = new URL(request.url()).pathname;
    const data = (value) => route.fulfill({ headers: { Date: new Date().toUTCString() }, json: { data: value } });
    if (pathname === '/api/v1/auth/session') return data(state.session);
    if (pathname === '/api/v1/auth/logout') {
      state.session = session(null);
      return data(state.session);
    }
    if (pathname === '/api/v1/auth/login') {
      state.session = session(request.postDataJSON().username);
      return data(state.session);
    }
    if (pathname === '/api/v1/me/preferences') {
      const username = state.session.username;
      if (request.method() === 'GET' && state.preferenceLoadFailures > 0) {
        state.preferenceLoadFailures--;
        return route.fulfill({ status: 503, json: { error: { message: 'Load unavailable' } } });
      }
      if (request.method() === 'PUT') {
        const preferences = request.postDataJSON();
        state.writes.push({ username, preferences, revision: request.headers()['if-match'] });
        const status = state.holdSaves ? await new Promise((resolve) => state.pending.push(resolve)) : 200;
        if (status !== 200) return route.fulfill({ status, json: { error: { message: 'Save unavailable' } } });
        state.profiles[username] = preferences;
        state.revisions[username]++;
      }
      return route.fulfill({ json: { revision: state.revisions[username], preferences: state.profiles[username] } });
    }
    if (pathname === '/api/v1/receiver-health') return data({ generated_at_ms: Date.now(),
      summary: { active_count: 0, warning_count: 0, critical_count: 0 }, active: [], resolved: [], measurements: [] });
    if (pathname === '/api/v1/status') return data({});
    if (pathname === '/api/v1/scan-lists') return data({ revision: 1, scan_lists: [] });
    if (pathname === '/api/v1/channel-catalog') return data({ revision: 1, channels: [] });
    if (pathname === '/api/v1/dashboard') return data({ call_activity: { totals: {}, buckets: [] },
      top_destinations: [], top_sources: [], source_activity_24h: [] });
    return data({ rows: [], limit: 25, offset: 0, has_more: false, next_offset: null, total_count: 0 });
  });
  await page.goto('/app.html?view=settings');
  await expect(page.getByRole('heading', { name: 'My Settings', exact: true })).toBeVisible();
  await expect(page.locator('#receiver-health-indicator')).toHaveClass(/receiver-health-healthy/);
  return state;
}


async function selectSettingsCategory(page, category, name) {
  await expect(page.locator('.ui-settings-workspace-focused')).toBeVisible();
  const picker = page.getByRole('combobox', { name: 'Section', exact: true });
  if (await picker.isVisible()) await picker.selectOption(category);
  else await page.getByRole('button', { name, exact: true }).click();
}

module.exports = { openSettingsApp, session, selectSettingsCategory };
