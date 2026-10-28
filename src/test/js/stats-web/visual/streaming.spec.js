const { expect, test } = require('@playwright/test');
const { readFileSync } = require('node:fs');
const { resolve } = require('node:path');
const { expectFormWorkflowGeometry, expectStableFormWorkflowInsets,
  captureFormWorkflowEvidence } = require('./fixtures/form-workflow-geometry.cjs');
const app = readFileSync(resolve(__dirname, '../../../../..', 'stats-web/assets/app.js'), 'utf8');
const helpersSource = app.slice(app.indexOf('function closeReadOnlyModal('), app.indexOf('function statsLoggingState(')) +
  app.slice(app.indexOf('function uiToggle('), app.indexOf('function uiPill(')) +
  app.slice(app.indexOf('function uiSegmentedControl('), app.indexOf('function channelSummaryCards(')) +
  app.slice(app.indexOf('const METRIC_ICONS ='), app.indexOf('function searchBar('));

async function install(page, theme = 'light', empty = false, radioReferenceAllowed = true, aliasCount = 60,
  provider = 'BROADCASTIFY_CALL') {
  await page.goto(`/design-system.html?theme=${theme}`);
  await page.evaluate(async ({helpersSource, theme, empty, radioReferenceAllowed, aliasCount, provider}) => {
    const { createStreamingWorkspace } = await import('/assets/features/streaming.js');
    const { openRadioReferenceConnect } = await import('/assets/features/radioreference-connect.js');
    const browsingWorkflows = await import('/assets/core/browsing-workflows.js');
    const tableDefaults = await import('/assets/core/table-defaults.js');
    document.documentElement.dataset.theme = theme;
    document.body.replaceChildren();
    const node = (tag, className = '', text = null) => {
      const element = document.createElement(tag);
      element.className = className;
      if (tag === 'select') element.classList.add('ui-select');
      if (text !== null && text !== undefined) element.textContent = String(text);
      return element;
    };
    const iconGlyph = (iconId = '') => {
      const icon = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
      icon.setAttribute('viewBox', '0 0 24 24');
      icon.setAttribute('aria-hidden', 'true');
      icon.innerHTML = iconId === 'icon-columns' ?
        '<path d="M3 6h3M10 6h11M3 12h11M18 12h3M3 18h5M12 18h9"></path>' +
          '<circle cx="8" cy="6" r="2"></circle><circle cx="16" cy="12" r="2"></circle>' +
          '<circle cx="10" cy="18" r="2"></circle>' : '<circle cx="12" cy="12" r="7"></circle>';
      return icon;
    };
    const iconButton = (iconId, label, className = 'ui-button ui-button-secondary ui-icon-button') => {
      const control = node('button', className);
      control.type = 'button';
      control.setAttribute('aria-label', label);
      control.dataset.uiHint = label;
      control.append(iconGlyph(iconId));
      return control;
    };
    const formField = (labelText, control, detail = '') => {
      const field = node('label', 'admin-form-field ui-field');
      field.append(node('span', 'admin-form-label ui-field-label', labelText), control);
      if (detail) field.append(node('small', 'admin-form-help ui-field-detail', detail));
      return field;
    };
    const uiSelectFrame = (select) => {
      const frame = node('span', 'ui-select-frame');
      frame.append(select, iconGlyph());
      return frame;
    };
    const uiPill = (label, tone = 'neutral') => {
      const pill = node('span', `ui-pill ui-pill-${tone}`);
      pill.append(node('span', '', label));
      return pill;
    };
    const uiStatus = (label, tone = 'neutral') => node('span', `ui-status ui-status-${tone}`, label);
    const table = (values, columns, emptyText, options = {}) => {
      options.controller?.layoutMenuCleanup?.();
      const wrapper = node('div', `table-wrap ui-table-wrap ${options.wrapperClass || ''}`.trim());
      const element = node('table', `data-table resizable-table ui-data-table ${options.tableClass || ''}`);
      element.dataset.tableType = options.type || 'generic';
      if (options.mobileCards) element.dataset.mobileCards = 'true';
      const head = node('thead');
      const colgroup = node('colgroup');
      columns.forEach((column) => {
        const col = node('col');
        col.style.width = `${tableDefaults.width(options.type, column)}px`;
        colgroup.append(col);
      });
      const header = node('tr');
      columns.forEach((column) => {
        const cell = node('th');
        if (column.renderHeader) cell.append(column.renderHeader());
        else cell.textContent = column.label;
        header.append(cell);
      });
      head.append(header);
      const tableBody = node('tbody');
      if (!values.length) {
        const row = node('tr');
        const cell = node('td', 'empty', emptyText);
        cell.colSpan = columns.length;
        row.append(cell);
        tableBody.append(row);
      } else {
        values.forEach((value) => {
          const row = node('tr');
          columns.forEach((column) => {
            const cell = node('td');
            cell.dataset.label = column.fullLabel || column.label || '';
            const rendered = column.render ? column.render(value) : value[column.id];
            cell.append(rendered instanceof Node ? rendered : document.createTextNode(String(rendered ?? '')));
            row.append(cell);
          });
          tableBody.append(row);
        });
      }
      element.append(colgroup, head, tableBody);
      if (options.layoutMenuHost) {
        const layoutMenu = node('div', 'table-layout-menu');
        const columnsButton = iconButton('icon-columns', 'Choose table columns',
          'ui-button ui-button-secondary ui-button-compact table-layout-trigger');
        columnsButton.append(node('span', '', 'Columns'));
        layoutMenu.append(columnsButton);
        options.layoutMenuHost.append(layoutMenu);
        if (options.controller) options.controller.layoutMenuCleanup = () => layoutMenu.remove();
      }
      wrapper.append(element);
      if (options.type === 'streaming-aliases') window.streamingTest.rebuildAliases = () => {
        const replacement = table(values, columns, emptyText, options);
        wrapper.replaceWith(replacement);
      };
      return wrapper;
    };

    const shared = new Function('node', 'valueNode', 'iconButton', 'iconGlyph', 'number',
      `let activeReadOnlyModal = null; ${helpersSource}
      return { openReadOnlyModal, confirmAction, uiToggleField, uiSegmentedControl, metrics };`)(node,
        value => value instanceof Node ? value : document.createTextNode(String(value)), iconButton,
        iconGlyph, String);
    const fields = [
      {key:'name',label:'Name',type:'text',maximum:255},
      {key:'enabled',label:'Enabled',type:'boolean'},
      {key:'host',label:'Server address',type:'text',maximum:1024},
      {key:'api_key',label:'API key',type:'password',maximum:1024},
      {key:'system_id',label:'System ID',type:'number',minimum:1,maximum:2147483647},
      {key:'maximum_recording_age',label:'Maximum recording age (ms)',type:'number',minimum:0,maximum:86400000,advanced:true}
    ];
    let otherName='Regional Archive';
    let editRevision='destination-edit-1', assignmentRevision='assignments-1';
    const providerLabel=provider==='RADIORESOLVE'?'RadioResolve':provider==='BROADCASTIFY_CALL_SITE'?'Broadcastify Call Site':'Broadcastify Calls';
    let definition={configuration_id:'destination',provider,settings:{name:'County Calls',enabled:true,
      host:'https://example.com/calls',system_id:42,maximum_recording_age:600000},configured_credentials:['api_key']};
    const status={configuration_id:'destination',name:'County Calls',provider,provider_label:providerLabel,
      enabled:true,state:'CONNECTED',state_label:'Connected',queued:3,sent:128,aged_off:0,duplicate_rejected:4,errors:0,last_error:null,attention:false};
    let assignments=new Set([1,51]);
    const aliases=Array.from({length:aliasCount},(_,i)=>({id:i+1,name:`Dispatch ${i+1}`,identifier:String(100+i),alias_list_id:7,alias_list_name:'County Public Safety'}));
    const calls=[];
    window.streamingTest={calls,status,changeOther:()=>{otherName='Updated archive';},changeDestination:()=>{editRevision='destination-edit-remote';},changeAssignments:()=>{assignmentRevision='assignments-remote';},fail:false,stale:false,holdAliasRead:false,releaseAliasRead:null,releaseAliasReadOnSave:false,holdWrite:false,releaseWrite:null,holdOptions:false,releaseOptions:null,references:{aliases:2,alias_lists:[]},feedMode:'ready',feedDelay:0,accountState:'VALID_PREMIUM',connectCount:0,feeds:[
      {id:1042,name:'Metro Public Safety Dispatch and Regional Interoperability Network',configured:false},
      {id:2087,name:'County Fire and EMS',configured:true},
      {id:3194,name:'Citywide Events',configured:false}
    ]};
    const requestJson=async (path, options={}) => {
      calls.push([path, options]);
      const url=new URL(path,location.href); const suffix=url.pathname.replace('/api/v1/admin/streaming','');
      if(url.pathname==='/api/v1/admin/radioreference') return {account:{state:window.streamingTest.accountState}};
      if(url.pathname==='/api/v1/admin/radioreference/session'&&options.method==='PUT') {
        if(window.streamingTest.holdConnect) await new Promise(resolve=>{window.streamingTest.releaseConnect=resolve;});
        window.streamingTest.accountState='VALID_PREMIUM';
        return {account:{state:'VALID_PREMIUM'}};
      }
      if(options.method&&window.streamingTest.holdWrite) await new Promise(resolve=>{window.streamingTest.releaseWrite=resolve;});
      if(suffix==='/options'&&window.streamingTest.holdOptions) await new Promise(resolve=>{window.streamingTest.releaseOptions=resolve;});
      if(window.streamingTest.fail) throw new Error('Connection unavailable');
      if(options.method && window.streamingTest.stale) throw Object.assign(new Error('This destination changed. Your edits are still here. Reload current values before saving'),{code:'stale_revision'});
      if(suffix==='/destination'&&(options.method==='PUT'||options.method==='DELETE')&&options.body.edit_revision!==editRevision)
        throw Object.assign(new Error('This destination changed. Your edits are still here. Reload current values before saving'),{code:'stale_revision'});
      if(suffix==='/feeds/refresh'&&options.method==='POST') {
        if(window.streamingTest.feedDelay) await new Promise(resolve=>setTimeout(resolve,window.streamingTest.feedDelay));
        if(window.streamingTest.feedMode==='error') throw new Error('Available feeds could not be loaded');
        return {items:window.streamingTest.feedMode==='empty'?[]:structuredClone(window.streamingTest.feeds)};
      }
      if(suffix==='/feeds'&&options.method==='POST') return {configuration_id:'destination'};
      if(suffix==='/options') return {providers:[{id:provider,label:providerLabel,connection_test:true,fields}],sites:[]};
      if(suffix.startsWith('/templates/')) return {...structuredClone(definition),settings:{...definition.settings,name:'',enabled:false},configured_credentials:[]};
      if(suffix==='/test') return {success:true,message:'Connection accepted'};
      if(suffix==='/destination'&&options.method==='DELETE') {
        empty=true; return {};
      }
      if(suffix==='/destination/aliases') {
        if(options.method==='POST') {
          if(options.body.revision!==assignmentRevision) throw Object.assign(new Error('Assignments changed. Your edits are still here. Reload current values before saving'),{code:'stale_revision'});
          options.body.add.forEach(id=>assignments.add(id)); options.body.remove.forEach(id=>assignments.delete(id));
          assignmentRevision='assignments-2';
          return {get revision() {
            if(window.streamingTest.releaseAliasReadOnSave) {
              window.streamingTest.releaseAliasReadOnSave=false;
              window.streamingTest.releaseAliasRead();
            }
            return assignmentRevision;
          },configuration_id:'destination'};
        }
        const filtered=aliases.filter(row=>(!url.searchParams.get('q')||row.name.includes(url.searchParams.get('q')))&&(url.searchParams.get('assigned')!=='true'||assignments.has(row.id)));
        const offset=Number(url.searchParams.get('offset'))||0;
        const result={revision:assignmentRevision,total:filtered.length,limit:50,items:filtered.slice(offset,offset+50).map(row=>({...row,assigned:assignments.has(row.id)}))};
        if(window.streamingTest.holdAliasRead) {
          window.streamingTest.holdAliasRead=false;
          await new Promise(resolve=>{window.streamingTest.releaseAliasRead=resolve;});
        }
        return result;
      }
      if(options.method==='POST'||options.method==='PUT') {
        definition.settings={...definition.settings,...options.body.settings}; editRevision='destination-edit-2'; empty=false;
        return {edit_revision:editRevision,configuration_id:'destination'};
      }
      if(suffix==='/destination') return {edit_revision:editRevision,destination:structuredClone(definition),status,references:structuredClone(window.streamingTest.references)};
      return {destinations:empty?[]:[status,{...status,configuration_id:'other',name:otherName,provider:'RADIORESOLVE',provider_label:'RadioResolve',state:'ERROR',state_label:'Network unavailable',queued:12,sent:842,duplicate_rejected:null,errors:1,last_error:'Network unavailable',attention:true}]};
    };
    const shell=node('main','content');
    const header=node('header','page-header ui-page-header');
    const labels=node('div'); labels.append(node('h1','page-title','Streaming'),node('div','page-subtitle','Manage destinations and monitor delivery')); header.append(labels);
    const abort=new AbortController();
    window.streamingTest.abort=()=>abort.abort();
    const connectRadioReference=options=>{
      window.streamingTest.connectCount+=1;
      return openRadioReferenceConnect({node,formField,...shared,requestJson},options);
    };
    const workspace=createStreamingWorkspace({node,formField,uiSelectFrame,uiStatus,table,...shared,requestJson,browsingWorkflows,
      connectRadioReference,radioReferenceAllowed,
      modalFooter:(...controls)=>{const footer=node('footer','alias-modal-footer ui-modal-footer ui-action-row');footer.append(...controls);return footer;},
      formatNumber:value=>new Intl.NumberFormat('en-US').format(value),href:(view,params)=>`/?${new URLSearchParams({view,...params})}`,signal:abort.signal});
    window.streamingTest.refresh=workspace.refresh;
    shell.append(header,workspace.element);document.body.append(shell);
  }, {helpersSource,theme,empty,radioReferenceAllowed,aliasCount,provider});
  await expect(page.getByRole('button',{name:'Add destination',exact:true})).toBeVisible();
}

