#!/usr/bin/env python3
"""Keyboard-driven Armpaba pre-guest boot screen. F5 opens setup."""
import curses
import os
import pathlib
import subprocess
import time
from guest import get_store, new_guest_disk, discover_sources

BLUE = 1
WHITE = 2
YELLOW = 3


def safe_add(stdscr, y, x, msg, style=0):
    height, width = stdscr.getmaxyx()
    if 0 <= y < height and 0 <= x < width:
        try:
            stdscr.addnstr(y, x, msg, width - x - 1, style)
        except curses.error:
            pass


def frame(stdscr, title, footer='UP/DOWN select  |  ENTER confirm  |  ESC back'):
    stdscr.erase()
    h, w = stdscr.getmaxyx()
    if h < 18 or w < 60:
        safe_add(stdscr, 0, 0, 'Armpaba requires a terminal of at least 60x18')
        stdscr.refresh()
        return 0
    stdscr.bkgd(' ', curses.color_pair(WHITE))
    for y in range(0, 4):
        safe_add(stdscr, y, 0, ' ' * (w - 1), curses.color_pair(BLUE))
    safe_add(stdscr, 1, 3, 'ARMPABA  /  FIRMWARE INTERFACE', curses.color_pair(BLUE) | curses.A_BOLD)
    safe_add(stdscr, 2, 3, title, curses.color_pair(BLUE))
    safe_add(stdscr, 5, 3, 'Host: x86-64 UEFI     Guest: ARM64 (QEMU virt + TCG)', curses.A_BOLD)
    safe_add(stdscr, 6, 3, 'Apple M-series macOS: NOT SUPPORTED (Apple SoC not emulated)', curses.color_pair(YELLOW))
    safe_add(stdscr, h - 2, 3, footer)
    stdscr.refresh()
    return 1


def select(stdscr, title, entries, footer=None):
    index = 0
    while True:
        if not frame(stdscr, title, footer or 'UP/DOWN select  |  ENTER confirm  |  ESC back'):
            stdscr.getch()
            continue
        for i, (label, _) in enumerate(entries):
            style = curses.A_REVERSE | curses.A_BOLD if i == index else 0
            safe_add(stdscr, 9 + i, 5, (' > ' if i == index else '   ') + label, style)
        stdscr.refresh()
        key = stdscr.getch()
        if key in (curses.KEY_UP, ord('k')):
            index = (index - 1) % len(entries)
        elif key in (curses.KEY_DOWN, ord('j')):
            index = (index + 1) % len(entries)
        elif key in (10, 13, curses.KEY_ENTER):
            return entries[index][1]
        elif key in (27, ord('q')):
            return None


def message(stdscr, content):
    frame(stdscr, 'System status', 'Press any key to return')
    for i, line in enumerate(content.splitlines()):
        safe_add(stdscr, 9+i, 4, line)
    stdscr.refresh()
    stdscr.getch()


