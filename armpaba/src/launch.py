#!/usr/bin/env python3
"""Launch the guest only from the controlled local boot manager."""
import os
import subprocess
import sys
from guest import get_store, build_command


def main():
    # xinit passes arbitrary extra arguments? Accept only explicit fixed forms.
    kind = os.environ.get('ARMPABA_SOURCE_KIND', '')
    path = os.environ.get('ARMPABA_SOURCE_PATH', '')
    source = (kind, path) if kind and path else None
    store = get_store()
    command = build_command(store, source=source)
    print('Armpaba: running ARM64 guest under QEMU software emulation')
    sys.stdout.flush()
    return subprocess.call(command)

if __name__ == '__main__':
    sys.exit(main())