async function expectStreamingBodyInsets(modal) {
  const insets = await modal.locator('.ui-form-workflow-body').evaluate(body => {
    const bounds = body.getBoundingClientRect();
    return [...body.querySelectorAll('.ui-feedback, .ui-notice, .ui-segmented')]
      .filter(node => node.getClientRects().length).map(node => {
        const box = node.getBoundingClientRect();
        return { left: box.left - bounds.left, right: bounds.right - box.right,
          top: box.top - bounds.top, bottom: bounds.bottom - box.bottom };
      });
  });
  for (const inset of insets) {
    expect(inset.left, 'Feedback and tabs have room inside the boundary').toBeGreaterThanOrEqual(8);
    expect(inset.right, 'Feedback and tabs have room inside the boundary').toBeGreaterThanOrEqual(8);
    expect(inset.top).toBeGreaterThanOrEqual(8);
    expect(inset.bottom).toBeGreaterThanOrEqual(8);
  }
}

async function expectStreamingWorkflowGeometry(modal) {
  const editor = modal.locator('.streaming-editor');
  const geometry = await expectFormWorkflowGeometry(editor);
  await expectStreamingBodyInsets(modal);
  await modal.locator('.modal-content').evaluate(element => { element.scrollTop = element.scrollHeight; });
  const bottom = await editor.evaluate(element => ({
    body: element.querySelector(':scope > .ui-form-workflow-body').getBoundingClientRect().bottom,
    footer: element.querySelector(':scope > .ui-modal-footer').getBoundingClientRect().top
  }));
  expect(bottom.footer, 'The highlighted fields end above the sticky actions').toBeGreaterThanOrEqual(bottom.body - 1);
  return geometry;
}

