'use strict';

// Every gallery composition is reviewed independently in both themes and sizes.
const galleryViews = [
  'mobile-table', 'gallery', 'loading-indicators', 'text-links', 'workflows', 'qr-code', 'app-chrome', 'audio-dock', 'access-landing', 'access-login-modal',
  'modal', 'modal-long', 'alias-list-create-modal', 'activity-action-tooltip', 'activity-filters',
  'health-alert-modal', 'focus', 'settings', 'health', 'computer', 'status-primitives', 'p25', 'admin-access',
  'admin-navigation', 'focused-settings', 'admin-receiver', 'admin-support', 'dashboard-health', 'dashboard-calls',
  'dashboard-activity', 'signal-quality-detail', 'radioreference-results', 'radioreference-connect', 'radio-directory-coverage',
  'radio-directory-panel', 'admin-scan-lists', 'scanner', 'tuner-spectrum', 'tuners', 'aliases', 'alerts',
  'alias-modal', 'alias-export', 'scan-list-members', 'channels', 'radio-directory', 'entity-details',
  'live-notice', 'live-filter', 'tuner-frequency-popover', 'spectrum-discovery', 'spectrum-search',
  'control-states', 'table-overflow', 'setup-guide', 'call-matching', 'receiving-windows', 'section-body',
  'awaiting-apply', 'comparison-table'
];
const gallerySelector = (view) => ({
  gallery: '.design-system-gallery', 'modal-long': '.visual-modal-example',
  'loading-indicators': '.visual-capture-section', 'text-links': '.visual-text-links-example',
  'radio-directory-panel': '.visual-radio-directory-coverage-example',
  'live-notice': '.visual-live-example',
  'receiving-windows': '.visual-frequency-window-example',
  'spectrum-discovery': '.visual-spectrum-discovery-example .spectrum-discovery-modal',
  'spectrum-search': '.visual-spectrum-search-example .spectrum-search-modal',
  'radioreference-connect': '[role="dialog"][aria-labelledby^="read-only-modal-title-radioreference-connect-"]',
  'comparison-table': '[role="dialog"][aria-labelledby^="read-only-modal-title-gallery-comparison-table-"]',
  'setup-guide': 'body'
}[view] || `.visual-${view}-example`);
const presentations = [
  { name: 'light-desktop', theme: 'light', viewport: { width: 1280, height: 900 } },
  { name: 'dark-desktop', theme: 'dark', viewport: { width: 1280, height: 900 } },
  { name: 'light-mobile', theme: 'light', viewport: { width: 390, height: 844 } },
  { name: 'dark-mobile', theme: 'dark', viewport: { width: 390, height: 844 } }
];

module.exports = { galleryViews, gallerySelector, presentations };
