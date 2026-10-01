'use strict';

const assert = require('assert');
const fs = require('fs');
const path = require('path');

const applicationPath = path.resolve(process.argv[2]);
const assets = path.dirname(applicationPath);
const application = fs.readFileSync(applicationPath, 'utf8');
const html = fs.readFileSync(path.resolve(assets, '../index.html'), 'utf8');
const css = fs.readFileSync(path.resolve(assets, 'styles/features/administration.css'), 'utf8');
const settingsCss = fs.readFileSync(path.resolve(assets, 'styles/compositions/settings.css'), 'utf8');

assert.match(html, /id="support-report-indicator"[^>]+receiver-health-indicator receiver-health-neutral icon-button[^>]+view=admin&amp;tab=support/);
assert.match(html, /id="icon-bug"/);
assert.match(application, /function renderAdminSupportReport\(\)/);
assert.match(application, /admin-form settings-page-form support-report-form/);
assert.match(application, /settingsCard\('Issue details'/);
assert.match(application, /settingsCard\('Problem details'/);
assert.match(application, /settingsCard\('Information for support'/);
assert.match(application, /settings-form-footer support-report-footer/);
assert.match(application, /formField\('Title'/);
assert.match(application, /formField\('What happened'/);
assert.match(application, /formField\('What happened before the problem\?'/);
assert.match(application, /Email address/);
assert.match(application, /A full history can take longer and create a large upload/);
assert.match(application, /Prepare support report/);
assert.match(application, /Submit report/);
assert.match(application, /Information to include/);
assert.match(application, /admin-form-field ui-field/);
assert.match(application, /admin-toggle-control ui-field-row/);
assert.match(application, /support-report-warning ui-notice ui-notice-warning/);
assert.match(application, /admin-form-actions ui-action-row/);
assert.match(application, /\/api\/v1\/admin\/support-reports/);
assert.match(application, /accessSession\.tier === 'ADMIN'/);
assert.match(css, /\.support-report-progress/);
assert.match(css, /\.support-report-warning/);
assert.match(css, /\.support-report-card-grid\s*\{[^}]*grid-template-columns:\s*repeat\(2,/);
assert.match(css, /\.support-report-diagnostics-card\s*\{[^}]*grid-column:\s*1 \/ -1;/);
assert.match(css, /\.support-report-narrative-fields\s*\{[^}]*grid-template-columns:\s*repeat\(2,/);
assert.match(css, /\.support-report-choice-list\s*\{[^}]*grid-template-columns:\s*repeat\(2,/);
assert.match(css, /\.support-report-footer > \.support-report-progress,[\s\S]*?grid-column:\s*1 \/ -1;/);
assert.match(css, /\.support-report-fields\s*\{[^}]*align-items:\s*start;/);
assert.doesNotMatch(css, /\.support-report-form\s*\{[^}]*linear-gradient/);
assert.doesNotMatch(css, /\.admin-settings-(?:branch|nested)-items\s*\{[^}]*border-left:/);
assert.match(settingsCss,
  /\.ui-settings-nav-link\[aria-current="page"\]\s*\{[^}]*background:\s*var\(--accent-soft\)/,
  'Administration navigation must use the shared composition and its accessible current-page state.');