for (const [theme, viewport] of [
  ['light', { width: 1440, height: 1000 }],
  ['dark', { width: 320, height: 700 }]
]) for (const creating of [false, true]) {
  test(`workflow spacing streaming ${creating ? 'new' : 'existing'} fields, feedback, and actions remain separated in ${theme}`, async ({ page }) => {
    await page.setViewportSize(viewport);
    await install(page, theme, creating);
    await page.evaluate(() => { window.streamingTest.holdOptions = true; });
    await page.getByRole('button', { name: creating ? 'Add destination' : 'County Calls', exact: true }).click();
    const modal = page.getByRole('dialog');
    await expect(modal.getByText('Loading destination…', { exact: true })).toBeVisible();
    await expectStreamingBodyInsets(modal);
    await captureFormWorkflowEvidence(modal.locator('.streaming-editor'), `streaming-${creating ? 'new' : 'existing'}-${theme}-loading`);
    await page.evaluate(() => { window.streamingTest.holdOptions = false; window.streamingTest.releaseOptions(); });
    await modal.getByLabel('Name', { exact: true }).fill('Updated fixture destination');
    await modal.getByText('Advanced settings', { exact: true }).click();
    await expect(modal.locator('.streaming-editor')).toHaveClass(/is-dirty/);
    const before = await expectStreamingWorkflowGeometry(modal);
    await captureFormWorkflowEvidence(modal.locator('.streaming-editor'), `streaming-${creating ? 'new' : 'existing'}-${theme}-dirty`);
    await page.evaluate(() => { window.streamingTest.holdWrite = true; });
    await modal.getByRole('button', { name: creating ? 'Add destination' : 'Save and reconnect', exact: true }).click();
    await expect(modal.getByLabel('Name', { exact: true })).toBeDisabled();
    await expect(modal.locator('.ui-feedback-loading')).toHaveText(creating ? 'Adding destination…' : 'Saving destination…');
    await expect(modal).toHaveAttribute('aria-busy', 'true');
    await page.keyboard.press('Escape');
    await expect(modal).toBeVisible();
    await expectStreamingWorkflowGeometry(modal);
    await expectStableFormWorkflowInsets(modal.locator('.streaming-editor'), before);
    await captureFormWorkflowEvidence(modal.locator('.streaming-editor'), `streaming-${creating ? 'new' : 'existing'}-${theme}-saving`);
    await page.evaluate(() => { window.streamingTest.fail = true; window.streamingTest.holdWrite = false; window.streamingTest.releaseWrite(); });
    await expect(modal.getByText('Connection unavailable', { exact: true })).toBeVisible();
    await expectStreamingWorkflowGeometry(modal);
    await expectStableFormWorkflowInsets(modal.locator('.streaming-editor'), before);
    await captureFormWorkflowEvidence(modal.locator('.streaming-editor'), `streaming-${creating ? 'new' : 'existing'}-${theme}-error`);
    if (!creating) {
      await page.evaluate(() => { window.streamingTest.fail = false; });
      await modal.getByRole('button', { name: 'Aliases', exact: true }).click();
      await modal.getByRole('checkbox', { name: 'Send Dispatch 1 to this destination', exact: true }).uncheck();
      const aliasesBefore = await expectStreamingWorkflowGeometry(modal);
      await captureFormWorkflowEvidence(modal.locator('.streaming-editor'), `streaming-aliases-${theme}-dirty`);
      await page.evaluate(() => { window.streamingTest.holdWrite = true; });
      await modal.getByRole('button', { name: 'Save assignments', exact: true }).click();
      await expect(modal.getByRole('checkbox', { name: 'Send Dispatch 1 to this destination', exact: true })).toBeDisabled();
      await expect(modal.locator('.ui-feedback-loading')).toHaveText('Saving alias assignments…');
      await expectStreamingWorkflowGeometry(modal);
      await expectStableFormWorkflowInsets(modal.locator('.streaming-editor'), aliasesBefore);
      await captureFormWorkflowEvidence(modal.locator('.streaming-editor'), `streaming-aliases-${theme}-saving`);
      await page.evaluate(() => { window.streamingTest.fail = true; window.streamingTest.holdWrite = false; window.streamingTest.releaseWrite(); });
      await expect(modal.getByText('Connection unavailable', { exact: true })).toBeVisible();
      await expectStreamingWorkflowGeometry(modal);
      await expectStableFormWorkflowInsets(modal.locator('.streaming-editor'), aliasesBefore);
      await captureFormWorkflowEvidence(modal.locator('.streaming-editor'), `streaming-aliases-${theme}-error`);
    }
  });
}

for(const theme of ['light']) for(const mobile of [false,true]) {
  test(`streaming ${theme} ${mobile?'mobile':'desktop'} page and settings`,async({page})=>{
    await page.setViewportSize(mobile?{width:390,height:844}:{width:1440,height:1000});
    await install(page,theme);
    await expect(page.getByRole('button',{name:'County Calls',exact:true})).toBeVisible();
    await expect(page.locator('.streaming-destination-card')).toHaveCount(2);
    await expect(page.locator('table[data-table-type="streaming-destinations"]')).toHaveCount(0);
    expect(await page.evaluate(()=>document.documentElement.scrollWidth)).toBeLessThanOrEqual(mobile?390:1440);
    await expect(page).toHaveScreenshot(`streaming-${theme}-${mobile?'mobile':'desktop'}.png`,{fullPage:true});
    await page.getByRole('button',{name:'County Calls',exact:true}).click();
    const modal=page.getByRole('dialog');
    await expect(modal.getByLabel('API key',{exact:true})).toHaveValue('');
    await expect(modal.getByLabel('Provider',{exact:true})).toBeDisabled();
    await expect(modal).toHaveScreenshot(`streaming-settings-${theme}-${mobile?'mobile':'desktop'}.png`);
    const bounds=await modal.boundingBox();expect(bounds.x).toBeGreaterThanOrEqual(0);expect(bounds.x+bounds.width).toBeLessThanOrEqual(mobile?390:1440);
    await page.keyboard.press('Escape');await expect(modal).toHaveCount(0);
    await expect(page.getByRole('button',{name:'County Calls',exact:true})).toBeFocused();
  });
}