def spawn_program(stdscr, args, env=None):
    curses.def_prog_mode()
    curses.endwin()
    try:
        return subprocess.call(args, env=env)
    finally:
        try:
            subprocess.call(['chvt', '1'], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        except FileNotFoundError:
            pass
        curses.reset_prog_mode()
        stdscr.refresh()


def boot_guest(stdscr, source=None):
    try:
        store = get_store()
        if not (store / 'guest.raw').exists():
            message(stdscr, 'No guest disk exists. Create one from the setup menu first.')
            return
    except Exception as exc:
        message(stdscr, str(exc))
        return
    env = os.environ.copy()
    env.pop('ARMPABA_SOURCE_KIND', None)
    env.pop('ARMPABA_SOURCE_PATH', None)
    if source:
        env['ARMPABA_SOURCE_KIND'], env['ARMPABA_SOURCE_PATH'] = source
    # Start Xorg on a separate virtual terminal for QEMU GTK graphics.
    status = spawn_program(stdscr, ['xinit', '/usr/local/lib/armpaba/xsession.sh',
                                    '--', ':1', 'vt2', '-nolisten', 'tcp'], env=env)
    if status:
        message(stdscr, 'Guest exited with error code ' + str(status) + '. Check firmware and guest media.')


def show_sources(stdscr):
    try:
        sources = discover_sources()
        # Also support ISO image placed into the storage partition (useful for testing).
        store = get_store()
        for iso in sorted(store.glob('*.iso')):
            sources.append(('iso', str(iso), f'ISO image: {iso.name}'))
    except Exception as exc:
        message(stdscr, str(exc))
        return
    if not sources:
        message(stdscr, 'No optical drives, USB drives or ISO images found.\n'
                        'Copy a compatible ARM64 ISO into the ARMPABA_DATA partition.')
        return
    entries = [(desc[:68], (kind, path)) for kind, path, desc in sources]
    choice = select(stdscr, 'Boot device override: ARM64 guest firmware', entries)
    if choice:
        boot_guest(stdscr, source=choice)


def create_disk(stdscr):
    try:
        store = get_store()
        choice = select(stdscr, 'New ARM64 virtual disk (sparse raw file)',
                        [(f'{n} GiB logical size', n) for n in (32, 64, 128, 256)])
        if choice:
            path = new_guest_disk(store, choice)
            message(stdscr, f'Created {path}\nSparse allocation: physical space grows as guest writes.\n'
                            'Select Boot CD / USB to install a supported ARM64 OS.')
    except Exception as exc:
        message(stdscr, str(exc))


def main_screen(stdscr):
    curses.curs_set(0)
    curses.start_color()
    curses.use_default_colors()
    curses.init_pair(BLUE, curses.COLOR_WHITE, curses.COLOR_BLUE)
    curses.init_pair(WHITE, curses.COLOR_WHITE, -1)
    curses.init_pair(YELLOW, curses.COLOR_YELLOW, -1)
    stdscr.keypad(True)
    has_guest = False
    try:
        has_guest = (get_store() / 'guest.raw').is_file()
    except Exception:
        pass
    # F5 is handled by the Armpaba boot environment *after* x86 firmware runs.
    if has_guest:
        deadline = time.monotonic() + 12
        stdscr.timeout(200)
        while True:
            remaining = max(0, int(deadline - time.monotonic()))
            frame(stdscr, 'Boot splash', 'Press F5 for boot setup  |  ENTER boot now')
            safe_add(stdscr, 10, 4, f'Booting default ARM64 guest in {remaining} seconds...')
            safe_add(stdscr, 12, 4, 'F5  Boot menu / select CD or USB', curses.A_BOLD)
            stdscr.refresh()
            k = stdscr.getch()
            if k == curses.KEY_F5 or k == 265 or k == ord('5'):
                break
            if k in (10, 13, curses.KEY_ENTER) or time.monotonic() >= deadline:
                boot_guest(stdscr)
                break
        stdscr.timeout(-1)
    while True:
        choice = select(stdscr, 'Boot & setup', [
            ('Boot guest virtual disk', 'disk'),
            ('Boot from CD / USB / ARM64 ISO', 'media'),
            ('Create virtual disk file', 'create'),
            ('Install Armpaba to a physical disk (ERASES DISK)', 'install'),
            ('About compatibility', 'about'),
            ('Power off', 'poweroff'),
            ('Reboot', 'reboot'),
        ])
        if choice == 'disk':
            boot_guest(stdscr)
        elif choice == 'media':
            show_sources(stdscr)
        elif choice == 'create':
            create_disk(stdscr)
        elif choice == 'install':
            spawn_program(stdscr, ['/usr/local/lib/armpaba/install.sh'])
        elif choice == 'about':
            message(stdscr, 'CPU: QEMU AArch64 max, software translation (TCG).\n'
                            'Machine: generic ARM virt, NOT an Apple M1/M2/M3/M4 SoC.\n'
                            'Guest media MUST boot on generic ARM64 UEFI + virtio.\n'
                            'Macintosh Apple Silicon firmware and macOS are NOT supported.\n'
                            'Physical disks selected for USB boot are READ ONLY.')
        elif choice == 'poweroff':
            spawn_program(stdscr, ['systemctl', 'poweroff'])
        elif choice == 'reboot':
            spawn_program(stdscr, ['systemctl', 'reboot'])


if __name__ == '__main__':
    try:
        curses.wrapper(main_screen)
    except KeyboardInterrupt:
        pass
