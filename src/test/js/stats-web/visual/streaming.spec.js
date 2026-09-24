const { expect, test } = require('@playwright/test');
const { readFileSync } = require('node:fs');
const { resolve } = require('node:path');
const app = readFileSync(resolve(__dirname, '../../../../..', 'stats-web/assets/app.js'), 'utf8');
const helpersSource = app.slice(app.indexOf('function closeReadOnlyModal('), app.indexOf('function statsLoggingState(')) +
  app.slice(app.indexOf('function uiToggle('), app.indexOf('function uiPill(')) +
  app.slice(app.indexOf('function uiSegmentedControl('), app.indexOf('function channelSummaryCards('));

async function install(page, theme = 'light', empty = false) {
  await page.goto(`/design-system.html?theme=${theme}`);
  await page.evaluate(async ({helpersSource, theme, empty}) => {
    const { createStreamingWorkspace } = await import('/assets/features/streaming.js');
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
    const iconGlyph = () => {
      const icon = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
      icon.setAttribute('viewBox', '0 0 24 24');
      icon.innerHTML = '<circle cx="12" cy="12" r="7"></circle>';
      return icon;
    };
    const iconButton = (_iconId, label, className = 'ui-button ui-button-secondary ui-icon-button') => {
      const control = node('button', className);
      control.type = 'button';
      control.setAttribute('aria-label', label);
      control.title = label;
      control.append(iconGlyph());
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
        const layoutMenu = node('div', 'table-layout-menu table-layout-menu-inline');
        const columnsButton = iconButton('icon-columns', 'Choose table columns',
          'ui-button ui-button-secondary ui-icon-button table-layout-trigger');
        layoutMenu.append(columnsButton);
        options.layoutMenuHost.append(layoutMenu);
        if (options.controller) options.controller.layoutMenuCleanup = () => layoutMenu.remove();
      }
      wrapper.append(element);
      return wrapper;
    };

    const shared = new Function('node', 'valueNode', 'iconButton', `let activeReadOnlyModal = null; ${helpersSource}
      return { openReadOnlyModal, uiToggleField, uiSegmentedControl };`)(node,
        value => value instanceof Node ? value : document.createTextNode(String(value)), iconButton);
    const fields = [
      {key:'name',label:'Name',type:'text',maximum:255},
      {key:'enabled',label:'Enabled',type:'boolean'},
      {key:'host',label:'Server address',type:'text',maximum:1024},
      {key:'api_key',label:'API key',type:'password',maximum:1024},
      {key:'system_id',label:'System ID',type:'number',minimum:1,maximum:2147483647},
      {key:'maximum_recording_age',label:'Maximum recording age (ms)',type:'number',minimum:0,maximum:86400000,advanced:true}
    ];
    let revision='1:1:1';
    let definition={configuration_id:'destination',provider:'BROADCASTIFY_CALL',settings:{name:'County Calls',enabled:true,
      host:'https://example.com/calls',system_id:42,maximum_recording_age:600000},configured_credentials:['api_key']};
    const status={configuration_id:'destination',name:'County Calls',provider:'BROADCASTIFY_CALL',provider_label:'Broadcastify Calls',
      enabled:true,state:'CONNECTED',state_label:'Connected',queued:3,sent:128,aged_off:0,errors:0,last_error:null,attention:false};
    let assignments=new Set([1,51]);
    const aliases=Array.from({length:60},(_,i)=>({id:i+1,name:`Dispatch ${i+1}`,identifier:String(100+i),alias_list_id:7,alias_list_name:'County Public Safety'}));
    const calls=[];
    window.streamingTest={calls,fail:false,stale:false,feedMode:'ready',feedDelay:0,feeds:[
      {id:1042,name:'Metro Public Safety Dispatch and Regional Interoperability Network',configured:false},
      {id:2087,name:'County Fire and EMS',configured:true},
      {id:3194,name:'Citywide Events',configured:false}
    ]};
    const requestJson=async (path, options={}) => {
      calls.push([path, options]);
      const url=new URL(path,location.href); const suffix=url.pathname.replace('/api/v1/admin/streaming','');
      if(window.streamingTest.fail) throw new Error('Connection unavailable');
      if(options.method && window.streamingTest.stale) throw Object.assign(new Error('Configuration changed. Reload this editor before saving'),{code:'stale_revision'});
      if(suffix==='/feeds/refresh'&&options.method==='POST') {
        if(window.streamingTest.feedDelay) await new Promise(resolve=>setTimeout(resolve,window.streamingTest.feedDelay));
        if(window.streamingTest.feedMode==='error') throw new Error('Available feeds could not be loaded');
        return {items:window.streamingTest.feedMode==='empty'?[]:structuredClone(window.streamingTest.feeds)};
      }
      if(suffix==='/feeds'&&options.method==='POST') return {revision:'2:1:1',configuration_id:'destination'};
      if(suffix==='/options') return {revision,providers:[{id:'BROADCASTIFY_CALL',label:'Broadcastify Calls',connection_test:true,fields}],sites:[]};
      if(suffix.startsWith('/templates/')) return {...structuredClone(definition),settings:{...definition.settings,name:'',enabled:false},configured_credentials:[]};
      if(suffix==='/test') return {success:true,message:'Connection accepted'};
      if(suffix==='/destination/aliases') {
        if(options.method==='POST') { options.body.add.forEach(id=>assignments.add(id)); options.body.remove.forEach(id=>assignments.delete(id)); revision='2:2:2'; return {revision,configuration_id:'destination'}; }
        const filtered=aliases.filter(row=>(!url.searchParams.get('q')||row.name.includes(url.searchParams.get('q')))&&(url.searchParams.get('assigned')!=='true'||assignments.has(row.id)));
        const offset=Number(url.searchParams.get('offset'))||0;
        return {revision,total:filtered.length,limit:50,items:filtered.slice(offset,offset+50).map(row=>({...row,assigned:assignments.has(row.id)}))};
      }
      if(options.method==='POST'||options.method==='PUT') {
        definition.settings={...definition.settings,...options.body.settings}; revision='2:1:1'; empty=false;
        return {revision,configuration_id:'destination'};
      }
      if(suffix==='/destination') return {revision,destination:structuredClone(definition),status,references:{aliases:2,alias_lists:[]}};
      return {revision,destinations:empty?[]:[status,{...status,configuration_id:'other',name:'Regional Archive',provider_label:'RadioResolve',state:'ERROR',state_label:'Network unavailable',queued:12,sent:842,errors:1,last_error:'Network unavailable',attention:true}]};
    };
    const shell=node('main','content');
    const header=node('header','page-header ui-page-header');
    const labels=node('div'); labels.append(node('h1','page-title','Streaming'),node('div','page-subtitle','Manage destinations and monitor delivery')); header.append(labels);
    const abort=new AbortController();
    const workspace=createStreamingWorkspace({node,formField,uiSelectFrame,uiStatus,table,...shared,requestJson,
      modalFooter:(...controls)=>{const footer=node('footer','alias-modal-footer ui-action-row');footer.append(...controls);return footer;},
      formatNumber:String,href:(view,params)=>`/?${new URLSearchParams({view,...params})}`,signal:abort.signal});
    shell.append(header,workspace.element);document.body.append(shell);
  }, {helpersSource,theme,empty});
  await expect(page.getByRole('button',{name:'Add destination',exact:true})).toBeVisible();
}

