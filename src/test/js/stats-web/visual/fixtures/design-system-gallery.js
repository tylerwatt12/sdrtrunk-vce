'use strict';

const parameters = new URLSearchParams(window.location.search);
const theme = parameters.get('theme') === 'dark' ? 'dark' : 'light';
const view = ['mobile-table', 'gallery', 'app-chrome', 'modal', 'modal-long', 'health-alert-modal', 'focus', 'settings', 'health', 'p25', 'admin-access',
  'admin-receiver', 'admin-support', 'dashboard-health', 'dashboard-calls', 'dashboard-activity',
  'signal-quality-detail', 'radioreference-results',
  'radio-directory-coverage', 'radio-directory-panel', 'admin-scan-lists', 'admin-scan-lists-columns',
  'scanner', 'tuner-spectrum', 'rf-planner', 'aliases', 'alias-modal', 'alias-export', 'scan-list-members', 'channels',
  'radio-directory', 'entity-details', 'live-notice', 'live-filter', 'tuner-frequency-popover'].includes(parameters.get('view')) ?
  parameters.get('view') : 'gallery';
document.documentElement.dataset.theme = theme;
document.body.dataset.galleryView = view;
document.querySelectorAll('.radioreference-detail-header').forEach((header) =>
  header.classList.add('ui-surface-header'));
if(view === 'radio-directory-panel') {
  const example = document.querySelector('.visual-radio-directory-coverage-example');
  const panel = document.createElement('section');
  panel.className = 'section ui-section';
  const title = document.createElement('div');
  title.className = 'section-title ui-section-title';
  title.textContent = 'Radio directory';
  const body = document.createElement('div');
  body.className = 'radio-directory-panel-body';
  body.append(example.querySelector('.radio-directory-perspective'), example.querySelector('.alias-coverage'));
  panel.append(title, body);
  example.replaceChildren(panel);
}
if(view === 'live-notice') document.body.dataset.view = 'live';
if(view === 'scanner') document.body.dataset.view = 'scanner';
if(view === 'entity-details') document.body.dataset.view = 'group-identity';
if(view === 'app-chrome' && window.matchMedia('(max-width: 1180px)').matches) {
  document.querySelector('.visual-app-chrome-example .playback-control-menu').open = false;
}
const label = document.getElementById('visual-theme-label');
if(label) label.textContent = `${theme[0].toUpperCase()}${theme.slice(1)} theme`;
