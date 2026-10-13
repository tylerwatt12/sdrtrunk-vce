// Classic worker script: only one bounded anonymous Direct device credential is retained.
(function(scope) {
  const DATABASE = 'vce-alert-worker-v1';
  const STORE = 'device';
  function normalized(value) {
    if (!value || typeof value.device_id !== 'string' || !value.device_id || value.device_id.length > 80 ||
      typeof value.manage_token !== 'string' || !/^[A-Za-z0-9_-]{16,512}$/.test(value.manage_token)) return null;
    return { device_id: value.device_id, manage_token: value.manage_token };
  }
  function createStore(indexedDB) {
    function access(mode, operation) {
      return new Promise((resolve, reject) => {
        if (!indexedDB) { reject(new Error('Notification storage is unavailable.')); return; }
        const opening = indexedDB.open(DATABASE, 1);
        opening.onupgradeneeded = () => opening.result.createObjectStore(STORE);
        opening.onerror = () => reject(new Error('Notification storage could not be opened.'));
        opening.onblocked = () => reject(new Error('Notification storage is busy.'));
        opening.onsuccess = () => {
          const database = opening.result;
          database.onversionchange = () => database.close();
          try {
            const transaction = database.transaction(STORE, mode);
            const request = operation(transaction.objectStore(STORE));
            transaction.oncomplete = () => { database.close(); resolve(request.result); };
            transaction.onabort = transaction.onerror = () => {
              database.close(); reject(new Error('Notification storage could not be updated.'));
            };
          } catch {
            database.close(); reject(new Error('Notification storage could not be updated.'));
          }
        };
      });
    }
    return {
      async read() { return normalized(await access('readonly', store => store.get('direct'))); },
      async save(value) {
        const record = normalized(value);
        if (!record) throw new Error('The notification device registration is invalid.');
        await access('readwrite', store => store.put(record, 'direct'));
      },
      async clear() { await access('readwrite', store => store.delete('direct')); }
    };
  }
  scope.VCEAlertWorkerStorage = Object.freeze({ createStore });
})(self);
