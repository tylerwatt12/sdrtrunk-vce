# SDRTrunk-VCE Bug Report Portal Specification

## Product Goal

Build a private PHP web portal at `https://bugreport.radioresolve.com` that accepts fixed-format SDRTrunk-VCE
diagnostic ZIPs, returns a human-readable report code, and lets authenticated administrators search, inspect,
annotate, retain, resolve, download, and delete reports.

The desktop client has no user-selectable collection categories. Every report contains one automatic application
screenshot, the current application log, complete sanitized configuration data, exact tuner identifiers and serial
numbers, hostname, machine specifications, the local timestamp, the user's report text, and the consent record. A user
may add zero to ten additional screenshots. The receiving server records the public IP address used for the upload.

**Excluded items: Passwords, API keys, encryption keys, the encryption vault, and recordings.**

Screenshots are not automatically redacted. Users are warned to review them for sensitive information before
submission. Selected PNG/JPEG screenshots are decoded and re-encoded as PNG by the client, so original paths and source
metadata such as EXIF/GPS are not included.

Submitted reports are retained for 30 days unless deleted sooner or retained for an active investigation. Report
contents are never public, and knowing a report code does not authorize access.

This document defines the only version-1 client and database contract. The earlier unpublished prototype has no
compatibility requirements: do not create legacy tables, legacy fields, alternate bundle layouts, conversion code, or
old-version parsing branches.

## User Workflows

### Direct desktop submission

1. The user opens **Help > Submit Bug Report...**.
2. The user enters a summary, description, and reproduction steps.
3. The user may add and remove up to ten PNG/JPEG screenshots; the automatic SDRTrunk-VCE screenshot remains required.
   Each source is limited to 15 MiB and 50 million pixels, with 75 million added pixels combined.
4. The user accepts the fixed disclosure and selects **Collect and Submit Report**.
5. The client builds the ZIP and sends it to `POST /api/v1/reports` without redirects.
6. After validation and durable storage, the portal returns a code such as `VCE-8F3K-2M7Q-9R5C`.

### Manual upload from another computer

1. The user accepts the same disclosure and selects **Save ZIP for Manual Upload...**.
2. SDRTrunk-VCE saves the exact same version-1 ZIP to a user-selected location and displays
   `https://bugreport.radioresolve.com/manual-upload`.
3. The user moves the ZIP to an internet-connected computer and uploads it through the public manual-upload form.
4. The portal runs the same ZIP validation/storage pipeline and displays the report code and retention deadline.

For manual uploads, the stored public IP is the browser uploader's public IP, which may differ from the SDRTrunk-VCE
machine. No report code exists until the portal successfully receives the ZIP.

## Version 1 Desktop API

### Request

```http
POST /api/v1/reports HTTP/1.1
Host: bugreport.radioresolve.com
Accept: application/json
Content-Type: multipart/form-data; boundary=...
User-Agent: sdrtrunk-vce/<version>
X-Bug-Report-Protocol: 1
X-Client-Report-ID: <UUID>
```

Multipart fields:

- `metadata`: `application/json; charset=UTF-8`
- `bundle`: `application/zip`, filename `diagnostic.zip`

```json
{
  "bundle_format_version": 1,
  "client_report_id": "35f03f28-9533-4d12-9703-a789fe2cba11",
  "submitted_at_utc": "2026-07-18T02:00:00Z",
  "application_version": "0.6.2-alpha-4"
}
```

The client report ID in the header, multipart metadata, `manifest.json`, `user-report.json`, and `system.json` must
match. The endpoint must not redirect because the desktop client refuses upload redirects.

### Success response

Return `201 Created` with `application/json` only after checksum verification, private bundle storage, extracted-file
storage, and the database transaction have succeeded:

```json
{
  "success": true,
  "report_code": "VCE-8F3K-2M7Q-9R5C",
  "received_at_utc": "2026-07-18T02:00:03Z",
  "retention_until_utc": "2026-08-17T02:00:03Z"
}
```

