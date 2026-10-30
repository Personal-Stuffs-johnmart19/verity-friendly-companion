"""Bounded retry for idempotent text-generation requests, without replaying game actions."""
import json
import logging
import math
import socket
import threading
import time
import urllib.error
import urllib.request


class GoogleError(RuntimeError):
    """Safe operational metadata; provider error text is never retained or logged."""
    def __init__(self,message,status=None,daily=False,retry_seconds=None,transient=False):
        super().__init__(message)
        self.status,self.daily,self.retry_seconds,self.transient=status,daily,retry_seconds,transient


def bounded_read(response,limit,deadline,clock):
    remaining=deadline-clock()
    if remaining<=0:raise GoogleError('Google response retry deadline exceeded',transient=True)
    # urllib's timeout is per socket operation. Interrupt trickled bodies at the
    # shared deadline instead of leaving a background generation request running.
    stream=response
    sock=None
    for _ in range(2):
        stream=getattr(stream,'fp',None)
        if stream is None:break
        sock=getattr(getattr(stream,'raw',None),'_sock',None)
        if sock is not None:break
    timer=None
    if sock is not None:
        sock.settimeout(min(25,remaining))
        def expire():
            try:sock.shutdown(socket.SHUT_RDWR)
            except OSError:pass
        timer=threading.Timer(remaining,expire);timer.daemon=True;timer.start()
    try:return response.read(limit)
    finally:
        if timer is not None:timer.cancel()


def http_error(error,deadline,clock):
    status=error.code;payload={}
    try:
        raw=bounded_read(error,65537,deadline,clock)
        if len(raw)<=65536:payload=json.loads(raw)
    except (ValueError,UnicodeError,OSError,GoogleError):pass
    finally:error.close()
    if status==429:
        from gemini_speech import quota_error
        quota=quota_error(payload,error.headers.get('Retry-After') if error.headers else None)
        return GoogleError('Google API HTTP 429',status=429,daily=quota.daily,retry_seconds=quota.retry)
    return GoogleError('Google API HTTP '+str(status),status=status,transient=status in (408,500,502,503,504))


def generate(model, body, key, opener=None, clock=time.monotonic, sleep=time.sleep,max_seconds=85):
    if type(max_seconds) not in (int,float) or not math.isfinite(max_seconds) or not 0<max_seconds<=95:
        raise ValueError('Google request budget must be greater than zero and at most 95 seconds')
    opener=opener or urllib.request.build_opener(urllib.request.ProxyHandler({}))
    request=urllib.request.Request('https://generativelanguage.googleapis.com/v1beta/models/'+model+':generateContent',
        data=json.dumps(body).encode(),headers={'Content-Type':'application/json','x-goog-api-key':key},method='POST')
    deadline=clock()+max_seconds  # Below the installed Java bridge timeouts.
    failure=GoogleError('Google response retry deadline exceeded',transient=True)
    for attempt in range(3):
        remaining=deadline-clock()
        if remaining<=0:
            raise GoogleError('Google response retry deadline exceeded',status=failure.status,transient=True) from None
        try:
            with opener.open(request,timeout=min(25,remaining)) as response:
                raw=bounded_read(response,4_000_001,deadline,clock)
                if len(raw)>4_000_000:
                    raise RuntimeError('Oversize Google response')
                result=json.loads(raw)
                if not isinstance(result,dict):
                    raise RuntimeError('Invalid Google response')
                if clock()>=deadline:
                    raise GoogleError('Google response retry deadline exceeded',transient=True)
                return result
        except urllib.error.HTTPError as error:
            failure=http_error(error,deadline,clock)
            if not failure.transient:
                # Quotas/auth/validation require their own policy, not transient retries.
                raise failure from None
            status='HTTP '+str(failure.status)
        except (urllib.error.URLError,TimeoutError,OSError):
            failure=GoogleError('Google temporary network failure',transient=True)
            status='temporary network failure'
        if attempt==2:
            raise GoogleError('Google temporarily unavailable after 3 attempts ('+status+')',status=failure.status,transient=True) from None
        delay=(2,5)[attempt]
        if deadline-clock() <= delay+1:
            raise GoogleError('Google response retry deadline exceeded',status=failure.status,transient=True) from None
        logging.warning('Google transient_failure model=%s attempt=%s retry_seconds=%s',model,attempt+1,delay)
        sleep(delay)
