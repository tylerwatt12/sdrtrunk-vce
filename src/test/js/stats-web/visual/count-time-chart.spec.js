'use strict';

const { test, expect } = require('@playwright/test');
const fs = require('node:fs');
const path = require('node:path');

const application = fs.readFileSync(path.resolve(__dirname, '../../../../../stats-web/assets/app.js'), 'utf8');
function between(start, end) {
  return application.slice(application.indexOf(`function ${start}(`), application.indexOf(`function ${end}(`));
}
const renderer = `
  const SVG_NAMESPACE = 'http://www.w3.org/2000/svg';
  const pageObservers = new Map();
  const number = (value) => Number(value).toLocaleString('en-US');
  const node = (tag, className = '', text) => {
    const result = document.createElement(tag);
    result.className = className;
    if (text !== undefined) result.textContent = text;
    return result;
  };
  ${between('svgNode', 'iconGlyph')}
  ${between('installTimeChartHover', 'storedTheme')}
  ${between('roundedChartMaximum', 'dashboardProtocolKey')}
  window.renderCountChart = (rows, series, options) => {
    document.querySelector('.count-time-chart')?.remove();
    const chart = countTimeSeriesChart(rows, series, options);
    document.querySelector('.dashboard-call-activity').append(chart);
  };
`;

const hour = 3_600_000;
const first = Date.UTC(2026, 9, 1);
const protocols = [
  { field: 'p25', label: 'P25', color: 'var(--chart-call)' },
  { field: 'nxdn', label: 'NXDN', color: 'var(--chart-data)' }
];

const consumers = `
  const protocolFamily = (row) => row.protocol;
  const dashboardChannelKind = (row) => row.channel_kind;
  const badge = (text, className) => node('span', className, text);
  ${application.slice(application.indexOf('const DASHBOARD_CALL_METRICS ='),
    application.indexOf('const GROUP_IDENTITY_CALL_ACTIVITY_SERIES ='))}
  ${between('dashboardProtocolKey', 'signalNumber')}
  ${application.slice(application.indexOf('function disconnectPageObserversWithin('),
    application.indexOf('const LIVE_MULTIPLEX_MAGIC'))}
  window.countChartObservers = () => pageObservers.size;
  window.renderConsumerChart = (response, series) => {
    const previous = document.querySelector('.visual-dashboard-calls-example .dashboard-call-activity');
    disconnectPageObserversWithin(previous);
    const chart = series ? groupIdentityActivityChart(response, series, 'Call outcomes by time') :
      dashboardCallActivityChart(response);
    chart.classList.add('dashboard-call-activity');
    previous.replaceWith(chart);
  };
`;

async function openChart(page, rows, series = protocols, options = {}) {
  await page.goto('/design-system.html?view=dashboard-calls&theme=light');
  await page.addScriptTag({ content: renderer });
  await page.evaluate(({ rows, series, options }) => window.renderCountChart(rows, series, options),
    { rows, series, options: { from: first, to: first + rows.length * hour, preserveNulls: true, ...options } });
  const chart = page.locator('.visual-dashboard-calls-example .count-time-chart');
  await expect(chart.locator('svg')).toBeVisible();
  await expect.poll(() => chart.locator('svg').evaluate((svg) =>
    Math.abs(svg.viewBox.baseVal.width - svg.getBoundingClientRect().width))).toBeLessThan(2);
  return chart;
}

test('protocol bars retain partial and unavailable hours in pointer and keyboard values', async ({ page }) => {
  const chart = await openChart(page, [
    { time_ms: first, p25: 4, nxdn: 3 },
    { time_ms: first + hour, p25: null, nxdn: 2 },
    { time_ms: first + 2 * hour, p25: null },
    { time_ms: first + 3 * hour, p25: 0, nxdn: 0 }
  ], protocols, { combineSeries: true, yAxisLabel: 'Calls per hour' });
  await expect(chart.locator('.count-time-chart-bar')).toHaveCount(3);
  const svg = chart.locator('svg');
  await svg.focus();
  await expect(chart.getByRole('tooltip')).toContainText('Total: 7');
  await page.keyboard.press('ArrowRight');
  await expect(chart.getByRole('tooltip')).toContainText('Total: 2');
  await expect(chart.getByRole('tooltip')).toContainText('P25: Unavailable');
  await expect(chart.getByRole('tooltip')).toContainText('Partial history');
  await page.keyboard.press('ArrowRight');
  await expect(chart.getByRole('tooltip')).toContainText('Total: Unavailable');
  await page.keyboard.press('End');
  await expect(chart.getByRole('tooltip')).toContainText('Total: 0');
  await page.keyboard.press('Escape');
  await expect(chart.getByRole('tooltip')).toBeHidden();
  await chart.locator('.chart-hover-surface').hover({ position: { x: 2, y: 25 } });
  await expect(chart.getByRole('tooltip')).toContainText('Total: 7');
});

test('call outcome bars compare overlapping counts and retain exact hourly values', async ({ page }) => {
  const chart = await openChart(page, [
    { time_ms: first, calls: 10, recorded: 6 },
    { time_ms: first + hour, calls: 8, recorded: 5 }
  ], [{ field: 'calls', label: 'Calls', color: 'var(--chart-call)' },
    { field: 'recorded', label: 'Recorded', color: 'var(--chart-recorded)' }]);
  const bars = chart.locator('.count-time-chart-bar');
  await expect(bars).toHaveCount(4);
  for (const [index, label] of [/Calls: 10$/, /Recorded: 6$/, /Calls: 8$/, /Recorded: 5$/].entries()) {
    await expect(bars.nth(index)).toHaveAttribute('aria-label', label);
  }
  await chart.locator('svg').focus();
  await expect(chart.getByRole('tooltip')).toContainText('Calls: 10');
  await expect(chart.getByRole('tooltip')).toContainText('Recorded: 6');
  await expect(chart.getByRole('tooltip')).not.toContainText('Total:');
});