The desktop client requires this exact code format:

```regex
VCE-[0-9A-HJKMNP-TV-Z]{4}-[0-9A-HJKMNP-TV-Z]{4}-[0-9A-HJKMNP-TV-Z]{4}
```

Generate all 12 Crockford Base32 characters using a cryptographically secure random generator, enforce a unique
database index, and retry a collision. The code identifies a report but is not an access credential.

### Error response

Use an appropriate 4xx/5xx status and a stable JSON error shape:

```json
{
  "success": false,
  "error": {
    "code": "invalid_bundle",
    "message": "The diagnostic bundle is invalid."
  }
}
```

Use `400` for malformed requests, `413` for excessive size, `415` for wrong media type, `422` for an invalid bundle,
`429` for rate limiting, and `503` for temporary unavailability. Never return stack traces, SQL errors, storage paths,
or proxy/origin details.

## Manual Upload HTTP Contract

- `GET /manual-upload` displays an accessible upload form, the data disclosure, screenshot warning, exclusions, 100 MiB
  limit, and 30-day policy.
- `POST /manual-upload` accepts one multipart field named `bundle` with a `.zip` file and a 100 MiB request limit.
- Protect the form with the framework's same-origin/CSRF controls and rate limits. JavaScript must not be required.
- Use the identical ZIP validator and persistence service used by `POST /api/v1/reports`.
- On success, display the report code, receipt time, retention deadline, and instruction to share the code. Do not expose
  any report contents.
- On failure, show a generic actionable message and a server-side correlation ID, never uploaded content.

If a client report ID already exists with the same whole-bundle SHA-256, return the existing report code idempotently.
If the UUID exists with a different hash, reject it as a conflict.

## Required ZIP Layout

Every ZIP contains these entries:

```text
user-report.json
system.json
tuners.json
screenshots/application.png
screenshots/user-001.png       # zero to ten contiguous user entries
screenshots/user-002.png
...
screenshots/user-010.png
logs/sdrtrunk_app.log
configuration/database_metadata.json
configuration/alias.json
configuration/alias_broadcast_channel.json
configuration/alias_talkgroup.json
configuration/alias_radio.json
configuration/alias_status.json
configuration/alias_tone_sequence.json
configuration/alias_text_identifier.json
configuration/alias_action.json
configuration/configuration_channel.json
configuration/configuration_channel_map.json
configuration/configuration_broadcast_stream.json
configuration/application_settings.json
configuration/application_icons.json
manifest.json
checksums.sha256
```

The checksum file covers every preceding entry, including `manifest.json`, and does not cover itself. Every screenshot
must be a decodable PNG. Additional screenshot names must be contiguous, start at `user-001.png`, and match the manifest.

`manifest.json` contains:

- bundle format, client UUID, local/UTC creation timestamps, endpoint, and manual-upload address;
- consent version and exact consent/disclosure/exclusion/retention/screenshot-warning text;
- `optional_categories: false`;
- `tuner_serial_numbers_redacted: false`;
- `local_ip_addresses_included: false`;
- `disk_serial_numbers_included: false`;
- `raw_database_included: false`;
- `screenshot_redaction_applied: false`;
- `screenshot_source_metadata_retained: false`;
- a `screenshots` array containing `{path, source}`, where source is `automatic_application` or `user_added`;
- an `entries` array containing each preceding entry's path, size in bytes, and SHA-256.

The raw SQLite database, vault, passwords, keys, audio/I/Q recordings, streaming files, JMBE/module jars, original image
files, and arbitrary user-selected files are never valid entries.

## `system.json` Machine Schema

The desktop client emits one clean schema; fields may contain a collection error when the OS cannot provide a value.

