"""Tiny XTEST driver for the screenshot client: move, click, press keys and type on an X display.

Usage: python3 xinput.py move X Y | click X Y [button] | key NAME | type TEXT
Needs python-xlib (pip install python-xlib). DISPLAY selects the Xvfb display.
"""
import sys
import time

from Xlib import X, XK, display
from Xlib.ext import xtest

d = display.Display()


def move(x, y):
    xtest.fake_input(d, X.MotionNotify, x=int(x), y=int(y))
    d.sync()


def click(x, y, button=1):
    move(x, y)
    time.sleep(0.08)
    xtest.fake_input(d, X.ButtonPress, int(button))
    d.sync()
    time.sleep(0.06)
    xtest.fake_input(d, X.ButtonRelease, int(button))
    d.sync()


def keycode(name):
    keysym = XK.string_to_keysym(name)
    if keysym == 0 and len(name) == 1:
        keysym = ord(name)
    return d.keysym_to_keycode(keysym)


def key(name, shift=False):
    code = keycode(name)
    shift_code = d.keysym_to_keycode(XK.string_to_keysym("Shift_L"))
    if shift:
        xtest.fake_input(d, X.KeyPress, shift_code)
    xtest.fake_input(d, X.KeyPress, code)
    d.sync()
    time.sleep(0.04)
    xtest.fake_input(d, X.KeyRelease, code)
    if shift:
        xtest.fake_input(d, X.KeyRelease, shift_code)
    d.sync()
    time.sleep(0.03)


SPECIAL = {" ": "space", "/": "slash", ".": "period", "-": "minus", "_": "underscore", ":": "colon"}


def type_text(text):
    for char in text:
        if char in SPECIAL:
            name = SPECIAL[char]
            key(name, shift=char in "_:")
        elif char.isupper():
            key(char.lower(), shift=True)
        else:
            key(char)


if __name__ == "__main__":
    command = sys.argv[1]
    if command == "move":
        move(sys.argv[2], sys.argv[3])
    elif command == "click":
        click(sys.argv[2], sys.argv[3], sys.argv[4] if len(sys.argv) > 4 else 1)
    elif command == "key":
        key(sys.argv[2])
    elif command == "type":
        type_text(" ".join(sys.argv[2:]))