test('count charts redraw at readable phone widths without overflowing partial final hours', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  const rows = Array.from({ length: 24 }, (_, index) => ({
    time_ms: first + hour * index, p25: index * 17, nxdn: index * 3
  }));
  const chart = await openChart(page, rows, protocols,
    { combineSeries: true, to: first + 23.5 * hour, yAxisLabel: 'Calls per hour' });
  for (const width of [390, 320, 1120]) {
    await page.setViewportSize({ width, height: 844 });
    await expect.poll(() => chart.locator('svg').evaluate((svg) =>
      Math.abs(svg.viewBox.baseVal.width - svg.getBoundingClientRect().width))).toBeLessThan(2);
    const geometry = await chart.locator('svg').evaluate((svg) => ({
      width: svg.viewBox.baseVal.width,
      right: Math.max(...[...svg.querySelectorAll('.count-time-chart-bar')]
        .map((bar) => Number(bar.getAttribute('x')) + Number(bar.getAttribute('width')))),
      overflow: document.documentElement.scrollWidth > document.documentElement.clientWidth,
      textSize: getComputedStyle(svg.querySelector('.chart-axis-label')).fontSize
    }));
    expect(geometry.right).toBeLessThanOrEqual(geometry.width - 11);
    expect(geometry.overflow).toBe(false);
    expect(parseFloat(geometry.textSize)).toBeGreaterThanOrEqual(12);
    if (width === 390) {
      await chart.locator('svg').focus();
      await page.keyboard.press('ArrowRight');
    } else {
      await expect(chart.locator('svg')).toBeFocused();
      await expect(chart.getByRole('tooltip')).toContainText('Total: 20');
    }
  }
});

test('changing protocol, metric and outcome filters releases each previous chart observer', async ({ page }) => {
  const series = [{ field: 'logical_call_count', label: 'Calls', visible: true },
    { field: 'recorded_logical_call_count', label: 'Recorded', visible: true }];
  const response = { from_ms: first, to_ms: first + 2 * hour, bucket_ms: hour,
    totals: { logical_call_count: 10, recorded_logical_call_count: 6 },
    coverage: [{ protocol: 'P25', channel_kind: 'TRUNKED', status: 'COLLECTED' }],
    series: [0, 1].map((index) => ({ time_ms: first + index * hour, protocol: 'P25',
      channel_kind: 'TRUNKED', logical_call_count: 5, recorded_logical_call_count: 3 })) };
  for (const outcome of [false, true]) {
    await page.goto('/design-system.html?view=dashboard-calls&theme=light');
    await page.addScriptTag({ content: renderer + consumers });
    await page.evaluate(({ response, series }) => window.renderConsumerChart(response, series),
      { response, series: outcome ? series : null });
    await expect.poll(() => page.evaluate(() => window.countChartObservers())).toBe(1);
    const chart = page.locator('.visual-dashboard-calls-example .dashboard-call-activity');
    const controls = outcome ? chart.locator('.activity-series-button').first() :
      chart.locator('[aria-label="Call activity metric"] button').nth(1);
    for (let index = 0; index < 4; index += 1) {
      await controls.click();
      await expect.poll(() => page.evaluate(() => window.countChartObservers())).toBe(1);
    }
    if (!outcome) {
      await chart.locator('.activity-series-button:not(:disabled)').click();
      await expect.poll(() => page.evaluate(() => window.countChartObservers())).toBe(0);
    }
  }
});

test('long-range outcome history uses reported intervals and infers wider intervals when needed', async ({ page }) => {
  await page.goto('/design-system.html?view=dashboard-calls&theme=light');
  await page.addScriptTag({ content: renderer + consumers });
  const series = [{ field: 'logical_call_count', label: 'Calls', visible: true }];
  await page.evaluate(({ first, hour, series }) => window.renderConsumerChart({
    from_ms: first, to_ms: first + 12 * hour, bucket_ms: 4 * hour,
    totals: { logical_call_count: 20 }, series: [0, 8].map((index) =>
      ({ time_ms: first + index * hour, logical_call_count: 10 }))
  }, series), { first, hour, series });
  const chart = page.locator('.visual-dashboard-calls-example .count-time-chart');
  await expect(chart.locator('.count-time-chart-caption')).toHaveText(
    'Counts per 4 hours for the selected activity types');
  await expect(chart.locator('.chart-axis-label').first()).toHaveText('Calls per 4 hours');
  await expect(chart.locator('svg')).toHaveAttribute('aria-label', /each interval/);
  await expect.poll(() => chart.locator('svg').evaluate((svg) =>
    Math.abs(svg.viewBox.baseVal.width - svg.getBoundingClientRect().width))).toBeLessThan(2);
  expect(await chart.locator('.count-time-chart-bar').first().evaluate((bar) =>
    Number(bar.getAttribute('width')) / bar.ownerSVGElement.viewBox.baseVal.width)).toBeGreaterThan(.25);

  const inferred = await openChart(page, [0, 4, 8].map((index) =>
    ({ time_ms: first + index * hour, p25: 10, nxdn: 5 })), protocols,
    { combineSeries: true, to: first + 12 * hour });
  await expect(inferred.locator('.chart-axis-label').first()).toHaveText('Count per 4 hours');
  await expect(inferred.locator('.count-time-chart-caption')).toHaveText(
    'Totals per 4 hours for the selected protocols');
});