```json
{
  "client_report_id": "UUID",
  "captured_at_local": "ISO-8601 timestamp with offset",
  "captured_at_utc": "ISO-8601 UTC timestamp",
  "time_zone_id": "America/New_York",
  "time_zone_display_name": "Eastern Daylight Time",
  "locale": "en-US",
  "hostname": "receiver-node",
  "public_ip_address": "Observed and stored by the report server from the HTTPS request",
  "application": {
    "product": "sdrtrunk-vce",
    "display_name": "sdrtrunk-vce v...",
    "version": "...",
    "build_timestamp": "...",
    "build_jdk": "...",
    "build_os": "...",
    "process_started_at_utc": "...",
    "process_uptime_ms": 12345,
    "jvm_arguments": []
  },
  "java_runtime": {
    "java_version": "25...",
    "java_vendor": "...",
    "java_vm_name": "...",
    "java_vm_version": "...",
    "javafx_version": "...",
    "default_charset": "UTF-8",
    "maximum_memory_bytes": 0,
    "allocated_memory_bytes": 0,
    "free_allocated_memory_bytes": 0
  },
  "machine": {
    "operating_system": {"name": "...", "version": "...", "architecture": "..."},
    "cpu": {
      "manufacturer": "AuthenticAMD",
      "model_name": "AMD Ryzen 7 5825U with Radeon Graphics",
      "family": "25",
      "model_identifier": "80",
      "stepping": "0",
      "microarchitecture": "Zen 3",
      "physical_packages": 1,
      "physical_cores": 8,
      "logical_threads": 16,
      "maximum_frequency_hz": 4500000000
    },
    "memory": {"total_bytes": 0, "available_bytes": 0, "used_bytes": 0},
    "data_storage": {
      "volume_name": "...",
      "filesystem_type": "...",
      "total_bytes": 0,
      "used_bytes": 0,
      "unallocated_bytes": 0,
      "available_to_application_bytes": 0,
      "physical_disk": {
        "matched": true,
        "name": "...",
        "manufacturer": "... or Unavailable",
        "manufacturer_inferred_from_model": true,
        "model": "...",
        "type": "SSD, HDD, Removable, Virtual, or Unknown",
        "capacity_bytes": 0,
        "partition_name": "...",
        "partition_type": "...",
        "disk_serial_number_included": false
      }
    }
  }
}
```

Do not trust the `public_ip_address` explanatory string as an address. The server-observed request address is
authoritative. Local interface names and local IP addresses are not collected.

## Upload Validation Pipeline

Apply validation before a report is visible to administrators:

1. Enforce HTTPS, method, content type, exact multipart fields, and a 100 MiB compressed request limit.
2. Rate limit by trusted public source IP; start with 5 reports/hour and 20/day per IP, configurable by administrators.
3. Stream to a random quarantine path outside the document root; never use uploaded names or report codes as paths.
4. Require ZIP; reject encryption, absolute/traversal paths, symlinks, duplicate names, more than 40 entries, more than
   250 MiB expanded content, suspicious compression ratios, and malformed central-directory data.
5. Require the fixed entries and configuration list, allow only the screenshot pattern above, and reject every other
   entry.
6. Strictly parse metadata/JSON with bounded sizes and depth. Require version 1 and matching client IDs.
7. Require manifest boolean values and exact disclosure strings to match this client contract.
8. Recompute and verify all manifest/checksum sizes and SHA-256 values and the whole-bundle SHA-256.
9. Decode every screenshot as PNG, enforce at most 11 screenshots, sensible pixel limits, and no trailing polyglot data.
10. Run a second server-side secret scan over JSON and logs. Reject or quarantine suspected passwords, API keys,
    authorization values, bearer tokens, private keys, or encryption-key material; never silently retain it.
11. Run malware scanning when available. Never execute, include, source, or interpret uploaded content as PHP/HTML.
12. Extract only allowlisted entries to private storage, insert metadata in one transaction, atomically promote storage,
    and only then acknowledge receipt.

Treat every uploaded string as untrusted and escape it for its output context. Never render uploaded HTML, SVG,
JavaScript, or arbitrary MIME types inline.

## Public IP Handling

