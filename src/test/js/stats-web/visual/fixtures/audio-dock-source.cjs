'use strict';

async function dockSourceControl(dock) {
  const picker = dock.getByRole('combobox', { name: 'Audio source', exact: true });
  await picker.waitFor({ state: 'visible' });
  return picker;
}

async function selectDockSource(dock, source) {
  const picker = await dockSourceControl(dock);
  await picker.selectOption(source);
}

module.exports = { dockSourceControl, selectDockSource };
