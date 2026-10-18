"""Minimal ISO9660 reader for bootable XP x64 source media.
Reads primary-volume file names and byte-for-byte file payloads; no third-party module required.
"""
import os
import struct

SECTOR = 2048

class ISO9660:
    def __init__(self, path):
        self.path = str(path)
        self.f = open(self.path, 'rb')
        self.f.seek(SECTOR * 16)
        pvd = self.f.read(SECTOR)
        if pvd[:6] != b'\x01CD001':
            raise ValueError('Expected an ISO9660 primary volume descriptor')
        self.label = pvd[40:72].decode('ascii', 'replace').strip()
        self.entries = {}
        self._walk('', self._parse(pvd[156:190]))

    def _parse(self, raw):
        size = raw[0]
        if size < 34:
            raise ValueError('Invalid ISO9660 directory record')
        v = raw[33:33+raw[32]]
        name = '.' if v == b'\0' else '..' if v == b'\1' else v.decode('latin1')
        name = name.split(';', 1)[0]
        return {'name': name, 'extent': struct.unpack_from('<I', raw, 2)[0],
                'size': struct.unpack_from('<I', raw, 10)[0], 'flags': raw[25]}

    def read(self, node):
        self.f.seek(SECTOR * node['extent'])
        return self.f.read(node['size'])

    def _walk(self, path, node):
        if path in self.entries:
            return
        self.entries[path] = node
        if not node['flags'] & 2:
            return
        data = self.read(node)
        pos = 0
        while pos < len(data):
            count = data[pos]
            if not count:
                pos = (pos // SECTOR + 1) * SECTOR
                continue
            child = self._parse(data[pos:pos+count])
            pos += count
            if child['name'] in ('.', '..'):
                continue
            self._walk(f'{path}/{child["name"]}'.lstrip('/'), child)

    def extract(self, path, target):
        node = self.entries[path]
        if node['flags'] & 2:
            os.makedirs(target, exist_ok=True)
            return
        os.makedirs(os.path.dirname(target), exist_ok=True)
        with open(target, 'wb') as dest:
            self.f.seek(node['extent'] * SECTOR)
            size = node['size']
            while size:
                block = self.f.read(min(1024 * 1024, size))
                if not block:
                    raise EOFError(f'Truncated ISO while reading {path}')
                dest.write(block)
                size -= len(block)

    def close(self):
        self.f.close()
