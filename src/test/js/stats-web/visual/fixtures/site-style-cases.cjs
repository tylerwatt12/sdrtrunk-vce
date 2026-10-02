'use strict';

// Every gallery composition is reviewed independently in both themes and sizes.
const galleryViews = [
  'mobile-table', 'gallery', 'workflows', 'app-chrome', 'audio-dock', 'access-landing', 'access-login-modal',
  'modal', 'modal-long', 'alias-list-create-modal', 'activity-action-tooltip', 'activity-filters',
  'health-alert-modal', 'focus', 'settings', 'health', 'status-primitives', 'p25', 'admin-access',
  'admin-navigation', 'admin-receiver', 'admin-support', 'dashboard-health', 'dashboard-calls',
  'dashboard-activity', 'signal-quality-detail', 'radioreference-results', 'radio-directory-coverage',
  'radio-directory-panel', 'admin-scan-lists', 'scanner', 'tuner-spectrum', 'tuners', 'aliases',
  'alias-modal', 'alias-export', 'scan-list-members', 'channels', 'radio-directory', 'entity-details',
  'live-notice', 'live-filter', 'tuner-frequency-popover', 'spectrum-discovery', 'spectrum-search',
  'control-states'
];
const gallerySelector = (view) => ({
  gallery: '.design-system-gallery', 'modal-long': '.visual-modal-example',
  'radio-directory-panel': '.visual-radio-directory-coverage-example',
  'live-notice': '.visual-live-example',
  'spectrum-discovery': '.visual-spectrum-discovery-example .spectrum-discovery-modal',
  'spectrum-search': '.visual-spectrum-search-example .spectrum-search-modal'
}[view] || `.visual-${view}-example`);
const presentations = [
  { name: 'light-desktop', theme: 'light', viewport: { width: 1280, height: 900 } },
  { name: 'dark-desktop', theme: 'dark', viewport: { width: 1280, height: 900 } },
  { name: 'light-mobile', theme: 'light', viewport: { width: 390, height: 844 } },
  { name: 'dark-mobile', theme: 'dark', viewport: { width: 390, height: 844 } }
];

module.exports = { galleryViews, gallerySelector, presentations };
