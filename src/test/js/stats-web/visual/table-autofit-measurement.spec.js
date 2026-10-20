'use strict';

const { test, expect } = require('@playwright/test');

async function loadProductionMeasurement(page, view = 'gallery', theme = 'light') {
  await page.setViewportSize({ width: 1280, height: 900 });
  await page.goto(`/design-system.html?theme=${theme}&view=${view}`);
  await page.evaluate(async () => {
    const application = await fetch('/assets/app.js').then(response => response.text());
    const start = application.indexOf('function measureTableColumnContentWidth(');
    const end = application.indexOf('function applyPreferredTableWidths(', start);
    const limits = application.match(/^const TABLE_WIDTH_(?:MINIMUM|MAXIMUM) = \d+;/gm).join('\n');
    window.measureColumnForAudit = new Function('node',
      `${limits}\n${application.slice(start, end)}\nreturn measureTableColumnContentWidth;`)(
      (tag, className = '') => {
        const element = document.createElement(tag);
        element.className = className;
        return element;
      });
  });
}

for (const theme of ['light', 'dark']) {
  for (const density of ['normal', 'dense']) {
    test(`every Live column fits independently in ${theme} ${density}`, async ({ page }) => {
      await loadProductionMeasurement(page, 'live-notice', theme);
      const results = await page.evaluate((rowDensity) => {
        const table = document.querySelector('.visual-live-example .channels-live-table');
        table.classList.add('resizable-table', 'ui-data-table-compact');
        table.dataset.rowDensity = rowDensity;
        const headers = [...table.tHead.rows[0].cells];
        const group = document.createElement('colgroup');
        const columns = headers.map(() => document.createElement('col'));
        group.append(...columns);
        table.prepend(group);
        return headers.map((header, index) => {
          const widths = [];
          for (const startingWidth of [48, 360, 1000]) {
            columns.forEach(column => { column.style.width = `${startingWidth}px`; });
            table.style.width = `${startingWidth * columns.length}px`;
            const width = window.measureColumnForAudit(table, header, index);
            columns[index].style.width = `${width}px`;
            widths.push(width, window.measureColumnForAudit(table, header, index));
          }
          return { column: header.dataset.column, widths };
        });
      }, density);
      expect(results.map(result => result.column)).toEqual([
        'status', 'tags', 'channel', 'frequency', 'signal', 'decode-health', 'source', 'target', 'decoder'
      ]);
      for (const result of results) {
        expect(new Set(result.widths).size, result.column).toBe(1);
        expect(result.widths[0], result.column).toBeGreaterThanOrEqual(48);
        expect(result.widths[0], result.column).toBeLessThan(400);
      }
      expect(results.find(result => result.column === 'tags').widths[0]).toBeLessThan(100);
      await expect(page.locator('.table-column-autofit-measurement')).toHaveCount(0);
    });
  }
}

test('group headings, spanning cells and collapsed rows do not enlarge data columns', async ({ page }) => {
  await loadProductionMeasurement(page);
  const result = await page.evaluate(() => {
    const table = document.createElement('table');
    table.className = 'ui-data-table resizable-table ui-data-table-quiet';
    table.innerHTML = '<thead><tr><th>Site</th><th>Type</th><th>State</th></tr></thead>'
      + '<tbody><tr><td>West</td><td>P25</td><td>Active</td></tr></tbody>';
    document.body.append(table);
    const measure = () => [...table.tHead.rows[0].cells]
      .map((header, index) => window.measureColumnForAudit(table, header, index));
    const baseline = measure();
    const label = 'Regional emergency services and public safety agencies '.repeat(30);
    table.tBodies[0].insertAdjacentHTML('afterbegin',
      `<tr class="ui-table-row-group"><th colspan="3"><button class="table-row-group-disclosure">`
      + `<strong class="table-row-group-label">${label}</strong></button></th></tr>`
      + `<tr class="ui-table-row-group-item" hidden><td>${label}</td><td>${label}</td><td>${label}</td></tr>`
      + `<tr hidden><td>${label}</td><td>${label}</td><td>${label}</td></tr>`
      + `<tr><td colspan="3">${label}</td></tr>`
      + `<tr><td class="empty ui-table-empty" colspan="3">${label}</td></tr>`);
    const withExtraRows = measure();
    table.remove();
    return { baseline, withExtraRows };
  });
  expect(result.withExtraRows).toEqual(result.baseline);
  await expect(page.locator('.table-column-autofit-measurement')).toHaveCount(0);
});

