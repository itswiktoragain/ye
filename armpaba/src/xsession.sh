#!/bin/sh
set -eu
openbox >/tmp/armpaba-openbox.log 2>&1 &
exec /usr/bin/python3 /usr/local/lib/armpaba/launch.py
