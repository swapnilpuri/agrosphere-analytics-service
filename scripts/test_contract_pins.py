"""Independent pin validation rejects tampered resources, metadata and prior archives."""
import json
from pathlib import Path
import unittest
from unittest.mock import patch
import validate_contract_pins as pins


class ContractPinsTests(unittest.TestCase):
    def read(self, path): return (pins.ROOT / 'src/main/resources/contracts' / path).read_bytes()

    def test_exact_six_resource_pins(self):
        pins.validate(self.read)
        self.assertEqual(6, len(pins.EXPECTED))

    def test_each_changed_resource_fails(self):
        for victim in pins.EXPECTED:
            with self.subTest(victim=victim), self.assertRaises(AssertionError):
                pins.validate(lambda path: b'{}\n' if path == victim else self.read(path))

    def test_wrong_metadata_fails(self):
        for field in ['release', 'sourceManifestSha256', 'files', 'archive']:
            metadata = json.loads(self.read('RELEASE.json')); metadata[field] = 'changed'
            with self.subTest(field=field), self.assertRaises(AssertionError):
                pins.validate(lambda path: json.dumps(metadata).encode() if path == 'RELEASE.json' else self.read(path))

    def test_changed_prior_archives_fail(self):
        original = Path.read_bytes
        for name in pins.ARCHIVE:
            victim = pins.ROOT / name
            with self.subTest(victim=name), patch.object(Path, 'read_bytes', lambda p: b'{}\n' if p == victim else original(p)):
                with self.assertRaises(AssertionError): pins.run()


if __name__ == '__main__': unittest.main()
