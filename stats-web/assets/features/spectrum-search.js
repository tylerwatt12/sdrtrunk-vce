import { systemIdentity } from '../core/system-labels.js?v=2';
import { receiverDisplayName } from '../core/receiver-names.js?v=1';
import { spectrumSearchProtocolLabel, spectrumSearchSystemName, spectrumSearchIdentityFacts, spectrumSearchMapDraft, spectrumSearchGroupKey } from './spectrum-search-data.js?v=1';
import { formatHexDecimal } from '../core/call-value-format.js?v=1';
import { createTableOverflow } from '../core/table-overflow.js?v=2';
import { createDiscoveryRadioReferenceContext, discoveryRadioReferenceResult, discoveryRadioReferenceSystemUrl, discoveryRadioReferencePending, discoveryRadioReferenceSelection, discoverySnapshotIsCurrent, createDiscoveryCompletion, createDiscoveryRadioReferenceWait, createDiscoveryAliasListChoice, discoveryAliasListName, discoveryAliasImportChoice, discoveryAliasImportResults, discoveryAliasImportForSelection, importDiscoveryAliases, discoveryAliasImportTargets } from './discovery-radioreference.js?v=19';

export function openSpectrumSearchWizard(ui, context = {}) {
  const { node, openReadOnlyModal, requestJson, uiActionButton, iconButton, uiSelect, uiSelectFrame, uiPill,
    formField, anchor, href, entityRefHref, channelMHz, hex, channelEditorControl, channelEditorFieldValue, protocols } = ui;
  const path = '/api/v1/admin/spectrum-search';
  const abort = new AbortController();
  const host = node('div', 'spectrum-discovery-workspace spectrum-search-workspace editor-workspace');
  const summary = node('div', 'spectrum-discovery-context');
  const steps = node('ol', 'spectrum-discovery-progress');
  steps.setAttribute('aria-label', 'Find Trunked Systems steps');
  const stage = node('div', 'spectrum-discovery-stage');
  const feedback = node('div', 'ui-feedback');
  feedback.setAttribute('role', 'status');
  const actions = node('footer', 'ui-modal-footer ui-action-row spectrum-discovery-actions');
  host.append(summary, steps, stage, actions);
  let catalog = null;
  let receiverId = '';
  let usedReceiverId = '';
  let selectedBandIds = null;
  const customRange = { minimum: '769', maximum: '775' };
  let lease = null;
  let job = null;
  let releasedJobId = null;
  let retiringJobId = null;
  let jobReleaseInFlight = null;
  const leaseReleasesInFlight = new Map();
  const lateLeases = new Map();
  let receiverFinishPromise = null;
  let pollTimer = null;
  let renewalTimer = null;
  let closed = false;
  const pendingReceiverUses = new Set();
  const trackReceiverUse = async (action) => {
    const pending = action();
    pendingReceiverUses.add(pending);
    try { return await pending; }
    finally { pendingReceiverUses.delete(pending); }
  };
  let busy = false;
  let generation = 0;
  let progressNodes = null;
  let currentStep = '';
  let wantsAliasImport = true;
  let aliasImportResult = null;
  let aliasImportSystemIds = new Set();
  let aliasImportSelections = [];
  let refreshReviewImportAction = null;
  let refreshDirectoryWait = null;
  let receiverChooser = null;
  let reviewBindings = [];
  let reviewGroupBindings = [];
  let resultBindings = null;
  let detailBinding = null;
  let disposeBandPicker = () => {};
  const resultOverflows = new Set();
  const disposeResultOverflows = () => {
    resultOverflows.forEach((overflow) => overflow.destroy());
    resultOverflows.clear();
  };
  let refreshedCheckingRun = null;
  let receiverMeasurementRevision = 0;
  const preparedCatalogTuners = new Map();
  const selected = new Set();
  const drafts = new Map();
  const groups = new Map();
  const disabled = new Map();
  const modal = openReadOnlyModal('Find Trunked Systems', host, {
    id: 'spectrum-search', className: 'channel-editor-modal spectrum-search-modal',
    cleanup: () => {
      closed = true;
      generation += 1;
      disposeBandPicker();
      disposeResultOverflows();
      abort.abort();
      window.removeEventListener('pagehide', abandon);
      void releaseForClose();
    }
  });
  if (!modal) return null;
  const current = () => !closed && !abort.signal.aborted;
  const request = async (url, options = {}) => {
    if (modal.ready && !await modal.ready || !current())
      throw new DOMException('Dialog dismissed', 'AbortError');
    return requestJson(url, { page: false, signal: options.method ? null : abort.signal, ...options });
  };
  const jobPath = () => `${path}/${encodeURIComponent(job.job_id)}`;
  const liveJob = () => Boolean(job?.job_id && job.job_id !== releasedJobId && job.job_id !== retiringJobId);
  const browsePath = (id) => `/api/v1/admin/tuners/${encodeURIComponent(id)}/browse`;
  const leaseNoLongerExists = (cause) => [404, 410].includes(Number(cause?.status)) ||
    ['tuner_not_found', 'tuner_browse_expired'].includes(String(cause?.code || ''));
  const leaseRenewalLostOwnership = (cause) => leaseNoLongerExists(cause) ||
    Number(cause?.status) === 409 || String(cause?.code || '') === 'tuner_browse_unavailable';
  const jobNoLongerExists = (cause) => [404, 410].includes(Number(cause?.status)) ||
    ['search_expired', 'spectrum_search_expired'].includes(String(cause?.code || ''));
  const clearRenewal = () => {
    window.clearTimeout(renewalTimer);
    renewalTimer = null;
  };
  const scheduleRenewal = () => {
    clearRenewal();
    if (current() && (lease || liveJob())) renewalTimer = window.setTimeout(() => void renew(), 10000);
  };
  const leaseKey = (value, id) => `${id}:${value.lease_id}`;
  const endOwnedLease = async (previous, id) => {
    const key = leaseKey(previous, id);
    const pending = leaseReleasesInFlight.get(key) || requestJson(browsePath(id), {
        method: 'DELETE', body: { lease_id: previous.lease_id }, page: false, keepalive: true,
        timeoutMs: 3000
      });
    leaseReleasesInFlight.set(key, pending);
    try { await pending; }
    catch (cause) { if (!leaseNoLongerExists(cause)) throw cause; }
    finally { if (leaseReleasesInFlight.get(key) === pending) leaseReleasesInFlight.delete(key); }
  };
  const releaseLease = async ({ bestEffort = false } = {}) => {
    clearRenewal();
    const previous = lease;
    if (!previous?.lease_id) return true;
    try {
      await endOwnedLease(previous, previous.tuner?.id || usedReceiverId);
      if (lease?.lease_id === previous.lease_id) lease = null;
      return true;
    } catch (cause) {
      if (bestEffort) return false;
      if (current() && lease?.lease_id === previous.lease_id) scheduleRenewal();
      throw cause;
    }
  };
  const releaseLateLease = async (acquired, id) => {
    if (lease?.lease_id === acquired.lease_id && usedReceiverId === id) {
      if (!current()) await releaseLease({ bestEffort: true });
      return;
    }
    const key = leaseKey(acquired, id);
    lateLeases.set(key, { acquired, id });
    try {
      await endOwnedLease(acquired, id);
      lateLeases.delete(key);
    } catch (_) {
      // Keep this exact lease for the ordered close cleanup to retry.
    }
  };
  const releaseJob = async ({ bestEffort = false, preserveResults = false } = {}) => {
    window.clearTimeout(pollTimer);
    pollTimer = null;
    const previous = job;
    if (!previous?.job_id) return true;
    const retainOrClear = () => {
      if (job?.job_id !== previous.job_id) return;
      if (preserveResults) releasedJobId = previous.job_id;
      else { job = null; releasedJobId = null; retiringJobId = null; }
    };
    if (previous.job_id === releasedJobId) { retainOrClear(); return true; }
    retiringJobId = previous.job_id;
    const pending = jobReleaseInFlight?.id === previous.job_id ? jobReleaseInFlight.promise :
      requestJson(`${path}/${encodeURIComponent(previous.job_id)}`, {
        method: 'DELETE', page: false, keepalive: true, timeoutMs: 3000
      });
    jobReleaseInFlight = { id: previous.job_id, promise: pending };
    try {
      await pending;
      retainOrClear();
      scheduleRenewal();
      return true;
    } catch (cause) {
      if (jobNoLongerExists(cause)) {
        retainOrClear();
        scheduleRenewal();
        return true;
      }
      if (bestEffort) return false;
      throw cause;
    } finally {
      if (jobReleaseInFlight?.promise === pending) jobReleaseInFlight = null;
    }
  };
  const releaseDelay = (milliseconds) => new Promise((resolve) => window.setTimeout(resolve, milliseconds));
  const releaseForClose = async () => {
    clearRenewal();
    await Promise.allSettled([...pendingReceiverUses]);
    const expiresAt = Math.max(Number(job?.expires_at_ms) || 0,
      Number(lease?.expires_at_epoch_ms || lease?.expires_at_ms) || 0,
      ...[...lateLeases.values()].map(({ acquired }) =>
        Number(acquired.expires_at_epoch_ms || acquired.expires_at_ms) || 0));
    const deadline = Math.max(Date.now() + 10_000, expiresAt) + 500;
    while (job?.job_id || lease?.lease_id || lateLeases.size) {
      const jobReleased = await releaseJob({ bestEffort: true });
      if (jobReleased) {
        await releaseLease({ bestEffort: true });
        for (const { acquired, id } of [...lateLeases.values()]) await releaseLateLease(acquired, id);
      }
      if (!job?.job_id && !lease?.lease_id && !lateLeases.size) break;
      if (Date.now() >= deadline) {
        job = null;
        lease = null;
        lateLeases.clear();
        break;
      }
      await releaseDelay(Math.min(1000, Math.max(0, deadline - Date.now())));
    }
  };
  const abandon = () => {
    abort.abort();
    void releaseForClose();
  };
  window.addEventListener('pagehide', abandon);
  const button = (label, action, primary = false, host = actions) => {
    const control = uiActionButton(label, '', action, `ui-button ui-button-${primary ? 'primary' : 'secondary'}`);
    host.append(control);
    return control;
  };
  const setBusy = (value) => {
    busy = value;
    modal.setBusy(value);
    stage.setAttribute('aria-busy', String(value));
    directory.setBusy(value);
    if (value) host.querySelectorAll('input,select,button').forEach((control) => {
      if (directory.element.contains(control)) return;
      if (!disabled.has(control)) disabled.set(control, control.disabled);
      control.disabled = true;
    });
    else {
      disabled.forEach((wasDisabled, control) => { if (control.isConnected) control.disabled = wasDisabled; });
      disabled.clear();
    }
    refreshDirectoryWait?.();
  };
  const disclosure = (label, ...children) => {
    const details = node('details', 'ui-section-disclosure ui-section-disclosure-flat spectrum-discovery-technical');
    details.append(node('summary', 'ui-section-summary', label), ...children);
    return details;
  };
  const error = (copy, cause) => {
    feedback.hidden = false;
    feedback.replaceChildren(node('p', '', copy));
    if (cause?.message) feedback.append(disclosure('Error details', node('p', 'muted', cause.message)));
  };
  const clearFeedback = () => {
    feedback.replaceChildren();
  };
  const show = (next, index, title) => {
    detailBinding?.modal.close();
    disposeBandPicker();
    disposeBandPicker = () => {};
    disposeResultOverflows();
    progressNodes = null;
    currentStep = next;
    modal.dialog.classList.toggle('spectrum-search-complete', next === 'saved');
    modal.setTitle(next === 'saved' ? title : 'Find Trunked Systems');
    receiverChooser = null;
    reviewBindings = [];
    reviewGroupBindings = [];
    refreshReviewImportAction = null;
    refreshDirectoryWait = null;
    resultBindings = null;
    steps.hidden = next === 'saved';
    const aliasStep = next === 'aliases' || next === 'importing';
    const labels = next === 'progress' ? ['Bands', 'Find channels', 'Review & add'] :
      ['Bands', 'Select channels', aliasStep ? 'Review' : 'Review & add', ...(aliasStep ? ['Talkgroups'] : [])];
    steps.dataset.stepCount = String(labels.length);
    steps.replaceChildren(...labels.map((label, position) => {
      const step = node('li');
      step.append(uiPill(position < index ? '✓' : String(position + 1),
        position < index ? 'success' : position === index ? 'blue' : 'neutral'), node('span', '', label));
      if (position === index) step.setAttribute('aria-current', 'step');
      return step;
    }));
    const heading = node('h3', next === 'saved' ? 'visually-hidden' : '', title);
    heading.tabIndex = -1;
    stage.replaceChildren(heading, feedback);
    feedback.hidden = false;
    clearFeedback();
    actions.replaceChildren();
    heading.focus({ preventScroll: true });
  };
  const receiver = () => catalog?.tuners?.find((tuner) => tuner.id === receiverId);
  const candidates = () => job?.candidates || [];
  const directory = createDiscoveryRadioReferenceContext(ui, request, { signal: abort.signal });
  const scopedAliasImport = (result) => {
    const scoped = result ? { ...result, targets: (result.targets || []).filter((target) =>
      aliasImportSystemIds.has(Number(target.system_id))) } : null;
    if (!wantsAliasImport) return scoped;
    const expected = aliasImportSelections.flatMap((selection) => candidates()
      .filter((candidate) => candidate.saved && selection.aliasGroupIds.includes(candidate.alias_group_id) &&
        Number(candidate.alias_list_id) > 0)
      .map((candidate) => ({ alias_list_id: Number(candidate.alias_list_id), alias_list_name: selection.name,
        system_id: selection.rrSystemId, system_name: selection.systemName })));
    return discoveryAliasImportForSelection(result, expected);
  };
  const recordingWindow = () => {
    const tuner = receiver();
    if (!tuner?.fixed_window && tuner?.source_type !== 'recording') return null;
    const center = Number(tuner.center_frequency_hz ?? tuner.frequency_hz);
    const width = Number(tuner.usable_bandwidth_hz);
    return Number.isFinite(center) && width > 0 ? { minimum_hz: Math.ceil(center - width / 2),
      maximum_hz: Math.floor(center + width / 2) } : null;
  };
  const selectedCandidates = () => candidates().filter((candidate) => selected.has(candidate.candidate_id));
  const pendingDirectory = () => discoveryRadioReferencePending(candidates());
  const selectedDirectoryCandidates = (ids = [...selected]) =>
    discoveryRadioReferenceSelection(candidates(), ids, spectrumSearchGroupKey);
  const pendingSelectedDirectory = (ids = [...selected]) =>
    discoveryRadioReferencePending(selectedDirectoryCandidates(ids));
  const scheduleDirectoryPoll = () => {
    if (current() && liveJob() && job.phase === 'complete' && pendingDirectory()) {
      window.clearTimeout(pollTimer);
      pollTimer = window.setTimeout(() => void poll(), 750);
    }
  };
  const refreshReviewDirectory = () => {
    reviewBindings.forEach((binding) => {
      const candidate = candidates().find((value) => value.candidate_id === binding.id);
      if (!candidate) return;
      binding.site.textContent = siteLabel(candidate);
      if (!binding.draft.name_edited) {
        binding.draft.name = candidateName(candidate);
        if (binding.name) binding.name.textContent = binding.draft.name;
        if (binding.input) binding.input.value = binding.draft.name;
      }
      const values = candidate.trunked_evidence?.frequency_map || [];
      if (binding.map && !binding.draft.map_draft && JSON.stringify(values) !== binding.mapValues) {
        const updated = binding.createMap(values);
        binding.mapValues = JSON.stringify(values);
        binding.map.replaceWith(updated);
        binding.map = updated;
        binding.editor.map = updated;
      }
    });
    reviewGroupBindings.forEach((binding) => refreshGroupDirectory(binding));
    refreshReviewImportAction?.();
  };
  const identityFacts = (candidate) => spectrumSearchIdentityFacts(candidate, hex);
  const identityText = (candidate) => identityFacts(candidate).map(([label, value]) => `${label} ${value}`).join(' · ') ||
    'Identity unavailable';
  const candidateName = (candidate) => candidate.name || candidate.site_name || `${channelMHz(candidate.frequency_hz)} MHz`;
  const systemLabel = (candidate) => {
    const identity = candidate.identity || candidate.trunked_evidence?.identity;
    return (candidate.radio_reference?.state === 'matched' && candidate.radio_reference.match?.system_name) ||
      spectrumSearchSystemName(candidate) || (identity?.wacn != null ?
        `WACN ${formatHexDecimal(identity.wacn, 5)} · SysID ${formatHexDecimal(identity.system, 3)}` :
        systemIdentity({ ...candidate, radio_system_key: candidate.radio_system_key || identity?.radio_system_key }) ||
          `${String(candidate.protocol_id || 'Digital').toUpperCase()} system`);
  };
  const aliasIdentityLabel = (candidate) => candidate?.identity?.wacn != null ?
    `WACN ${formatHexDecimal(candidate.identity.wacn, 5)} · SysID ${formatHexDecimal(candidate.identity.system, 3)}` :
    systemLabel(candidate);
  const factList = (entries) => {
    const list = node('dl', 'ui-fact-list');
    entries.forEach(([label, value]) => list.append(node('dt', '', label), node('dd', '', String(value ?? '—'))));
    return list;
  };
  const receiverSummary = (tuner) => {
    const frequency = Number(tuner.center_frequency_hz);
    const width = Number(tuner.usable_bandwidth_hz);
    const frequencyText = Number.isFinite(frequency) && frequency > 0 ?
      `${(frequency / 1_000_000).toFixed(6)} MHz` : 'Frequency unavailable';
    const widthText = Number.isFinite(width) && width > 0 ?
      `${(width / 1_000_000).toFixed(2)} MHz` : 'Width unavailable';
    return `${frequencyText} @ ${widthText}`;
  };
  const updateReceiverMeasurements = (tuner, id) => {
    if (!tuner || tuner.id !== id) return;
    const measurements = {};
    if (Object.hasOwn(tuner, 'frequency_hz')) measurements.center_frequency_hz = tuner.frequency_hz;
    else if (Object.hasOwn(tuner, 'center_frequency_hz')) measurements.center_frequency_hz = tuner.center_frequency_hz;
    if (Object.hasOwn(tuner, 'usable_bandwidth_hz')) measurements.usable_bandwidth_hz = tuner.usable_bandwidth_hz;
    catalog.tuners = (catalog.tuners || []).map((candidate) => {
      if (candidate.id !== id) return candidate;
      if (Object.entries(measurements).some(([key, value]) => !Object.is(candidate[key], value)))
        receiverMeasurementRevision += 1;
      return { ...candidate, ...measurements,
        ...(Object.hasOwn(tuner, 'name') ? { name: tuner.name } : {}),
        ...(Object.hasOwn(tuner, 'nickname') ? { nickname: tuner.nickname } : {}) };
    });
  };
  const updateContext = () => {
    let tuner = receiver();
    const currentCenter = Number(job?.progress?.current_frequency_hz);
    if (tuner && job?.phase === 'scanning' && lease?.lease_id && receiverId === usedReceiverId &&
        Number.isFinite(currentCenter) && currentCenter > 0)
      tuner = { ...tuner, center_frequency_hz: currentCenter };
    receiverChooser?.querySelectorAll('option').forEach((option) => {
      const listed = catalog.tuners.find((candidate) => candidate.id === option.value);
      if (listed) option.textContent = `${receiverDisplayName(listed)} · ${receiverSummary(listed)}`;
    });
    summary.replaceChildren(node('strong', '', receiverDisplayName(tuner, 'Choose an idle receiver')),
      node('p', 'muted', tuner ? `${tuner.source_type === 'recording' ? 'WAV recording · ' : ''}${receiverSummary(tuner)}` :
        'Find P25, DMR, and NXDN trunked systems.'));
  };
  const refreshCheckingMeasurements = async () => {
    const operation = generation;
    const previousLeaseId = lease?.lease_id;
    const id = usedReceiverId;
    const jobId = job?.job_id;
    const candidateFrequency = Number(job?.progress?.current_frequency_hz);
    const previousMeasurements = receiverMeasurementRevision;
    const run = `${operation}:${jobId}:${candidateFrequency}`;
    if (!current() || !previousLeaseId || receiverId !== id || job?.phase !== 'checking' ||
        !Number.isFinite(candidateFrequency) || candidateFrequency <= 0 || refreshedCheckingRun === run) return;
    refreshedCheckingRun = run;
    try {
      const inventory = await request('/api/v1/admin/tuners', { csrf: false });
      if (!current() || operation !== generation || lease?.lease_id !== previousLeaseId ||
          usedReceiverId !== id || receiverId !== id || job?.job_id !== jobId || job.phase !== 'checking' ||
          Number(job.progress?.current_frequency_hz) !== candidateFrequency ||
          receiverMeasurementRevision !== previousMeasurements) return;
      updateReceiverMeasurements((inventory.tuners || []).find((tuner) => tuner.id === id), id);
      updateContext();
    } catch (_) {
      // The last observed receiver center remains useful if this optional read fails.
    }
  };
  const receiverPreparationState = (tuner) => {
    const isDisabled = String(tuner.operator_state || '').toLowerCase() === 'disabled';
    const channelCount = Math.max(0, Number(tuner.channel_count || 0));
    const lockSetting = (tuner.settings || []).find((setting) => setting.id === 'center_frequency_locked');
    const reasonText = String(tuner.reason || '');
    const needsStop = channelCount > 0 || /channel|serving/i.test(reasonText);
    const locked = tuner.center_frequency_locked === true || lockSetting?.value === true ||
      /lock center|center.*lock|unlock/i.test(reasonText);
    const canPrepare = isDisabled || (typeof context.prepareReceiver === 'function' &&
      tuner.takeover_allowed !== false && (needsStop || locked));
    return { tuner, channelCount, reasonText, needsStop, locked, canPrepare, isDisabled };
  };
  const prepareReceiver = (state) => trackReceiverUse(() => prepareReceiverUse(state));
  const prepareReceiverUse = async ({ tuner, isDisabled }) => {
    if (busy) return;
    const operation = generation;
    setBusy(true);
    clearFeedback();
    feedback.hidden = true;
    try {
      if (isDisabled) {
        await request(`/api/v1/admin/tuners/${encodeURIComponent(tuner.id)}/state`, {
          method: 'PUT', body: { state: 'setup', ...(tuner.operator_generation !== undefined ?
            { operator_generation: tuner.operator_generation } : {}) }
        });
        const deadline = Date.now() + 30_000;
        while (current() && operation === generation) {
          const inventory = await request('/api/v1/admin/tuners', { csrf: false });
          if (!current() || operation !== generation) return;
          const started = (inventory.tuners || []).find((candidate) => candidate.id === tuner.id);
          if (!started) throw new Error('This tuner is no longer available. Choose another receiver.');
          if (!started.transition) {
            if (started.maintenance_error) throw new Error(started.maintenance_error);
            if (String(started.operator_state || '').toLowerCase() !== 'setup')
              throw new Error('The receiver did not enter Setup. Check its connection and try again.');
            await refreshReceiverCatalog();
            if (!current() || operation !== generation) return;
            receiverId = tuner.id;
            showBand();
            return;
          }
          if (Date.now() >= deadline) throw new Error('The receiver is still starting. Refresh its status shortly.');
          await new Promise((resolve) => window.setTimeout(resolve, 250));
        }
        return;
      }
      const acquired = await context.prepareReceiver(tuner);
      if (!acquired) return;
      if (!current() || operation !== generation) {
        await releaseLateLease(acquired, tuner.id);
        return;
      }
      if (!preparedCatalogTuners.has(tuner.id)) preparedCatalogTuners.set(tuner.id, tuner);
      receiverId = tuner.id;
      usedReceiverId = tuner.id;
      lease = acquired;
      const prepared = { ...tuner, ...(acquired.tuner || {}), eligible: true, reason: null,
        channel_count: 0, center_frequency_locked: false };
      catalog.tuners = (catalog.tuners || []).map((candidate) =>
        candidate.id === tuner.id ? prepared : candidate);
      updateReceiverMeasurements(acquired.tuner, tuner.id);
      scheduleRenewal();
      showBand();
    } catch (cause) {
      error('This receiver could not be prepared. Check its channels and try again.', cause);
    } finally { if (current()) setBusy(false); }
  };
  const receiverCard = (state) => {
    const { tuner, channelCount, reasonText, needsStop, locked, canPrepare, isDisabled } = state;
    const status = isDisabled ? 'Disabled' : channelCount ? `${channelCount} channel${channelCount === 1 ? '' : 's'} running` :
      needsStop ? 'Channels running' : locked ? 'Center locked' : 'Unavailable';
    const row = node('article', 'ui-surface spectrum-search-receiver-card');
    row.setAttribute('role', 'listitem');
    const heading = node('div', 'spectrum-search-receiver-heading');
    const statusPill = uiPill(status, isDisabled ? 'neutral' : 'warning');
    statusPill.classList.add('spectrum-search-receiver-status');
    heading.append(node('strong', '', receiverDisplayName(tuner)), statusPill);
    const details = node('p', 'muted spectrum-search-receiver-detail', receiverSummary(tuner));
    const copy = node('div', 'spectrum-search-receiver-copy');
    copy.append(heading, details);
    if (!canPrepare && reasonText) copy.append(node('p', 'muted', reasonText));
    row.append(copy);
    if (canPrepare) {
      const prepareLabel = isDisabled ? 'Start Setup' : needsStop ? 'Stop channels and use' : 'Unlock and use';
      const prepare = uiActionButton(prepareLabel, '', () => void prepareReceiver(state),
        'ui-button ui-button-primary spectrum-search-prepare-receiver');
      prepare.setAttribute('aria-label', `${prepareLabel}: ${receiverDisplayName(tuner)}`);
      row.append(prepare);
    }
    return row;
  };
  const receiverList = (states) => {
    const list = node('div', 'spectrum-search-receiver-list');
    list.setAttribute('role', 'list');
    states.forEach((state) => list.append(receiverCard(state)));
    return list;
  };
  const renew = async () => {
    if (!current() || (!lease && !liveJob())) return;
    clearRenewal();
    const previous = lease;
    const previousJobId = liveJob() ? job.job_id : null;
    const id = usedReceiverId;
    const operation = generation;
    let observedJob = null;
    try {
      if (previous) {
        const renewed = await request(browsePath(id), {
          method: 'POST', body: { lease_id: previous.lease_id }
        });
        if (!current() || lease?.lease_id !== previous.lease_id || operation !== generation || usedReceiverId !== id ||
            (previousJobId && (!liveJob() || job.job_id !== previousJobId))) return;
        lease = renewed;
        updateReceiverMeasurements(renewed.tuner, id);
        updateContext();
      }
      if (!busy && liveJob() && !['scanning', 'checking'].includes(job.phase)) {
        observedJob = job;
        const updated = await request(jobPath(), { csrf: false });
        if (discoverySnapshotIsCurrent(observedJob, job, { active: current() && operation === generation &&
            liveJob() && job.job_id === updated.job_id, busy })) {
          job = updated;
          if (actions.querySelector('[data-directory-retry]')) {
            actions.querySelector('[data-directory-retry]').remove();
            clearFeedback();
          }
          if (updated.phase !== 'complete' || updated.restart_required) drawJob();
          else if (currentStep === 'review') refreshReviewDirectory();
          else if (currentStep === 'results') refreshResultsDirectory();
          else refreshDirectoryWait?.();
        }
      }
    } catch (cause) {
      if (!current() || operation !== generation || usedReceiverId !== id ||
          (previous && lease?.lease_id !== previous.lease_id) ||
          (previousJobId && (!liveJob() || job.job_id !== previousJobId))) return;
      if (discoverySnapshotIsCurrent(observedJob || job, job, { active: current(), busy })) {
        if (previous && leaseRenewalLostOwnership(cause)) {
          if (lease?.lease_id === previous.lease_id) lease = null;
          showExpired();
          return;
        }
        if (isExpired(cause)) showExpired();
        else error('We couldn’t refresh this search. Check your connection before continuing.', cause);
      }
    }
    if (current() && operation === generation && (lease || liveJob())) scheduleRenewal();
  };
  const showBand = () => {
    const available = (catalog.tuners || []).filter((tuner) => tuner.eligible &&
      String(tuner.operator_state || '').toLowerCase() !== 'disabled');
    show('band', 0, available.length ? 'Choose where to look' : 'Choose a receiver');
    if (!available.length) {
      summary.replaceChildren();
      summary.hidden = true;
      feedback.hidden = true;
      const blockedTuners = (catalog.tuners || []).map(receiverPreparationState);
      const allStopping = blockedTuners.length > 0 && blockedTuners.every((state) =>
        state.needsStop && state.canPrepare);
      const anyPreparation = blockedTuners.some((state) => state.canPrepare);
      const anyStopping = blockedTuners.some((state) => state.needsStop && state.canPrepare);
      const anyDisabled = blockedTuners.some((state) => state.isDisabled);
      const unavailable = node('div', 'spectrum-search-unavailable');
      const introduction = node('div', 'ui-notice ui-notice-warning spectrum-search-unavailable-intro');
      introduction.append(node('strong', '', !blockedTuners.length ? 'No receivers are available' :
        allStopping ? 'All receivers are in use' : anyPreparation ? 'A receiver needs to be prepared' :
          'No receiver is ready for a search'),
      node('p', '', !blockedTuners.length ? 'Connect or enable a supported receiver, then refresh this list.' :
        allStopping ? 'Choose one below. VCE will stop its active channels for the search and restart the channels it stopped when you finish. Calls in progress will end and will not resume.' :
          anyPreparation ? (anyDisabled ? 'Start Setup on a receiver before using it for a search.' : anyStopping ?
            'Choose an action below. VCE restores temporary changes when you finish. Stopping channels may end calls in progress.' :
            'Choose Unlock and use below. VCE will restore Lock center when you finish.') :
            'Check the reason below, then manage receivers or refresh this list.'));
      const manage = node('p', 'ui-field-hint');
      manage.append('Need to change receiver settings? ', anchor('Manage receivers', href('tuners')));
      introduction.append(manage);
      const list = receiverList(blockedTuners);
      unavailable.append(introduction, list);
      stage.append(unavailable);
      button('Close', () => modal.close());
      button('Refresh receivers', () => void load(), !anyPreparation);
      return;
    }
    summary.hidden = false;
    if (!available.some((tuner) => tuner.id === receiverId)) receiverId = catalog.suggested_tuner_id ||
      available.slice().sort((a, b) => b.usable_bandwidth_hz - a.usable_bandwidth_hz)[0].id;
    if (!available.some((tuner) => tuner.id === receiverId)) receiverId = available[0].id;
    const form = node('form', 'spectrum-search-form');
    const chooser = uiSelect(available.map((tuner) => ({ value: tuner.id,
      label: `${receiverDisplayName(tuner)} · ${receiverSummary(tuner)}` })), receiverId);
    receiverChooser = chooser;
    chooser.setAttribute('aria-label', 'Receiver');
    chooser.addEventListener('change', () => { receiverId = chooser.value; updateContext(); });
    const presets = catalog.presets || [];
    if (selectedBandIds === null) {
      const defaults = presets.filter((preset) => ['700mhz', '800mhz'].includes(preset.id)).map((preset) => preset.id);
      selectedBandIds = new Set(defaults.length ? defaults : [presets[0]?.id || 'custom']);
    }
    const bands = node('fieldset', 'spectrum-search-band-field');
    const bandLabel = node('legend', 'ui-field-label', 'Bands');
    bandLabel.id = 'spectrum-search-band-label';
    const bandTrigger = node('button', 'ui-select spectrum-search-band-trigger');
    bandTrigger.type = 'button';
    bandTrigger.id = 'spectrum-search-band-trigger';
    bandTrigger.setAttribute('aria-labelledby', 'spectrum-search-band-label spectrum-search-band-summary');
    bandTrigger.setAttribute('aria-haspopup', 'dialog');
    bandTrigger.setAttribute('aria-controls', 'spectrum-search-band-options');
    bandTrigger.setAttribute('aria-expanded', 'false');
    bandTrigger.setAttribute('popovertarget', 'spectrum-search-band-options');
    const bandSummary = node('span', 'spectrum-search-band-summary');
    bandSummary.id = 'spectrum-search-band-summary';
    bandTrigger.append(bandSummary);
    const bandMenu = node('div', 'ui-popover spectrum-search-band-popover');
    bandMenu.id = 'spectrum-search-band-options';
    bandMenu.setAttribute('popover', 'auto');
    bandMenu.setAttribute('role', 'dialog');
    bandMenu.setAttribute('aria-labelledby', bandLabel.id);
    bands.append(bandLabel, uiSelectFrame(bandTrigger, 'spectrum-search-band-frame'), bandMenu);
    const custom = node('div', 'channel-editor-grid');
    const minimum = node('input', 'ui-input');
    const maximum = node('input', 'ui-input');
    [minimum, maximum].forEach((input) => { input.type = 'number'; input.step = '0.000001'; });
    minimum.value = customRange.minimum;
    maximum.value = customRange.maximum;
    minimum.addEventListener('input', () => { customRange.minimum = minimum.value; updateBands(); });
    maximum.addEventListener('input', () => { customRange.maximum = maximum.value; updateBands(); });
    custom.append(formField('Start frequency (MHz)', minimum), formField('End frequency (MHz)', maximum));
    const description = node('p', 'ui-field-hint');
    const coverageNotice = node('div', 'ui-notice ui-notice-warning');
    coverageNotice.setAttribute('role', 'status');
    coverageNotice.hidden = true;
    let findSignals;
    const selectedRanges = () => recordingWindow() ? [recordingWindow()] : [
      ...presets.filter((preset) => selectedBandIds.has(preset.id)).flatMap((preset) => preset.ranges),
      ...(selectedBandIds.has('custom') ? [{ minimum_hz: Math.round(Number(minimum.value) * 1_000_000),
        maximum_hz: Math.round(Number(maximum.value) * 1_000_000) }] : [])
    ];
    const selectedBandLabels = () => [...presets, { id: 'custom', label: 'Custom range' }]
      .filter((preset) => selectedBandIds.has(preset.id))
      .map((preset) => preset.label.split(' · ')[0]);
    const configuredRangeFailures = () => {
      const tuner = receiver();
      const outside = (range) => Number.isFinite(range.minimum_hz) && Number.isFinite(range.maximum_hz) &&
        range.minimum_hz > 0 && range.maximum_hz > range.minimum_hz &&
        (range.minimum_hz < Number(tuner?.minimum_frequency_hz) ||
          range.maximum_hz > Number(tuner?.maximum_frequency_hz));
      if (recordingWindow()) return [];
      const invalid = presets.filter((preset) => selectedBandIds.has(preset.id) && preset.ranges.some(outside))
        .map((preset) => preset.label);
      if (selectedBandIds.has('custom') && minimum.value && maximum.value) {
        const range = { minimum_hz: Math.round(Number(minimum.value) * 1_000_000),
          maximum_hz: Math.round(Number(maximum.value) * 1_000_000) };
        if (outside(range)) invalid.push(`Custom range · ${(range.minimum_hz / 1_000_000).toFixed(6)}–${
          (range.maximum_hz / 1_000_000).toFixed(6)} MHz`);
      }
      return invalid;
    };
    const updateBands = () => {
      const fixed = recordingWindow();
      bands.hidden = Boolean(fixed);
      custom.hidden = Boolean(fixed) || !selectedBandIds.has('custom');
      minimum.required = maximum.required = !custom.hidden;
      minimum.disabled = maximum.disabled = custom.hidden;
      const tuner = receiver();
      [minimum, maximum].forEach((input) => {
        input.min = String((tuner?.minimum_frequency_hz || 0) / 1_000_000);
        input.max = String((tuner?.maximum_frequency_hz || 0) / 1_000_000);
      });
      description.textContent = fixed ? `Checks the recording window from ${channelMHz(fixed.minimum_hz)} to ${channelMHz(fixed.maximum_hz)} MHz.` : `Choose one or more bands. Maximum search span: ${(catalog.bounds.maximum_total_hz / 1_000_000).toFixed(0)} MHz.`;
      const labels = selectedBandLabels();
      bandSummary.textContent = labels.length <= 2 ? labels.join(', ') || 'Choose bands' :
        `${labels.slice(0, 2).join(', ')} +${labels.length - 2}`;
      bandTrigger.dataset.uiHint = labels.join(', ');
      const invalid = configuredRangeFailures();
      coverageNotice.hidden = !invalid.length;
      coverageNotice.replaceChildren();
      if (invalid.length) {
        coverageNotice.append(node('strong', '', `Configured tuning limits: ${
          (Number(tuner.minimum_frequency_hz) / 1_000_000).toFixed(6)}–${
          (Number(tuner.maximum_frequency_hz) / 1_000_000).toFixed(6)} MHz`),
        node('p', '', `Outside these limits: ${invalid.join('; ')}.`),
        node('p', '', 'Choose another receiver or adjust its configured tuning limits.'));
        coverageNotice.append(anchor('Manage receivers', href('tuners')));
      }
      if (findSignals) findSignals.disabled = Boolean(invalid.length) ||
        (!fixed && !presets.some((preset) => selectedBandIds.has(preset.id)) && custom.hidden);
    };
    const bandOrder = new Map(['800mhz', '700mhz', 'uhf', 'vhf-high'].map((id, index) => [id, index]));
    const menuPresets = [...presets].sort((a, b) =>
      (bandOrder.get(a.id) ?? bandOrder.size) - (bandOrder.get(b.id) ?? bandOrder.size));
    [...menuPresets, { id: 'custom', label: 'Custom frequency range' }].forEach((preset) => {
      const choice = node('label', 'ui-choice-card spectrum-search-band-option');
      const check = node('input', 'ui-selection-check');
      check.type = 'checkbox';
      check.value = preset.id;
      check.checked = selectedBandIds.has(preset.id);
      check.addEventListener('change', () => {
        if (check.checked) selectedBandIds.add(preset.id); else selectedBandIds.delete(preset.id);
        updateBands();
      });
      const [label, detail] = preset.id === 'custom' ? ['Custom range', 'Enter a start and end frequency'] :
        preset.label.split(' · ');
      check.setAttribute('aria-label', preset.id === 'custom' ? label : preset.label);
      const copy = node('span', 'spectrum-search-band-option-copy');
      copy.append(node('strong', '', label));
      if (detail) copy.append(node('small', 'ui-field-detail', detail));
      choice.append(check, copy);
      bandMenu.append(choice);
    });
    const positionBandMenu = () => {
      if (!bandMenu.matches(':popover-open')) return;
      const gutter = 8;
      const gap = 6;
      const anchor = bandTrigger.getBoundingClientRect();
      bandMenu.style.minWidth = `${Math.round(anchor.width)}px`;
      bandMenu.style.maxHeight = '';
      const menu = bandMenu.getBoundingClientRect();
      const availableBelow = Math.max(0, window.innerHeight - anchor.bottom - gap - gutter);
      const availableAbove = Math.max(0, anchor.top - gap - gutter);
      const openAbove = availableBelow < Math.min(menu.height, 160) && availableAbove > availableBelow;
      const maxHeight = Math.min(350, openAbove ? availableAbove : availableBelow);
      const top = openAbove ? Math.max(gutter, anchor.top - gap - Math.min(menu.height, maxHeight)) :
        anchor.bottom + gap;
      const left = Math.max(gutter, Math.min(anchor.left, window.innerWidth - menu.width - gutter));
      bandMenu.style.left = `${Math.round(left)}px`;
      bandMenu.style.top = `${Math.round(top)}px`;
      bandMenu.style.maxHeight = `${Math.floor(maxHeight)}px`;
    };
    const bandEvents = new AbortController();
    bandMenu.addEventListener('toggle', (event) => {
      const open = event.newState === 'open';
      bandTrigger.setAttribute('aria-expanded', String(open));
      if (open) {
        positionBandMenu();
        window.requestAnimationFrame(() => {
          if (!current() || !bandMenu.matches(':popover-open') ||
            bandMenu.contains(document.activeElement)) return;
          const choices = [...bandMenu.querySelectorAll('input:not(:disabled)')];
          (choices.find((choice) => choice.checked) || choices[0])?.focus();
        });
      }
    }, { signal: bandEvents.signal });
    bandTrigger.addEventListener('blur', () => window.requestAnimationFrame(() => {
      if (bandMenu.matches(':popover-open') && !bandMenu.contains(document.activeElement) &&
        document.activeElement !== bandTrigger) bandMenu.hidePopover();
    }), { signal: bandEvents.signal });
    bandTrigger.addEventListener('keydown', (event) => {
      if (event.key === 'Escape' && bandMenu.matches(':popover-open')) {
        event.stopPropagation();
        bandMenu.hidePopover();
        return;
      }
      if (!['ArrowDown', 'ArrowUp'].includes(event.key)) return;
      event.preventDefault();
      if (!bandMenu.matches(':popover-open')) bandMenu.showPopover();
      window.requestAnimationFrame(() => {
        const choices = [...bandMenu.querySelectorAll('input:not(:disabled)')];
        (event.key === 'ArrowUp' ? choices.at(-1) : choices[0])?.focus();
      });
    }, { signal: bandEvents.signal });
    bandMenu.addEventListener('keydown', (event) => {
      if (event.key === 'Escape') event.stopPropagation();
    }, { signal: bandEvents.signal });
    bandMenu.addEventListener('focusout', () => window.requestAnimationFrame(() => {
      if (bandMenu.matches(':popover-open') && !bandMenu.contains(document.activeElement) &&
        document.activeElement !== bandTrigger) bandMenu.hidePopover();
    }), { signal: bandEvents.signal });
    window.addEventListener('resize', positionBandMenu, { signal: bandEvents.signal });
    window.addEventListener('scroll', positionBandMenu,
      { signal: bandEvents.signal, capture: true, passive: true });
    disposeBandPicker = () => {
      bandEvents.abort();
      if (bandMenu.matches(':popover-open')) bandMenu.hidePopover();
    };
    chooser.addEventListener('change', updateBands);
    form.append(formField('Receiver', uiSelectFrame(chooser)), bands, custom, description, coverageNotice,
      node('div', 'ui-notice', 'The receiver scans each band, then checks promising signals.'), directory.element);
    void directory.load();
    form.addEventListener('submit', (event) => event.preventDefault());
    stage.append(form);
    const disabledReceivers = (catalog.tuners || []).map(receiverPreparationState)
      .filter((state) => state.isDisabled);
    if (disabledReceivers.length) {
      const otherReceivers = node('section', 'spectrum-search-unavailable');
      otherReceivers.append(node('h4', '', 'Disabled receivers'), receiverList(disabledReceivers));
      stage.append(otherReceivers);
    }
    updateContext();
    button('Cancel', () => modal.close());
    findSignals = button('Find signals', () => {
      if (!form.reportValidity()) return;
      const ranges = selectedRanges();
      if (!ranges?.length || ranges.some((range) => range.minimum_hz >= range.maximum_hz) ||
        ranges.reduce((total, range) => total + range.maximum_hz - range.minimum_hz, 0) > catalog.bounds.maximum_total_hz) {
        error('Choose a valid band, or enter a smaller range with the end frequency above the start.');
        return;
      }
      if (configuredRangeFailures().length) {
        updateBands();
        return;
      }
      void begin(ranges);
    }, true);
    updateBands();
  };
  const restorePreparedCatalog = () => {
    if (!catalog || !preparedCatalogTuners.size) return;
    catalog.tuners = (catalog.tuners || []).map((tuner) => preparedCatalogTuners.get(tuner.id) || tuner);
    preparedCatalogTuners.clear();
  };
  const refreshReceiverCatalog = async () => {
    const refreshed = await request(`${path}/catalog`, { csrf: false });
    catalog = refreshed;
    preparedCatalogTuners.clear();
  };
  const begin = (ranges) => trackReceiverUse(() => beginSearch(ranges));
  const beginSearch = async (ranges) => {
    const operation = ++generation;
    setBusy(true);
    try {
      await directory.load();
      const directoryStateId = directory.stateId();
      await releaseJob();
      if (!current() || operation !== generation) return;
      const reusingPreparedReceiver = Boolean(lease?.lease_id && usedReceiverId === receiverId);
      if (!reusingPreparedReceiver) await releaseLease();
      if (!current() || operation !== generation) return;
      const id = receiverId;
      usedReceiverId = id;
      const acquired = await request(browsePath(id), { method: 'POST',
        body: reusingPreparedReceiver ? { lease_id: lease.lease_id } :
          String(receiver()?.operator_state || 'live').toLowerCase() === 'setup' ? {} : { takeover: true } });
      if (!current() || operation !== generation) {
        await releaseLateLease(acquired, id);
        return;
      }
      lease = acquired;
      updateReceiverMeasurements(acquired.tuner, id);
      const created = await request(path, { method: 'POST', body: {
        tuner_id: id, browse_lease_id: lease.lease_id, ranges,
        radioreference_state_id: directoryStateId
      }, timeoutMs: 30000 });
      if (!current() || operation !== generation) {
        job = created;
        releasedJobId = null;
        retiringJobId = null;
        await releaseJob({ bestEffort: true });
        return;
      }
      job = created;
      releasedJobId = null;
      retiringJobId = null;
      selected.clear();
      drafts.clear();
      groups.clear();
      wantsAliasImport = true;
      aliasImportResult = null;
      aliasImportSystemIds = new Set();
      aliasImportSelections = [];
      modal.setDirty(false);
      scheduleRenewal();
      drawJob();
    } catch (cause) {
      try { await releaseLease(); } catch (releaseCause) {
        if (current() && operation === generation) {
          error('The search did not start, and this receiver could not be released yet. Try again.', releaseCause);
        }
        return;
      }
      if (!current() || operation !== generation) return;
      restorePreparedCatalog();
      try { await refreshReceiverCatalog(); } catch (_) {
        /* The original catalog row is safer than leaving the borrowed receiver shown as idle. */
      }
      if (current() && operation === generation) {
        showBand();
        error(cause.code === 'invalid_request' ?
          cause.message || 'This search is too large for this receiver. Choose a smaller range or a wider receiver.' :
          'We couldn’t start the search. The receiver was restored; check it and try again.', cause);
      }
    } finally { if (current() && operation === generation) setBusy(false); }
  };
  const restart = async () => {
    const operation = ++generation;
    setBusy(true);
    let step = 'job';
    try {
      await releaseJob();
      step = 'receiver';
      await releaseLease();
      step = 'catalog';
      if (!current() || operation !== generation) return;
      await refreshReceiverCatalog();
      if (!current() || operation !== generation) return;
      modal.setDirty(false);
      showBand();
    } catch (cause) {
      if (!current() || operation !== generation) return;
      show('failed', 0, 'Choose where to look');
      error(step === 'job' ?
        'The previous search could not be stopped yet. Try again before starting another search.' :
        step === 'receiver' ?
        (lease?.takeover ?
          'This receiver could not resume its channels yet. Try again before starting another search.' :
          'This receiver could not be released yet. Try again before starting another search.') :
        'We couldn’t refresh the receiver list. Check your connection and try again.', cause);
      button('Close', () => modal.close());
      button('Try again', () => void restart(), true);
    } finally { if (current() && operation === generation) setBusy(false); }
  };
  const drawProgress = () => {
    if (!progressNodes) {
      show('progress', 1, job.phase === 'checking' ? 'Check signals' : 'Find signals');
      const notice = node('div', 'ui-feedback ui-feedback-loading');
      notice.setAttribute('role', 'status');
      const status = node('div', 'spectrum-discovery-status');
      const details = node('div', 'spectrum-discovery-status');
      const heading = node('strong');
      const copy = node('p');
      const count = node('p', 'muted');
      details.append(heading, count, copy);
      notice.append(details);
      status.append(notice);
      stage.append(status);
      button('Stop search', () => modal.close());
      progressNodes = { title: stage.querySelector('h3'), notice, heading, copy, count };
    }
    const checking = job.phase === 'checking';
    progressNodes.title.textContent = checking ? 'Check signals' : 'Find signals';
    const progress = job.progress || {};
    const currentCenter = Number(progress.current_frequency_hz);
    const hasFrequency = Number.isFinite(currentCenter) && currentCenter > 0;
    progressNodes.notice.classList.add('ui-feedback-loading');
    progressNodes.heading.textContent = checking ? (hasFrequency ?
      `Checking ${channelMHz(currentCenter)} MHz` : 'Checking signals') : 'Searching selected bands';
    progressNodes.copy.textContent = checking ? 'Each signal can take up to 30 seconds.' : '';
    progressNodes.copy.hidden = !checking;
    if (!checking && lease?.lease_id && receiverId === usedReceiverId &&
        Number.isFinite(currentCenter) && currentCenter > 0)
      updateReceiverMeasurements({ id: usedReceiverId, frequency_hz: currentCenter }, usedReceiverId);
    updateContext();
    if (checking) void refreshCheckingMeasurements();
    progressNodes.count.textContent = checking ? `${progress.checked || 0} of ${progress.total_signals || 0} signals checked` :
      `${progress.completed || 0} of ${progress.total || 0} band sections searched${hasFrequency ?
        ` · ${channelMHz(currentCenter)} MHz` : ''}`;
    pollTimer = window.setTimeout(() => void poll(), 750);
  };
  const poll = async () => {
    if (!current() || !liveJob()) return;
    const operation = generation;
    const observed = job;
    const directoryRefresh = job.phase === 'complete';
    if (directoryRefresh && busy) { scheduleDirectoryPoll(); return; }
    try {
      const updated = await request(jobPath(), { csrf: false });
      if (!discoverySnapshotIsCurrent(observed, job, { active: current() && operation === generation && liveJob(), busy })) return;
      job = updated;
      clearFeedback();
      actions.querySelector('[data-directory-retry]')?.remove();
      if (job.restart_required) drawJob();
      else if (directoryRefresh && updated.phase === 'complete' && currentStep === 'review') {
        refreshReviewDirectory();
        scheduleDirectoryPoll();
      } else if (directoryRefresh && updated.phase === 'complete' && currentStep === 'results') {
        refreshResultsDirectory();
        scheduleDirectoryPoll();
      } else if (directoryRefresh && updated.phase === 'complete' && currentStep === 'aliases') {
        refreshDirectoryWait?.();
        scheduleDirectoryPoll();
      } else if (directoryRefresh && updated.phase === 'complete' && pendingDirectory()) scheduleDirectoryPoll();
      else drawJob();
    } catch (cause) {
      if (!discoverySnapshotIsCurrent(observed, job, { active: current() && operation === generation && liveJob(), busy })) return;
      if (isExpired(cause)) { showExpired(); return; }
      if (directoryRefresh) {
        error(pendingSelectedDirectory() ?
          'RadioReference names could not be refreshed. Retry the lookup before adding the selected channels.' :
          'RadioReference names could not be refreshed. Retry to check the remaining systems.', cause);
        refreshDirectoryWait?.();
        if (!actions.querySelector('[data-directory-retry]')) {
          const retry = button('Retry directory lookup', () => { retry.remove(); void poll(); });
          retry.dataset.directoryRetry = 'true';
        }
        return;
      }
      if (progressNodes) {
        progressNodes.notice.classList.remove('ui-feedback-loading');
        progressNodes.heading.textContent = 'Last reported progress';
        progressNodes.copy.hidden = true;
      }
      error('We lost the connection during the search. Retry to check its progress, or stop.', cause);
      actions.replaceChildren();
      button('Stop search', () => modal.close());
      button('Retry connection', () => { progressNodes = null; void poll(); }, true);
    }
  };
  const drawJob = () => {
    window.clearTimeout(pollTimer);
    if (job.restart_required) showSaved();
    else if (['scanning', 'checking'].includes(job.phase)) drawProgress();
    else if (job.phase === 'complete') showResults();
    else if (candidates().some((candidate) => candidate.saved)) showSaved();
    else {
      show('failed', 1, 'Search stopped');
      stage.append(node('div', 'ui-notice ui-notice-warning', job.reason || 'The search could not finish. Try again with an idle receiver.'));
      button('Close', () => modal.close());
      button('Try another search', () => void restart(), true);
    }
  };
  const groupFor = (candidate) => (job.alias_groups || []).find((group) =>
    group.group_id === candidate.alias_group_id);
  const groupedCandidates = (values) => {
    const ordered = [];
    const byKey = new Map();
    values.forEach((candidate) => {
      const key = spectrumSearchGroupKey(candidate);
      if (!byKey.has(key)) {
        const entry = { key, aliasGroup: groupFor(candidate), candidates: [] };
        byKey.set(key, entry);
        ordered.push(entry);
      }
      byKey.get(key).candidates.push(candidate);
    });
    return ordered;
  };
  const siteLabel = (candidate) => {
    if (candidate.site_name) return candidate.site_name;
    const identity = candidate.identity || candidate.trunked_evidence?.identity;
    return identity?.wacn != null ?
      `RFSS ${formatHexDecimal(identity.rfss, 2)} · Site ${formatHexDecimal(identity.site, 2)}` :
      identity?.site != null ? `Site ${identity.site}` : 'Site unavailable';
  };
  const systemIdentityLabel = (entry) => {
    const candidate = entry.candidates[0];
    const identity = candidate?.identity || candidate?.trunked_evidence?.identity || entry.aliasGroup;
    const count = entry.candidates.length;
    const label = identity?.wacn != null && identity?.system != null ?
      `WACN ${formatHexDecimal(identity.wacn, 5)} · SysID ${formatHexDecimal(identity.system, 3)}` :
      systemIdentity({ ...candidate, radio_system_key: candidate?.radio_system_key || identity?.radio_system_key }) ||
      identityText(candidate);
    return `${label} · ${count} ${count === 1 ? 'site' : 'sites'}`;
  };
  const candidateDescription = (candidate) => [spectrumSearchProtocolLabel(candidate, protocols?.profiles),
    candidate.modulation].filter(Boolean).join(' · ');
  const groupRepresentative = (members) => members.find((candidate) =>
    candidate.radio_reference?.state === 'matched' && candidate.radio_reference.match?.system_name) ||
    members.find((candidate) => candidate.radio_reference?.state === 'pending') || members[0];
  const refreshGroupDirectory = (binding) => {
    const members = binding.candidateIds.map((id) => candidates().find((candidate) => candidate.candidate_id === id))
      .filter(Boolean);
    if (!members.length) return;
    const observed = candidates().filter((candidate) =>
      spectrumSearchGroupKey(candidate) === spectrumSearchGroupKey(members[0]));
    const representative = groupRepresentative(observed);
    const title = systemLabel(representative);
    const url = directory.available() ? discoveryRadioReferenceSystemUrl(representative) : null;
    const signature = JSON.stringify([title, url]);
    if (binding.titleSignature !== signature) {
      binding.title.replaceChildren(url ? anchor(title, url) : document.createTextNode(title));
      binding.titleSignature = signature;
    }
    const lookup = representative.radio_reference?.state === 'matched' ? null :
      members.find((candidate) => candidate.radio_reference?.state === 'pending') ||
      members.find((candidate) => candidate.radio_reference);
    const result = directory.available() && lookup ? discoveryRadioReferenceResult(ui, lookup, binding.directoryResult) : null;
    const returnDirectoryFocus = !result && binding.directoryHost.contains(document.activeElement);
    if (result !== binding.directoryResult) binding.directoryHost.replaceChildren(...(result ? [result] : []));
    binding.directoryResult = result;
    binding.directoryHost.hidden = !result;
    if (returnDirectoryFocus) binding.title.querySelector('a')?.focus({ preventScroll: true });
    if (binding.identity) binding.identity.textContent = systemIdentityLabel({ candidates: members });
    if (binding.groupCheck) binding.groupCheck.setAttribute('aria-label', `Select all channels in ${title}`);
    if (binding.draft && !binding.draft.alias_name_edited) {
      binding.draft.new_alias_list_name = discoveryAliasListName(title, binding.draft.new_alias_list_name);
      binding.aliasChoice?.refreshName(binding.draft.new_alias_list_name);
      binding.syncAliasSummary?.();
    }
  };
  const refreshOpenDetails = () => {
    if (!detailBinding) return;
    const candidate = candidates().find((candidate) => candidate.candidate_id === detailBinding.id);
    if (!candidate) return;
    const health = candidate.health || {};
    const identity = identityFacts(candidate).filter(([label]) => label !== 'Protocol');
    const entries = [
      ['System', systemLabel(candidate)], ['Site', siteLabel(candidate)],
      ['Protocol', spectrumSearchProtocolLabel(candidate, protocols?.profiles)]
    ];
    const signal = [
      ['Signal', candidate.strength_dbfs == null ? null : `${Number(candidate.strength_dbfs).toFixed(1)} dBFS`],
      ['Modulation', candidate.modulation],
      ['Decoder quality', health.quality_pct == null ? null : `${Math.round(health.quality_pct)}%`],
      ['Valid control messages', health.valid_control_messages],
      ['Invalid control messages', health.invalid_control_messages],
      ['Last checked', health.checked_at_ms ? new Date(health.checked_at_ms).toLocaleTimeString() : 'During this search']
    ];
    const signature = JSON.stringify([entries, identity, signal]);
    if (detailBinding.signature !== signature) {
      detailBinding.system.replaceChildren(factList(entries));
      detailBinding.identity.replaceChildren(factList(identity));
      detailBinding.identitySection.hidden = identity.length === 0;
      detailBinding.signal.replaceChildren(factList(signal));
      detailBinding.signature = signature;
    }
  };
  const showCandidateDetails = (id) => {
    const candidate = candidates().find((value) => value.candidate_id === id);
    if (!candidate) return;
    const content = node('div', 'ui-editor-sections');
    const section = (label) => {
      const panel = node('fieldset', 'ui-form-section');
      const body = node('div');
      panel.append(node('legend', '', label), body);
      content.append(panel);
      return { panel, body };
    };
    const system = section('Channel');
    const identity = section('On-air identity');
    const signal = section('Signal health');
    const footer = node('footer', 'ui-modal-footer ui-action-row');
    const back = uiActionButton('Back to channels', '', () => details.close(), 'ui-button ui-button-secondary');
    footer.append(back);
    content.append(footer);
    const details = openReadOnlyModal(`Signal details · ${channelMHz(candidate.frequency_hz)} MHz`, content, {
      id: 'spectrum-search-signal', className: 'spectrum-search-detail-modal', stack: 'child',
      cleanup: () => { if (detailBinding?.id === id) detailBinding = null; }
    });
    if (!details) return;
    detailBinding = { id, modal: details, system: system.body, identity: identity.body,
      identitySection: identity.panel, signal: signal.body };
    refreshOpenDetails();
  };
  const refreshResultsDirectory = () => {
    if (!resultBindings) return;
    const latest = new Map(candidates().map((candidate) => [candidate.candidate_id, candidate]));
    const changedStructure = latest.size !== resultBindings.rows.length || resultBindings.rows.some(({ candidate }) => {
      const updated = latest.get(candidate.candidate_id);
      return !updated || spectrumSearchGroupKey(updated) !== spectrumSearchGroupKey(candidate) ||
        updated.selectable !== candidate.selectable || updated.saved !== candidate.saved ||
        updated.known_channel?.configuration_id !== candidate.known_channel?.configuration_id;
    });
    if (changedStructure) {
      selected.forEach((id) => {
        const candidate = latest.get(id);
        if (!candidate || candidate.selectable === false || candidate.known_channel || candidate.saved) selected.delete(id);
      });
      showResults();
      return;
    }
    resultBindings.rows.forEach((binding) => {
      const candidate = latest.get(binding.candidate.candidate_id);
      binding.candidate = candidate;
      binding.label.textContent = candidateDescription(candidate);
      binding.site.textContent = siteLabel(candidate);
      binding.check.setAttribute('aria-label', `Select ${candidateName(candidate)}`);
    });
    resultBindings.sections.forEach((binding) => {
      refreshGroupDirectory(binding);
    });
    refreshOpenDetails();
    resultOverflows.forEach((overflow) => overflow.refresh());
    resultBindings.updateSelection();
  };
  const showResults = () => {
    const found = candidates().length;
    show('results', 1, `${found} ${found === 1 ? 'channel' : 'channels'} found`);
    if (job.truncated_reason) stage.append(node('div', 'ui-notice ui-notice-warning',
      'This search reached its signal limit. Try a smaller range to check the remaining signals.'));
    const toolbar = node('div', 'spectrum-search-results-toolbar');
    const heading = node('div', 'spectrum-search-results-heading');
    heading.append(node('strong', '', 'Grouped by trunked system'), node('span', 'muted'));
    const count = heading.lastChild;
    toolbar.append(heading);
    const rows = [];
    const sections = [];
    const directoryWait = createDiscoveryRadioReferenceWait(ui);
    const review = button('Review selected', showReview, true);
    refreshDirectoryWait = () => {
      directoryWait.update(selected.size ? selectedDirectoryCandidates() : candidates(), review, busy || selected.size === 0);
    };
    const another = button('Search another band', () => void restart());
    actions.prepend(another);
    const updateSelection = () => {
      count.textContent = `${selected.size} selected`;
      review.textContent = selected.size ? `Review ${selected.size} ${selected.size === 1 ? 'channel' : 'channels'}` :
        'Review selected';
      sections.forEach(({ groupCheck, members }) => {
        const available = members.filter(({ check }) => !check.disabled);
        const chosen = available.filter(({ candidate }) => selected.has(candidate.candidate_id));
        groupCheck.disabled = available.length === 0;
        groupCheck.checked = available.length > 0 && chosen.length === available.length;
        groupCheck.indeterminate = chosen.length > 0 && chosen.length < available.length;
      });
      refreshDirectoryWait?.();
    };
    const selectAll = button('Select all available', () => {
      rows.forEach(({ candidate, check }) => {
        if (!check.disabled) { check.checked = true; selected.add(candidate.candidate_id); }
      });
      updateSelection();
    }, false, toolbar);
    toolbar.append(selectAll);
    const list = node('div', 'spectrum-search-system-list');
    groupedCandidates(candidates()).forEach((entry) => {
      const section = node('section', 'spectrum-search-system-group');
      const sectionHeader = node('header', 'spectrum-search-system-header');
      const groupCheck = node('input', 'ui-selection-check');
      groupCheck.type = 'checkbox';
      groupCheck.setAttribute('aria-label', `Select all channels in ${systemLabel(entry.candidates[0])}`);
      const identity = node('div', 'spectrum-search-system-identity');
      const groupTitle = node('strong', '', systemLabel(entry.candidates[0]));
      const groupIdentity = node('span', 'muted', systemIdentityLabel(entry));
      identity.append(groupTitle, groupIdentity);
      const tableActions = node('div', 'ui-section-actions');
      if (entry.candidates.some((candidate) => candidate.identity || candidate.trunked_evidence?.identity?.radio_system_key)) tableActions.append(uiPill('Stable identity', 'success'));
      sectionHeader.append(groupCheck, identity, tableActions);
      const wrapper = node('div', 'ui-table-wrap');
      const table = node('table', 'ui-data-table ui-data-table-quiet ui-mobile-cards spectrum-search-group-table');
      const columns = node('colgroup');
      ['selection', 'channel', 'site', 'signal', 'quality', 'details'].forEach((name) =>
        columns.append(node('col', `spectrum-search-column-${name}`)));
      const head = node('tr');
      ['', 'Channel', 'Site', 'Signal', 'Quality', 'Details'].forEach((label) => {
        const cell = node('th', '', label); cell.scope = 'col';
        if (!label) cell.setAttribute('aria-label', 'Select channels');
        head.append(cell);
      });
      const thead = node('thead'); thead.append(head);
      const body = node('tbody');
      const members = [];
      entry.candidates.forEach((candidate) => {
        const row = node('tr');
        const cells = ['', 'Channel', 'Site', 'Signal', 'Quality', 'Details'].map((label) => {
          const cell = node('td'); cell.dataset.label = label; row.append(cell); return cell;
        });
        const check = node('input', 'ui-selection-check spectrum-search-channel-check');
        check.type = 'checkbox';
        check.setAttribute('aria-label', `Select ${candidateName(candidate)}`);
        check.disabled = candidate.selectable === false || Boolean(candidate.known_channel) || candidate.saved === true;
        check.checked = selected.has(candidate.candidate_id);
        check.addEventListener('change', () => {
          if (check.checked) selected.add(candidate.candidate_id); else selected.delete(candidate.candidate_id);
          updateSelection();
        });
        const channel = node('div', 'identity-summary');
        const label = node('span', 'muted', candidateDescription(candidate));
        channel.append(node('strong', 'identity-summary-primary', `${channelMHz(candidate.frequency_hz)} MHz`),
          label);
        if (candidate.known_channel) {
          const known = candidate.known_channel;
          const link = entityRefHref(known.entity_ref) || (known.configuration_id ?
            href('channel', { configuration_id: known.configuration_id }) : null);
          channel.append(uiPill('Already added'), link ? anchor(known.name || 'Open channel', link) :
            node('span', 'muted', known.name || 'Known channel'));
        } else if (check.disabled) channel.append(node('span', 'muted', candidate.reason || 'Unavailable to add'));
        cells[0].append(check);
        cells[1].append(channel);
        const site = node('span', '', siteLabel(candidate));
        cells[2].append(site);
        const strength = Number.isFinite(Number(candidate.strength_dbfs)) && candidate.strength_dbfs != null ?
          `${Number(candidate.strength_dbfs).toFixed(1)} dBFS` : '—';
        cells[3].append(node('span', '', strength));
        const health = candidate.health || {};
        cells[4].append(node('strong', '', health.quality_pct == null ? '—' : `${Math.round(health.quality_pct)}%`));
        const details = uiActionButton('Details', '', () => showCandidateDetails(candidate.candidate_id),
          'ui-button ui-button-secondary');
        details.setAttribute('aria-label', `Details for ${channelMHz(candidate.frequency_hz)} MHz`);
        cells[5].append(details);
        row.hidden = false;
        const member = { candidate, row, check, label, site };
        members.push(member);
        rows.push(member);
        body.append(row);
      });
      groupCheck.addEventListener('change', () => {
        members.forEach(({ candidate, check }) => {
          if (check.disabled) return;
          check.checked = groupCheck.checked;
          if (groupCheck.checked) selected.add(candidate.candidate_id); else selected.delete(candidate.candidate_id);
        });
        updateSelection();
      });
      table.append(columns, thead, body);
      wrapper.append(table);
      const directoryHost = node('div', 'spectrum-search-system-directory');
      section.append(sectionHeader, directoryHost, wrapper);
      const overflow = createTableOverflow({ wrapper, table, actionsHost: tableActions, iconButton,
        signal: abort.signal, label: `${systemLabel(entry.candidates[0])} search results` });
      resultOverflows.add(overflow);
      const binding = { section, groupCheck, members, title: groupTitle, identity: groupIdentity, directoryHost,
        candidateIds: entry.candidates.map((candidate) => candidate.candidate_id) };
      sections.push(binding);
      refreshGroupDirectory(binding);
      list.append(section);
    });
    selectAll.disabled = rows.every(({ check }) => check.disabled);
    stage.append(toolbar, directoryWait.element, found ? list : node('div', 'ui-empty-state',
      'No trunked control channels were identified. Try another band or a different receiver.'));
    resultOverflows.forEach((overflow) => overflow.refresh());
    updateSelection();
    resultBindings = { rows, sections, updateSelection };
    scheduleDirectoryPoll();
  };
  const showReview = () => {
    if (job.restart_required) { showSaved(); return; }
    if (pendingSelectedDirectory()) { refreshDirectoryWait?.(); scheduleDirectoryPoll(); return; }
    const chosen = selectedCandidates();
    show('review', 2, `Review ${chosen.length} ${chosen.length === 1 ? 'channel' : 'channels'}`);
    const form = node('form', 'spectrum-discovery-review spectrum-search-review-form');
    form.addEventListener('submit', (event) => event.preventDefault());
    const systemList = node('div', 'spectrum-search-review-systems');
    const mapEditors = [];

    groupedCandidates(chosen).forEach((entry, index) => {
      const representative = groupRepresentative(candidates().filter((candidate) =>
        spectrumSearchGroupKey(candidate) === entry.key));
      const group = entry.aliasGroup;
      const lists = group?.alias_lists || [];
      const draft = group ? groups.get(group.group_id) || {
        alias_list_id: null,
        new_alias_list_name: discoveryAliasListName(systemLabel(representative), group.default_new_alias_list_name)
      } : null;
      if (draft) groups.set(group.group_id, draft);
      const section = node('details', 'ui-section-disclosure spectrum-search-review-system');
      section.name = `${modal.dialog.id}-review-systems`;
      section.open = index === 0;
      const sectionHeader = node('summary', 'ui-section-summary spectrum-search-review-system-header');
      const sectionBody = node('div', 'spectrum-search-review-system-body');
      const title = node('div', 'spectrum-search-review-system-title');
      const aliasSummary = node('span', 'muted');
      const syncAliasSummary = () => {
        const existing = lists.find((aliasList) => aliasList.id === Number(draft?.alias_list_id));
        aliasSummary.textContent = existing ? `Alias List: ${existing.name} · Existing` : draft?.alias_list_id === 0 ?
          `Alias List: ${draft.new_alias_list_name || 'Name required'} · New` : draft ? 'Choose an Alias List' : 'Alias List unavailable';
      };
      const groupTitle = node('h4', 'ui-record-title', systemLabel(representative));
      title.append(groupTitle, aliasSummary);
      const settings = node('div', 'spectrum-search-review-settings');
      const count = entry.candidates.length;
      sectionHeader.append(title, node('span', 'muted', `${count} ${count === 1 ? 'channel' : 'channels'}`));
      const rows = node('div', 'spectrum-search-review-channel-list');
      const channelFields = node('div', 'channel-editor-grid spectrum-search-channel-fields');
      const directoryHost = node('div');
      const groupBinding = { section, title: groupTitle, directoryHost, draft, syncAliasSummary,
        candidateIds: entry.candidates.map((candidate) => candidate.candidate_id) };
      reviewGroupBindings.push(groupBinding);
      refreshGroupDirectory(groupBinding);
      entry.candidates.forEach((candidate) => {
        const channelDraft = drafts.get(candidate.candidate_id) || { name: candidateName(candidate) };
        const binding = { id: candidate.candidate_id, draft: channelDraft };
        drafts.set(candidate.candidate_id, channelDraft);
        const row = node('div', 'spectrum-search-review-channel');
        const frequency = node('span'); frequency.dataset.label = 'Frequency';
        frequency.append(node('strong', '', `${channelMHz(candidate.frequency_hz)} MHz`));
        const site = node('span', '', siteLabel(candidate)); site.dataset.label = 'Site';
        const name = node('span', '', channelDraft.name); name.dataset.label = 'Channel name';
        row.append(frequency, site, name);
        Object.assign(binding, { site, name });
        reviewBindings.push(binding);
        if (candidate.saved || candidate.known_channel) row.append(uiPill(candidate.saved ? 'Added' : 'Already added',
          candidate.saved ? 'success' : 'neutral'));
        else {
          const input = node('input', 'ui-input'); input.type = 'text'; input.required = true; input.maxLength = 255;
          input.value = channelDraft.name;
          input.setAttribute('aria-label', `Channel name for ${channelMHz(candidate.frequency_hz)} MHz`);
          input.addEventListener('input', () => {
            channelDraft.name = input.value;
            channelDraft.name_edited = true;
            modal.setDirty(true);
          });
          binding.input = input;
          binding.name = null;
          name.replaceWith(formField('Channel name', input));
          const profile = protocols?.profiles?.find((profile) => profile.id === candidate.protocol_id);
          const mapField = profile?.sections?.flatMap((section) => section.fields || [])
            .find((field) => field.type === 'frequency_map');
          if (mapField && channelEditorControl && channelEditorFieldValue) {
            const createMap = (values) => {
              const control = channelEditorControl(mapField, profile, {}, { frequency_map: values }, protocols);
              const preserveMap = () => { modal.setDirty(true); channelDraft.map_draft = spectrumSearchMapDraft(control); };
              control.addEventListener('input', preserveMap);
              control.addEventListener('change', preserveMap);
              return control;
            };
            const map = createMap(channelDraft.map_draft || channelDraft.frequency_map || candidate.trunked_evidence?.frequency_map || []);
            const editor = { map, field: mapField, draft: channelDraft };
            mapEditors.push(editor);
            Object.assign(binding, { map, createMap, editor, mapValues: JSON.stringify(candidate.trunked_evidence?.frequency_map || []) });
            const mapWrapper = formField(`${mapField.label} · ${channelMHz(candidate.frequency_hz)} MHz`, map,
              'Enter the verified channel numbers and frequencies. ' +
              'Unmapped channels can be followed only when the system broadcasts their frequencies.');
            mapWrapper.classList.add('channel-wide-field');
            channelFields.append(mapWrapper);
          }
        }
        rows.append(row);
      });
      if (draft) {
        const aliasChoice = createDiscoveryAliasListChoice(ui, {
          lists, aliasListId: draft.alias_list_id, newAliasListName: draft.new_alias_list_name,
          suggestedAliasListId: group.suggested_alias_list_id,
          labelSuffix: ` for ${aliasIdentityLabel(entry.candidates[0])}`,
          nameEdited: Boolean(draft.alias_name_edited),
          ...(draft.alias_choice_edited != null ? { choiceEdited: draft.alias_choice_edited } : {}),
          onChange: ({ alias_list_id, new_alias_list_name, name_edited, choice_edited }) => {
            draft.alias_list_id = alias_list_id;
            draft.new_alias_list_name = new_alias_list_name;
            draft.alias_name_edited ||= name_edited;
            draft.alias_choice_edited ||= choice_edited;
            if (name_edited || choice_edited) modal.setDirty(true);
            syncAliasSummary();
            refreshReviewImportAction?.();
          }
        });
        groupBinding.aliasChoice = aliasChoice;
        draft.alias_list_id = aliasChoice.value();
        draft.new_alias_list_name = aliasChoice.newName();
        sectionBody.append(aliasChoice.element);
      }
      if (channelFields.childElementCount) settings.append(channelFields);
      settings.append(directoryHost);
      if (group) settings.append(disclosure('System identity', factList(identityFacts(entry.candidates[0]))));
      syncAliasSummary();
      sectionBody.prepend(rows);
      sectionBody.append(settings);
      section.append(sectionHeader, sectionBody);
      systemList.append(section);
    });

    form.append(systemList);
    const directoryWait = createDiscoveryRadioReferenceWait(ui);
    stage.append(directoryWait.element, form);
    button('Back', showResults);
    const aliasGroups = groupedCandidates(chosen).filter((entry) => entry.aliasGroup);
    const addLabel = `Add ${chosen.length} ${chosen.length === 1 ? 'channel' : 'channels'}`;
    const importTargets = () => discoveryAliasImportTargets(aliasGroups.map((entry) => {
      const draft = groups.get(entry.aliasGroup.group_id);
      const members = candidates().filter((candidate) => spectrumSearchGroupKey(candidate) === entry.key);
      const aliasListId = draft?.alias_list_id;
      return { groupId: entry.aliasGroup.group_id, values: members, aliasListId,
        name: Number(aliasListId) > 0 ? entry.aliasGroup.alias_lists?.find((list) => list.id === Number(aliasListId))?.name :
          draft?.new_alias_list_name,
        systemName: systemLabel(groupRepresentative(members)) };
    }), { available: directory.available() });
    const add = button(importTargets().length ? 'Continue' : addLabel, async () => {
      if (busy || pendingSelectedDirectory()) { refreshReviewImportAction?.(); return; }
      const invalidChoice = reviewGroupBindings.find((binding) =>
        binding.aliasChoice?.element.querySelector('input:invalid, select:invalid'));
      if (invalidChoice) {
        invalidChoice.section.open = true;
        invalidChoice.aliasChoice.validate();
        return;
      }
      const invalidControl = form.querySelector('input:invalid, select:invalid, textarea:invalid');
      if (invalidControl) {
        invalidControl.closest('.spectrum-search-review-system').open = true;
        invalidControl.reportValidity();
        return;
      }
      try {
        mapEditors.forEach(({ map, field, draft }) => { draft.frequency_map = channelEditorFieldValue(map, field); });
      } catch (cause) { error(cause.message || 'Check the channel maps and try again.'); return; }
      const ids = chosen.map((candidate) => candidate.candidate_id);
      const targets = importTargets();
      if (pendingSelectedDirectory(ids)) { refreshReviewImportAction?.(); scheduleDirectoryPoll(); return; }
      aliasImportSystemIds = new Set(targets.map((target) => target.rrSystemId));
      aliasImportSelections = targets;
      if (!targets.length) { void save(ids); return; }
      show('aliases', 3, `Do you want to import talkgroups into your Alias ${targets.length === 1 ? 'List' : 'Lists'}?`);
      const choice = discoveryAliasImportChoice(ui, targets, wantsAliasImport);
      const aliasDirectoryWait = createDiscoveryRadioReferenceWait(ui);
      stage.append(aliasDirectoryWait.element, choice.element);
      button('Back', () => { wantsAliasImport = choice.wantsImport(); showReview(); });
      const addChannels = button(addLabel, () => {
        if (busy || pendingSelectedDirectory(ids)) { refreshDirectoryWait?.(); return; }
        wantsAliasImport = choice.wantsImport();
        void save(ids);
      }, true);
      refreshDirectoryWait = () => aliasDirectoryWait.update(selectedDirectoryCandidates(ids), addChannels, busy);
      refreshDirectoryWait();
      scheduleDirectoryPoll();
    }, true);
    refreshReviewImportAction = () => {
      add.textContent = importTargets().length ? 'Continue' : addLabel;
      directoryWait.update(selectedDirectoryCandidates(), add, busy || chosen.length === 0);
    };
    refreshDirectoryWait = refreshReviewImportAction;
    refreshReviewImportAction();
    scheduleDirectoryPoll();
  };
  const payload = (ids) => ({ revision: job.revision,
    candidates: candidates().filter((candidate) => ids.includes(candidate.candidate_id) && !candidate.saved && !candidate.known_channel).map((candidate) => ({
      candidate_id: candidate.candidate_id, name: drafts.get(candidate.candidate_id)?.name.trim() || candidateName(candidate),
      auto_start: false,
      ...(drafts.get(candidate.candidate_id)?.frequency_map ?
        { frequency_map: drafts.get(candidate.candidate_id).frequency_map } : {})
    })),
    alias_groups: (job.alias_groups || []).filter((group) => candidates().some((candidate) =>
      ids.includes(candidate.candidate_id) && candidate.alias_group_id === group.group_id)).map((group) => ({
      group_id: group.group_id, alias_list_id: Number(groups.get(group.group_id)?.alias_list_id || 0),
      new_alias_list_name: Number(groups.get(group.group_id)?.alias_list_id) === 0 ?
        groups.get(group.group_id)?.new_alias_list_name.trim() || null : null
    }))
  });
  const isExpired = (cause) => [404, 410].includes(cause?.status) ||
    ['search_expired', 'spectrum_search_expired'].includes(cause?.code);
  const surfaceReceiverReleaseFailure = (cause) => {
    const takeover = lease?.takeover === true;
    error(takeover ?
      'This receiver could not resume its channels yet. Try again before closing the search.' :
      'This receiver could not be released yet. Try again before closing the search.', cause);
    if (actions.querySelector('[data-retry-receiver-release]')) return;
    const retry = button(takeover ? 'Try resuming channels' : 'Try releasing receiver',
      () => void retryReceiverRelease(), true);
    retry.dataset.retryReceiverRelease = 'true';
    if (busy) setBusy(true);
  };
  const finishReceiverUse = () => {
    if (receiverFinishPromise) return receiverFinishPromise;
    const pending = trackReceiverUse(async () => {
      try {
        await releaseJob({ preserveResults: true });
        await releaseLease();
        if (current()) {
          actions.querySelector('[data-retry-receiver-release]')?.remove();
          clearFeedback();
        }
        return true;
      } catch (cause) {
        if (current()) surfaceReceiverReleaseFailure(cause);
        return false;
      }
    });
    receiverFinishPromise = pending;
    void pending.finally(() => { if (receiverFinishPromise === pending) receiverFinishPromise = null; });
    return pending;
  };
  const retryReceiverRelease = async () => {
    if (busy) return;
    setBusy(true);
    try { await finishReceiverUse(); }
    finally { if (current()) setBusy(false); }
  };
  const showExpired = () => {
    if (candidates().some((candidate) => candidate.saved)) {
      showSaved();
      error('This search expired. Your added channels are saved; open them to continue.');
      stage.querySelectorAll('button').forEach((control) => { control.disabled = true; });
    } else {
      show('expired', 2, 'This search expired');
      stage.append(node('div', 'ui-notice ui-notice-warning', 'Nothing was added. Start a new search to check the signals again.'));
      button('Close', () => modal.close());
      button('Start a new search', () => void restart(), true);
    }
    void releaseJob().then(() => finishReceiverUse()).catch((cause) => {
      if (current()) error('This search could not be closed yet. Try starting a new search again.', cause);
    });
  };
  const finishReceiverIfComplete = async () => {
    const finished = job?.restart_required || selectedCandidates().every((candidate) =>
      candidate.saved || candidate.known_channel);
    if (!finished) return false;
    return finishReceiverUse();
  };
  const save = (ids) => trackReceiverUse(() => saveChannels(ids));
  const saveChannels = async (ids) => {
    if (job.restart_required) { showSaved(); return; }
    if (pendingSelectedDirectory(ids)) {
      if (currentStep !== 'review') showReview();
      refreshReviewImportAction?.();
      scheduleDirectoryPoll();
      return;
    }
    setBusy(true);
    let added = false;
    try {
      const plan = payload(ids);
      if (plan.candidates.length) job = await request(`${jobPath()}/save`, { method: 'POST', body: plan, timeoutMs: 30000 });
      if (!current()) return;
      added = true;
      modal.setDirty(false);
      aliasImportResult = scopedAliasImport(job.alias_import) || aliasImportResult;
      showSaved();
      await finishReceiverIfComplete();
      if (!job.restart_required && wantsAliasImport && aliasImportResult?.targets?.length) await runAliasImports();
    } catch (cause) {
      if (!current()) return;
      if (isExpired(cause)) { showExpired(); return; }
      let ledgerConfirmed = false;
      try { job = await request(jobPath(), { csrf: false }); ledgerConfirmed = true; } catch (_) { /* Preserve the last confirmed ledger. */ }
      if (!current()) return;
      if (cause.code === 'channel_saved_restart_required' && !ledgerConfirmed)
        job = { ...job, restart_required: true, ledger_unavailable: true };
      if (job.restart_required || added || candidates().some((candidate) => candidate.saved)) {
        modal.setDirty(false);
        aliasImportResult = scopedAliasImport(job.alias_import) || aliasImportResult;
        showSaved();
        await finishReceiverIfComplete();
        if (!job.restart_required && wantsAliasImport && aliasImportResult?.targets?.length) await runAliasImports();
        if (!job.restart_required) error('Some channels were added. Check each result before retrying.', cause);
      } else if (cause.code === 'stale_revision' && job?.phase === 'complete') {
        showReview();
        error('Saved choices changed. Check the Alias Lists and try adding again.', cause);
      } else error('We couldn’t add your channels. Your choices are still here. Try again.', cause);
    } finally { if (current()) setBusy(false); }
  };
  const runAliasImports = async () => {
    if (job.restart_required) { showSaved(); return; }
    setBusy(true);
    try {
      aliasImportResult = await importDiscoveryAliases(request, `${jobPath()}/aliases/import`, aliasImportResult, (result) => {
        aliasImportResult = result;
        if (result.targets.some((target) => target.state === 'restart_required')) job.restart_required = true;
        show('importing', 3, 'Importing talkgroups');
        stage.append(discoveryAliasImportResults(ui, result));
      }, current);
      if (current()) showSaved();
    } finally { if (current()) setBusy(false); }
  };
  const showSaved = () => {
    const results = selectedCandidates();
    const complete = !job.restart_required && results.every((candidate) => candidate.saved || candidate.known_channel);
    const added = results.filter((candidate) => candidate.saved).length;
    show('saved', 2, complete ? `${added} ${added === 1 ? 'channel' : 'channels'} added` : 'Channel results');
    if (job.restart_required) stage.append(node('div', 'ui-notice ui-notice-warning',
      `${candidates().some((candidate) => candidate.saved) ? 'Some channels were saved, but VCE could not load the changes.' :
        'VCE needs to restart before changes can be loaded.'} Restart VCE before opening these channels, adding more, or listening. Saved channels do not need to be added again.`));
    const channels = results.map((candidate) => {
      const known = !candidate.saved && candidate.known_channel;
      const aliasGroup = job.alias_groups?.find((group) => group.group_id === candidate.alias_group_id);
      const draft = groups.get(candidate.alias_group_id);
      const chosenList = aliasGroup?.alias_lists?.find((list) => Number(list.id) === Number(draft?.alias_list_id));
      const rowActions = [];
      if (!candidate.saved && !known && !job.restart_required) {
        const retry = uiActionButton('Retry', '', () => void save([candidate.candidate_id]), 'ui-button ui-button-primary');
        retry.setAttribute('aria-label', `Retry adding ${candidateName(candidate)}`);
        rowActions.push(retry, uiActionButton('Review choices', '', showReview, 'ui-button ui-button-secondary'));
      }
      return { name: drafts.get(candidate.candidate_id)?.name || candidateName(candidate),
        system: systemLabel(candidate), system_key: spectrumSearchGroupKey(candidate), site: siteLabel(candidate),
        frequency_hz: candidate.frequency_hz, protocol: spectrumSearchProtocolLabel(candidate, protocols?.profiles),
        status: candidate.saved ? 'added' : known ? 'already_saved' : job.ledger_unavailable ? 'status_unknown' :
          job.restart_required ? 'not_added' : 'failed',
        error: !candidate.saved && !known ? candidate.save_error : null,
        ...(candidate.saved || known ? { alias_list_id: candidate.alias_list_id,
          alias_list_name: chosenList?.name || draft?.new_alias_list_name,
          ...(draft?.alias_list_id != null ? { alias_list_existing: Number(draft.alias_list_id) > 0 } : {}) } : {}),
        details: identityFacts(candidate), actions: rowActions };
    });
    stage.append(createDiscoveryCompletion(ui, { channels, grouped: results.length > 1, showDetails: false,
      imports: wantsAliasImport ? aliasImportResult : null,
      receiver: receiverDisplayName(receiver(), receiver()?.label || '') }));
    if (wantsAliasImport && !job.restart_required && aliasImportResult?.targets?.some((target) =>
      target.state === 'failed' || target.state === 'pending'))
      button('Retry talkgroup imports', () => void runAliasImports());
    button('Done', () => modal.close());
    actions.append(anchor('Open Channels', href('channel-setup'), 'ui-button ui-button-primary'));
    if (busy) setBusy(true);
  };
  const load = async () => {
    if (modal.ready && !await modal.ready || !current()) return;
    show('loading', 0, 'Preparing the search…');
    setBusy(true);
    try {
      catalog = await request(`${path}/catalog`, { csrf: false });
      preparedCatalogTuners.clear();
      if (current()) showBand();
    } catch (cause) {
      if (current()) {
        error('We couldn’t load the search options. Check your connection and try again.', cause);
        button('Close', () => modal.close());
        button('Try again', () => void load(), true);
      }
    } finally { if (current()) setBusy(false); }
  };
  void load();
  return modal;
}
