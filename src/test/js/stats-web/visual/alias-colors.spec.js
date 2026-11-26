const { test, expect } = require('@playwright/test');
const path = require('node:path');
const { installSiteStyleApplication, channelId, systemKey, groupKey, radioKey } = require('./fixtures/site-style-app.cjs');
const { openAudioApp, liveCall, recording } = require('./fixtures/audio-dock-app.cjs');
const { expectNoHorizontalOverflow } = require('./fixtures/metric-geometry.cjs');
const { expectContrast } = require('./fixtures/contrast-audit.cjs');
const red = -65536, blue = -16776961;
const capture = async (page,name) => {
  if (process.env.WEB_REVIEW_DIR) await page.screenshot({path:path.join(process.env.WEB_REVIEW_DIR,`${name}.png`)});
};
const reference = (id,name,color) => ({ alias_id:id,alias_list_id:1,name,color });
const base = { key:'color-call',configuration_id:channelId,lcn:2,frequency_hz:851037500,
  protocol:'P25',status:'CALL',tags:['VOICE'],role:'TRAFFIC',activation_order:2,source_id:'30914',source_form:'RADIO',
  target_id:'1201',target_form:'TALKGROUP',source_alias:'Engine 4',target_alias:'Fire Dispatch',
  source_aliases:[reference(102,'Engine 4',red)],target_aliases:[reference(101,'Fire Dispatch',red)],
  source_entity_ref:{kind:'radio',radio_system_key:systemKey,identity_key:radioKey},
  target_entity_ref:{kind:'talkgroup',radio_system_key:systemKey,identity_key:groupKey} };

for (const theme of ['light','dark']) for (const width of [1280,390]) {
  test(`Live alias backgrounds preserve identities and refresh color at rest ${theme} ${width}`,async({page})=>{
    await page.setViewportSize({width,height:900});
    const table={ table_id:channelId,configuration_id:channelId,title:'Sample aliases',channel_running:true,rows:[base] };
    const fixture=await installSiteStyleApplication(page,theme,{revision:1,tables:[table]}, {
      configurePreferences(p) {p.presentation.source_name_display='both';}
    });
    await page.goto(`/app.html?view=live&channel=${channelId}`);
    const row=page.locator('tr[data-id="color-call"]');
    const target=row.locator('[data-column="target"] .ui-alias-color');
    await expect(target).toHaveAttribute('data-alias-family','0');
    await expect(target).toContainText('Fire Dispatch');
    await expect(target).toContainText('1201');
    await expect(target.locator('a').first()).toHaveAttribute('href',/view=group-identity/);
    await capture(page,`implemented-alias-live-${theme}-${width}`);
    const height=(await row.boundingBox()).height;
    const status=await row.locator('[data-column="status"]').evaluate(el=>el.textContent);
    let revision=1;
    const update=async(values)=>page.evaluate(({table,revision,values})=>window.fixtureSendLiveSnapshot({revision,
      tables:[{...table,rows:[{...table.rows[0],...values}]}]}),{table,revision:++revision,values});
    await update({target_aliases:[reference(101,'Fire Dispatch',blue)]});
    await expect(target).toHaveAttribute('data-alias-family','8');
    expect((await row.boundingBox()).height).toBe(height);
    expect(await row.locator('[data-column="status"]').textContent()).toBe(status);
    await expectContrast(page,{reportName:`alias-live-${theme}-${width}`});
    await target.locator('a').first().focus();
    await expectContrast(page,{reportName:`alias-live-focus-${theme}-${width}`});
    await target.locator('a').first().hover();
    await expectContrast(page,{reportName:`alias-live-hover-${theme}-${width}`});
    await update({target_aliases:[reference(101,'Fire Dispatch',0)]});
    await expect(row.locator('[data-column="target"] .ui-alias-color')).toHaveCount(0);
    await update({target_aliases:[reference(101,'Fire Dispatch',red),reference(103,'Mutual aid',blue)],
      source_aliases:[reference(102,'Engine 4',red),reference(104,'Portable 4',blue)],talker_alias:'Operator 4'});
    await expect(row.locator('[data-column="target"] [data-alias-family="0"]')).toContainText('Fire Dispatch');
    await expect(row.locator('[data-column="target"] [data-alias-family="8"]')).toContainText('Mutual aid');
    await expect(row.locator('[data-column="source"]')).toContainText('Operator 4');
    await expect(row.locator('[data-column="source"] .ui-alias-color')).toHaveCount(2);
    await expectNoHorizontalOverflow(page);
    expect(fixture.pageErrors).toEqual([]);expect(fixture.unexpected).toEqual([]);
  });

  test(`Detail aliases are colored without changing directory rendering ${theme} ${width}`,async({page})=>{
    await page.setViewportSize({width,height:900});
    const fixture=await installSiteStyleApplication(page,theme,undefined,{decorateResponse(data) {
      if (data?.alias_name) return {...data,alias_color:blue};
      return data;
    }});
    for (const [view,key] of [['group-identity',groupKey],['radio',radioKey]]) {
      await page.goto(`/app.html?view=${view}&radio_system_key=${systemKey}&identity_key=${key}`);
      await expect(page.locator('h1 .ui-alias-color')).toHaveAttribute('data-alias-family','8');
      await expect(page.locator('.ui-fact .ui-alias-color')).toHaveAttribute('data-alias-family','8');
      await expectContrast(page,{reportName:`alias-${view}-${theme}-${width}`});
      await capture(page,`implemented-alias-${view}-${theme}-${width}`);
      await expectNoHorizontalOverflow(page);
    }
    await page.goto('/app.html?view=dashboard');
    await expect(page.locator('#content .ui-alias-color')).toHaveCount(0);
    expect(fixture.pageErrors).toEqual([]);
  });

  test(`Scanner and each player size share alias backgrounds ${theme} ${width}`,async({page})=>{
    await page.setViewportSize({width,height:900});
    await openAudioApp(page,{theme,view:'scanner',liveCalls:[{...liveCall,source_alias_color:red,target_alias_color:blue}]});
    await page.locator('#scanner-play').click();
    await expect(page.locator('.scanner-call-title .ui-alias-color')).toHaveAttribute('data-alias-family','8');
    await expect(page.locator('.scanner-source-fields .ui-alias-color')).toHaveAttribute('data-alias-family','0');
    await expectContrast(page,{reportName:`alias-scanner-${theme}-${width}`});
    await capture(page,`implemented-alias-scanner-${theme}-${width}`);
    await page.getByRole('link',{name:'VCE home',exact:true}).click();
    const player=page.locator('#audio-dock');
    await expect(player.locator('.audio-dock-compact-copy > strong')).toHaveAttribute('data-alias-family','8');
    await expect(player.locator('.audio-dock-compact-copy > span:not(.ui-audio-spectrum) .ui-alias-color')).toHaveAttribute('data-alias-family','0');
    for (const size of ['full','collapsed','minimal']) {
      const handle=player.locator('#audio-dock-size-handle:visible, #audio-dock-slim-expand:visible');
      if (size==='full') await handle.press('End');
      else {
        await handle.press('Home');
        if(size==='minimal') await player.locator('#audio-dock-slim-expand:visible').press('ArrowUp');
      }
      await expect(player).toHaveAttribute('data-state',size);
      await expectContrast(page,{reportName:`alias-player-${size}-${theme}-${width}`});
      if(size==='full') await capture(page,`implemented-alias-player-${theme}-${width}`);
    }
    await expectNoHorizontalOverflow(page);
  });
}

