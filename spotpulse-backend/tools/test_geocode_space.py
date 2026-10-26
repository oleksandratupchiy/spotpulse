import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
import geocode_space as tool


class GeocodingTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.cache = Path(self.temp.name) / 'cache.db'
        self.result = [{'display_name': 'Confirmed address', 'lat': '50.45', 'lon': '30.52'}]
        self.space = dict(name='Hub', city='Kyiv', address='Street 1', latitude=None, longitude=None)

    def test_cache_avoids_repeated_provider_calls(self):
        with patch.object(tool, 'request_json', return_value=self.result) as request:
            for _ in range(2):
                self.assertEqual(tool.candidates('Street 1, Kyiv', 'https://provider.test/search', 'SpotPulse/contact', self.cache), self.result)
            request.assert_called_once()

    def test_no_result_or_cancel_does_not_save(self):
        for results in ([], self.result):
            with patch.object(tool, 'candidates', return_value=results), patch.object(tool, 'request_json', return_value=self.space) as request:
                self.assertFalse(tool.geocode(1, 'http://localhost/api', '', '', self.cache, choose=lambda _: ''))
                self.assertEqual(request.call_count, 1)

    def test_explicit_selection_saves_coordinates_and_preserves_fields(self):
        with patch.object(tool, 'candidates', return_value=self.result), patch.object(tool, 'request_json', return_value=self.space) as request:
            self.assertTrue(tool.geocode(1, 'http://localhost/api', '', '', self.cache, choose=lambda _: '1'))
            self.assertEqual(request.call_args.kwargs['method'], 'PUT')
            self.assertEqual(request.call_args.kwargs['body']['latitude'], 50.45)
            self.assertEqual(request.call_args.kwargs['body']['name'], 'Hub')

    def test_invalid_coordinates_and_provider_failure_are_not_cached(self):
        with patch.object(tool, 'request_json', return_value=[dict(lat='NaN', lon='30')]):
            with self.assertRaises(ValueError):
                tool.candidates('Street', 'https://provider.test', 'SpotPulse/contact', self.cache)
        with patch.object(tool, 'request_json', side_effect=OSError('unavailable')):
            with self.assertRaises(OSError):
                tool.candidates('Other street', 'https://provider.test', 'SpotPulse/contact', self.cache)

    def test_rate_limit_and_changed_address(self):
        with patch.object(tool, 'request_json', return_value=self.result), patch.object(tool.time, 'sleep') as sleep:
            tool.candidates('First', 'https://provider.test', 'SpotPulse/contact', self.cache)
            tool.candidates('Second', 'https://provider.test', 'SpotPulse/contact', self.cache)
            self.assertGreater(sleep.call_args.args[0], 1)
        with patch.object(tool, 'candidates', return_value=self.result), patch.object(tool, 'request_json', side_effect=[self.space, {**self.space, 'city': 'Lviv'}]) as request:
            with self.assertRaises(ValueError):
                tool.geocode(1, 'http://localhost/api', '', '', self.cache, choose=lambda _: '1')
            self.assertEqual(request.call_count, 2)


if __name__ == '__main__':
    unittest.main()
