'use strict';

const { expect } = require('@playwright/test');

async function selectAudioPanel(dock, name) {
  const back = dock.locator('#audio-dock-context-back');
  if (await back.isVisible()) await back.click();
  const selector = { Details: '#audio-dock-call-details', Transcript: '#audio-dock-call-transcript',
    'Scan Lists': '.audio-dock-lists-trigger:visible', Settings: '#audio-dock-settings' }[name];
  if (name === 'Queue') {
    const queue = dock.getByRole('button', { name: 'Open queue', exact: true });
    if (!await queue.first().isVisible() && !await queue.last().isVisible()) {
      await dock.locator('#audio-dock-size-handle').press('ArrowDown');
    }
    await dock.getByRole('button', { name: 'Open queue', exact: true }).filter({ visible: true }).click();
  } else await dock.locator(selector).click();
  await expect(dock.locator('#audio-dock-context-title')).toHaveText({ Details: 'Call details',
    Transcript: 'Transcript', Queue: 'Queue', 'Scan Lists': 'Lists', Settings: 'Listening settings' }[name]);
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

module.exports = { selectAudioPanel, openAudioVolume };