test('Live event markers retain each measured column position', async ({ page }) => {
  await loadProductionMeasurement(page);
  const result = await page.evaluate(() => {
    const table = document.createElement('table');
    table.className = 'ui-data-table resizable-table live-events-table';
    table.innerHTML = '<thead><tr><th>Time</th><th>Event</th><th>Details</th></tr></thead>'
      + '<tbody><tr><td>9:48:33 AM</td><td>Regional emergency dispatch voice grant</td>'
      + '<td>Regional emergency dispatch voice grant</td></tr></tbody>';
    document.body.append(table);
    const measure = () => [...table.tHead.rows[0].cells]
      .map((header, index) => window.measureColumnForAudit(table, header, index));
    const baseline = measure();
    table.tBodies[0].rows[0].classList.add('live-event-category-voice');
    const marked = measure();
    table.remove();
    return { baseline, marked };
  });
  expect(result.marked.slice(1)).toEqual(result.baseline.slice(1));
});

test('column measurements retain workspace control sizing and clear cell width floors', async ({ page }) => {
  await loadProductionMeasurement(page);
  const result = await page.evaluate(() => {
    const sizes = [];
    for (const density of ['data-workspace', 'editor-workspace']) {
      const host = document.createElement('div');
      host.className = density;
      host.innerHTML = '<table class="ui-data-table resizable-table channel-catalog-table">'
        + '<thead><tr><th>Order</th><th class="channel-frequency-cell">MHz</th></tr></thead><tbody><tr>'
        + '<td><div class="channel-order-controls"><button class="ui-button ui-icon-button" aria-label="Earlier">↑</button>'
        + '<span class="channel-order-number">1</span><button class="ui-button ui-icon-button" aria-label="Later">↓</button></div></td>'
        + '<td class="channel-frequency-cell">85.0</td></tr></tbody></table>';
      document.body.append(host);
      const table = host.querySelector('table');
      const control = table.querySelector('.channel-order-controls');
      const cell = control.closest('td');
      const styles = getComputedStyle(cell);
      const controlWidth = control.getBoundingClientRect().width;
      sizes.push({
        density,
        expected: Math.ceil(controlWidth + parseFloat(styles.paddingLeft)
          + parseFloat(styles.paddingRight) + parseFloat(styles.borderRightWidth)),
        order: window.measureColumnForAudit(table, table.tHead.rows[0].cells[0], 0),
        frequency: window.measureColumnForAudit(table, table.tHead.rows[0].cells[1], 1)
      });
      host.remove();
    }
    return sizes;
  });
  for (const size of result) {
    expect(Math.abs(size.order - size.expected), size.density).toBeLessThanOrEqual(1);
    expect(size.frequency, size.density).toBeLessThan(100);
  }
  expect(result[1].order).toBeGreaterThan(result[0].order);
});

