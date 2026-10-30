"""Optional NVIDIA libraries in the service venv; no toolkit or driver replacement."""
import ctypes
import os
from pathlib import Path
import sys

_DLL_HANDLES = {}


def configure_libraries():
    """Expose pip's NVIDIA runtime DLLs to this process only."""
    directories = []
    for base in sys.path:
        for package in ('cublas', 'cudnn', 'cuda_runtime'):
            for leaf in ('bin', 'lib'):
                directory = Path(base) / 'nvidia' / package / leaf
                if directory.is_dir() and str(directory) not in directories:
                    directories.append(str(directory))
    if directories:
        previous = os.environ.get('PATH', '').split(os.pathsep)
        os.environ['PATH'] = os.pathsep.join(directories + [p for p in previous if p not in directories])
        if os.name == 'nt' and hasattr(os, 'add_dll_directory'):
            for path in directories:
                if path not in _DLL_HANDLES:
                    _DLL_HANDLES[path] = os.add_dll_directory(path)


def device_available():
    """Driver/device check without downloading CUDA libraries or loading a model."""
    configure_libraries()
    try:
        import ctranslate2
        return ctranslate2.get_cuda_device_count() > 0
    except (ImportError, OSError, RuntimeError, ValueError):
        return False


def probe():
    if not device_available():
        raise RuntimeError('No usable NVIDIA CUDA device/driver detected')
    import ctranslate2
    if os.name == 'nt':
        # Device enumeration alone does not establish cuBLAS/cuDNN availability.
        for library in ('cublas64_12.dll', 'cudnn64_9.dll'):
            ctypes.WinDLL(library)
    elif sys.platform.startswith('linux'):
        for library in ('libcublas.so.12', 'libcudnn.so.9'):
            ctypes.CDLL(library)
    return sorted(ctranslate2.get_supported_compute_types('cuda'))