Store the address observed by the application server. Use `REMOTE_ADDR` unless the request came from a configured
trusted reverse proxy. Behind Cloudflare, trust `CF-Connecting-IP` only when the immediate peer is in the maintained
Cloudflare network list. Never trust arbitrary forwarding headers. Store IPv4/IPv6 with `inet_pton()` in
`VARBINARY(16)`. Record whether the submission was `desktop` or `manual`.

## MySQL 8 / MariaDB Schema

Use `utf8mb4`, UTC timestamps, InnoDB, strict mode, prepared statements, and framework migrations. These are the initial
tables, not migrations from an earlier report schema.

```sql
CREATE TABLE admin_users (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    email VARCHAR(254) NOT NULL,
    display_name VARCHAR(120) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    role ENUM('administrator', 'reviewer') NOT NULL DEFAULT 'reviewer',
    active BOOLEAN NOT NULL DEFAULT TRUE,
    last_login_at DATETIME(3) NULL,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_admin_users_email (email)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE reports (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    client_report_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    report_code CHAR(18) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    bundle_format_version SMALLINT UNSIGNED NOT NULL,
    consent_version SMALLINT UNSIGNED NOT NULL,
    submission_method ENUM('desktop', 'manual') NOT NULL,
    status ENUM('new', 'reviewing', 'needs_information', 'resolved') NOT NULL DEFAULT 'new',
    summary VARCHAR(255) NOT NULL,
    description MEDIUMTEXT NOT NULL,
    reproduction_steps MEDIUMTEXT NOT NULL,
    application_product VARCHAR(80) NULL,
    application_version VARCHAR(80) NULL,
    application_build_timestamp VARCHAR(80) NULL,
    hostname VARCHAR(255) NULL,
    public_ip VARBINARY(16) NOT NULL,
    client_local_at VARCHAR(80) NOT NULL,
    client_timezone VARCHAR(100) NULL,
    screenshot_count TINYINT UNSIGNED NOT NULL,
    cpu_model VARCHAR(255) NULL,
    cpu_physical_cores SMALLINT UNSIGNED NULL,
    cpu_logical_threads SMALLINT UNSIGNED NULL,
    memory_total_bytes BIGINT UNSIGNED NULL,
    disk_model VARCHAR(255) NULL,
    disk_type VARCHAR(40) NULL,
    data_volume_total_bytes BIGINT UNSIGNED NULL,
    data_volume_used_bytes BIGINT UNSIGNED NULL,
    received_at DATETIME(3) NOT NULL,
    retention_until DATETIME(3) NOT NULL,
    retention_hold BOOLEAN NOT NULL DEFAULT FALSE,
    bundle_storage_key VARCHAR(512) NOT NULL,
    bundle_size_bytes BIGINT UNSIGNED NOT NULL,
    bundle_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    manifest_json JSON NOT NULL,
    user_report_json JSON NOT NULL,
    system_json JSON NOT NULL,
    tuners_json JSON NOT NULL,
    last_viewed_at DATETIME(3) NULL,
    resolved_at DATETIME(3) NULL,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_reports_client_report_id (client_report_id),
    UNIQUE KEY uq_reports_report_code (report_code),
    KEY idx_reports_received (received_at DESC),
    KEY idx_reports_status_received (status, received_at DESC),
    KEY idx_reports_retention (retention_hold, retention_until),
    KEY idx_reports_version_received (application_version, received_at DESC),
    KEY idx_reports_hostname_received (hostname, received_at DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE report_files (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    report_id BIGINT UNSIGNED NOT NULL,
    path VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    category ENUM('manifest', 'user_report', 'system', 'tuners', 'screenshot', 'log', 'configuration', 'checksums') NOT NULL,
    content_type VARCHAR(100) NOT NULL,
    size_bytes BIGINT UNSIGNED NOT NULL,
    sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    storage_key VARCHAR(512) NOT NULL,
    created_at DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_report_files_path (report_id, path),
    KEY idx_report_files_category (report_id, category),
    CONSTRAINT fk_report_files_report FOREIGN KEY (report_id) REFERENCES reports(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE report_notes (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    report_id BIGINT UNSIGNED NOT NULL,
    admin_user_id BIGINT UNSIGNED NOT NULL,
    note MEDIUMTEXT NOT NULL,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_report_notes_report_created (report_id, created_at),
    CONSTRAINT fk_report_notes_report FOREIGN KEY (report_id) REFERENCES reports(id) ON DELETE CASCADE,
    CONSTRAINT fk_report_notes_admin FOREIGN KEY (admin_user_id) REFERENCES admin_users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE report_events (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    report_id BIGINT UNSIGNED NOT NULL,
    admin_user_id BIGINT UNSIGNED NULL,
    event_type ENUM('received', 'viewed', 'downloaded', 'status_changed', 'note_added', 'retention_changed', 'deleted') NOT NULL,
    actor_ip VARBINARY(16) NULL,
    details_json JSON NULL,
    created_at DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_report_events_report_created (report_id, created_at),
    CONSTRAINT fk_report_events_report FOREIGN KEY (report_id) REFERENCES reports(id) ON DELETE CASCADE,
    CONSTRAINT fk_report_events_admin FOREIGN KEY (admin_user_id) REFERENCES admin_users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE tags (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    name VARCHAR(80) NOT NULL,
    color VARCHAR(16) NULL,
    created_at DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_tags_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE report_tags (
    report_id BIGINT UNSIGNED NOT NULL,
    tag_id BIGINT UNSIGNED NOT NULL,
    PRIMARY KEY (report_id, tag_id),
    CONSTRAINT fk_report_tags_report FOREIGN KEY (report_id) REFERENCES reports(id) ON DELETE CASCADE,
    CONSTRAINT fk_report_tags_tag FOREIGN KEY (tag_id) REFERENCES tags(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
```