for (const theme of ['light','dark']) {
  test(`Classic keeps alias backgrounds on phone rows ${theme}`,async({page})=>{
    await page.setViewportSize({width:390,height:844});
    await installSiteStyleApplication(page,theme,{revision:1,tables:[{table_id:channelId,
      configuration_id:channelId,title:'Sample aliases',channel_running:true,rows:[base]}]}, {
      configurePreferences(p) {p.live_workspace.active_channel_table_id='classic';}
    });
    await page.goto('/app.html?view=live');
    await expect(page.locator('#live-selected-view-title')).toHaveText('Classic');
    await expect(page.locator('[data-column="target"] .ui-alias-color')).toHaveAttribute('data-alias-family','0');
    await expectContrast(page,{reportName:`alias-classic-${theme}`});
  });

  test(`Recorded audio uses current alias colors without coloring the recording catalog ${theme}`,async({page})=>{
    await page.setViewportSize({width:390,height:844});
    await openAudioApp(page,{theme,calls:[{...recording,source_alias_color:red,group_alias_color:blue}],
      feedCallsEnabled:false});
    await page.locator('.recordings-call-info').first().click();
    await page.getByRole('dialog',{name:'Call actions',exact:true}).getByRole('button',{name:/^Play once/}).click();
    const player=page.locator('#audio-dock');
    await expect(player).toHaveAttribute('data-source','recordings');
    await expect(player.locator('.audio-dock-compact-copy > strong')).toHaveAttribute('data-alias-family','8');
    await expect(page.locator('#content .ui-alias-color')).toHaveCount(0);
    await expectContrast(page,{reportName:`alias-recorded-player-${theme}`});
  });
}

for (const theme of ['light','dark']) test(`All twelve alias hue families remain readable with custom appearances ${theme}`,async({page})=>{
  await page.goto(`/design-system.html?view=alias-colors&theme=${theme}`);
  const palette=page.locator('[data-alias-palette]');
  await expect(palette.locator('.ui-alias-color')).toHaveCount(12);
  const colors=await palette.locator('.ui-alias-color').evaluateAll(elements=>elements.map(el=>({
    family:el.dataset.aliasFamily,ink:getComputedStyle(el).color,fill:getComputedStyle(el).backgroundColor
  })));
  for (let hue=0;hue<360;hue++) {
    const next=await page.evaluate(async hue=>{
      const {applyThemeHue}=await import('/assets/core/theme.js?v=2');applyThemeHue(hue);
      return [...document.querySelectorAll('[data-alias-palette] .ui-alias-color')].map(el=>({
        family:el.dataset.aliasFamily,ink:getComputedStyle(el).color,fill:getComputedStyle(el).backgroundColor
      }));
    },hue);
    expect(next).toEqual(colors);
  }
  await expectContrast(page,{reportName:`alias-palette-${theme}`});
  await expect(page.locator('.visual-alias-colors-example')).toHaveScreenshot(`alias-palette-${theme}.png`);
});
