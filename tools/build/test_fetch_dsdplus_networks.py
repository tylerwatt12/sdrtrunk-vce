"""Hardware-free contracts for extraction and immutable build-snapshot handling."""
import email.message
import importlib.util
import io
from pathlib import Path
import tempfile
import unittest
from unittest import mock


SPEC = importlib.util.spec_from_file_location("fetch_networks", Path(__file__).with_name("fetch_dsdplus_networks.py"))
FETCH = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(FETCH)
LIST = b'; DSD+ networks file\nP25, 12345.001, "Example County / Other County" ; combined\n'


class NetworkFetchTest(unittest.TestCase):
    def setUp(self):
        self.work = tempfile.TemporaryDirectory()
        self.addCleanup(self.work.cleanup)
        self.root = Path(self.work.name)

    def test_extracts_text_and_decodes_entities_once_while_preserving_comments(self):
        html = b'<html><pre>irrelevant</pre><pre>; DSD+ networks file\nP25, 12345.001, &quot;Example &amp; Other&quot; ; combined\n</pre></html>'
        self.assertEqual(b'; DSD+ networks file\nP25, 12345.001, "Example & Other" ; combined\n', FETCH.extract_networks(html))
        literal = b'<pre>; DSD+ networks file\nP25, 12345.001, &quot;Example &amp;amp; Other&quot;\n</pre>'
        self.assertIn(b'Example &amp; Other', FETCH.extract_networks(literal))

    def test_rejects_missing_duplicate_unclosed_and_markup_blocks(self):
        for html in (b'<html>Login required</html>', b'<pre>' + LIST,
                     b'<pre>' + LIST + b'</pre><pre>' + LIST + b'</pre>',
                     b'<pre>' + LIST + b'<script>bad()</script></pre>',
                     b'<pre>; DSD+ networks file\nP25, 12345.001, &quot;&lt;img&gt;&quot;\n</pre>',
                     b'\xff'):
            with self.subTest(html=html):
                with self.assertRaises(FETCH.CatalogError):
                    FETCH.extract_networks(html)

    def test_allows_bad_individual_rows_but_requires_valid_supported_identity(self):
        self.assertEqual(1, FETCH.validate_networks(LIST + b'P25, 12345.002, "Bad quotation\n'))
        for data in (b'', b'Cap+, 123, "Synthetic catalog ID"\n', b'P25, 123, "No WACN"\n',
                     b'P25, 12345.001, "Bad quotation\n', LIST + b'\0', b'\xff',
                     b';' * (FETCH.MAXIMUM_BYTES + 1)):
            with self.subTest(data=data[:100]):
                with self.assertRaises(FETCH.CatalogError):
                    FETCH.validate_networks(data)

    def test_override_is_exact_and_never_performs_network_io(self):
        source = self.root / 'snapshot.networks'
        output = self.root / 'generated' / 'dsdplus.networks'
        source.write_bytes(LIST)
        with mock.patch.object(FETCH, 'fetch_networks', side_effect=AssertionError('Unexpected network')):
            digest = FETCH.prepare(output, source)
        self.assertEqual(LIST, output.read_bytes())
        self.assertEqual(64, len(digest))
        source.write_bytes(b'<html>Unavailable</html>')
        with self.assertRaises(FETCH.CatalogError):
            FETCH.prepare(output, source)
        self.assertEqual(LIST, output.read_bytes(), 'Validation must precede replacing an existing snapshot')

    def response(self, data, content_type='text/html', url=FETCH.SOURCE_URL):
        response = io.BytesIO(data)
        response.headers = email.message.Message()
        response.headers['Content-Type'] = content_type
        response.geturl = lambda: url
        return response

    def test_fetch_is_bounded_and_refuses_challenges_redirects_and_truncated_lists(self):
        many = b'; DSD+ networks file\n' + b''.join(
            f'P25, 12345.{number:03X}, "Example {number}"\n'.encode() for number in range(100))
        page = b'<pre>' + many + b'</pre>'
        with mock.patch.object(FETCH.urllib.request, 'urlopen', return_value=self.response(page)) as opener:
            self.assertEqual(many, FETCH.fetch_networks())
            self.assertEqual(FETCH.SOCKET_TIMEOUT_SECONDS, opener.call_args.kwargs['timeout'])
        for response in (self.response(b'<pre>' + LIST + b'</pre>'),
                         self.response(b'<html>Request denied</html>'),
                         self.response(page, content_type='application/octet-stream'),
                         self.response(page, url='https://example.test/login'),
                         self.response(b'x' * (FETCH.MAXIMUM_BYTES + 1))):
            with mock.patch.object(FETCH.urllib.request, 'urlopen', return_value=response):
                with self.assertRaises(FETCH.CatalogError):
                    FETCH.fetch_networks()
        with mock.patch.object(FETCH.time, 'monotonic', side_effect=[0, FETCH.TOTAL_TIMEOUT_SECONDS + 1]), \
             mock.patch.object(FETCH.urllib.request, 'urlopen', return_value=self.response(page)):
            with self.assertRaisesRegex(FETCH.CatalogError, 'timeout'):
                FETCH.fetch_networks()


if __name__ == '__main__':
    unittest.main()