Use one transaction for the report, file rows, and initial event. If private-storage promotion cannot participate in the
SQL transaction, use a pending internal state and compensating cleanup. The JSON columns retain the authoritative full
client documents; the scalar hardware columns are bounded search/display projections.

## Private Storage

Use an adapter so local private storage can later move to S3-compatible object storage:

```text
<private-storage>/reports/2026/07/<client-uuid>/bundle.zip
<private-storage>/reports/2026/07/<client-uuid>/screenshots/application.png
<private-storage>/reports/2026/07/<client-uuid>/screenshots/user-001.png
<private-storage>/reports/2026/07/<client-uuid>/logs/sdrtrunk_app.log
<private-storage>/reports/2026/07/<client-uuid>/configuration/...
```

Keep storage outside the web document root and deny it at the web-server layer. All downloads pass through an
authenticated controller, verify authorization, log the action, set `nosniff`, and use safe attachment headers. Encrypt
storage at rest and subject backups to a documented bounded retention policy.

## Pages

### Public

- `/`: product/privacy summary, collection disclosure, separate highlighted exclusion line, 30-day policy, and report
  code instructions.
- `/manual-upload`: ZIP upload form and success/error result; never displays report contents.
- `/privacy`: full collection, screenshots, public-IP, administrator access, retention, investigation hold, deletion,
  and backup policy.
- `/healthz`: minimal status without infrastructure details.
- There is no public report-detail or download page.

### Administrator

- `/admin/login`: secure login, throttling, and generic failures.
- `/admin/reports`: newest-first list, exact report-code search, and filters for status, date, submission method,
  application version, hostname, tuner type, CPU model, disk type, tag, and retention hold.
- `/admin/reports/{report_code}`: summary, report text, direct/manual receipt, client and server times, public IP,
  hostname, build, structured CPU/RAM/storage data, a screenshot gallery, full tuner identifiers, validation state,
  status, tags, notes, retention controls, and audit timeline.
- `/admin/reports/{report_code}/logs`: escaped, searchable, line-numbered log viewer and authorized download.
- `/admin/reports/{report_code}/configuration`: formatted JSON table/file navigation, row counts, and download.
- `/admin/reports/{report_code}/files`: validated inventory, hashes, and authorized individual/original ZIP downloads.
- `/admin/users`: administrator-only user activation, role, and password reset.
- `/admin/settings`: retention defaults, rate limits, storage/scan health, and purge-job status; never show secrets.

