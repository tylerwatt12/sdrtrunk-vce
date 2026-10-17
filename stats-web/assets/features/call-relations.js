import { systemLabel } from '../core/system-labels.js?v=2';

// Exact local relationships use the shared detail modal, tables, feedback, and audio player.
export function createCallRelations(deps) {
  const { node, table, section, sectionActionHost, openReadOnlyModal, requestJson, dateTime, anchor, href,
    activitySource, activityTarget, activityChannel, activityEventLabel, playRecording, canViewRadio, canViewRecordings } = deps;
  const identity = (primary, context = []) => {
    const label = node('span', 'identity-summary-primary');
    label.append(primary || '—');
    const result = node('span', 'identity-summary');
    result.append(label);
    const detail = context.filter(Boolean).join(' · ');
    if (detail) result.append(node('small', 'identity-summary-context', detail));
    return result;
  };
  return async function openCallRelations(kind, id) {
    if (!['activity', 'recording', 'alert'].includes(kind) || !id) return;
    const controller = new AbortController();
    const body = node('div');
    const loading = node('p', 'ui-notice ui-notice-spaced', 'Loading related call details…');
    loading.setAttribute('role', 'status');
    body.append(loading);
    const tableControllers = new Map();
    const cleanupTables = () => {
      for (const value of tableControllers.values()) {
        value.layoutMenuCleanup?.();
        value.overflowControls?.destroy();
        value.widthObserver?.disconnect();
        value.layoutMenuCleanup = value.overflowControls = value.widthObserver = null;
      }
    };
    const modal = openReadOnlyModal('Related call details', body, {
      id: 'call-relations-modal', cleanup: () => { controller.abort(); cleanupTables(); }
    });
    if (!modal || !await modal.ready) return;
    const dataSection = (title, type, rows, columns, empty) => {
      const actions = sectionActionHost();
      let tableController = tableControllers.get(type);
      if (!tableController) { tableController = {}; tableControllers.set(type, tableController); }
      return section(title, table(rows, columns, empty, { type, layoutMenuHost: actions,
        controller: tableController, overflowLabel: `Related ${title.toLowerCase()}` }), actions);
    };
    let busy = false;
    const load = async () => {
      if (busy || controller.signal.aborted) return;
      busy = true;
      cleanupTables();
      body.replaceChildren(loading);
      try {
        const endpoint = kind === 'alert' ? `/api/v1/alerts/history/${encodeURIComponent(id)}/related` :
          `/api/v1/call-relations/${kind}/${encodeURIComponent(id)}`;
        const data = await requestJson(endpoint, { signal: controller.signal, page: false });
        if (controller.signal.aborted || !modal.dialog.isConnected) return;
        const parts = [];
        if (canViewRadio()) {
          parts.push(dataSection('Activity', 'call-relations-activity', data.activity_rows || [], [
            { id: 'time', label: 'Seen', render: row => dateTime(row.observed_at_ms) },
            { id: 'event', label: 'Event', render: row => activityEventLabel(row) },
            { id: 'source-alias', label: 'Source', className: 'alias-cell', render: row => identity(activitySource(row), [
              row.source_alias_name && row.source_radio_id != null ? `Radio ${row.source_radio_id}` : '', systemLabel(row)]) },
            { id: 'target-alias', label: 'Target', className: 'alias-cell', render: row => identity(activityTarget(row), [
              row.target_alias_name && row.target_id != null ? `${({ radio: 'Radio', patch_group: 'Patch group' })[row.target_kind] || 'Talkgroup'} ${row.target_id}` : '',
              systemLabel(row)]) },
            { id: 'channel', label: 'Channel', render: activityChannel }
          ], 'No related activity is available.'));
        }
        if (canViewRecordings()) {
          parts.push(dataSection('Recordings', 'call-relations-recordings', data.recordings || [], [
            { id: 'time', label: 'Recorded', render: row => dateTime(row.start_ms) },
            { id: 'target-alias', label: 'Call', className: 'alias-cell', render: row => {
              const target = row.talkgroup_id != null ? `Talkgroup ${row.talkgroup_id}` :
                row.destination_radio_id != null ? `Radio ${row.destination_radio_id}` : '';
              const alias = row.group_alias || row.talkgroup_alias || row.destination_radio_alias;
              return identity(alias || target || row.channel_name || 'Recorded call', [alias ? target : '', systemLabel(row)]);
            } },
            { id: 'source-alias', label: 'Source', className: 'alias-cell', render: row => identity(row.source_alias || row.source_ota_alias ||
              (row.source_id != null ? `Radio ${row.source_id}` : ''), [
              (row.source_alias || row.source_ota_alias) && row.source_id != null ? `Radio ${row.source_id}` : '']) },
            { id: 'channel', label: 'Channel', render: row => row.channel_name || row.site_name || '' },
            { id: 'actions', label: 'Actions', render: row => {
              const actions = node('div', 'ui-action-row');
              const play = node('button', 'ui-button ui-button-primary', 'Play');
              play.type = 'button';
              play.addEventListener('click', () => {
                let started = false;
                const start = () => { if (!started) { started = true; playRecording(row); } };
                if (modal.close(start)) start();
              });
              actions.append(play, anchor('Open in Recordings', href('recordings', { recording_queue: row.id }),
                'ui-button ui-button-secondary'));
              return actions;
            } }
          ], 'No related recording is available.'));
        }
        parts.push(dataSection('Alerts', 'call-relations-alerts', data.alerts || [], [
          { id: 'time', label: 'Sent', render: row => dateTime(row.created_at_ms) },
          { id: 'name', label: 'Alert', render: row => anchor(row.title || row.alert_name || 'View alert',
            href('topics', { topic: row.topic_id, alert: row.id })) },
          { id: 'message', label: 'Message', render: row => row.body || '' }
        ], 'No related alerts are available.'));
        const refresh = node('button', 'ui-button ui-button-secondary', 'Refresh');
        refresh.type = 'button';
        refresh.addEventListener('click', load);
        const footer = node('footer', 'ui-modal-footer ui-action-row');
        footer.append(refresh);
        if (canViewRecordings() && data.recordings?.length > 1) {
          const ids = data.recordings.map(row => String(row.id));
          if (ids.every(value => /^[1-9]\d{0,18}$/.test(value))) footer.append(anchor('View all recordings',
            href('recordings', { recording_queue: ids.join(',') }), 'ui-button ui-button-primary'));
        }
        body.replaceChildren(...parts, footer);
      } catch (error) {
        if (controller.signal.aborted || !modal.dialog.isConnected) return;
        const notice = node('p', 'ui-notice ui-notice-danger ui-notice-spaced',
          'Related call details could not be loaded. Try again.');
        notice.setAttribute('role', 'alert');
        const retry = node('button', 'ui-button ui-button-secondary', 'Try again');
        retry.type = 'button';
        retry.addEventListener('click', load);
        cleanupTables();
        body.replaceChildren(notice, retry);
      } finally { busy = false; }
    };
    await load();
  };
}
