import { createCallDisplayHangtimeField } from '../core/call-display-settings.js?v=2';
import { preferenceGroups } from '../core/preference-registry.js?v=1';
import { createPreferenceNumberInput, createRecordingClickModeSelect, createStatusAlertControl,
  mergeStatusAlertDisabledCodes, listeningFieldDefinitions } from '../core/preference-controls.js?v=1';

// Focused personal settings reuse the settings composition, choice controls,
// form workflow and confirmation modal on desktops and phones in both themes.
export function createMySettingsWorkspace({ node, iconGlyph, uiToggle, createFormWorkflow,
  schemes, alertGroups, readSnapshot, updatePreferences, setAppearancePreview,
  confirmAction, openReset, initialSection = 'appearance', onSectionChange = () => {},
  onSessionChanged = () => {}, canAccess = () => false, canView = () => false,
  href = () => null, openLivePresentation = null }) {
  const editorGroups = new Set(['appearance', 'names', 'status', 'listening', 'guides']);
  const categories = [
    ...preferenceGroups.filter(group => editorGroups.has(group.id) &&
      (!group.capability || canAccess(group.capability))),
    { id: 'pages', label: 'Page preferences', icon: 'icon-dashboard' }
  ];
  const snapshot = readSnapshot();
  const identity = snapshot.identity;
  const scope = snapshot.scope || (identity === null ? 'guest' : 'account');
  const knownAlerts = new Set(alertGroups.flatMap((group) => group.alerts.map((alert) => alert.id)));
  const clone = (value) => JSON.parse(JSON.stringify(value));
  const sectionValue = (preferences) => active === 'appearance' ? clone(preferences.appearance) :
    active === 'names' ? preferences.presentation.source_name_display :
      active === 'status' ? [...preferences.health_alerts.disabled_codes].sort() :
        active === 'listening' ? {
          volume: preferences.playback.volume,
          target_grouping: preferences.playback.target_grouping,
          target_burst_limit: preferences.playback.target_burst_limit,
          call_display_hangtime_seconds: preferences.playback.call_display_hangtime_seconds,
          recording_click_mode: preferences.playback.recording_click_mode,
          prepend_playing_call: preferences.page_titles.prepend_playing_call
        } : active === 'guides' ? preferences.guides.automatic_page_guides : null;
  const element = node('div', 'ui-settings-workspace ui-settings-workspace-focused user-settings-workspace');
  const navigation = node('nav', 'ui-settings-navigation');
  navigation.setAttribute('aria-label', 'Personal preferences');
  const page = node('div', 'ui-settings-page');
  const pickerField = node('label', 'ui-settings-picker');
  const picker = node('select', 'ui-select');
  pickerField.append(node('span', 'ui-settings-picker-label', 'Section'), picker);
  let active = categories.some((category) => category.id === initialSection) ? initialSection : 'appearance';
  let saved = sectionValue(snapshot.preferences);
  let draft = clone(saved);
  let workflow = null;
  let form = null;
  let stopped = false;
  let sessionChanged = false;
  let confirmationPending = false;
  let formRevision = 0;
  const links = new Map();
  const resets = [];
  const isDirty = () => !stopped && !sessionChanged && JSON.stringify(saved) !== JSON.stringify(draft);
  const isBusy = () => Boolean(workflow?.isBusy());
  const current = () => !stopped && !sessionChanged && readSnapshot().identity === identity &&
    (readSnapshot().scope || scope) === scope;
  const preview = () => setAppearancePreview(current() && active === 'appearance' ?
    { identity, theme: draft.theme, hue: draft.hue } : null);
  const focusEditor = () => {
    const revision = formRevision;
    window.requestAnimationFrame(() => {
      if (!current() || revision !== formRevision) return;
      const target = [...page.querySelectorAll('input:checked, input, select, summary')]
        .find((control) => control.getClientRects().length) || page.querySelector('h2');
      target?.focus({ preventScroll: true });
    });
  };
  const button = (label, className = 'ui-button ui-button-secondary') => {
    const control = node('button', className, label);
    control.type = 'button';
    return control;
  };
  const syncNavigation = () => {
    links.forEach((link, id) => {
      if (id === active) link.setAttribute('aria-current', 'page');
      else link.removeAttribute('aria-current');
      link.disabled = isBusy() || sessionChanged;
    });
    picker.value = active;
    picker.disabled = isBusy() || sessionChanged;
    resets.forEach((reset) => { reset.disabled = isBusy() || sessionChanged; });
  };
  const discard = () => {
    saved = sectionValue(readSnapshot().preferences);
    draft = clone(saved);
    workflow?.setDirty(false);
    preview();
  };
  const requestLeave = (onAccepted) => {
    if (stopped || sessionChanged) return true;
    if (isBusy() || confirmationPending) return false;
    if (!isDirty()) return true;
    confirmationPending = true;
    void confirmAction('Your changes haven’t been saved.', {
      title: 'Discard changes?', cancelLabel: 'Keep editing', confirmLabel: 'Discard changes', tone: 'primary'
    }).then((accepted) => {
      confirmationPending = false;
      syncNavigation();
      if (!accepted || !current()) return;
      discard();
      onAccepted?.();
    });
    return false;
  };
  const switchSection = (section, focus = true) => {
    if (!categories.some((category) => category.id === section)) return;
    const change = () => {
      setAppearancePreview(null);
      active = section;
      saved = sectionValue(readSnapshot().preferences);
      draft = clone(saved);
      renderEditor();
      syncNavigation();
      onSectionChange(section);
      if (focus) focusEditor();
    };
    if (section === active) { syncNavigation(); return; }
    if (requestLeave(change)) change();
    else picker.value = active;
  };
  for (const [index, category] of categories.entries()) {
    if (index === 0 || category.id === 'pages') {
      const group = node('div', 'ui-settings-nav-group');
      const items = node('div', 'ui-settings-nav-items');
      group.append(items);
      navigation.append(group);
    }
    const link = button('', 'ui-settings-nav-link');
    link.append(iconGlyph(category.icon), node('span', '', category.label));
    link.dataset.section = category.id;
    link.addEventListener('click', () => switchSection(category.id));
    navigation.lastElementChild.firstElementChild.append(link);
    links.set(category.id, link);
    const option = node('option', '', category.label);
    option.value = category.id;
    picker.append(option);
  }
  const resetButton = (mobile) => {
    const reset = button(scope === 'guest' ? 'Reset guest preferences' : 'Reset personal preferences',
      `ui-button ui-button-quiet ui-settings-reset${mobile ? ' ui-settings-reset-mobile' : ''}`);
    reset.id = mobile ? 'reset-user-preferences-mobile' : 'reset-user-preferences';
    reset.addEventListener('click', () => {
      const proceed = () => { renderEditor(); openReset(`#${reset.id}`); };
      if (requestLeave(proceed)) proceed();
    });
    resets.push(reset);
    return reset;
  };
  const profileNote = node('p', 'ui-settings-account-note');
  const updateProfileNote = (value) => {
    profileNote.textContent = scope === 'guest' ? value.persistence === 'memory' ?
      'Guest choices apply for this page.' : 'Guest choices saved in this browser.' : 'Personal choices for this account.';
  };
  updateProfileNote(snapshot);
  navigation.append(profileNote, resetButton(false));
  element.append(navigation, pickerField, page, resetButton(true));
  picker.addEventListener('change', () => switchSection(picker.value));

  function makePanel(title, glyph) {
    const panel = node('section', 'ui-settings-panel ui-settings-panel-focused');
    const heading = node('div', 'ui-settings-panel-header');
    const titleElement = node('h2', '', title);
    titleElement.tabIndex = -1;
    heading.append(iconGlyph(glyph), titleElement);
    const body = node('div', 'ui-settings-panel-body');
    panel.append(heading, body);
    return { panel, body };
  }

  function samplePreview(names = false) {
    const sample = node('div', 'ui-settings-preview');
    sample.append(node('div', 'ui-settings-preview-label', 'Preview'));
    const row = node('div', 'ui-settings-preview-row');
    const mark = node('div', 'ui-settings-preview-mark');
    mark.append(iconGlyph('icon-radio-tower'));
    const copy = node('div', 'ui-settings-preview-copy');
    const label = names ? draft === 'both' ? 'Unit 42 · Patrol 12' :
      draft === 'source_alias' ? 'Patrol 12' : 'Unit 42' : 'Dispatch';
    copy.append(node('strong', '', label), node('span', 'muted',
      names ? 'Source · 1042' : 'County Public Safety · Unit 42'));
    row.append(mark, copy, node('span', 'ui-settings-preview-end', 'Talkgroup 101'));
    sample.append(row);
    return sample;
  }

  function field(label) {
    const wrapper = node('div', 'ui-field ui-settings-field');
    const heading = node('span', 'ui-field-label', label);
    heading.id = `my-settings-${active}-${label === 'Theme' ? 'theme' : 'scheme'}`;
    wrapper.append(heading);
    return { wrapper, heading };
  }

  function appearanceControls(body) {
    const theme = field('Theme');
    const choices = node('div', 'ui-settings-theme-options');
    choices.setAttribute('role', 'radiogroup');
    choices.setAttribute('aria-labelledby', theme.heading.id);
    [['light', 'Light', 'icon-sun'], ['dark', 'Dark', 'icon-moon']].forEach(([value, label, glyph]) => {
      const choice = node('label', 'ui-choice-card ui-settings-theme-choice');
      const input = node('input', 'ui-choice-radio');
      input.type = 'radio'; input.name = 'my-settings-theme'; input.value = value;
      input.checked = draft.theme === value;
      input.addEventListener('change', () => { if (input.checked) { draft.theme = value; preview(); } });
      choice.append(input, node('span', '', label), iconGlyph(glyph));
      choices.append(choice);
    });
    theme.wrapper.append(choices);
    const colors = field('Color scheme');
    const palette = node('div', 'ui-palette-grid ui-palette-grid-compact');
    palette.setAttribute('role', 'radiogroup');
    palette.setAttribute('aria-labelledby', colors.heading.id);
    const custom = node('p', 'ui-settings-help', 'Saved custom color. Choose a scheme to replace it.');
    custom.hidden = schemes.some((scheme) => scheme.hue === draft.hue);
    schemes.forEach((scheme) => {
      const choice = node('label', 'ui-choice-card ui-palette-choice ui-palette-choice-compact');
      const input = node('input', 'ui-choice-radio');
      input.type = 'radio'; input.name = 'my-settings-scheme'; input.value = scheme.name;
      input.checked = scheme.hue === draft.hue;
      const swatch = node('span', 'ui-palette-swatch');
      swatch.setAttribute('aria-hidden', 'true');
      swatch.style.setProperty('--palette-hue', String(scheme.previewHue));
      swatch.append(iconGlyph('icon-check'));
      input.addEventListener('change', () => {
        if (!input.checked) return;
        draft.hue = scheme.hue;
        custom.hidden = true;
        preview();
      });
      choice.append(input, swatch, node('span', '', scheme.name));
      palette.append(choice);
    });
    colors.wrapper.append(palette, custom);
    body.append(theme.wrapper, colors.wrapper, samplePreview());
  }

  function sourceNameControls(body) {
    const fieldset = node('div', 'ui-field ui-settings-field');
    const label = node('span', 'ui-field-label', 'Source name display');
    label.id = 'my-settings-source-name-display';
    const choices = node('div', 'ui-settings-choice-stack');
    choices.setAttribute('role', 'radiogroup');
    choices.setAttribute('aria-labelledby', label.id);
    const sample = node('div');
    [['talker_alias', 'Talker Alias preferred'], ['source_alias', 'Source Alias preferred'], ['both', 'Both']]
      .forEach(([value, name]) => {
        const choice = node('label', 'ui-choice-card ui-settings-source-choice');
        const input = node('input', 'ui-choice-radio');
        input.type = 'radio'; input.name = 'my-settings-source-names'; input.value = value;
        input.checked = draft === value;
        input.addEventListener('change', () => {
          if (!input.checked) return;
          draft = value;
          sample.replaceChildren(samplePreview(true));
        });
        choice.append(input, node('span', '', name));
        choices.append(choice);
      });
    const help = node('p', 'ui-settings-help',
      'Use the other name when the preferred name is unavailable. Both shows each distinct name once.');
    help.id = 'my-settings-source-name-help';
    choices.setAttribute('aria-describedby', help.id);
    fieldset.append(label, choices, help);
    sample.append(samplePreview(true));
    body.append(fieldset, sample);
  }

  function statusControls(body) {
    body.append(node('p', 'ui-settings-intro', 'Choose which receiver issues appear in the status icon.'));
    const groups = node('div', 'ui-settings-disclosures');
    alertGroups.forEach((group) => {
      const details = node('details', 'ui-settings-disclosure');
      const summary = node('summary');
      const count = node('span', 'ui-settings-count');
      const updateCount = () => {
        count.textContent = `${group.alerts.filter((alert) => !draft.includes(alert.id)).length} of ${group.alerts.length} shown`;
      };
      summary.append(node('strong', '', group.name), count);
      const controls = node('div', 'ui-settings-disclosure-body');
      group.alerts.forEach((alert) => {
        const { control, input } = createStatusAlertControl(node, uiToggle, alert,
          { checked: !draft.includes(alert.id), compact: true });
        input.addEventListener('change', () => {
          draft = draft.filter((id) => id !== alert.id);
          if (!input.checked) draft.push(alert.id);
          draft.sort();
          updateCount();
        });
        controls.append(control);
      });
      updateCount();
      details.append(summary, controls);
      groups.append(details);
    });
    body.append(groups);
  }

  function listeningControls(body) {
    const { targetGrouping, targetBurstLimit, prependPlayingCall, recordingClickMode } = listeningFieldDefinitions;
    const numberField = (label, value, minimum, maximum, step, onChange, detail = '') => {
      const { wrapper, heading } = field(label);
      heading.id = `my-settings-${label.toLowerCase().replace(/[^a-z0-9]+/g, '-')}-label`;
      const input = createPreferenceNumberInput(node, {
        value, minimum, maximum, step, required: true, labelledBy: heading.id
      });
      input.addEventListener('input', () => onChange(Number(input.value)));
      wrapper.append(input);
      if (detail) {
        const help = node('small', 'ui-field-detail', detail); help.id = `${heading.id}-help`;
        input.setAttribute('aria-describedby', help.id); wrapper.append(help);
      }
      body.append(wrapper);
      return input;
    };
    const toggleField = (label, value, onChange) => {
      const row = node('div', 'ui-field-row');
      const copy = node('span', 'ui-toggle-copy'); copy.append(node('strong', '', label));
      const toggle = uiToggle(value, label);
      toggle.querySelector('input').addEventListener('change', event => onChange(event.target.checked));
      row.append(copy, toggle); body.append(row);
    };
    numberField('Playback volume (%)', Math.round(draft.volume * 100), 0, 100, 1,
      value => { draft.volume = value / 100; });
    toggleField(targetGrouping.label, draft.target_grouping,
      value => { draft.target_grouping = value; });
    numberField(targetBurstLimit.label, draft.target_burst_limit,
      targetBurstLimit.limits.minimum, targetBurstLimit.limits.maximum, 1,
      value => { draft.target_burst_limit = value; },
      targetBurstLimit.detail);
    const { field: hangtimeField } = createCallDisplayHangtimeField(node, {
      id: 'my-settings-call-display-hangtime', value: draft.call_display_hangtime_seconds,
      onChange: (value) => { draft.call_display_hangtime_seconds = value; }
    });
    body.append(hangtimeField);
    const { wrapper, heading } = field(recordingClickMode.label);
    const select = createRecordingClickModeSelect(node, { value: draft.recording_click_mode, labelledBy: heading.id });
    select.addEventListener('change', () => { draft.recording_click_mode = select.value; });
    wrapper.append(select); body.append(wrapper);
    toggleField(prependPlayingCall.label, draft.prepend_playing_call,
      value => { draft.prepend_playing_call = value; });
    if (canView('scanner')) {
      const lists = node('a', 'link-button', 'Open Scanner for Scan Lists');
      lists.href = href('scanner'); body.append(lists);
    }
  }

  function guideControls(body) {
    const row = node('div', 'ui-field-row');
    const copy = node('span', 'ui-toggle-copy');
    copy.append(node('strong', '', 'Show page guides automatically'));
    const toggle = uiToggle(draft, 'Show page guides automatically');
    toggle.querySelector('input').addEventListener('change', event => { draft = event.target.checked; });
    row.append(copy, toggle); body.append(row);
  }

  function pagePreferences(body) {
    body.append(node('p', 'ui-settings-intro', 'Change these preferences from the page you’re using.'));
    const destinations = node('div', 'ui-settings-destinations');
    preferenceGroups.filter(group => !editorGroups.has(group.id)).forEach(group => {
      const view = group.view || (group.id === 'tables' ?
        ['live', 'dashboard'].find(candidate => canView(candidate)) : null);
      if (!view || !canView(view)) return;
      const row = node('div', 'ui-settings-destination');
      const copy = node('div', 'ui-settings-destination-copy');
      copy.append(node('strong', '', group.label));
      if (group.location) copy.append(node('span', 'ui-settings-destination-description', group.location));
      const target = href(view);
      if (target) {
        const link = node('a', 'link-button', `Open ${group.id === 'tables' ? 'tables' : group.label}`);
        link.href = target; copy.append(link);
      }
      if (group.id === 'live_workspace' && openLivePresentation) {
        const open = button('Open Live presentation', 'link-button');
        open.id = 'my-settings-live-presentation';
        open.addEventListener('click', () => openLivePresentation(`#${open.id}`)); copy.append(open);
      }
      row.append(iconGlyph(group.icon), copy);
      destinations.append(row);
    });
    body.append(destinations);
  }

  function renderEditor() {
    const revision = ++formRevision;
    workflow = null;
    const category = categories.find((item) => item.id === active);
    const { panel, body } = makePanel(category.label, category.icon);
    if (active === 'pages') {
      form = null;
      pagePreferences(body);
      page.replaceChildren(panel);
      return;
    }
    form = node('form', 'ui-settings-stack ui-form-workflow-contained');
    form.setAttribute('aria-label', category.label);
    if (active === 'appearance') appearanceControls(body);
    else if (active === 'names') sourceNameControls(body);
    else if (active === 'listening') listeningControls(body);
    else if (active === 'guides') guideControls(body);
    else statusControls(body);
    const feedback = node('div', 'admin-form-message');
    feedback.setAttribute('role', 'status');
    const cancel = button('Cancel', 'ui-button ui-button-quiet');
    const save = button('Save changes', 'ui-button ui-button-primary');
    save.type = 'submit';
    const actions = node('div', 'ui-settings-form-actions');
    actions.append(cancel, save);
    const footer = node('div', 'settings-form-footer ui-settings-form-footer');
    footer.append(feedback, actions);
    form.append(panel, footer);
    page.replaceChildren(form);
    workflow = createFormWorkflow({ form, submit: save, feedback, changed: isDirty, hideCleanSubmit: false });
    const ownWorkflow = workflow;
    cancel.addEventListener('click', () => {
      const reset = () => {
        discard();
        renderEditor();
        focusEditor();
      };
      if (requestLeave(reset)) reset();
    });
    form.addEventListener('submit', async (event) => {
      event.preventDefault();
      const selectedSection = active;
      const submitted = clone(draft);
      await ownWorkflow.save(async () => {
        await updatePreferences((preferences) => {
          if (!current()) throw Object.assign(new Error('The active preference profile changed.'),
            { code: 'preference_session_changed' });
          if (selectedSection === 'appearance') preferences.appearance = clone(submitted);
          else if (selectedSection === 'names') preferences.presentation.source_name_display = submitted;
          else if (selectedSection === 'listening') {
            const { prepend_playing_call, ...playback } = submitted;
            Object.assign(preferences.playback, playback);
            preferences.page_titles.prepend_playing_call = prepend_playing_call;
          } else if (selectedSection === 'guides') preferences.guides.automatic_page_guides = submitted;
          else preferences.health_alerts.disabled_codes = mergeStatusAlertDisabledCodes(
            preferences.health_alerts.disabled_codes, knownAlerts, submitted);
        });
        if (!current()) throw Object.assign(new Error('The active preference profile changed.'),
          { code: 'preference_session_changed' });
        saved = sectionValue(readSnapshot().preferences);
        draft = clone(saved);
      }, {
        saving: 'Saving changes…',
        onSuccess: () => {
          if (!current() || revision !== formRevision) return;
          preview();
          ownWorkflow.showFeedback('status', readSnapshot().scope === 'guest' && readSnapshot().persistence === 'memory' ?
            'Changes apply for this page. Browser storage is unavailable.' : 'Changes saved');
        },
        onError: (error) => {
          if (!current() || error?.code === 'preference_session_changed') {
            ownWorkflow.setReady(false);
            return { state: 'warning', message: 'The active profile changed. Reopen My Settings to continue.' };
          }
          if (error?.code === 'preference_conflict' && !error.reloadError) {
            saved = sectionValue(readSnapshot().preferences);
            draft = clone(saved);
            renderEditor();
            workflow.showFeedback('warning', 'These settings changed in another session. The saved values were loaded.');
            return { state: 'warning', message: 'These settings changed in another session. The saved values were loaded.' };
          }
          preview();
          return { state: 'error', message: error?.code === 'preference_conflict' ?
            'These settings changed in another session, but the saved values could not be loaded. Try saving again or reload the page.' :
            'Your settings could not be saved. Your choices are still here. Try saving again.' };
        }
      });
      syncNavigation();
    });
    // The workflow locks its form immediately; navigation lives outside it.
    form.addEventListener('submit', syncNavigation);
    preview();
  }

  function onSnapshot(next) {
    if (stopped) return;
    updateProfileNote(next);
    if (next.identity !== identity || (next.scope || scope) !== scope || !next.loaded) {
      sessionChanged = true;
      setAppearancePreview(null);
      workflow?.setReady(false);
      page.replaceChildren();
      syncNavigation();
      onSessionChanged();
      return;
    }
    if (isBusy() || isDirty() || confirmationPending) return;
    const nextValue = sectionValue(next.preferences);
    if (JSON.stringify(nextValue) === JSON.stringify(saved)) return;
    saved = nextValue;
    draft = clone(saved);
    renderEditor();
    syncNavigation();
  }

  renderEditor();
  syncNavigation();
  return { element, requestLeave, isDirty, isBusy, onSnapshot,
    close() {
      stopped = true;
      ++formRevision;
      setAppearancePreview(null);
    } };
}
