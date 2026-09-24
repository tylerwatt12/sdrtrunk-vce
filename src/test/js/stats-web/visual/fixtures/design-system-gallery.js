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
document.querySelectorAll('.ui-table-row-group > th').forEach((heading, index) => {
  const groupRow = heading.parentElement;
  const button = document.createElement('button');
  const groupId = `gallery-group-${index + 1}`;
  button.type = 'button';
  button.className = 'table-row-group-disclosure';
  button.dataset.rowGroup = groupId;
  button.setAttribute('aria-expanded', 'true');
  button.setAttribute('aria-label', `Collapse ${heading.querySelector('.table-row-group-label')?.textContent || 'group'}`);
  const icon = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
  icon.setAttribute('aria-hidden', 'true');
  const use = document.createElementNS('http://www.w3.org/2000/svg', 'use');
  use.setAttribute('href', '#visual-icon-chevron');
  icon.append(use);
  button.append(icon, ...heading.childNodes);
  heading.append(button);
  groupRow.dataset.rowGroup = groupId;
  const items = [];
  for(let row = groupRow.nextElementSibling; row && !row.classList.contains('ui-table-row-group');
      row = row.nextElementSibling) {
    row.classList.add('ui-table-row-group-item');
    row.dataset.rowGroup = groupId;
    items.push(row);
  }
  button.addEventListener('click', () => {
    const expanded = button.getAttribute('aria-expanded') === 'true';
    button.setAttribute('aria-expanded', String(!expanded));
    button.setAttribute('aria-label', `${expanded ? 'Expand' : 'Collapse'} ${
      button.querySelector('.table-row-group-label')?.textContent || 'group'}`);
    items.forEach((row) => { row.hidden = expanded; });
  });
});
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
if(view === 'live-notice') {
  document.body.dataset.view = 'live';
  const split = document.querySelector('.visual-live-example .live-split');
  const picker = split?.querySelector('.live-channel-picker');
  const collapse = split?.querySelector('.live-picker-collapse');
  collapse?.addEventListener('click', () => {
    const collapsed = !split.classList.contains('picker-collapsed');
    split.classList.toggle('picker-collapsed', collapsed);
    picker?.classList.toggle('is-collapsed', collapsed);
    collapse.setAttribute('aria-expanded', String(!collapsed));
    const label = collapsed ? 'Expand live view picker' : 'Collapse live view picker';
    collapse.setAttribute('aria-label', label);
    collapse.title = label;
  });
  const details = split?.querySelector('.live-details');
  const detailsCollapse = details?.querySelector('.live-details-collapse');
  const setDetailsCollapsed = (collapsed) => {
    details?.classList.toggle('collapsed', collapsed);
    split?.classList.toggle('details-collapsed', collapsed);
    if (detailsCollapse) {
      detailsCollapse.textContent = collapsed ? 'Expand' : 'Collapse';
      detailsCollapse.setAttribute('aria-expanded', String(!collapsed));
    }
  };
  detailsCollapse?.addEventListener('click', () => setDetailsCollapsed(!details.classList.contains('collapsed')));
  if(window.matchMedia('(max-width: 760px)').matches) setDetailsCollapsed(true);
}
if(view === 'scanner') document.body.dataset.view = 'scanner';
if(view === 'entity-details') document.body.dataset.view = 'group-identity';
if(view === 'app-chrome' && window.matchMedia('(max-width: 1180px)').matches) {
  document.querySelector('.visual-app-chrome-example .playback-control-menu').open = false;
}
const label = document.getElementById('visual-theme-label');
if(label) label.textContent = `${theme[0].toUpperCase()}${theme.slice(1)} theme`;