test('partial settings preserve saved credentials and stale edits remain reviewable',async({page})=>{
  await install(page);await page.getByRole('button',{name:'County Calls',exact:true}).click();
  const modal=page.getByRole('dialog');
  await modal.getByLabel('Name',{exact:true}).fill('Renamed calls');
  await page.evaluate(()=>{window.streamingTest.stale=true;});
  await modal.getByRole('button',{name:'Save and reconnect'}).click();
  await expect(modal.getByRole('button',{name:'Reload current values'})).toBeVisible();
  await expect(modal.getByLabel('Name',{exact:true})).toHaveValue('Renamed calls');
  await page.evaluate(()=>{window.streamingTest.stale=false;});
  await modal.getByRole('button',{name:'Save and reconnect'}).click();
  await expect(modal.locator('.ui-form-submit')).toBeHidden();
  await expect(modal.locator('.streaming-editor')).not.toHaveClass(/is-dirty/);
  await expect(modal.getByText('Changes saved.',{exact:true})).toHaveCount(0);
  const writes=await page.evaluate(()=>window.streamingTest.calls.filter(([,options])=>options.method==='PUT'));
  expect(writes.at(-1)[1].body).toEqual({edit_revision:'destination-edit-1',provider:'BROADCASTIFY_CALL',settings:{name:'Renamed calls'}});
  await expect(modal.getByLabel('Provider',{exact:true})).toBeDisabled();
  await modal.getByLabel('Name',{exact:true}).fill('Edited again');
  await expect(modal.locator('.streaming-editor')).toHaveClass(/is-dirty/);
  await expect(modal.locator('.ui-form-state')).toHaveText('Unsaved changes');
  await expect(modal.getByRole('button',{name:'Save and reconnect'})).toBeVisible();
  await expect(modal.getByText('Changes saved.',{exact:true})).toHaveCount(0);
  await modal.getByLabel('Name',{exact:true}).fill('Renamed calls');
  await expect(modal.locator('.ui-form-submit')).toBeHidden();
  await expect(modal.locator('.ui-form-state')).toBeHidden();
});

test('streaming deletion keeps its editor and sends no write until themed confirmation is accepted', async ({page}) => {
  await install(page);
  await page.evaluate(() => { window.streamingTest.references={aliases:0,alias_lists:[]}; });
  await page.getByRole('button',{name:'County Calls',exact:true}).click();
  const editor=page.getByRole('dialog',{name:'Streaming destination'});
  const remove=editor.getByRole('button',{name:'Delete destination'});
  const deletes=() => page.evaluate(() => window.streamingTest.calls.filter(([,options])=>options.method==='DELETE').length);
  await remove.click();
  const confirmation=page.getByRole('alertdialog',{name:'Delete streaming destination'});
  await expect(confirmation.getByRole('button',{name:'Cancel'})).toBeFocused();
  expect(await deletes()).toBe(0);
  await confirmation.getByRole('button',{name:'Cancel'}).click();
  await expect(editor).toBeVisible();
  await expect(remove).toBeEnabled();
  await expect(remove).toBeFocused();
  expect(await deletes()).toBe(0);
  await remove.click();
  await confirmation.getByRole('button',{name:'Delete destination'}).click();
  await expect(editor).toHaveCount(0);
  expect(await deletes()).toBe(1);
});

test('delivery counters retain their live values and update error severity after recovery', async ({page}) => {
  await install(page);
  const counters = page.locator('.streaming-destination-card').first().locator('.streaming-destination-metrics');
  const errors = counters.locator('.ui-metric').filter({hasText:'Errors'});
  await expect(counters.locator('.ui-metric')).toHaveCount(5);
  await expect(errors).toHaveClass(/\bui-metric-blue\b/);
  await page.evaluate(() => {
    window.streamingTest.errorCounter = document.querySelector('.streaming-destination-metrics .ui-metric:last-child strong span');
    Object.assign(window.streamingTest.status, {queued:7, sent:141, errors:2, attention:true, last_error:'Delivery unavailable'});
  });
  await expect(errors).toHaveClass(/\bui-metric-danger\b/);
  await expect(errors.locator('strong')).toHaveText('2');
  await expect(counters.locator('.ui-metric strong')).toHaveText(['7','141','0','4','2']);
  await page.evaluate(() => Object.assign(window.streamingTest.status, {attention:false, last_error:null}));
  await expect(errors).toHaveClass(/\bui-metric-warning\b/);
  await expect(page.locator('.streaming-destination-card').first().locator('.streaming-destination-error')).toBeHidden();
  await page.evaluate(() => { window.streamingTest.status.errors = 0; });
  await expect(errors).toHaveClass(/\bui-metric-blue\b/);
  expect(await page.evaluate(() => window.streamingTest.errorCounter ===
    document.querySelector('.streaming-destination-metrics .ui-metric:last-child strong span'))).toBe(true);
});

test('destination status keeps shared delivery cards and metadata through severity recovery', async ({page}) => {
  await page.setViewportSize({width:390,height:844});
  await install(page, 'dark');
  await page.getByRole('button',{name:'County Calls',exact:true}).click();
  const modal = page.getByRole('dialog');
  await modal.getByRole('button',{name:'Status',exact:true}).click();
  const panel = modal.locator('.streaming-panel:not([hidden])');
  const errors = panel.locator('.ui-metric').filter({hasText:'Errors'});
  await expect(panel.locator('.ui-metric-label')).toHaveText(['Queued','Sent / uploaded','Aged off','Duplicates skipped','Errors']);
  await expect(panel.locator('.ui-fact dt')).toHaveText(['Last error','Provider']);
  await expect(errors).toHaveClass(/\bui-metric-blue\b/);
  await page.evaluate(() => Object.assign(window.streamingTest.status,
    {queued:7, sent:141, errors:2, attention:true, last_error:'Delivery unavailable'}));
  await panel.getByRole('button',{name:'Refresh status',exact:true}).click();
  await expect(panel.locator('.ui-metric strong')).toHaveText(['7','141','0','4','2']);
  await expect(errors).toHaveClass(/\bui-metric-danger\b/);
  await expect(panel.locator('.ui-fact dd')).toHaveText(['Delivery unavailable','Broadcastify Calls']);
  await page.evaluate(() => Object.assign(window.streamingTest.status, {attention:false, last_error:null}));
  await panel.getByRole('button',{name:'Refresh status',exact:true}).click();
  await expect(errors).toHaveClass(/\bui-metric-warning\b/);
  await page.evaluate(() => Object.assign(window.streamingTest.status, {errors:null}));
  await panel.getByRole('button',{name:'Refresh status',exact:true}).click();
  await expect(errors).toHaveClass(/\bui-metric-blue\b/);
  await expect(errors.locator('strong')).toHaveText('—');
  await expect(panel.locator('.ui-fact dd')).toHaveText(['None','Broadcastify Calls']);
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(390);
});

