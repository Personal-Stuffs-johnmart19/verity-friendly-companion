"""Keep cold model loading out of the first player's request."""
from pathlib import Path
import time
import urllib.request
import drama_speech

root=Path(__file__).resolve().parent.parent/'verity-tts'
for attempt in range(30):
    try:
        with urllib.request.urlopen('http://127.0.0.1:8773/health',timeout=2):break
    except OSError:time.sleep(1)
else:raise RuntimeError('Drama 3 did not start')
start=time.monotonic()
for chunk in drama_speech.generate('Привет!',root):pass
print('Drama 3 warmed in',round(time.monotonic()-start,2),'seconds',flush=True)
