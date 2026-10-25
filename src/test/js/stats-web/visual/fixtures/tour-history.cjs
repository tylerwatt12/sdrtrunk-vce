'use strict';

// A healthy signed-in account keeps the same completed versions across reloads.
function tourHistoryFixture(dismissed) {
  const history = { version: 1, dismissed: { ...dismissed } };
  return async (route) => {
    const method = route.request().method();
    if (!['GET', 'PUT'].includes(method)) throw new Error('Unexpected tour-history fixture method');
    if (method === 'PUT') {
      const update = route.request().postDataJSON();
      if (update.version !== 1 || !update.dismissed || Array.isArray(update.dismissed))
        throw new Error('Invalid tour-history fixture write');
      for (const [id, version] of Object.entries(update.dismissed)) {
        if (!Number.isInteger(version) || version < 1) throw new Error('Invalid tour-history fixture version');
        history.dismissed[id] = Math.max(history.dismissed[id] || 0, version);
      }
    }
    await route.fulfill({ json: { data: history } });
  };
}

module.exports = { tourHistoryFixture };