Prioritize log warnings/errors near the client's timestamp. Clearly label screenshots as unredacted and tuner serials as
exact. Never show local IP because the client does not collect it.

## Administrator Actions and Audit

- Search/open by exact report code.
- Change status among new, reviewing, needs information, and resolved.
- Add internal notes and tags.
- Download validated files or the original validated ZIP.
- Place/remove an investigation hold and set an explicit future retention date.
- Permanently delete after confirmation naming the report code.
- Audit receipt, views, downloads, status changes, notes, tags, retention changes, and deletion requests.

## Retention

Run at least daily. Select `retention_hold = 0 AND retention_until <= UTC_TIMESTAMP(3)` using the retention index.
Delete extracted files and the original ZIP, then the report row/cascaded metadata. Retry storage failures and expose
them to administrators. Do not claim deletion until storage and database content are both gone. Backups must age out on
a documented bounded schedule.

## Authentication and Security

- Use a maintained PHP framework and its routing, validation, CSRF, migrations, escaping, and sessions.
- Bind every query parameter. Hash passwords with Argon2id when available or the framework's current strong default.
- Use `Secure`, `HttpOnly`, `SameSite=Strict` cookies, rotate IDs at login, and enforce idle/absolute timeouts.
- Require CSRF protection for state changes, login throttling, role checks on every route, and TOTP/WebAuthn support.
- Configure CSP, HSTS, `frame-ancestors 'none'`, `nosniff`, and a conservative referrer policy.
- Keep production errors generic with correlation IDs. Never log uploaded bodies, configuration values, or credentials.
- Never send report contents to analytics, error tracking, AI, or third parties without a new policy/consent decision.

## Acceptance Criteria

- Desktop and manual uploads share one strict validator and return the required code.
- A report is acknowledged only after checksum-verified durable storage and metadata persistence.
- The fixed automatic screenshot and zero-to-ten contiguous added screenshots are displayed as a gallery.
- Full tuner serial numbers are visible to authorized administrators and are never hashed/redacted.
- CPU manufacturer/model, package/core/thread counts, RAM total/available/used, volume total/used/available, and matched
  disk manufacturer/model/type/capacity are usable from the report page.
- The portal rejects raw databases, vaults, recordings, unexpected entries, traversal, duplicates, zip bombs, checksum
  mismatches, mismatched IDs, malformed images, and invalid manifest flags.
- Report contents and downloads are inaccessible without an authorized administrator session.
- Public IP, hostname, local timestamp, UTC receipt, consent version, submission method, and retention deadline persist.
- Immediate and scheduled deletion remove database and private-storage contents.
- Tests cover both submission paths, idempotency/conflict behavior, code collisions, multipart/ZIP/checksum validation,
  screenshot bounds, traversal/zip-bomb defense, proxy IP trust, authorization, CSRF, escaping, deletion, and retention.

## Coding-Agent Prompt

Build the PHP portal at `bugreport.radioresolve.com` exactly as specified in this document. Treat this document and the
current SDRTrunk-VCE version-1 client as the only source of truth; the earlier prototype was never released, so create
no legacy schema, compatibility fields, conversion migrations, or alternate parsers. Use a maintained PHP framework,
MySQL/MariaDB, framework migrations, private filesystem storage behind an adapter, server-rendered accessible pages,
and a scheduled retention command. Implement the direct desktop API and public manual-ZIP workflow through one strict
validation/persistence service, authenticated administration, screenshot gallery, structured hardware display, exact
tuner serial handling, schema, audit events, deletion/holds, automated tests, production configuration documentation,
and a deployment checklist. Preserve the fixed exclusions and separate highlighted exclusion line. Before finishing,
run the complete test suite plus end-to-end fixtures for both the exact desktop multipart request and manual ZIP upload,
then report deployment prerequisites and any operator decisions still required.
