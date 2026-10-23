'use strict';

const assert = require('node:assert/strict');
const vm = require('node:vm');

class Element {
  constructor(tag, className = '', text = '') {
    Object.assign(this, { tag, className, text, children: [], dataset: {}, style: {}, attributes: {}, events: {} });
    this.classList = { toggle() {} };
  }
  get isConnected() { return this.root === true || Boolean(this.parent?.isConnected); }
  get firstChild() { return this.children[0]; }
  get textContent() { return this.text + this.children.map(child => child.textContent).join(''); }
  set textContent(value) { this.text = value; this.replaceChildren(); }
  append(...children) {
    for (const child of children) {
      const value = child instanceof Element ? child : new Element('text', '', String(child));
      value.parent = this;
      this.children.push(value);
    }
  }
  replaceChildren(...children) {
    this.children.forEach(child => { child.parent = null; });
    this.children = [];
    this.append(...children);
  }
  setAttribute(name, value) { this.attributes[name] = String(value); }
  addEventListener(name, callback) { this.events[name] = callback; }
  focus() { this.focused = true; }
  querySelectorAll(selector) {
    const matches = child => selector === '[data-action]' ? Boolean(child.dataset.action) :
      child.className.split(' ').includes(selector.slice(1));
    return this.children.flatMap(child => [...(matches(child) ? [child] : []), ...child.querySelectorAll(selector)]);
  }
  querySelector(selector) { return this.querySelectorAll(selector)[0] || null; }
}

const settle = () => new Promise(resolve => setImmediate(resolve));

