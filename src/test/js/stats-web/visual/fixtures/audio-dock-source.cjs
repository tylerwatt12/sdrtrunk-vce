'use strict';

async function dockSourceControl(dock) {
  const picker = dock.getByRole('combobox', { name: 'Audio source', exact: true });
  return await picker.isVisible() ? picker : dock.getByRole('group', { name: 'Audio source', exact: true });
}

async function selectDockSource(dock, source) {
  const picker = dock.getByRole('combobox', { name: 'Audio source', exact: true });
  if (await picker.isVisible()) await picker.selectOption(source);
  else await dock.getByRole('button', { name: source === 'live' ? 'Live' : 'Recordings', exact: true }).click();
}

module.exports = { dockSourceControl, selectDockSource };
