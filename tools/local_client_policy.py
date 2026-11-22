"""Workstation input policy, independent of GLFW, Win32 libraries and the current directory."""

from pathlib import Path

MARKER = Path(".tools/no-local-game-clients")
DISABLED = "Local graphical game tests are disabled on this workstation"


def blocking_marker(root):
    root = Path(root).resolve()
    for folder in (root, *root.parents):
        local = folder / MARKER
        if local.exists():
            return local
        # Git worktrees have a gitdir file pointing at the main repository's worktrees/<id>.
        link = folder / ".git"
        if link.is_file():
            prefix, separator, value = (
                link.read_text("utf-8-sig").strip().partition(":")
            )
            if separator and prefix == "gitdir":
                metadata = (folder / value.strip()).resolve()
                common = metadata / "commondir"
                if common.is_file():
                    gitdir = (
                        metadata / common.read_text("utf-8-sig").strip()
                    ).resolve()
                    shared = gitdir.parent / MARKER
                    if shared.exists():
                        return shared
    return None


def require_local_client(root):
    marker = blocking_marker(root)
    if marker is not None:
        raise RuntimeError(f"{DISABLED}: {marker}")


class PrivateDesktop:
    def __init__(self, api, handle):
        self._api = api
        self._handle = handle

    def close(self):
        if self._handle is not None:
            self._api.CloseDesktop(self._handle)
            self._handle = None


def _desktop_api():
    import ctypes
    from ctypes import wintypes as w

    api = ctypes.WinDLL("user32", use_last_error=True)
    api.CreateDesktopW.argtypes = [
        w.LPCWSTR,
        w.LPCWSTR,
        ctypes.c_void_p,
        w.DWORD,
        w.DWORD,
        ctypes.c_void_p,
    ]
    api.CreateDesktopW.restype = w.HANDLE
    api.CloseDesktop.argtypes = [w.HANDLE]
    api.CloseDesktop.restype = w.BOOL
    return api


def create_private_desktop(root, name):
    require_local_client(root)
    api = _desktop_api()
    handle = api.CreateDesktopW(name, None, None, 0, 0x01FF, None)
    if not handle:
        import ctypes

        raise ctypes.WinError(ctypes.get_last_error())
    return PrivateDesktop(api, handle)
