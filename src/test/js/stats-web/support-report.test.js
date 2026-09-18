'use strict';

const assert = require('assert');
const fs = require('fs');
const path = require('path');

const applicationPath = path.resolve(process.argv[2]);
const assets = path.dirname(applicationPath);
const application = fs.readFileSync(applicationPath, 'utf8');
const html = fs.readFileSync(path.resolve(assets, '../index.html'), 'utf8');
const css = fs.readFileSync(path.resolve(assets, 'styles/features/administration.css'), 'utf8');

assert.match(html, /id="support-report-indicator"[^>]+view=admin&amp;tab=support/);
assert.match(html, /id="icon-bug"/);
assert.match(application, /function renderAdminSupportReport\(\)/);
assert.match(application, /Title for your issue/);
assert.match(application, /Description for your issue/);
assert.match(application, /Steps to reproduce this issue/);
assert.match(application, /Email address/);
assert.match(application, /Full activity history may take much longer/);
assert.match(application, /Generate Support Bundle/);
assert.match(application, /Submit Bug Report/);
assert.match(application, /What’s happening/);
assert.match(application, /\/api\/v1\/admin\/support-reports/);
assert.match(application, /accessSession\.tier === 'ADMIN'/);
assert.match(css, /\.support-report-progress/);
assert.match(css, /\.support-report-warning/);
