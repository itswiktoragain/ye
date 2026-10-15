import pathlib
import shutil
import tempfile
import unittest
from unittest.mock import patch
import sys
sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1] / 'src'))
import guest

class GuestTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.path = pathlib.Path(self.tmp.name)

    def tearDown(self):
        self.tmp.cleanup()

    def test_sparse_image_creation_and_guard(self):
        disk = guest.new_guest_disk(self.path, 8)
        self.assertEqual(disk.stat().st_size, 8 * 1024 ** 3)
        with self.assertRaises(FileExistsError):
            guest.new_guest_disk(self.path, 8)

    def test_guest_command_uses_aarch64_and_tcg(self):
        guest.new_guest_disk(self.path, 8)
        with patch('guest.firmware_args', return_value=['-bios', '/fake/QEMU_EFI.fd']):
            command = guest.build_command(self.path)
        self.assertEqual(command[0], 'qemu-system-aarch64')
        self.assertIn('tcg,thread=multi', command)
        self.assertIn('virt,highmem=on', command)
        self.assertNotIn('kvm', command)
        self.assertIn('virtio-blk-pci,drive=guestdisk,bootindex=2', command)

    def test_iso_media_boot_order_and_readonly(self):
        guest.new_guest_disk(self.path, 8)
        iso = self.path / 'test.iso'
        iso.write_bytes(b'demo')
        with patch('guest.firmware_args', return_value=['-bios', '/fake/QEMU_EFI.fd']):
            command = guest.build_command(self.path, ('iso', str(iso)))
        self.assertIn('scsi-cd,drive=cd0,bus=scsi0.0,bootindex=1', command)
        self.assertTrue(any('readonly=on' in c and str(iso) in c for c in command))

    def test_invalid_size_and_source_rejected(self):
        with self.assertRaises(ValueError):
            guest.new_guest_disk(self.path, 99999)
        guest.new_guest_disk(self.path, 8)
        with patch('guest.firmware_args', return_value=[]):
            with self.assertRaises(ValueError):
                guest.build_command(self.path, ('disk', 'bogus'))

if __name__ == '__main__':
    unittest.main()
