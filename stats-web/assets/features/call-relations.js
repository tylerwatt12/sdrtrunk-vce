import { systemLabel } from '../core/system-labels.js?v=2';
import { recordingProtocolLabel, recordingCallTypeLabel } from './recordings.js?v=58';

// Relationships come from the receiver; playback uses the same player as Recordings and the audio dock.
export function createCallRelations(deps) {
  const { node, openReadOnlyModal, requestJson, dateTime, anchor, href, iconGlyph, entityRefHref,
    activitySource, activityTarget, activityChannel, activityEventLabel, radioIdentifierText, groupIdentifierText,
    playback, canViewRadio, canViewRecordings } = deps;
  const join = values => [...new Set(values.filter(Boolean))].join(' · ');
  const inline = values => {
    const result = node('span');
    values.filter(Boolean).forEach((value, index) => {
      if (index) result.append(' · ');
      result.append(value);
    });
    return result;
  };
  const seconds = value => Math.max(0, Number(value) || 0);
  const clock = value => {
    const time = Math.floor(seconds(value));
    return `${Math.floor(time / 60)}:${String(time % 60).padStart(2, '0')}`;
  };
  const identity = (primary, context = '') => {
    const result = node('span', 'identity-summary');
    const label = node('span', 'identity-summary-primary');
    label.append(primary || '—');
    result.append(label);
    if (context) result.append(node('small', 'identity-summary-context', context));
    return result;
  };
  const linked = (label, ref) => {
    const target = canViewRadio() && entityRefHref(ref);
    return target ? anchor(label, target) : label;
  };
  const recordingSource = row => {
    const id = row.source_id == null ? '' : `Radio ${radioIdentifierText(row, row.source_id, 'source')}`;
    const alias = row.source_alias || row.source_ota_alias;
    return identity(linked(alias || id, row.source_entity_ref), alias ? id : '');
  };
  const recordingTargetText = row => {
    const radio = row.destination_radio_id != null && row.talkgroup_id == null;
    const value = radio ? row.destination_radio_id : row.talkgroup_id ?? row.target_id;
    const type = radio ? 'Radio' : String(row.call_type).toUpperCase().includes('PATCH') ? 'Patch group' : 'Talkgroup';
    return value == null ? '' : `${type} ${radio ? radioIdentifierText(row, value, 'target') : groupIdentifierText(row, value, 'target')}`;
  };
  const recordingAlias = row => row.group_alias || row.talkgroup_alias || row.destination_radio_alias;
  const recordingTarget = row => identity(linked(recordingAlias(row) || recordingTargetText(row) || row.channel_name,
    row.target_entity_ref), recordingAlias(row) ? recordingTargetText(row) : '');
  const activityTargetText = row => {
    const kind = String(row.target_kind || '').toLowerCase();
    const type = kind === 'radio' ? 'Radio' : kind === 'patch_group' ? 'Patch group' : 'Talkgroup';
    return row.target_id == null ? '' : `${type} ${kind === 'radio' ?
      radioIdentifierText(row, row.target_id, 'target') : groupIdentifierText(row, row.target_id, 'target')}`;
  };
  const button = (label, action, primary = false) => {
    const control = node('button', `ui-button ui-button-${primary ? 'primary' : 'secondary'}`, label);
    control.type = 'button';
    control.addEventListener('click', action);
    return control;
  };
  return async function openCallRelations(kind, id) {
    if (!['activity', 'recording', 'alert'].includes(kind) || !id) return;
    const controller = new AbortController();
    const body = node('div', 'call-summary');
    const loading = node('div', 'ui-notice', 'Loading call details…');
    loading.setAttribute('role', 'status');
    body.append(loading);
    let unsubscribe = null;
    const cleanupPlayback = () => { unsubscribe?.(); unsubscribe = null; };
    const modal = openReadOnlyModal('Call details', body, {
      id: 'call-relations-modal', className: 'call-summary-modal modal-size-medium',
      cleanup: () => { controller.abort(); cleanupPlayback(); }
    });
    if (!modal || !await modal.ready) return;
    const section = (title, content) => {
      const element = node('section', 'call-summary-section');
      element.append(node('h3', 'ui-section-heading', title), content);
      return element;
    };
    let busy = false;
    const load = async () => {
      if (busy || controller.signal.aborted) return;
      busy = true;
      cleanupPlayback();
      body.replaceChildren(loading);
      try {
        const endpoint = kind === 'alert' ? `/api/v1/alerts/history/${encodeURIComponent(id)}/related` :
          `/api/v1/call-relations/${kind}/${encodeURIComponent(id)}`;
        const data = await requestJson(endpoint, { signal: controller.signal, page: false });
        if (controller.signal.aborted || !modal.dialog.isConnected) return;
        // Filter before choosing any overview text as well as before rendering sections.
        const activity = canViewRadio() ? data.activity_rows || [] : [];
        const recordings = canViewRecordings() ? data.recordings || [] : [];
        const alerts = data.alerts || [];
        const selectedAlert = kind === 'alert' ? alerts.find(row => String(row.id) === String(id)) : null;
        const selectedActivity = kind === 'activity' ? activity.find(row => String(row.id) === String(id)) : null;
        const selectedRecording = kind === 'recording' ? recordings.find(row => String(row.id) === String(id)) : null;
        const observation = selectedActivity || activity[0];
        const recording = selectedRecording || recordings[0];
        const overview = selectedActivity || recording || observation;
        const isRecording = overview && overview === recording;
        const title = selectedAlert?.title || (isRecording ? recordingAlias(overview) || recordingTargetText(overview) || overview.channel_name :
          overview?.target_alias_name || (overview && activityTargetText(overview)) || overview?.name) || 'Call summary';
        const parts = [];
        const heading = node('div', 'call-summary-heading ui-heading-group');
        heading.append(node('h3', '', title));
        const started = selectedAlert?.created_at_ms || overview?.start_ms || overview?.observed_at_ms;
        if (started || overview?.site_name) {
          const context = node('p', 'ui-muted');
          context.append(inline([started ? dateTime(started) : '', overview?.site_name]));
          heading.append(context);
        }
        parts.push(heading);
        if (overview) {
          const facts = node('dl', 'call-summary-facts ui-fact-list ui-fact-list-grid');
          const fact = (label, value) => {
            const row = node('div', 'ui-fact-row');
            const detail = node('dd');
            detail.append(value || '—');
            row.append(node('dt', '', label), detail);
            facts.append(row);
          };
          fact('From', isRecording ? recordingSource(overview) : identity(activitySource(overview),
            overview.source_alias_name && overview.source_radio_id != null ? `Radio ${radioIdentifierText(overview, overview.source_radio_id, 'source')}` : ''));
          fact('To', isRecording ? recordingTarget(overview) : identity(activityTarget(overview),
            overview.target_alias_name ? activityTargetText(overview) : ''));
          fact('System / channel', identity(isRecording ? linked(overview.channel_name || overview.site_name || systemLabel(overview),
            overview.channel_entity_ref) : linked(overview.name || systemLabel(overview), overview.entity_ref), systemLabel(overview)));
          fact('Call', join([recordingProtocolLabel(overview.protocol), isRecording ? recordingCallTypeLabel(overview.call_type) : activityEventLabel(overview),
            overview.frequency_hz ? `${Number(overview.frequency_hz) / 1e6} MHz` : '']));
          parts.push(facts);
        }
        const controls = [];
        if (canViewRecordings()) {
          const list = node('div', 'call-summary-recordings');
          for (const [index, row] of recordings.entries()) {
            const card = node('div', 'call-summary-recording ui-surface-inset');
            card.setAttribute('role', 'group');
            card.setAttribute('aria-label', recordings.length > 1 ? `Part ${index + 1}` : 'Recorded call');
            const top = node('div', 'call-summary-recording-top');
            const play = button('Play', () => {
              const state = playback.viewState();
              if (String(state.current?.id) === String(row.id) && !state.stopped) void playback.toggle();
              else playback.play(row, 'once');
            }, true);
            const label = node('div', 'call-summary-recording-label ui-heading-group');
            label.append(node('h3', '', recordings.length > 1 ? `Part ${index + 1}` : 'Recorded call'));
            const metadata = node('p', 'ui-muted');
            metadata.append(inline([row.duration_ms ? `${seconds(row.duration_ms / 1000)} seconds` : '',
              row.start_ms ? dateTime(row.start_ms) : '']));
            label.append(metadata);
            const open = anchor(iconGlyph('icon-open-details'), href('recordings', { recording_queue: row.id }),
              'ui-button ui-button-secondary ui-icon-button');
            open.setAttribute('aria-label', 'Open in Recordings');
            open.dataset.uiHint = 'Open in Recordings';
            top.append(play, label, open);
            const transport = node('div', 'call-summary-transport');
            const seek = node('input', 'ui-range call-summary-seek');
            seek.type = 'range'; seek.min = '0'; seek.step = '0.1'; seek.value = '0';
            seek.setAttribute('aria-label', 'Recording position');
            seek.addEventListener('input', () => playback.seek(Number(seek.value)));
            const timing = node('div', 'call-summary-timing ui-muted ui-label');
            const elapsed = node('span', '', '0:00');
            const duration = node('span', '', clock(row.duration_ms / 1000));
            timing.append(elapsed, duration);
            transport.append(seek, timing);
            card.append(top, transport);
            if (recordings.length > 1) {
              const detail = node('p', 'call-summary-recording-context ui-muted');
              detail.append(recordingSource(row), node('span', '', join([systemLabel(row), row.channel_name, row.site_name,
                recordingAlias(row), recordingTargetText(row)])));
              card.append(detail);
            }
            const status = node('div', 'ui-notice');
            status.setAttribute('role', 'status');
            status.hidden = true;
            card.append(status);
            list.append(card);
            controls.push({ row, play, seek, elapsed, duration, status });
          }
          if (!recordings.length) list.append(node('div', 'ui-notice', 'No recording available'));
          parts.push(section(recordings.length > 1 ? 'Recordings' : 'Recording', list));
        }
        if (canViewRadio()) {
          const list = node('div', 'call-summary-list');
          for (const row of activity) {
            const item = node('div', 'call-summary-observation');
            const copy = node('div', 'call-summary-observation-copy');
            const line = node('p');
            line.append(activityChannel(row));
            const metadata = node('p', 'ui-muted');
            metadata.append(inline([dateTime(row.observed_at_ms), activityEventLabel(row), systemLabel(row)]));
            copy.append(line, metadata);
            const icon = node('span', 'ui-record-card-icon');
            icon.append(iconGlyph('icon-activity'));
            item.append(icon, copy);
            if (row.configuration_id && Number.isFinite(Number(row.observed_at_ms))) item.append(anchor('Open', href('channel', {
              configuration_id: row.configuration_id, tab: 'activity', activity_range: 'custom',
              activity_from_ms: row.observed_at_ms, activity_to_ms: Number(row.observed_at_ms) + 1,
              activity_include_grants: String(row.action).toUpperCase() === 'GRANT' ? true : null
            }), 'ui-button ui-button-secondary'));
            list.append(item);
          }
          if (!activity.length) list.append(node('div', 'ui-notice', 'No related activity available'));
          parts.push(section('Activity', list));
        }
        if (alerts.length) {
          const list = node('div', 'call-summary-list');
          for (const row of alerts) {
            const item = node('div', 'call-summary-alert');
            const line = node('div', 'call-summary-section-line');
            const sent = node('small', 'ui-muted');
            sent.append(dateTime(row.created_at_ms));
            line.append(anchor(row === selectedAlert ? row.topic_name || 'View alert' : row.title || row.alert_name || 'View alert',
              href('topics', { topic: row.topic_id, alert: row.id })), sent);
            item.append(line);
            if (row.body) item.append(node('p', '', row.body));
            list.append(item);
          }
          parts.push(section(alerts.length > 1 ? 'Alerts' : 'Alert', list));
        }
        const footer = node('footer', 'ui-modal-footer ui-action-row');
        footer.append(button('Refresh', load));
        if (recordings.length > 1) {
          const ids = recordings.map(row => String(row.id));
          if (ids.every(value => /^[1-9]\d{0,18}$/.test(value))) footer.append(anchor('View all recordings',
            href('recordings', { recording_queue: ids.join(',') }), 'ui-button ui-button-secondary'));
        }
        footer.append(button('Done', () => modal.close(), true));
        parts.push(footer);
        body.replaceChildren(...parts);
        if (controls.length) unsubscribe = playback.subscribeState(state => {
          for (const { row, play, seek, elapsed, duration, status } of controls) {
            const current = String(state.current?.id) === String(row.id);
            const total = seconds(current && state.duration || row.duration_ms / 1000);
            const position = current ? Math.min(total, seconds(state.currentTime)) : 0;
            play.textContent = current && state.loading ? 'Loading…' : current && state.playing ? 'Pause' : 'Play';
            play.disabled = current && state.loading;
            seek.disabled = !current || state.stopped || !total;
            seek.max = String(total); seek.value = String(position);
            seek.setAttribute('aria-valuetext', `${clock(position)} of ${clock(total)}`);
            elapsed.textContent = clock(position); duration.textContent = clock(total);
            const message = current ? state.status || '' : '';
            if (status.textContent !== message) status.textContent = message;
            status.hidden = !message;
          }
        });
      } catch (error) {
        if (controller.signal.aborted || !modal.dialog.isConnected) return;
        cleanupPlayback();
        const notice = node('div', 'ui-notice ui-notice-danger', 'Call details could not be loaded. Try again.');
        notice.setAttribute('role', 'alert');
        body.replaceChildren(notice, button('Try again', load));
      } finally { busy = false; }
    };
    await load();
  };
}