for (const [provider, theme, viewport] of [
  ['BROADCASTIFY_CALL', 'light', {width:1440, height:1000}],
  ['BROADCASTIFY_CALL_SITE', 'dark', {width:320, height:700}]
]) {
  test(`${provider} shows neutral duplicate counts in destination cards and Status through refresh in ${theme}`, async ({page}) => {
    await page.setViewportSize(viewport);
    await install(page, theme, false, true, 60, provider);
    const counters = page.locator('.streaming-destination-card').first().locator('.streaming-destination-metrics');
    const duplicates = counters.locator('.ui-metric').filter({hasText:'Duplicates skipped'});
    const errors = counters.locator('.ui-metric').filter({hasText:'Errors'});
    await expect(duplicates.locator('strong')).toHaveText('4');
    await expect(duplicates).toHaveClass(/\bui-metric-blue\b/);
    await page.evaluate(async () => {
      Object.assign(window.streamingTest.status, {duplicate_rejected:1234, errors:2, attention:true});
      await window.streamingTest.refresh();
    });
    await expect(duplicates.locator('strong')).toHaveText('1,234');
    await expect(duplicates).toHaveClass(/\bui-metric-blue\b/);
    await expect(errors.locator('strong')).toHaveText('2');
    await expect(errors).toHaveClass(/\bui-metric-danger\b/);
    expect(await duplicates.evaluate(element => [...element.querySelectorAll('.ui-metric-label, strong')]
      .every(part => part.scrollWidth <= part.clientWidth + 1))).toBe(true);
    await test.info().attach(`duplicate-count-card-${theme}`, {body:await page.screenshot(), contentType:'image/png'});
    await page.evaluate(async () => {
      window.streamingTest.status.duplicate_rejected=null;
      await window.streamingTest.refresh();
    });
    await expect(duplicates.locator('strong')).toHaveText('—');
    await page.getByRole('button',{name:'County Calls',exact:true}).click();
    const modal = page.getByRole('dialog');
    await modal.getByRole('button',{name:'Status',exact:true}).click();
    const panel = modal.locator('.streaming-panel:not([hidden])');
    const statusDuplicates = panel.locator('.ui-metric').filter({hasText:'Duplicates skipped'});
    await expect(statusDuplicates.locator('strong')).toHaveText('—');
    await page.evaluate(() => { window.streamingTest.status.duplicate_rejected=12345; });
    await panel.getByRole('button',{name:'Refresh status',exact:true}).click();
    await expect(statusDuplicates.locator('strong')).toHaveText('12,345');
    await expect(statusDuplicates).toHaveClass(/\bui-metric-blue\b/);
    await expect(panel.locator('.ui-metric').filter({hasText:'Errors'})).toHaveClass(/\bui-metric-danger\b/);
    await statusDuplicates.scrollIntoViewIfNeeded();
    await expect(statusDuplicates).toBeVisible();
    expect(await statusDuplicates.evaluate(element => [...element.querySelectorAll('.ui-metric-label, strong')]
      .every(part => part.scrollWidth <= part.clientWidth + 1))).toBe(true);
    await test.info().attach(`duplicate-count-status-${theme}`, {body:await page.screenshot(), contentType:'image/png'});
    await page.evaluate(() => { window.streamingTest.status.duplicate_rejected=0; });
    await panel.getByRole('button',{name:'Refresh status',exact:true}).click();
    await expect(statusDuplicates.locator('strong')).toHaveText('0');
    await page.evaluate(() => { window.streamingTest.status.duplicate_rejected=null; });
    await panel.getByRole('button',{name:'Refresh status',exact:true}).click();
    await expect(statusDuplicates.locator('strong')).toHaveText('—');
  });
}

test('unsupported destinations retain four delivery metrics in both views', async ({page}) => {
  await install(page, 'dark', false, true, 60, 'RADIORESOLVE');
  const counters = page.locator('.streaming-destination-card').first().locator('.streaming-destination-metrics');
  await expect(counters.locator('.ui-metric-label')).toHaveText(['Queued','Sent / uploaded','Aged off','Errors']);
  await page.getByRole('button',{name:'County Calls',exact:true}).click();
  const modal = page.getByRole('dialog');
  await modal.getByRole('button',{name:'Status',exact:true}).click();
  await expect(modal.locator('.streaming-panel:not([hidden]) .ui-metric-label'))
    .toHaveText(['Queued','Sent / uploaded','Aged off','Errors']);
  await expect(modal.getByText('Duplicates skipped',{exact:true})).toHaveCount(0);
});

test('alias changes survive paging and save only explicit selections',async({page})=>{
  await install(page);await page.getByRole('button',{name:'County Calls',exact:true}).click();
  const modal=page.getByRole('dialog');
  await modal.getByLabel('Name',{exact:true}).fill('Unsaved destination name');
  await modal.getByRole('button',{name:'Aliases',exact:true}).click();
  await expect(modal.locator('.ui-form-submit')).toBeHidden();
  await expect(modal.locator('.ui-form-state')).toBeHidden();
  await expect(modal.getByRole('checkbox',{name:'Send Dispatch 1 to this destination',exact:true})).toBeVisible();
  await expect(modal.getByRole('button',{name:'Choose table columns',exact:true})).toBeVisible();
  await expect(modal.locator('.table-layout-menu')).toHaveCount(1);
  await expect(modal.locator('.streaming-alias-pager')).toHaveClass(/ui-pager/);
  await expect(modal).toHaveScreenshot('streaming-aliases-light-desktop.png');
  await modal.getByRole('checkbox',{name:'Send Dispatch 1 to this destination',exact:true}).uncheck();
  await modal.getByRole('button',{name:'Next',exact:true}).click();
  await modal.getByRole('checkbox',{name:'Send Dispatch 52 to this destination',exact:true}).check();
  await expect(modal.locator('.table-layout-menu')).toHaveCount(1);
  await modal.getByRole('button',{name:'Previous',exact:true}).click();
  await expect(modal.getByRole('checkbox',{name:'Send Dispatch 1 to this destination',exact:true})).not.toBeChecked();
  await expect(modal.getByRole('button',{name:'Choose table columns',exact:true})).toBeVisible();
  await expect(modal.locator('.table-layout-menu')).toHaveCount(1);
  await expect(modal.getByRole('link',{name:'Dispatch 1',exact:true})).toHaveAttribute('href','/?view=aliases&list=7&alias=1');
  await modal.getByRole('button',{name:'Save assignments'}).click();
  await expect(modal.locator('.ui-form-submit')).toBeHidden();
  await expect(modal.getByText('Changes saved.',{exact:true})).toHaveCount(0);
  const writes=await page.evaluate(()=>window.streamingTest.calls.filter(([path,options])=>path.endsWith('/aliases')&&options.method==='POST'));
  expect(writes[0][1].body).toEqual({revision:'assignments-1',add:[52],remove:[1]});
  await modal.getByRole('button',{name:'Settings',exact:true}).click();
  await expect(modal.getByLabel('Name',{exact:true})).toHaveValue('Unsaved destination name');
  await expect(modal.getByRole('button',{name:'Save and reconnect'})).toBeVisible();
  await modal.getByRole('button',{name:'Aliases',exact:true}).click();
  await modal.getByRole('checkbox',{name:'Send Dispatch 1 to this destination',exact:true}).check();
  await expect(modal.locator('.ui-form-state')).toHaveText('Unsaved changes');
  await expect(modal.getByRole('button',{name:'Save assignments'})).toBeVisible();
});

test('unrelated configuration changes leave settings and assignment edits saveable',async({page})=>{
  await install(page);await page.getByRole('button',{name:'County Calls',exact:true}).click();
  const modal=page.getByRole('dialog');
  await modal.getByLabel('Name',{exact:true}).fill('Updated calls');
  await page.evaluate(()=>window.streamingTest.changeOther());
  await modal.getByRole('button',{name:'Save and reconnect'}).click();
  await expect(modal.locator('.ui-form-submit')).toBeHidden();
  await expect(modal.getByLabel('Name',{exact:true})).toHaveValue('Updated calls');
  await modal.getByRole('button',{name:'Aliases',exact:true}).click();
  await modal.getByRole('checkbox',{name:'Send Dispatch 1 to this destination',exact:true}).uncheck();
  await page.evaluate(()=>window.streamingTest.changeOther());
  await modal.getByRole('button',{name:'Next',exact:true}).click();
  await modal.getByRole('checkbox',{name:'Send Dispatch 52 to this destination',exact:true}).check();
  await modal.getByRole('button',{name:'Save assignments',exact:true}).click();
  await expect(modal.locator('.ui-form-submit')).toBeHidden();
  const writes=await page.evaluate(()=>window.streamingTest.calls.filter(([,options])=>options.method==='PUT'));
  expect(writes).toHaveLength(1);
  expect(writes[0][1].body.edit_revision).toBe('destination-edit-1');
});

