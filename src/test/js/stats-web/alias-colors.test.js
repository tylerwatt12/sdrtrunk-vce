'use strict';
const assert = require('node:assert/strict');
const { aliasColorFamily, applyAliasColor, commonAliasColor, callAliasColor } =
  require('../../../../stats-web/assets/core/alias-colors.js');

for (const value of [null, undefined, '', '16711680', NaN, Infinity, 0.5, 2147483648, -2147483649,
  0, -16777216, -1, 0x808080, 0x7f8081]) assert.equal(aliasColorFamily(value), null);
assert.equal(aliasColorFamily(-65536), 0);
assert.equal(aliasColorFamily(0xff0001), 0, 'Hue wraps through red');
assert.equal(aliasColorFamily(0x00ff00), 4);
assert.equal(aliasColorFamily(0x0000ff), 8);
const families = new Set();
for (let hue = 0; hue < 360; hue++) {
  const sector=hue/60, x=1-Math.abs(sector%2-1);
  const rgb=[[1,x,0],[x,1,0],[0,1,x],[0,x,1],[x,0,1],[1,0,x]][Math.floor(sector)];
  const color=rgb.reduce((value,part)=>(value<<8)|Math.round(part*255),0);
  const family=aliasColorFamily(color);
  const distance = Math.abs(family * 30 - hue);
  assert.ok(Math.min(distance, 360 - distance) <= 16, 'Nearest hue allows RGB rounding at palette boundaries');
  families.add(family);
}
assert.equal(families.size,12);
const classes = new Set();
const element = { dataset:{}, classList:{ toggle(name,on) { if(on)classes.add(name);else classes.delete(name); } } };
applyAliasColor(element,-65536);assert.equal(element.dataset.aliasFamily,'0');assert.ok(classes.has('ui-alias-color'));
applyAliasColor(element,0);assert.equal(element.dataset.aliasFamily,undefined);assert.equal(classes.size,0);
assert.equal(commonAliasColor([{color:-65536},{color:0xff0100}]),-65536);
assert.equal(commonAliasColor([{color:-65536},{color:255}]),null);
assert.equal(commonAliasColor([{color:-65536},{}]),null);
assert.equal(callAliasColor({source_alias_color:0,radio_alias_color:-65536},'source'),0);
assert.equal(callAliasColor({target_alias_color:null,talkgroup_alias_color:-65536},'target'),null);
assert.equal(callAliasColor({talkgroup_alias_color:-65536},'target'),-65536);
console.log('Alias hue grouping, neutral colors, stale-paint clearing and metadata precedence passed.');
