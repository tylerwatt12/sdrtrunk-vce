'use strict';

const parameters = new URLSearchParams(window.location.search);
const theme = parameters.get('theme') === 'dark' ? 'dark' : 'light';
const view = ['gallery', 'modal', 'modal-long', 'focus', 'settings', 'health', 'p25', 'admin-access',
  'admin-receiver', 'dashboard-health', 'dashboard-calls', 'dashboard-activity'].includes(parameters.get('view')) ?
  parameters.get('view') : 'gallery';
document.documentElement.dataset.theme = theme;
document.body.dataset.galleryView = view;
const label = document.getElementById('visual-theme-label');
if(label) label.textContent = `${theme[0].toUpperCase()}${theme.slice(1)} theme`;