test('same destination changes retain the draft and never refresh the baseline silently',async({page})=>{
  await install(page);await page.getByRole('button',{name:'County Calls',exact:true}).click();
  const modal=page.getByRole('dialog');
  await modal.getByLabel('Name',{exact:true}).fill('My pending name');
  await modal.getByLabel('API key',{exact:true}).fill('pending-fixture-credential');
  await page.evaluate(()=>window.streamingTest.changeDestination());
  await modal.getByRole('button',{name:'Status',exact:true}).click();
  await modal.getByRole('button',{name:'Refresh status',exact:true}).click();
  await modal.getByRole('button',{name:'Settings',exact:true}).click();
  await modal.getByRole('button',{name:'Save and reconnect'}).click();
  await expect(modal.getByRole('button',{name:'Reload current values'})).toBeVisible();
  await expect(modal.getByLabel('Name',{exact:true})).toHaveValue('My pending name');
  await expect(modal.getByLabel('API key',{exact:true})).toHaveValue('pending-fixture-credential');
  await modal.getByRole('button',{name:'Save and reconnect'}).click();
  const writes=await page.evaluate(()=>window.streamingTest.calls.filter(([,options])=>options.method==='PUT'));
  expect(writes).toHaveLength(2);
  expect(writes.every(([,options])=>options.body.edit_revision==='destination-edit-1')).toBe(true);
  await expect(modal.locator('.streaming-editor')).toHaveClass(/is-dirty/);
});

test('assignment paging preserves pending choices when destination routing changes',async({page})=>{
  await install(page);await page.getByRole('button',{name:'County Calls',exact:true}).click();
  const modal=page.getByRole('dialog');
  await modal.getByRole('button',{name:'Aliases',exact:true}).click();
  await modal.getByRole('checkbox',{name:'Send Dispatch 1 to this destination',exact:true}).uncheck();
  await page.evaluate(()=>window.streamingTest.changeAssignments());
  await modal.getByRole('button',{name:'Next',exact:true}).click();
  await expect(modal.getByRole('button',{name:'Reload current values'})).toBeVisible();
  await expect(modal.getByRole('checkbox',{name:'Send Dispatch 1 to this destination',exact:true})).not.toBeChecked();
  await modal.getByRole('button',{name:'Save assignments',exact:true}).click();
  const writes=await page.evaluate(()=>window.streamingTest.calls.filter(([path,options])=>path.endsWith('/aliases')&&options.method==='POST'));
  expect(writes).toHaveLength(1);
  expect(writes[0][1].body).toEqual({revision:'assignments-1',add:[],remove:[1]});
  await expect(modal.locator('.streaming-editor')).toHaveClass(/is-dirty/);
});

test('a pre-save alias response cannot replace assignments after a successful save',async({page})=>{
  await install(page);await page.getByRole('button',{name:'County Calls',exact:true}).click();
  const modal=page.getByRole('dialog');
  await modal.getByRole('button',{name:'Aliases',exact:true}).click();
  const dispatch=modal.getByRole('checkbox',{name:'Send Dispatch 1 to this destination',exact:true});
  await expect(dispatch).toBeChecked();
  await page.evaluate(()=>{
    window.streamingTest.holdAliasRead=true;
    window.streamingTest.releaseAliasReadOnSave=true;
  });
  await modal.getByRole('button',{name:'Search',exact:true}).click();
  await expect.poll(()=>page.evaluate(()=>typeof window.streamingTest.releaseAliasRead)).toBe('function');
  await dispatch.uncheck();
  await modal.getByRole('button',{name:'Save assignments',exact:true}).click();
  await expect(modal.locator('.ui-form-submit')).toBeHidden();
  await expect(dispatch).not.toBeChecked();
  await expect.poll(()=>page.evaluate(()=>window.streamingTest.calls.filter(([path,options])=>
    path.includes('/destination/aliases?')&&!options.method).length)).toBe(3);
  await modal.getByRole('checkbox',{name:'Send Dispatch 2 to this destination',exact:true}).check();
  await modal.getByRole('button',{name:'Save assignments',exact:true}).click();
  await expect(modal.locator('.ui-form-submit')).toBeHidden();
  const writes=await page.evaluate(()=>window.streamingTest.calls.filter(([path,options])=>path.endsWith('/aliases')&&options.method==='POST'));
  expect(writes).toHaveLength(2);
  expect(writes[0][1].body).toEqual({revision:'assignments-1',add:[],remove:[1]});
  expect(writes[1][1].body).toEqual({revision:'assignments-2',add:[2],remove:[]});
});

test('alias column header toggles only the visible page and tracks partial assignments',async({page})=>{
  await install(page);
  await page.getByRole('button',{name:'County Calls',exact:true}).click();
  const modal=page.getByRole('dialog');
  await modal.getByRole('button',{name:'Aliases',exact:true}).click();
  const header=modal.getByRole('checkbox',{name:'Send all aliases on this page to this destination'});
  const rows=modal.locator('table[data-table-type="streaming-aliases"] tbody input[type="checkbox"]');
  await expect(modal.locator('table[data-table-type="streaming-aliases"] thead th:first-child')).toHaveText('');
  await expect(modal.getByRole('button',{name:'Select visible'})).toHaveCount(0);
  await expect(modal.getByRole('button',{name:'Clear visible'})).toHaveCount(0);
  await expect(header).toHaveJSProperty('indeterminate',true);
  await page.evaluate(()=>window.streamingTest.rebuildAliases());
  await expect(header).toHaveJSProperty('indeterminate',true);
  await header.check();
  await expect(header).toBeChecked();
  await expect(rows).toHaveCount(50);
  expect(await rows.evaluateAll(controls=>controls.every(control=>control.checked))).toBe(true);
  await page.evaluate(()=>window.streamingTest.rebuildAliases());
  await expect(header).toBeChecked();
  await modal.getByRole('button',{name:'Next',exact:true}).click();
  await expect(rows).toHaveCount(10);
  await expect(header).toHaveJSProperty('indeterminate',true);
  await modal.getByRole('checkbox',{name:'Send Dispatch 51 to this destination'}).uncheck();
  await expect(header).not.toBeChecked();
  await expect(header).toHaveJSProperty('indeterminate',false);
  await modal.getByRole('button',{name:'Previous',exact:true}).click();
  await expect(header).toBeChecked();
  await header.uncheck();
  expect(await rows.evaluateAll(controls=>controls.every(control=>!control.checked))).toBe(true);
  await modal.getByRole('button',{name:'Save assignments'}).click();
  const writes=await page.evaluate(()=>window.streamingTest.calls.filter(([path,options])=>path.endsWith('/aliases')&&options.method==='POST'));
  expect(writes[0][1].body).toMatchObject({revision:'assignments-1',add:[]});
  expect([...writes[0][1].body.remove].sort((left,right)=>left-right)).toEqual([1,51]);
});

