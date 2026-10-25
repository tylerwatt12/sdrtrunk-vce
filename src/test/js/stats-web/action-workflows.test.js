'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const filename = path.resolve(process.argv[2]);
const source = fs.readFileSync(filename, 'utf8').replaceAll('export function ', 'function ');
const { createActionWorkflow, actionFailureMessage } = vm.runInNewContext(`${source};
  ({ createActionWorkflow, actionFailureMessage });`);
const deferred = () => { let resolve, reject; const promise = new Promise((yes, no) => {
  resolve = yes; reject = no;
}); return { promise, resolve, reject }; };

function element() {
  const attributes = new Map();
  const classes = new Set();
  return { disabled: false, textContent: '', value: 'unsaved draft', hidden: false,
    classList: { add: (...names) => names.forEach(name => classes.add(name)),
      remove: (...names) => names.forEach(name => classes.delete(name)), contains: name => classes.has(name) },
    setAttribute: (name, value) => attributes.set(name, value),
    getAttribute: name => attributes.get(name) ?? null, removeAttribute: name => attributes.delete(name) };
}

async function main() {
  // Each family passes through this lifecycle, including multi-phase operations
  // that retain their existing job or download progress after submission.
  for (const pending of ['Deleting 20 channels…', 'Deleting alias…', 'Deleting Alias List…',
    'Updating 500 aliases…', 'Removing memberships…', 'Saving alias assignments…',
    'Deleting destination…', 'Adding feed…', 'Deleting 500 recordings…', 'Run retention…']) {
    const surface = element(), feedback = element(), submit = element(), input = element(),
      locked = element(), navigation = element(), cancel = element();
    locked.disabled = true;
    const modal = { busy: false, setBusy(value) { this.busy = value; } };
    const controls = [submit, input, locked, cancel];
    const workflow = createActionWorkflow({ surface, feedback, controls, modal });
    const operation = deferred();
    let writes = 0, readbacks = 0;
    const request = workflow.run(() => { writes++; return operation.promise; }, { pending,
      onSuccess: result => { assert.equal(result, 7); readbacks++; } });
    assert.equal(feedback.textContent, pending, 'Action feedback is synchronous');
    assert.equal(feedback.classList.contains('ui-feedback-loading'), true);
    assert.equal(feedback.getAttribute('role'), 'status');
    assert.equal(surface.getAttribute('aria-busy'), 'true');
    assert.equal(modal.busy, true);
    assert.equal(controls.every(control => control.disabled), true);
    assert.equal(navigation.disabled, false, 'Unrelated navigation remains enabled');
    assert.equal(await workflow.run(() => { writes++; }), undefined);
    const secondWorkflow = createActionWorkflow({ surface, feedback, controls });
    assert.equal(await secondWorkflow.run(() => { writes++; }), undefined,
      'Recreating a controller cannot bypass duplicate prevention');
    const replacement = element();
    controls.push(replacement);
    input.disabled = false;
    workflow.refreshControls();
    assert.equal(input.disabled, true, 'A renderer cannot re-enable conflicting controls');
    assert.equal(replacement.disabled, true, 'New controls join the pending operation');
    let navigated = false;
    await Promise.resolve().then(() => { navigated = true; });
    assert.equal(navigated, true, 'Pending work yields to unrelated interactions');
    assert.equal(readbacks, 0);
    operation.resolve(7);
    assert.equal(await request, 7);
    assert.equal(writes, 1);
    assert.equal(readbacks, 1);
    assert.equal(surface.getAttribute('aria-busy'), null);
    assert.equal(modal.busy, false);
    assert.equal(input.disabled, false);
    assert.equal(replacement.disabled, false, 'New controls recover their original state');
    assert.equal(locked.disabled, true, 'Existing disabled state survives the operation');
    assert.equal(feedback.hidden, true);

    const failure = deferred();
    const retry = workflow.run(() => failure.promise, { pending });
    failure.reject(Object.assign(new Error('Changed elsewhere. Reload current values.'), {
      status: 409, code: 'stale_revision'
    }));
    await assert.rejects(retry, /Changed elsewhere/);
    assert.equal(input.value, 'unsaved draft');
    assert.equal(submit.disabled, false);
    assert.equal(workflow.isBusy(), false);
    assert.equal(feedback.classList.contains('ui-feedback-loading'), false);
    assert.equal(feedback.classList.contains('ui-feedback-error'), true);
    assert.equal(feedback.getAttribute('role'), 'alert');
    assert.match(feedback.textContent, /Reload current values/);
  }

  for (const error of [new TypeError('Failed to fetch'), Object.assign(new Error('Timeout'),
    { code: 'request_timeout' }), Object.assign(new Error('Invalid response'), { code: 'invalid_response' })]) {
    assert.match(actionFailureMessage(error), /did not confirm.*Check the current state/);
  }
  const surface = element(), feedback = element();
  const workflow = createActionWorkflow({ surface, feedback, controls: [] });
  await assert.rejects(workflow.run(async () => 1, { onSuccess: () => { throw new Error('Readback failed'); } }));
  assert.match(feedback.textContent, /action completed.*view could not be updated/,
    'A failed readback must not claim the committed action failed');
  assert.equal(workflow.isBusy(), false);
  console.log('Bulk action feedback, duplicate prevention, scoped locking, draft recovery and unconfirmed outcomes passed.');
}

main().catch(error => { console.error(error); process.exitCode = 1; });