async function checkDashboardActivity(source, functionBinding) {
  const node = (tag, className, text) => new Element(tag, className, text);
  const content = node('main');
  content.root = true;
  const lifetime = new AbortController();
  const summaries = [];
  const requests = [];
  const tables = [];
  let rangeChange;
  let rangeButtons;
  let defer = false;
  let access = true;
  let history = true;
  let cleanups = 0;
  let nextFailure = false;
  const page = offset => ({ rows: [{ radio_id: offset + 30914, alias_name: 'Engine 4',
    alias_description: 'North station', radio_entity_ref: { kind: 'radio', identity_key: 'unit-4' } }],
  offset, limit: 100, total_count: 201, has_more: offset < 200, next_offset: offset + 100,
  action_observation_count: 1000, retained_observation_count: 900,
  identified_observation_count: 800, unknown_source_observation_count: 100 });
  const context = vm.createContext({
    node, content, AbortController, DASHBOARD_ACTIVITY_RANGES: [['6h', '6h'], ['24h', '24h'], ['7d', '7d']],
    number: value => String(value), activityOptionLabel: value => ({ GRANT: 'Calls', AFFILIATE: 'Affiliations' }[value]),
    activityActionColor: () => '#123456', ACCESS_CAPABILITIES: { RADIO: 'radio' },
    capabilityAllowed: () => access, detailedHistoryAvailable: () => history,
    detailedHistoryNotice: () => history ? null : node('p', '', 'Saved event details are unavailable.'),
    loadActivityMessageTypes: async () => {},
    renderIsCurrent: ({ signal }) => !signal.aborted,
    cleanupTableLayoutMenu: () => { cleanups += 1; },
    sectionActionHost: () => node('div'),
    section: (title, body, actions) => {
      const result = node('section');
      const heading = node('div', 'section-title');
      heading.append(node('span', '', title));
      result.append(heading, body);
      if (actions) result.append(actions);
      return result;
    },
    svgNode: (tag, attributes, text) => {
      const element = node(tag, attributes.class, text);
      Object.entries(attributes).forEach(([name, value]) => element.setAttribute(name, value));
      return element;
    },
    rangeControls: (_ranges, _selected, callback) => {
      rangeChange = callback;
      rangeButtons = [node('button'), node('button'), node('button')];
      return { controls: node('div'), buttons: rangeButtons };
    },
    api: async (path, parameters, options) => {
      summaries.push({ path, parameters, options });
      return { rows: [{ action: 'GRANT', observation_count: 1000 }, { action: 'AFFILIATE', observation_count: 500 }] };
    },
    apiPage: async (path, parameters, options) => {
      const request = { path, parameters: { ...parameters }, options };
      requests.push(request);
      if (nextFailure) { nextFailure = false; throw new Error('Temporary failure'); }
      if (defer) { defer = false; return new Promise(resolve => { request.resolve = resolve; }); }
      return page(parameters.offset);
    },
    table: (rows, columns, _empty, options) => {
      tables.push({ rows, columns, options });
      return node('table', '', rows[0]?.alias_name || 'No radios');
    },
    radioIdentityValue: (row, options) => ({ row, options }),
    dashboardIdentitySystem: row => row.system_name,
    dateTime: value => String(value),
    activityMetricGuide: () => node('section', '', 'Activity guide'),
    asyncSectionFailure: (_error, message, retry) => {
      const failure = node('div', '', message);
      const button = node('button', 'async-section-retry', 'Retry');
      button.addEventListener('click', retry);
      failure.append(button);
      return failure;
    },
    browsingWorkflows: {
      pageRangeText: ({ offset, total }) => `${offset + 1}–${Math.min(offset + 100, total)} of ${total}`,
      createBrowsingPager: ({ previous, next, countText }) => {
        const pager = node('nav', 'pager', countText);
        for (const [name, action] of [['Previous', previous], ['Next', next]]) {
          const button = node('button', name.toLowerCase(), name);
          button.disabled = !action.enabled;
          button.addEventListener('click', action.onClick);
          pager.append(button);
        }
        return pager;
      }
    }
  });
  for (const [name, parameters] of Object.entries({ dashboardActivityActionConfiguration: 'action',
    dashboardActivityActionRows: 'response', dashboardActivityMix: 'response, selectedAction, onSelect',
    dashboardActivityRadio: 'row', dashboardActivityRangeLabel: 'range',
    dashboardActivityRadioPager: 'page, onOffset', dashboardActivityRadioNote: 'page' })) {
    vm.runInContext(`function ${name}(${parameters}) ${functionBinding(source, name)}`, context);
  }
  vm.runInContext(source.slice(source.indexOf('const dashboardActivityRadioColumns = ['),
    source.indexOf('\n];', source.indexOf('const dashboardActivityRadioColumns = [')) + 3), context);
  vm.runInContext(`async function renderDashboardActivity(renderContext) ${functionBinding(source, 'renderDashboardActivity')}`, context);
  const select = async action => {
    content.querySelectorAll('[data-action]').find(element => element.tag === 'button' && element.dataset.action === action)
      .events.click();
    await settle();
  };
  await context.renderDashboardActivity({ signal: lifetime.signal });
  assert.equal(requests.length, 0, 'The source-radio query starts only after a category is selected.');
  assert.match(content.textContent, /Select an activity type/);
  await select('GRANT');
  assert.deepEqual(requests[0].parameters, { range: '24h', action: 'GRANT', limit: 100, offset: 0 });
  assert.match(content.textContent, /Calls · Source radios/);
  assert.match(content.textContent, /1000.*900.*800.*100/s, 'The retained/identified/unknown accounting is visible.');
  assert.equal(tables.at(-1).options.type, 'dashboard-activity-radios');
  const radioCell = tables.at(-1).columns.find(column => column.id === 'radio').render(tables.at(-1).rows[0]);
  assert.equal(radioCell.options.reference.identity_key, 'unit-4');
  assert.equal(radioCell.options.context, 'North station');
  assert.strictEqual(tables.at(-1).columns[0].render, context.dashboardIdentitySystem);
  content.querySelector('.next').events.click();
  await settle();
  assert.equal(requests.at(-1).parameters.offset, 100);
  assert.equal(content.querySelector('.pager').focused, true, 'Paging restores focus to the replacement pager.');
  content.querySelector('.previous').events.click();
  await settle();
  assert.equal(requests.at(-1).parameters.offset, 0);
  defer = true;
  await select('GRANT');
  const stale = requests.at(-1);
  await select('AFFILIATE');
  assert.equal(stale.options.signal.aborted, true, 'Changing category aborts the previous radio request.');
  stale.resolve(page(999));
  await settle();
  assert.equal(tables.at(-1).rows[0].radio_id, 30914, 'An older response cannot replace the new selection.');
  await rangeChange('7d', rangeButtons);
  assert.equal(summaries.at(-1).parameters.range, '7d');
  const afterRange = requests.length;
  assert.match(content.textContent, /Select an activity type/);
  await select('GRANT');
  assert.equal(requests.length, afterRange + 1);
  assert.equal(requests.at(-1).parameters.range, '7d');
  assert.equal(requests.at(-1).parameters.offset, 0);
  nextFailure = true;
  await select('AFFILIATE');
  assert.match(content.textContent, /Source radios could not be loaded/);
  await content.querySelector('.async-section-retry').events.click();
  assert.match(content.textContent, /Engine 4/);
  access = false;
  const beforeDenied = requests.length;
  await select('GRANT');
  assert.equal(requests.length, beforeDenied);
  assert.match(content.textContent, /Your account cannot view radios/);
  history = false;
  await select('GRANT');
  assert.equal(requests.length, beforeDenied);
  assert.match(content.textContent, /Saved event details are unavailable/);
  access = history = true;
  defer = true;
  await select('GRANT');
  const pending = requests.at(-1);
  const beforeCleanup = cleanups;
  lifetime.abort();
  assert.equal(pending.options.signal.aborted, true, 'Page navigation cancels the pending radio request.');
  assert.equal(cleanups, beforeCleanup + 1, 'Page navigation disposes the table layout menu.');
  pending.resolve(page(999));
  await settle();
  const finalRequestCount = requests.length;
  await rangeChange('6h', rangeButtons);
  assert.equal(requests.length, finalRequestCount, 'A stale control cannot fetch on the next page.');
  console.log('Dashboard Activity category, range, pagination, identity, retry, access and cancellation checks passed.');
}

module.exports = { checkDashboardActivity };