test('alias assignment workspace stays usable in dark mobile layout',async({page})=>{
  await page.setViewportSize({width:390,height:844});
  await install(page,'dark');
  await page.getByRole('button',{name:'County Calls',exact:true}).click();
  const modal=page.getByRole('dialog');
  await modal.getByRole('button',{name:'Aliases',exact:true}).click();
  await expect(modal.getByRole('button',{name:'Choose table columns',exact:true})).toBeVisible();
  await expect(modal.locator('.table-layout-menu')).toHaveCount(1);
  expect(await modal.evaluate(element=>element.scrollWidth)).toBeLessThanOrEqual(390);
  await expect(modal).toHaveScreenshot('streaming-aliases-dark-mobile.png');
});

test('new destinations enable assignment tabs only after saving',async({page})=>{
  await install(page,'light',true);
  await expect(page.getByText('No streaming destinations configured. Add a destination to get started.')).toBeVisible();
  await page.getByRole('button',{name:'Add destination',exact:true}).click();
  const modal=page.getByRole('dialog');await expect(modal.getByRole('button',{name:'Aliases',exact:true})).toBeDisabled();
  await modal.getByLabel('Name',{exact:true}).fill('New destination');
  await modal.getByRole('button',{name:'Add destination',exact:true}).click();
  await expect(modal.locator('.ui-form-submit')).toBeHidden();
  await expect(modal.getByText('Changes saved.',{exact:true})).toHaveCount(0);
  await expect(modal.getByRole('button',{name:'Aliases',exact:true})).toBeEnabled();
  await expect(modal.getByLabel('Provider',{exact:true})).toBeDisabled();
  const writes=await page.evaluate(()=>window.streamingTest.calls.filter(([path,options])=>path.endsWith('/streaming')&&options.method==='POST'));
  expect(writes).toHaveLength(1);
  expect(writes[0][1].body).not.toHaveProperty('revision');
  expect(writes[0][1].body).not.toHaveProperty('edit_revision');
});

for(const scenario of [
  {theme:'light',mobile:false,snapshot:'streaming-feeds-light-desktop.png'},
  {theme:'dark',mobile:true,snapshot:'streaming-feeds-dark-mobile.png'}
]) test(`Broadcastify feed cards stay usable in ${scenario.theme} ${scenario.mobile?'mobile':'desktop'} layout`,async({page})=>{
  await page.setViewportSize(scenario.mobile?{width:390,height:844}:{width:1440,height:1000});
  await install(page,scenario.theme);
  await page.evaluate(()=>{window.streamingTest.feedDelay=120;});
  await page.getByRole('button',{name:'Find Broadcastify feeds',exact:true}).click();
  const modal=page.getByRole('dialog');
  await expect(modal.getByText('Loading available Broadcastify feeds…',{exact:true})).toBeVisible();
  await expect(modal.locator('.streaming-feed-card')).toHaveCount(3);
  await expect(modal.locator('table[data-table-type="streaming-feeds"]')).toHaveCount(0);
  await expect(modal.getByRole('heading',{name:'Metro Public Safety Dispatch and Regional Interoperability Network',exact:true})).toBeVisible();
  await expect(modal.getByRole('button',{name:'Already added',exact:true})).toBeDisabled();
  const add = modal.getByRole('button',{name:'Add feed',exact:true}).first();
  await expect(add).toHaveClass(/ui-button-primary/);
  if(scenario.mobile) {
    const [controlWidth, contentWidth] = await add.evaluate(element => [
      element.getBoundingClientRect().width,
      element.parentElement.clientWidth - parseFloat(getComputedStyle(element.parentElement).paddingLeft) -
        parseFloat(getComputedStyle(element.parentElement).paddingRight)
    ]);
    expect(Math.abs(controlWidth-contentWidth)).toBeLessThanOrEqual(1);
  }
  expect(await modal.evaluate(element=>element.scrollWidth)).toBeLessThanOrEqual(scenario.mobile?390:900);
  await expect(modal).toHaveScreenshot(scenario.snapshot);
});

test('Broadcastify feed picker presents recoverable error and empty states',async({page})=>{
  await install(page);
  await page.evaluate(()=>{window.streamingTest.feedMode='error';});
  await page.getByRole('button',{name:'Find Broadcastify feeds',exact:true}).click();
  const modal=page.getByRole('dialog');
  await expect(modal.getByText('Available feeds could not be loaded',{exact:true})).toBeVisible();
  await expect(modal.getByRole('button',{name:'Connect account',exact:true})).toBeVisible();
  await expect(modal.getByRole('link')).toHaveCount(0);
  await page.evaluate(()=>{window.streamingTest.feedMode='empty';});
  await modal.getByRole('button',{name:'Try again',exact:true}).click();
  await expect(modal.getByRole('heading',{name:'No assigned feeds',exact:true})).toBeVisible();
  await expect(modal.getByText('No Broadcastify feeds are assigned to the saved RadioReference account.',{exact:true})).toBeVisible();
});

test('available Broadcastify feed opens its destination editor',async({page})=>{
  await install(page);
  await page.getByRole('button',{name:'Find Broadcastify feeds',exact:true}).click();
  let modal=page.getByRole('dialog');
  await expect(modal.locator('.streaming-feed-card')).toHaveCount(3);
  await page.evaluate(()=>{window.streamingTest.holdWrite=true;});
  await modal.getByRole('button',{name:'Add feed',exact:true}).first().click();
  await expect(modal.getByText('Adding feed…',{exact:true})).toBeVisible();
  for(const control of await modal.getByRole('button').all()) await expect(control).toBeDisabled();
  await page.keyboard.press('Escape');
  await expect(modal.getByRole('heading',{name:'Available Broadcastify feeds',exact:true})).toBeVisible();
  await page.evaluate(()=>{window.streamingTest.holdWrite=false;window.streamingTest.releaseWrite();});
  modal=page.getByRole('dialog');
  await expect(modal.getByRole('heading',{name:'Streaming destination',exact:true})).toBeVisible();
  await expect(modal.getByLabel('Name',{exact:true})).toHaveValue('County Calls');
  const writes=await page.evaluate(()=>window.streamingTest.calls.filter(([path,options])=>path.endsWith('/feeds')&&options.method==='POST'));
  expect(writes).toHaveLength(1);
  expect(writes[0][1].body).toEqual({feed_id:1042});
});

for(const theme of ['light']) test(`shared labeled mobile table ${theme}`,async({page})=>{
  await page.setViewportSize({width:390,height:844});
  await page.goto(`/design-system.html?theme=${theme}&view=mobile-table`);
  await expect(page.locator('.visual-mobile-table-example')).toHaveScreenshot(`shared-mobile-table-${theme}.png`);
});


test('signed-out Broadcastify discovery resumes once after shared sign-in without leaving the feed picker', async ({ page }) => {
  await install(page);
  await page.evaluate(() => { window.streamingTest.accountState = 'NOT_CONFIGURED'; });
  const originalUrl = page.url();
  await page.getByRole('button', { name: 'Find Broadcastify feeds', exact: true }).click();
  const connection = page.getByRole('dialog', { name: 'Connect RadioReference', exact: true });
  await expect(connection).toBeVisible();
  expect(await page.evaluate(() => window.streamingTest.calls.filter(([path]) => path.endsWith('/feeds/refresh')).length)).toBe(0);
  await connection.getByRole('textbox', { name: 'Username', exact: true }).fill('fixture-user');
  await connection.getByLabel('Password', { exact: true }).fill('synthetic-password');
  await connection.getByRole('button', { name: 'Connect account', exact: true }).click();
  await expect(connection).toHaveCount(0);
  const picker = page.getByRole('dialog', { name: 'Available Broadcastify feeds', exact: true });
  await expect(picker.locator('.streaming-feed-card')).toHaveCount(3);
  expect(page.url()).toBe(originalUrl);
  expect(await page.evaluate(() => ({ connections: window.streamingTest.connectCount,
    refreshes: window.streamingTest.calls.filter(([path]) => path.endsWith('/feeds/refresh')).length })))
    .toEqual({ connections: 1, refreshes: 1 });
});

