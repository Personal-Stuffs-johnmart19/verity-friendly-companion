"""Same-provider free chat resilience; no model loading or routing changes.

Only an explicit server-owned free-project confirmation enables the fixed fallback.
Billing remains an external project setting, as for the existing Google speech path.
"""
import logging
import threading
import time

from gemini_speech import pacific_day
from google_retry import GoogleError

PRIMARY='gemini-3.1-flash-lite'
FALLBACK='gemini-3.5-flash-lite'


def eligible(error):
    return error.status in (408,429,500,502,503,504) or error.status is None and error.transient


def provider_body(request,model):
    contents=[];system=''
    for message in request['messages']:
        if message['role']=='system':system+=message['content']+'\n';continue
        contents.append({'role':'model' if message['role']=='assistant' else 'user','parts':[{'text':message['content']}]})
    thinking={'thinkingLevel':'minimal' if model in (PRIMARY,FALLBACK) else 'low'}
    generation={'temperature':request.get('temperature',0.2),'maxOutputTokens':request.get('max_tokens',2048),'responseMimeType':'application/json','thinkingConfig':thinking}
    fmt=request.get('response_format',{})
    if fmt.get('type')=='json_schema':generation['responseJsonSchema']=fmt['json_schema']['schema']
    return {'systemInstruction':{'parts':[{'text':system}]},'contents':contents,'generationConfig':generation}


def envelope(result,model,fallback_from=None):
    candidates=result.get('candidates') if isinstance(result,dict) else None
    candidate=candidates[0] if isinstance(candidates,list) and candidates and isinstance(candidates[0],dict) else {}
    content=candidate.get('content')
    parts=content.get('parts') if isinstance(content,dict) else None
    if not isinstance(parts,list) or any(not isinstance(part,dict) or ('text' in part and not isinstance(part['text'],str)) for part in parts):
        raise RuntimeError('Google returned no response text')
    # A token-limited, blocked or unconfirmed response can contain valid action
    # JSON. The game consumes text alone; require explicit completion first.
    if candidate.get('finishReason')!='STOP':
        raise RuntimeError('Google did not complete the response; no actions executed')
    text=''.join(part.get('text','') for part in parts if not part.get('thought'))
    if not text:raise RuntimeError('Google returned no response text')
    output={'provider':'google','model':model,'choices':[{'message':{'role':'assistant','content':text},'finish_reason':candidate.get('finishReason','unknown')}],'usage':result.get('usageMetadata',{})}
    if fallback_from is not None:output['fallback_from']=fallback_from
    return output


class FreeChatFallback:
    def __init__(self,clock=time.monotonic,wall_clock=time.time):
        self.clock,self.wall_clock=clock,wall_clock
        self.lock=threading.Lock()
        self.blocks={}

    def blocked(self,model):
        with self.lock:
            until,day=self.blocks.get(model,(0,None))
            if day is not None and day==pacific_day(self.wall_clock()):return True
            if day is None and self.clock()<until:return True
            self.blocks.pop(model,None)
            return False

    def failed(self,model,error):
        if not eligible(error):return
        delay=min(3600,max(1,error.retry_seconds or 62)) if error.status==429 else 30
        now=self.clock();today=pacific_day(self.wall_clock())
        with self.lock:
            until,day=self.blocks.get(model,(0,None))
            # Concurrent in-flight requests can fail out of order. A weaker
            # failure must not reopen a daily quota or shorten its retry delay.
            if day==today:return
            self.blocks[model]=(max(until if day is None else 0,now+delay),today if error.daily else None)

    def generate(self,request,config,call):
        primary=request.get('model',config['chat_model'])
        if primary not in (PRIMARY,'gemini-3.8-flash'):raise ValueError('Unsupported companion chat model')
        enabled=config.get('google_free_project_confirmed') is True and primary==PRIMARY
        models=(PRIMARY,FALLBACK) if enabled else (primary,)
        deadline=self.clock()+95
        failure=None
        for model in models:
            if enabled and self.blocked(model):continue
            remaining=deadline-self.clock()
            if remaining<=0:raise GoogleError('Google chat deadline exceeded',transient=True) from None
            budget=min(45 if enabled and model==PRIMARY else 85,remaining)
            try:
                result=call(model,provider_body(request,model),max_seconds=budget)
                if self.clock()>=deadline:raise GoogleError('Google chat deadline exceeded',transient=True)
            except GoogleError as error:
                if not enabled or not eligible(error):raise
                self.failed(model,error);failure=error
                logging.warning('CHAT free_model_unavailable model=%s status=%s daily=%s',model,error.status,error.daily)
                continue
            output=envelope(result,model,primary if model!=primary else None)
            logging.info('CHAT provider=google model=%s input_messages=%s',model,len(request['messages']))
            return output
        if failure is not None:raise failure
        raise GoogleError('Free Google chat models are cooling down; retry later',status=429)


_ROUTER=FreeChatFallback()


def generate(request,config,call):
    return _ROUTER.generate(request,config,call)
