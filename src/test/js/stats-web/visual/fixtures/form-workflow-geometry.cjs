'use strict';

const { expect } = require('@playwright/test');
const fs = require('node:fs');
const path = require('node:path');

async function readFormWorkflowGeometry(form) {
  return form.evaluate(element => {
    const body = element.querySelector(':scope > .ui-form-workflow-body') ||
      element.querySelector('.ui-form-workflow-surface') || element;
    const footer = element.querySelector(':scope > .ui-modal-footer, :scope > .settings-form-footer');
    const dialog = element.closest('[role="dialog"]');
    const box = node => {
      if (!node || !node.getClientRects().length) return null;
      const rect = node.getBoundingClientRect();
      return { left: rect.left, right: rect.right, top: rect.top, bottom: rect.bottom, width: rect.width };
    };
    const styles = getComputedStyle(body);
    const bodyBox = box(body);
    const fields = [...body.querySelectorAll('input:not([type="hidden"]), select, textarea, .ui-field-label, .admin-toggle-control, .ui-notice, .ui-feedback')]
      .filter(field => !field.closest('.ui-modal-footer, .settings-form-footer'))
      .map(field => ({ name: field.getAttribute('aria-label') || field.name || field.tagName,
        feedback: field.matches('.ui-notice, .ui-feedback'),
        ...box(field) })).filter(field => Number.isFinite(field.left));
    const controls = fields.filter(field => !field.feedback);
    return {
      wrapped: body !== element, dirty: element.classList.contains('is-dirty'),
      form: box(element), body: bodyBox, footer: box(footer), dialog: box(dialog),
      footerInsideBody: Boolean(footer && body !== element && body.contains(footer)),
      padding: [styles.paddingTop, styles.paddingRight, styles.paddingBottom, styles.paddingLeft].map(parseFloat),
      contentBottom: Math.max(bodyBox.top, ...[...body.children]
        .filter(child => child !== footer).map(child => box(child)?.bottom).filter(Number.isFinite)),
      fields,
      fieldInsets: controls.length ? {
        left: Math.min(...controls.map(field => field.left)) - bodyBox.left,
        right: bodyBox.right - Math.max(...controls.map(field => field.right)),
        top: Math.min(...controls.map(field => field.top)) - bodyBox.top
      } : null
    };
  });
}

async function expectFormWorkflowGeometry(form, { modal = true, minInset = 8 } = {}) {
  await expect(form).toBeVisible();
  const geometry = await readFormWorkflowGeometry(form);
  expect(geometry.fields.length, 'The workflow has visible fields to inspect').toBeGreaterThan(0);
  if (modal) {
    expect(geometry.wrapped, 'Dialog fields share one padded body').toBe(true);
    expect(geometry.footerInsideBody, 'Actions remain outside the highlighted body').toBe(false);
    expect(geometry.dialog).not.toBeNull();
  }
  if (modal || geometry.dirty) {
    expect(geometry.fieldInsets.left, 'Fields have room inside the dirty boundary').toBeGreaterThanOrEqual(minInset - 1);
    expect(geometry.fieldInsets.right, 'Fields have room inside the dirty boundary').toBeGreaterThanOrEqual(minInset - 1);
    expect(geometry.fieldInsets.top, 'The first field has room above it').toBeGreaterThanOrEqual(minInset - 1);
  }
  for (const field of geometry.fields) {
    expect(field.left, `${field.name} stays inside the form`).toBeGreaterThanOrEqual(geometry.body.left - 1);
    expect(field.right, `${field.name} stays inside the form`).toBeLessThanOrEqual(geometry.body.right + 1);
  }
  if (geometry.dialog && geometry.footer) {
    expect(geometry.footer.left).toBeGreaterThanOrEqual(geometry.dialog.left - 1);
    expect(geometry.footer.right).toBeLessThanOrEqual(geometry.dialog.right + 1);
    const previousScroll = await form.evaluate(element => {
      const content = element.closest('.modal-content');
      if (!content) return null;
      const previous = content.scrollTop;
      content.scrollTop = content.scrollHeight;
      return previous;
    });
    const atEnd = await readFormWorkflowGeometry(form);
    expect(atEnd.contentBottom, 'The last field and feedback can scroll above sticky actions')
      .toBeLessThanOrEqual(atEnd.footer.top + 1);
    if (previousScroll !== null) await form.evaluate((element, previous) => {
      element.closest('.modal-content').scrollTop = previous;
    }, previousScroll);
  }
  return geometry;
}

async function expectStableFormWorkflowInsets(form, before) {
  const after = await readFormWorkflowGeometry(form);
  expect(after.padding, 'State changes preserve the owning body padding').toEqual(before.padding);
  if (before.fieldInsets && after.fieldInsets) {
    expect(after.fieldInsets.left).toBeCloseTo(before.fieldInsets.left, 0);
    expect(after.fieldInsets.right).toBeCloseTo(before.fieldInsets.right, 0);
    expect(after.fieldInsets.top).toBeCloseTo(before.fieldInsets.top, 0);
  }
  return after;
}

async function captureFormWorkflowEvidence(form, name) {
  const directory = process.env.FORM_WORKFLOW_AUDIT_DIR;
  if (!directory) return;
  fs.mkdirSync(directory, { recursive: true });
  const dialog = form.locator('xpath=ancestor::*[@role="dialog"][1]');
  const previousScroll = await form.evaluate(element => {
    const content = element.closest('.modal-content');
    if (!content) return null;
    const previous = content.scrollTop;
    content.scrollTop = content.scrollHeight;
    return previous;
  });
  await (await dialog.count() ? dialog : form).screenshot({
    path: path.join(directory, `${name}.png`), animations: 'disabled', caret: 'hide',
    style: fs.readFileSync(path.join(__dirname, 'hide-global-audio-dock.css'), 'utf8') +
      '\n.app-header { visibility: hidden !important; }'
  });
  if (previousScroll !== null) await form.evaluate((element, previous) => {
    element.closest('.modal-content').scrollTop = previous;
  }, previousScroll);
}

async function holdFormWorkflowFailure(page, url) {
  let finish = null;
  await page.route(url, async route => {
    if (route.request().method() === 'GET') return route.fallback();
    await new Promise(resolve => {
      finish = async () => {
        finish = null;
        await route.fulfill({ status: 503, json: { error: { message: 'The changes could not be saved. Try again.' } } });
        resolve();
      };
    });
  });
  return { pending: () => Boolean(finish), fail: () => finish() };
}

module.exports = { readFormWorkflowGeometry, expectFormWorkflowGeometry,
  expectStableFormWorkflowInsets, captureFormWorkflowEvidence, holdFormWorkflowFailure };