test('Cancel from Broadcastify sign-in preserves the picker and reconnect retries in place', async ({ page }) => {
  await install(page);
  await page.evaluate(() => { window.streamingTest.accountState = 'EXPIRED_PREMIUM'; });
  await page.getByRole('button', { name: 'Find Broadcastify feeds', exact: true }).click();
  const connection = page.getByRole('dialog', { name: 'Connect RadioReference', exact: true });
  await connection.getByRole('button', { name: 'Cancel', exact: true }).click();
  const picker = page.getByRole('dialog', { name: 'Available Broadcastify feeds', exact: true });
  await expect(picker.getByRole('button', { name: 'Connect account', exact: true })).toBeVisible();
  await expect(picker.locator('.ui-feedback-error')).toHaveCount(0);
  expect(await page.evaluate(() => window.streamingTest.calls.filter(([path]) => path.endsWith('/feeds/refresh')).length)).toBe(0);
  await picker.getByRole('button', { name: 'Connect account', exact: true }).click();
  await connection.getByRole('textbox', { name: 'Username', exact: true }).fill('fixture-user');
  await connection.getByLabel('Password', { exact: true }).fill('synthetic-password');
  await connection.getByRole('button', { name: 'Connect account', exact: true }).click();
  await expect(picker.locator('.streaming-feed-card')).toHaveCount(3);
});

test('failed Broadcastify refresh can reconnect and load feeds without navigation', async ({ page }) => {
  await install(page);
  await page.evaluate(() => { window.streamingTest.feedMode = 'error'; });
  await page.getByRole('button', { name: 'Find Broadcastify feeds', exact: true }).click();
  const picker = page.getByRole('dialog', { name: 'Available Broadcastify feeds', exact: true });
  await picker.getByRole('button', { name: 'Connect account', exact: true }).click();
  const connection = page.getByRole('dialog', { name: 'Connect RadioReference', exact: true });
  await connection.getByRole('textbox', { name: 'Username', exact: true }).fill('fixture-user');
  await connection.getByLabel('Password', { exact: true }).fill('synthetic-password');
  await page.evaluate(() => { window.streamingTest.feedMode = 'ready'; });
  await connection.getByRole('button', { name: 'Connect account', exact: true }).click();
  await expect(picker.locator('.streaming-feed-card')).toHaveCount(3);
  expect(await page.evaluate(() => window.streamingTest.calls.filter(([path]) => path.endsWith('/feeds/refresh')).length)).toBe(2);
});

test('Broadcastify discovery respects account access while manual destination setup remains available', async ({ page }) => {
  await install(page, 'light', false, false);
  await expect(page.getByRole('button', { name: 'Find Broadcastify feeds', exact: true })).toBeDisabled();
  await page.getByRole('button', { name: 'Add destination', exact: true }).click();
  await expect(page.getByRole('dialog', { name: 'Add streaming destination', exact: true })).toBeVisible();
  expect(await page.evaluate(() => window.streamingTest.calls.filter(([path]) => path.includes('/radioreference')).length)).toBe(0);
});


test('leaving Streaming aborts the shared sign-in and ignores a late connection result', async ({ page }) => {
  await install(page);
  await page.evaluate(() => {
    window.streamingTest.accountState = 'NOT_CONFIGURED';
    window.streamingTest.holdConnect = true;
  });
  await page.getByRole('button', { name: 'Find Broadcastify feeds', exact: true }).click();
  const connection = page.getByRole('dialog', { name: 'Connect RadioReference', exact: true });
  await connection.getByRole('textbox', { name: 'Username', exact: true }).fill('fixture-user');
  await connection.getByLabel('Password', { exact: true }).fill('synthetic-password');
  await connection.getByRole('button', { name: 'Connect account', exact: true }).click();
  await expect(connection).toHaveAttribute('aria-busy', 'true');
  await page.evaluate(() => { window.streamingTest.abort(); window.streamingTest.releaseConnect(); });
  await expect(page.getByRole('dialog')).toHaveCount(0);
  expect(await page.evaluate(() => window.streamingTest.calls.filter(([path]) => path.endsWith('/feeds/refresh')).length)).toBe(0);
});

test('500 explicit streaming assignments remain responsive across pages and a held save', async ({ page }) => {
  await install(page, 'light', false, true, 500);
  await page.getByRole('button', { name: 'County Calls', exact: true }).click();
  const modal = page.getByRole('dialog');
  await modal.getByRole('button', { name: 'Aliases', exact: true }).click();
  await page.evaluate(() => {
    const probe = { last: performance.now(), maximum: 0, ticks: 0 };
    probe.timer = setInterval(() => {
      const now = performance.now(); probe.maximum = Math.max(probe.maximum, now - probe.last);
      probe.last = now; probe.ticks++;
    }, 10);
    window.assignmentHeartbeat = probe;
  });
  for (let index = 0; index < 10; index++) {
    await expect(modal.locator('table[data-table-type="streaming-aliases"] tbody tr')).toHaveCount(50);
    await modal.getByRole('checkbox', { name: 'Send all aliases on this page to this destination', exact: true }).check();
    // Flip the two previously assigned rows as well: 498 additions and two removals.
    if (index < 2) await modal.getByRole('checkbox', {
      name: `Send Dispatch ${index * 50 + 1} to this destination`, exact: true }).uncheck();
    if (index < 9) await modal.getByRole('button', { name: 'Next', exact: true }).click();
  }
  await page.evaluate(() => { window.streamingTest.holdWrite = true; });
  await modal.getByRole('button', { name: 'Save assignments', exact: true }).click();
  await expect(modal.locator('.ui-feedback-loading')).toHaveText('Saving alias assignments…');
  await expect(modal).toHaveAttribute('aria-busy', 'true');
  await page.keyboard.press('Escape');
  await expect(modal).toBeVisible();
  await expect(modal.getByRole('button', { name: 'Save assignments', exact: true })).toBeDisabled();
  await expect.poll(() => page.evaluate(() => window.assignmentHeartbeat.ticks)).toBeGreaterThan(5);
  const result = await page.evaluate(() => {
    const probe = window.assignmentHeartbeat; clearInterval(probe.timer);
    const writes = window.streamingTest.calls.filter(([path, options]) => path.endsWith('/aliases') && options.method === 'POST');
    return { maximum: Math.max(probe.maximum, performance.now() - probe.last), writes };
  });
  expect(result.maximum).toBeLessThan(250);
  expect(result.writes).toHaveLength(1);
  expect(result.writes[0][1].body.add).toHaveLength(498);
  expect(result.writes[0][1].body.remove).toEqual([1, 51]);
  await test.info().attach('browser-heartbeat', { body: JSON.stringify({ maximum: result.maximum }),
    contentType: 'application/json' });
  await page.evaluate(() => { window.streamingTest.releaseWrite(); });
  await expect(modal.locator('.ui-feedback-loading')).toHaveCount(0);
  await expect(modal.getByRole('button', { name: 'Save assignments', exact: true })).toBeHidden();
});