test('rich content auto-fit preserves line breaks, controls and bounded long values', async ({ page }) => {
  await loadProductionMeasurement(page);
  const result = await page.evaluate(() => {
    const table = document.createElement('table');
    table.className = 'ui-data-table resizable-table ui-data-table-quiet';
    const longName = 'Regional Emergency Services Dispatch';
    const values = [
      `<span class="identity-summary"><strong>${longName}</strong><small class="identity-summary-context">Radio 30412 · County P25</small></span>`,
      `<span class="identity-summary identity-summary-inline"><strong class="identity-summary-primary">${longName}</strong><small class="identity-summary-context">30412</small></span>`,
      '<span class="ui-badge-group"><span class="ui-pill">Voice</span><span class="ui-pill">Encrypted</span><a class="ui-pill" href="#">County P25</a></span>',
      '<div class="ui-action-row"><button class="ui-button">Edit rule</button><button class="ui-button">Delete rule</button></div>',
      '<input class="ui-selection-check" type="checkbox" aria-label="Select item">',
      `${longName}<br>${longName}`,
      `${longName} ${longName}`,
      'UnbrokenIdentifier'.repeat(100),
      '<time class="ui-time-stacked"><span>2026-10-19</span><span>23:59:59</span></time>'
    ];
    table.innerHTML = `<colgroup>${values.map(() => '<col>').join('')}</colgroup><thead><tr>`
      + values.map((_, index) => `<th><span class="table-column-label">${index + 1}</span></th>`).join('')
      + '</tr></thead><tbody><tr>' + values.map(value => `<td>${value}</td>`).join('')
      + '</tr></tbody>';
    document.body.append(table);
    const headers = [...table.tHead.rows[0].cells];
    const widths = headers.map((header, index) => window.measureColumnForAudit(table, header, index));
    const columns = [...table.querySelectorAll('col')];
    columns.forEach((column, index) => { column.style.width = `${widths[index]}px`; });
    table.style.width = `${widths.reduce((sum, width) => sum + width, 0)}px`;
    const repeated = headers.map((header, index) => window.measureColumnForAudit(table, header, index));
    table.remove();
    return { widths, repeated };
  });
  expect(result.repeated).toEqual(result.widths);
  expect(result.widths.every(width => width >= 48 && width <= 1200)).toBe(true);
  expect(result.widths[0]).toBeGreaterThan(200);
  expect(result.widths[1]).toBeGreaterThan(result.widths[0]);
  expect(result.widths[2]).toBeGreaterThan(180);
  expect(result.widths[3]).toBeGreaterThan(150);
  expect(result.widths[4]).toBeLessThan(90);
  expect(result.widths[5]).toBeLessThan(result.widths[6] * 0.7);
  expect(result.widths[7]).toBe(1200);
  expect(result.widths[8]).toBeLessThan(150);
  await expect(page.locator('.table-column-autofit-measurement')).toHaveCount(0);
});

test('RadioReference table minimums do not become individual column widths', async ({ page }) => {
  await loadProductionMeasurement(page);
  const widths = await page.evaluate(() => {
    return ['radioreference-sites', 'radioreference-talkgroups', 'radioreference-conventional'].map(type => {
      const table = document.createElement('table');
      table.className = 'data-table ui-data-table resizable-table ui-mobile-cards';
      table.dataset.tableType = type;
      table.dataset.mobileCards = 'true';
      table.style.setProperty('--table-content-min-width', '1800px');
      table.innerHTML = '<thead><tr><th><span class="table-column-label">Site</span></th><th>Mode</th></tr></thead>'
        + '<tbody><tr><td>West</td><td>P25</td></tr></tbody>';
      document.body.append(table);
      const width = window.measureColumnForAudit(table, table.tHead.rows[0].cells[0], 0);
      table.remove();
      return { type, width };
    });
  });
  for (const result of widths) {
    expect(result.width, result.type).toBeGreaterThanOrEqual(48);
    expect(result.width, result.type).toBeLessThan(100);
  }
});

test('fitted inline identities show both short aliases with long IDs and long aliases with normal IDs', async ({ page }) => {
  await loadProductionMeasurement(page);
  const results = await page.evaluate(() => {
    return [
      ['A', 'Working subscriber 16777215 · Home system 1A2B3.4C5'],
      ['Regional Emergency Services Dispatch', '30412']
    ].map(([alias, identifier]) => {
      const table = document.createElement('table');
      table.className = 'ui-data-table resizable-table channels-live-table ui-data-table-compact';
      table.innerHTML = '<colgroup><col></colgroup><thead><tr><th>Source</th></tr></thead>'
        + '<tbody><tr><td><span class="identity-summary identity-summary-inline">'
        + `<span class="identity-summary-primary">${alias}</span>`
        + `<small class="identity-summary-context">${identifier}</small></span></td></tr></tbody>`;
      document.body.append(table);
      const width = window.measureColumnForAudit(table, table.tHead.rows[0].cells[0], 0);
      table.querySelector('col').style.width = `${width}px`;
      table.style.width = `${width}px`;
      table.style.minWidth = `${width}px`;
      const values = [...table.querySelectorAll('.identity-summary > *')].map(value => ({
        text: value.textContent,
        clientWidth: value.clientWidth,
        scrollWidth: value.scrollWidth
      }));
      const repeated = window.measureColumnForAudit(table, table.tHead.rows[0].cells[0], 0);
      table.remove();
      return { width, repeated, values };
    });
  });
  for (const result of results) {
    expect(result.repeated).toBe(result.width);
    for (const value of result.values) {
      expect(value.scrollWidth, value.text).toBeLessThanOrEqual(value.clientWidth + 1);
    }
  }
});
