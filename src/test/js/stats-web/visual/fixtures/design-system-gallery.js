'use strict';

const parameters = new URLSearchParams(window.location.search);
const theme = parameters.get('theme') === 'dark' ? 'dark' : 'light';
const view = ['gallery', 'modal', 'modal-long', 'health-alert-modal', 'focus', 'settings', 'health', 'p25', 'admin-access',
  'admin-receiver', 'admin-support', 'dashboard-health', 'dashboard-calls', 'dashboard-activity',
  'radio-directory-coverage',
  'scanner', 'tuner-spectrum', 'rf-planner', 'aliases', 'alias-modal', 'alias-export', 'scan-list-members', 'channels',
  'radio-directory', 'entity-details', 'live-notice', 'live-filter', 'tuner-frequency-modal'].includes(parameters.get('view')) ?
  parameters.get('view') : 'gallery';
document.documentElement.dataset.theme = theme;
document.body.dataset.galleryView = view;
if(view === 'live-notice') document.body.dataset.view = 'live';
if(view === 'scanner') document.body.dataset.view = 'scanner';
if(view === 'entity-details') document.body.dataset.view = 'group-identity';
const label = document.getElementById('visual-theme-label');
if(label) label.textContent = `${theme[0].toUpperCase()}${theme.slice(1)} theme`;