for(const theme of ['light','dark']) for(const mobile of [false,true]) {
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
  await expect(modal.getByText('Changes saved.',{exact:true})).toBeVisible();
  const writes=await page.evaluate(()=>window.streamingTest.calls.filter(([,options])=>options.method==='PUT'));
  expect(writes.at(-1)[1].body.settings).toEqual({name:'Renamed calls'});
  await expect(modal.getByLabel('Provider',{exact:true})).toBeDisabled();
});

test('alias changes survive paging and save only explicit selections',async({page})=>{
  await install(page);await page.getByRole('button',{name:'County Calls',exact:true}).click();
  const modal=page.getByRole('dialog');await modal.getByRole('button',{name:'Aliases',exact:true}).click();
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
  await expect(modal.getByText('Changes saved.',{exact:true})).toBeVisible();
  const writes=await page.evaluate(()=>window.streamingTest.calls.filter(([path,options])=>path.endsWith('/aliases')&&options.method==='POST'));
  expect(writes[0][1].body).toEqual({revision:'1:1:1',add:[52],remove:[1]});
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
  await expect(modal.getByText('Changes saved.',{exact:true})).toBeVisible();
  await expect(modal.getByRole('button',{name:'Aliases',exact:true})).toBeEnabled();
  await expect(modal.getByLabel('Provider',{exact:true})).toBeDisabled();
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
  expect(await modal.evaluate(element=>element.scrollWidth)).toBeLessThanOrEqual(scenario.mobile?390:900);
  await expect(modal).toHaveScreenshot(scenario.snapshot);
});

test('Broadcastify feed picker presents recoverable error and empty states',async({page})=>{
  await install(page);
  await page.evaluate(()=>{window.streamingTest.feedMode='error';});
  await page.getByRole('button',{name:'Find Broadcastify feeds',exact:true}).click();
  const modal=page.getByRole('dialog');
  await expect(modal.getByText('Available feeds could not be loaded',{exact:true})).toBeVisible();
  await expect(modal.getByRole('link',{name:'Open RadioReference settings',exact:true})).toHaveAttribute('href','/?view=radioreference');
  await page.evaluate(()=>{window.streamingTest.feedMode='empty';});
  await modal.getByRole('button',{name:'Try again',exact:true}).click();
  await expect(modal.getByRole('heading',{name:'No assigned feeds',exact:true})).toBeVisible();
  await expect(modal.getByText('No Broadcastify feeds are assigned to the saved RadioReference account.',{exact:true})).toBeVisible();
});

test('available Broadcastify feed opens its destination editor',async({page})=>{
  await install(page);
  await page.getByRole('button',{name:'Find Broadcastify feeds',exact:true}).click();
  let modal=page.getByRole('dialog');
  await modal.getByRole('button',{name:'Add feed',exact:true}).first().click();
  modal=page.getByRole('dialog');
  await expect(modal.getByRole('heading',{name:'Streaming destination',exact:true})).toBeVisible();
  await expect(modal.getByLabel('Name',{exact:true})).toHaveValue('County Calls');
  const writes=await page.evaluate(()=>window.streamingTest.calls.filter(([path,options])=>path.endsWith('/feeds')&&options.method==='POST'));
  expect(writes).toHaveLength(1);
  expect(writes[0][1].body).toEqual({feed_id:1042,revision:'1:1:1'});
});

for(const theme of ['light','dark']) test(`shared labeled mobile table ${theme}`,async({page})=>{
  await page.setViewportSize({width:390,height:844});
  await page.goto(`/design-system.html?theme=${theme}&view=mobile-table`);
  await expect(page.locator('.visual-mobile-table-example')).toHaveScreenshot(`shared-mobile-table-${theme}.png`);
});
