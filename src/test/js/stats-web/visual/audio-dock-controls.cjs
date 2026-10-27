'use strict';

const { expect } = require('@playwright/test');

async function selectAudioPanel(dock, name) {
  const back = dock.locator('#audio-dock-context-back');
  const title = dock.locator('#audio-dock-context-title');
  const labels = { Details: 'Call details', Transcript: 'Transcript', Queue: 'Queue',
    'Player menu': 'Player menu', 'Scan Lists': 'Lists', Lists: 'Lists',
    Settings: 'Listening settings', 'Listening settings': 'Listening settings',
    'Direct Listen': 'Direct Listen', 'Avoid List': 'Avoid List' };
  const targetTitle = labels[name];
  if (!targetTitle) throw new Error(`Unknown audio panel: ${name}`);
  if (await title.isVisible() && await title.textContent() === targetTitle) return;
  const menuItem = { 'Scan Lists': 'Lists', Lists: 'Lists', Settings: 'Listening settings',
    'Listening settings': 'Listening settings', 'Direct Listen': 'Direct Listen', 'Avoid List': 'Avoid List' }[name];
  if (menuItem || name === 'Player menu') {
    if (await back.isVisible() && await title.textContent() !== 'Player menu') await back.click();
    if (!await title.isVisible() || await title.textContent() !== 'Player menu') {
      const trigger = dock.getByRole('button', { name: 'Player menu', exact: true }).filter({ visible: true });
      if (await trigger.count()) await trigger.first().click();
      else await dock.page().getByRole('button', { name: 'Player menu', exact: true }).filter({ visible: true }).first().click();
    }
    await expect(title).toHaveText('Player menu');
    if (menuItem) await dock.getByRole('button', { name: menuItem, exact: true }).click();
    await expect(title).toHaveText(targetTitle);
    return;
  }
  for (let depth = 0; depth < 3 && await back.isVisible(); depth++) await back.click();
  const selector = { Details: '#audio-dock-call-details', Transcript: '#audio-dock-call-transcript' }[name];
  if (name === 'Queue') {
    const queue = dock.getByRole('button', { name: 'Open queue', exact: true });
    if (!await queue.first().isVisible() && !await queue.last().isVisible()) {
      await dock.locator('#audio-dock-size-handle').press('ArrowDown');
    }
    await dock.getByRole('button', { name: 'Open queue', exact: true }).filter({ visible: true }).click();
  } else await dock.locator(selector).click();
  await expect(title).toHaveText(targetTitle);
}

async function openAudioVolume(page) {
  const popup = page.locator('#audio-dock-volume-popover');
  const trigger = page.locator('#audio-dock [data-volume-trigger]:visible');
  if (!await popup.isVisible()) await trigger.click();
  await expect(trigger).toHaveAttribute('aria-expanded', 'true');
  const range = popup.getByRole('slider', { name: 'Volume', exact: true });
  await expect(range).toBeVisible();
  return range;
}

async function expectAudioSettings(dock) {
  const fields = [
    ['checkbox', 'Group calls by target', 'Keeps calls for the same group together to preserve conversationality'],
    ['spinbutton', 'Calls per target', 'When grouping is on, switch to another waiting target after this many consecutive calls.'],
    ['checkbox', 'Playing call in page title', 'Show the playing Live call in browser tab titles outside Scanner.'],
  ];
  const settings = dock.locator('.audio-dock-settings');
  await expect(settings.locator(':scope > .ui-toggle-field')).toHaveCount(3);
  for (const [role, name, description] of fields) {
    const control = settings.getByRole(role, { name, exact: true });
    await expect(control).toHaveAccessibleDescription(description);
    const helpId = await control.getAttribute('aria-describedby');
    await expect(settings.locator(`[id="${helpId}"]`)).toBeVisible();
  }
  const geometry = await settings.locator(':scope > .ui-toggle-field').evaluateAll(rows => rows.map(row => {
    const input = row.querySelector('input');
    const target = input.type === 'checkbox' ? input.closest('.ui-toggle') : input;
    const box = row.getBoundingClientRect(), action = target.getBoundingClientRect();
    const copy = row.querySelector('.ui-toggle-copy').getBoundingClientRect();
    return { left: box.left, right: box.right, top: box.top, bottom: box.bottom,
      actionLeft: action.left, actionRight: action.right, copyRight: copy.right };
  }));
  for (let index = 0; index < geometry.length; index++) {
    const row = geometry[index];
    expect(Math.abs(row.left - geometry[0].left)).toBeLessThanOrEqual(1);
    expect(Math.abs(row.right - geometry[0].right)).toBeLessThanOrEqual(1);
    expect(Math.abs(row.actionRight - geometry[0].actionRight)).toBeLessThanOrEqual(1);
    expect(row.actionRight).toBeLessThanOrEqual(row.right);
    expect(row.actionLeft - row.copyRight).toBeGreaterThanOrEqual(8);
    if (index) expect(row.top - geometry[index - 1].bottom).toBeGreaterThanOrEqual(8);
  }
}

module.exports = { selectAudioPanel, openAudioVolume, expectAudioSettings };
