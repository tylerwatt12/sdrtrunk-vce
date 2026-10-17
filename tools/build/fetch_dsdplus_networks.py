#!/usr/bin/env python3
"""Prepare the immutable public network-name resource; never used by the running application."""
from __future__ import annotations

import argparse
import hashlib
from html.parser import HTMLParser
from pathlib import Path
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request


SOURCE_URL = "https://wiki.radioreference.com/index.php/US_and_Canada_National_DSDPlus_Network_List"
MAXIMUM_BYTES = 2 * 1024 * 1024
MAXIMUM_LINES = 50_000
MAXIMUM_LINE_LENGTH = 4096
MINIMUM_REMOTE_ENTRIES = 100
SOCKET_TIMEOUT_SECONDS = 15
TOTAL_TIMEOUT_SECONDS = 30
P25_ROW = re.compile(
    r'^\s*P25\s*,\s*[0-9A-Fa-f]{1,5}\.[0-9A-Fa-f]{1,3}\s*,\s*"((?:[^"\r\n]|"")*)"\s*(?:;[^\r\n]*)?$'
)
MARKUP = re.compile(r"</?(?:html|body|pre|script|iframe|svg|img|a)\b", re.IGNORECASE)


class CatalogError(RuntimeError):
    """An absent, unsafe, or structurally changed source must stop this build."""


class NetworkBlockParser(HTMLParser):
    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.blocks = []
        self.current = None
        self.invalid = False
        self.block_count = 0

    def handle_starttag(self, tag, attrs):
        if tag == "pre":
            self.block_count += 1
            if self.block_count > 16:
                raise CatalogError("The source contains too many preformatted blocks")
            if self.current is not None:
                self.invalid = True
            else:
                self.current = []
                self.invalid = False
        elif self.current is not None:
            # A network block is text, not executable or linked HTML. Entity-escaped text is decoded by HTMLParser.
            self.invalid = True

    def handle_endtag(self, tag):
        if tag == "pre" and self.current is not None:
            if not self.invalid:
                self.blocks.append("".join(self.current))
            self.current = None

    def handle_data(self, data):
        if self.current is not None:
            self.current.append(data)


def validate_networks(data, minimum_entries=1):
    if not data or len(data) > MAXIMUM_BYTES:
        raise CatalogError("The network list is empty or exceeds its size limit")
    try:
        text = data.decode("utf-8-sig", errors="strict")
    except UnicodeError as error:
        raise CatalogError("The network list is not valid UTF-8") from error
    if "\0" in text or MARKUP.search(text):
        raise CatalogError("The network list contains HTML or invalid control characters")
    lines = text.splitlines()
    if len(lines) > MAXIMUM_LINES or any(len(line) > MAXIMUM_LINE_LENGTH for line in lines):
        raise CatalogError("The network list exceeds its line limits")
    count = sum(1 for line in lines if (match := P25_ROW.fullmatch(line)) and
                0 < len(match.group(1).replace('""', '"')) <= 256)
    if count < minimum_entries:
        raise CatalogError(f"The network list has fewer than {minimum_entries} valid P25 entries")
    return count


def extract_networks(html):
    if not html or len(html) > MAXIMUM_BYTES:
        raise CatalogError("The source page is empty or exceeds its size limit")
    try:
        document = html.decode("utf-8", errors="strict")
    except UnicodeError as error:
        raise CatalogError("The source page is not valid UTF-8") from error
    parser = NetworkBlockParser()
    parser.feed(document)
    parser.close()
    candidates = [block for block in parser.blocks if "DSD+ networks file" in block]
    if len(candidates) != 1:
        raise CatalogError("The source page no longer contains one recognizable DSDPlus network block")
    # Preserve labels, comments and malformed individual rows for the runtime's bounded line parser.
    result = (candidates[0].replace("\r\n", "\n").replace("\r", "\n").strip("\n") + "\n").encode("utf-8")
    validate_networks(result)
    return result


def fetch_networks():
    request = urllib.request.Request(SOURCE_URL, headers={
        "User-Agent": "sdrtrunk-vce-build/1 (public network-name snapshot)",
        "Accept": "text/html",
        "Accept-Encoding": "identity",
    })
    deadline = time.monotonic() + TOTAL_TIMEOUT_SECONDS
    try:
        with urllib.request.urlopen(request, timeout=SOCKET_TIMEOUT_SECONDS) as response:
            final = urllib.parse.urlparse(response.geturl())
            if final.scheme != "https" or final.hostname != "wiki.radioreference.com":
                raise CatalogError("The source page redirected outside the expected HTTPS wiki")
            content_type = response.headers.get_content_type()
            if content_type not in {"text/html", "application/xhtml+xml"}:
                raise CatalogError("The source page did not return HTML")
            advertised = response.headers.get("Content-Length")
            if advertised is not None and (not advertised.isascii() or not advertised.isdecimal() or
                                           int(advertised) > MAXIMUM_BYTES):
                raise CatalogError("The source page has an invalid or oversized content length")
            chunks = []
            size = 0
            while True:
                if time.monotonic() >= deadline:
                    raise CatalogError("The source page exceeded the total download timeout")
                chunk = response.read1(min(64 * 1024, MAXIMUM_BYTES + 1 - size))
                if not chunk:
                    break
                chunks.append(chunk)
                size += len(chunk)
                if size > MAXIMUM_BYTES:
                    raise CatalogError("The source page exceeds its size limit")
            result = extract_networks(b"".join(chunks))
            validate_networks(result, MINIMUM_REMOTE_ENTRIES)
            return result
    except (urllib.error.URLError, OSError, ValueError) as error:
        raise CatalogError("Could not download the public DSDPlus network list; retry the build") from error


def prepare(output, source_file=None):
    if source_file is None:
        data = fetch_networks()
    else:
        try:
            with Path(source_file).open("rb") as source:
                data = source.read(MAXIMUM_BYTES + 1)
        except OSError as error:
            raise CatalogError("The selected local network-list snapshot could not be read") from error
        validate_networks(data)
    destination = Path(output)
    destination.parent.mkdir(parents=True, exist_ok=True)
    # Validation precedes replacement; no stale snapshot can count as a successful preparation.
    temporary = destination.with_name(destination.name + ".tmp")
    try:
        temporary.write_bytes(data)
        temporary.replace(destination)
    finally:
        temporary.unlink(missing_ok=True)
    return hashlib.sha256(data).hexdigest()


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--source-file", type=Path, help="Use one explicit local snapshot instead of downloading")
    arguments = parser.parse_args(argv)
    try:
        digest = prepare(arguments.output, arguments.source_file)
    except (CatalogError, OSError) as error:
        parser.exit(1, f"Radio-system catalog preparation failed: {error}\n")
    print(f"Radio-system catalog prepared (SHA-256 {digest})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
