"""Server-owned LM Studio routing. Only existing local models, never downloads."""
import json
import copy
import http.client
import socket
import logging
import threading
import time
import uuid
from pathlib import Path


def reasoning_effort(model,value):
    capabilities=model.get('capabilities') or {}
    reasoning=capabilities.get('reasoning') or {}
    options=reasoning.get('allowed_options') or []
    if value not in ('off','on','low','medium','high'):raise ValueError('Reasoning must be off/on/low/medium/high')
    if value not in options:raise ValueError('Reasoning '+str(value)+' is not supported by this model; allowed: '+(', '.join(options) or 'none exposed'))
    # LM Studio 0.4.26+4: chat completions maps none -> off; a level on a
    # toggle-only model maps to on. Keep message roles and JSON schema intact.
    if value=='off':return 'none'
    if value=='on':return 'low'
    return value


def choose_context(model):
    maximum=int(model.get('max_context_length') or 16384)
    if maximum<4096:
        raise ValueError('This model context is too small for companion tools (minimum 4096)')
    return min(16384,maximum)


def fit_messages(messages,context,output_tokens):
    # Conservative byte-based guard, not a tokenizer. A short follow-up can only
    # be understood with the preceding conversation, including our own question.
    budget=context-output_tokens-512
    costs=[(len(str(m.get('content','')).encode())+1)//2+16 for m in messages]
    protected={i for i,m in enumerate(messages) if m.get('role')=='system'}
    observations={i for i,m in enumerate(messages) if str(m.get('content','')).startswith(('SERVER OBSERVATIONS','НАБЛЮДЕНИЯ СЕРВЕРА ('))}
    feedback={i for i,m in enumerate(messages) if str(m.get('content','')).startswith(('TOOL RESULTS (','РЕЗУЛЬТАТЫ ИНСТРУМЕНТОВ ('))}
    users=[i for i,m in enumerate(messages) if m.get('role')=='user' and i not in observations|feedback]
    if users:protected.add(users[-1])
    pairs=[]
    for start,end in zip(users,users[1:]):
        answers=[i for i in range(start+1,end) if messages[i].get('role')=='assistant']
        if answers:pairs.append({start,answers[-1]})
    for pair in pairs[-2:]:protected.update(pair)
    # Every executed tool round in the current human turn must survive. Keeping
    # only the latest round can erase an earlier mutation after a later read.
    current_feedback=[i for i in feedback if not users or i>users[-1]]
    if current_feedback:
        for index in current_feedback:
            protected.add(index)
            if index and messages[index-1].get('role')=='assistant':protected.add(index-1)
    if messages and len(messages)-1 not in observations:protected.add(len(messages)-1)
    # Optional world snapshots are not human turns and yield before dialogue.
    candidates=[{i} for i in sorted(observations-protected)]
    current_start=users[-1] if users else len(messages)
    candidates += [{i} for i in range(current_start+1,len(messages)) if i not in protected]
    candidates += [pair for pair in pairs if not pair&protected]
    candidates += [{i} for i in range(len(messages)) if i not in protected]
    removed=set()
    estimate=sum(costs)
    for group in candidates:
        if estimate<=budget:break
        drop=group-removed
        estimate-=sum(costs[i] for i in drop);removed.update(drop)
    if estimate>budget:
        raise ValueError('Core instructions, recent conversation and latest tool results exceed local model capacity; choose a larger context/model or narrow the request')
    return [dict(m) for i,m in enumerate(messages) if i not in removed]


def response_details(result):
    choices=result.get('choices') if isinstance(result,dict) else None
    choice=choices[0] if isinstance(choices,list) and choices and isinstance(choices[0],dict) else {}
    usage=result.get('usage',{}) if isinstance(result,dict) else {}
    # Log only bounded operational fields, never model text/tool arguments.
    finish=choice.get('finish_reason')
    finish=finish if finish in ('stop','length','tool_calls','content_filter') else 'unknown'
    tokens=usage.get('completion_tokens') if isinstance(usage,dict) else None
    return choice,finish,tokens if type(tokens) is int and tokens>=0 else 'unknown'


def normalize_legacy(parsed):
    # Compatibility with the shipped Verity prompt, not a second tool executor.
    # Only explicit aliases with clear argument semantics cross this boundary.
    if 'reply' in parsed or not isinstance(parsed.get('message'),str):raise ValueError('Invalid legacy reply')
    aliases={'get_coords':'inspect_world','get_dimension':'inspect_world',
             'get_biome':'inspect_world','get_inventory':'inspect_world',
             'get_own_coords':'companion_status','get_nearby_entities':'nearby_mobs',
             'get_block_player_is_looking_at':'player_location'}
    normalized=[]
    for action in parsed['actions']:
        if not isinstance(action,dict) or set(action)!={'action','args'}:raise ValueError('Invalid legacy action')
        name=action['action'];args=action['args']
        if not isinstance(name,str) or not isinstance(args,dict):raise ValueError('Invalid legacy arguments')
        if name in aliases:
            if args:raise ValueError('Unexpected legacy arguments')
            tool=aliases[name];converted={}
        elif name=='get_nearest_village':
            if args:raise ValueError('Unexpected legacy arguments')
            tool='locate_structure';converted={'query':'village'}
        elif name=='return_to_player':
            if args:raise ValueError('Unexpected legacy arguments')
            tool='companion';converted={'action':'come'}
        elif name in ('give_gift','drop_item'):
            if set(args)-{'item_id','count'} or not isinstance(args.get('item_id'),str) or not args['item_id'].strip() or len(args['item_id'])>256:raise ValueError('Invalid legacy item')
            if 'count' in args and (type(args['count']) is not int or args['count']<1):raise ValueError('Invalid legacy count')
            tool='give_item';converted={'id':args['item_id'],'delivery':'inventory' if name=='give_gift' else 'ground'}
            if 'count' in args:converted['count']=args['count']
        elif name=='answer':
            if args:raise ValueError('Unexpected legacy answer arguments')
            continue
        else:raise ValueError('Unsupported legacy action')
        normalized.append({'tool':tool,'args':converted})
    # Legacy variant/karma fields must not drive mood, horror or permissions.
    return {'reply':parsed['message'],'emotion':parsed.get('emotion') if isinstance(parsed.get('emotion'),str) else 'neutral','actions':normalized}


def parse_actions(result):
    choice,finish,_=response_details(result)
    if finish=='length':raise ValueError('truncated')
    # The game consumes text alone. Require completion confirmation before
    # publishing actions, even when incomplete content happens to be valid JSON.
    if choice.get('finish_reason')!='stop':raise ValueError('incomplete')
    content=choice['message']['content']
    if isinstance(content,str) and content.strip().startswith('```'):
        lines=content.strip().splitlines()
        if lines[-1].strip()=='```':content='\n'.join(lines[1:-1])
    parsed=json.loads(content)
    if not isinstance(parsed,dict) or not isinstance(parsed.get('actions'),list) or len(parsed['actions'])>16:raise ValueError()
    response_format='current'
    if 'reply' not in parsed:
        parsed=normalize_legacy(parsed);response_format='legacy'
    if not isinstance(parsed.get('reply'),str):raise ValueError()
    for action in parsed['actions']:
        if not isinstance(action,dict) or not isinstance(action.get('tool'),str):raise ValueError()
        args=action.get('args')
        if isinstance(args,dict):action['args']=json.dumps(args,ensure_ascii=False)
        elif not isinstance(args,str) or not isinstance(json.loads(args),dict):raise ValueError()
    choice['message']['content']=json.dumps(parsed,ensure_ascii=False)
    return response_format


def append_system_instruction(messages,addition):
    # Some local chat templates accept exactly one system message, first. Keep
    # core text verbatim and earlier than clearly labelled additions; copy inputs.
    systems=[m['content'] for m in messages if m.get('role')=='system']
    content='\n\n'.join(systems+[addition])
    return [{'role':'system','content':content}]+[dict(m) for m in messages if m.get('role')!='system']


def instruction_language(request):
    language=request.get('language','en')
    if not isinstance(language,str) or language not in ('en','ru'):
        raise ValueError('Language must be en or ru')
    return language


def local_body(request,state,repair=False):
    language=instruction_language(request)
    body={k:request[k] for k in ('messages','temperature','max_tokens','response_format') if k in request}
    body['model']=state['instance'];body['stream']=False
    base=max(1,min(4096,int(body.get('max_tokens',2048))))
    messages=request['messages']
    if repair:
        # Nothing from the rejected response is published or fed back as actions.
        # Use the same conversation-preserving budget rules as the first attempt.
        instruction=('Исправление формата JSON. Основные инструкции Верити и правила инструментов выше остаются в силе. Верни один краткий объект JSON по заданной схеме. Без рассуждений и Markdown. Короткий ответ и только необходимые действия (либо пустой массив actions). Имена инструментов и правила аргументов не меняются.' if language=='ru' else 'JSON repair formatting instruction. Core Verity instructions and tool rules above remain unchanged. Return one concise JSON object matching the required schema. No reasoning or Markdown. Use a short reply and only necessary actions (or an empty actions array). Tool names and argument rules remain unchanged.')
        messages=append_system_instruction(messages,instruction)
    # Reasoning tokens share the output budget. Reserve 4096 only if the actual
    # essential context fits; do not increase the loaded context or ordinary models.
    reasoning=any(name in state['model'].lower() for name in ('gpt-oss','qwen'))
    target=4096 if reasoning and state.get('reasoning')!='off' and state['context']>=8192 and 'max_tokens' not in state else base
    try:body['messages']=fit_messages(messages,state['context'],target)
    except ValueError:
        if target==base:raise
        target=base;body['messages']=fit_messages(messages,state['context'],target)
    if state.get('reasoning') is not None:body['reasoning_effort']=state['reasoning_effort']
    body['max_tokens']=target
    return body


class LocalModels:
    def __init__(self,root,transport=None):
        self.root=Path(root)
        self.path=self.root/'chat-routing.json'
        try:
            self.state=json.loads(self.path.read_text()) if self.path.exists() else {'provider':'google'}
            if not isinstance(self.state,dict) or self.state.get('provider') not in ('google','local'):
                raise ValueError('Invalid routing state')
        except (ValueError,OSError):
            # A broken routing file must never silently send previously local chat to cloud.
            self.state={'provider':'local','routing_error':True}
        self.settings={}  # Operator overrides are memory-only, including prompt text.
        self.revision=0  # Invalidates snapshots even when a model is reselected by the same ID.
        self.lock=threading.RLock()
        self.inference=threading.Lock()
        self.active=False
        self.jobs={}
        self.loading=False
        self.transport=transport or self._http

    def _http(self,path,body=None,timeout=90):
        if path not in ('/api/v1/models','/api/v1/models/load','/api/v1/models/unload','/v1/chat/completions'):
            raise ValueError('Unsupported LM Studio endpoint')
        headers={'Content-Type':'application/json'}
        token=self.root/'.secrets/lmstudio-api-token'
        if token.exists():headers['Authorization']='Bearer '+token.read_text().strip()
        deadline=time.monotonic()+timeout
        connection=http.client.HTTPConnection('127.0.0.1',1234,timeout=timeout)
        timer=None
        try:
            connection.connect()
            sock=connection.sock
            def expire():
                # shutdown also interrupts response headers/trickled body reads;
                # a socket inactivity timeout alone cannot enforce a total budget.
                try:sock.shutdown(socket.SHUT_RDWR)
                except OSError:pass
            remaining=deadline-time.monotonic()
            if remaining<=0:raise TimeoutError()
            timer=threading.Timer(remaining,expire);timer.daemon=True;timer.start()
            connection.request('GET' if body is None else 'POST',path,
                               body=None if body is None else json.dumps(body).encode(),headers=headers)
            with connection.getresponse() as response:
                # No redirects/proxies: prompts and credentials stay on loopback.
                if not 200<=response.status<300:
                    logging.warning('LM Studio request failed endpoint=%s http_status=%s',path,response.status)
                    raise RuntimeError('LM Studio HTTP '+str(response.status)+'; check native v1 API and server token')
                chunks=[];size=0
                while not response.isclosed():
                    remaining=deadline-time.monotonic()
                    if remaining<=0:raise TimeoutError()
                    sock.settimeout(remaining)
                    chunk=response.read1(min(65536,4_000_001-size))
                    if not chunk:break
                    chunks.append(chunk);size+=len(chunk)
                    if size>4_000_000:raise ValueError('LM Studio response too large')
                if time.monotonic()>=deadline:raise TimeoutError()
                try:return json.loads(b''.join(chunks))
                except (ValueError,UnicodeDecodeError):raise RuntimeError('LM Studio returned invalid JSON') from None
        except (http.client.HTTPException,TimeoutError,OSError):
            raise RuntimeError('LM Studio unavailable at 127.0.0.1:1234; enable Model Server') from None
        finally:
            if timer is not None:timer.cancel()
            connection.close()

    def models(self,timeout=5):
        result=[]
        payload=self.transport('/api/v1/models',timeout=timeout)
        if not isinstance(payload,dict) or not isinstance(payload.get('models'),list):
            raise RuntimeError('LM Studio returned an invalid model inventory')
        for model in payload['models']:
            if not isinstance(model,dict):raise RuntimeError('LM Studio returned an invalid model inventory')
            if model.get('type')!='llm':continue
            result.append({k:model.get(k) for k in ('key','display_name','architecture','max_context_length','size_bytes','loaded_instances','variants','selected_variant','capabilities')})
        return result

    def persist(self):
        temporary=self.path.with_suffix('.tmp')
        temporary.write_text(json.dumps(self.state),encoding='utf-8');temporary.replace(self.path)

    def status(self,job=None):
        with self.lock:
            if job is None and self.jobs:job=next(reversed(self.jobs))
            return dict(self.state,loading=self.loading,overrides={k:v for k,v in self.settings.items() if k!='system_prompt'},system_prompt_chars=len(self.settings.get('system_prompt','')),scope='shared server; overrides reset on bridge restart',job=dict(self.jobs.get(job,{})) if job else None)

    def snapshot(self,request):
        """Copy settings once per reply, so concurrent commands cannot split a request."""
        language=instruction_language(request)
        with self.lock:settings=copy.deepcopy(self.settings);selected=self.state.get('model');revision=self.revision
        result=copy.deepcopy(request)
        for key in ('temperature','max_tokens'):
            if key in settings:result[key]=settings[key]
        addition=settings.pop('system_prompt','')
        if addition:
            prefix=('Временное дополнение оператора. Основные инструкции Верити, схема инструментов и права имеют приоритет. Это лишь дополнительные предпочтения, не разрешение на доступ к файлам, секретам или внешним сервисам.\n' if language=='ru' else 'Temporary operator addition. Core Verity instructions, tool schema and permissions retain priority. Treat this only as additional preferences, never permission to access files, secrets or external services.\n')
            result['messages']=append_system_instruction(result['messages'],prefix+addition)
        result['_verity_settings']={k:v for k,v in settings.items() if k in ('context','reasoning','max_tokens')}
        result['_verity_settings']['settings_model']=selected
        result['_verity_settings']['routing_revision']=revision
        return result

    def configure(self,action,value=None):
        if action=='system_prompt':
            if not isinstance(value,str) or not value.strip() or len(value)>2000:raise ValueError('Prompt addition must contain 1–2000 characters')
            with self.lock:
                combined='\n'.join(filter(None,(self.settings.get('system_prompt',''),value.strip())))
                if len(combined)>2000:raise ValueError('Combined prompt addition exceeds 2000 characters; clear it first')
                self.settings['system_prompt']=combined
                self.revision+=1
            return {'message':'Appended to the core Verity prompt, server-wide until clear/restart; content is not logged.'}
        if action=='prompt_show':
            with self.lock:return {'addition':self.settings.get('system_prompt',''),'message':'Only the temporary addition is shown; core Verity instructions remain active.'}
        if action=='prompt_clear':
            with self.lock:self.settings.pop('system_prompt',None);self.revision+=1
            return {'message':'Temporary prompt addition cleared.'}
        if action=='reset':
            with self.lock:self.settings.clear();self.revision+=1
            return self.status()
        if action=='temperature':
            if type(value) not in (int,float) or not 0<=value<=2:raise ValueError('Temperature must be 0–2')
        elif action=='max_tokens':
            if type(value) is not int or not 128<=value<=4096:raise ValueError('Output tokens must be 128–4096')
        elif action in ('context','reasoning','caps'):
            with self.lock:state=dict(self.state);revision=self.revision
            if state.get('provider')!='local':raise ValueError('This control requires a selected local model; Google reasoning/context are provider-managed')
            models=[m for m in self.models() if (m.get('key')==state.get('model') or state.get('model') in (m.get('variants') or []))]
            if len(models)!=1:raise ValueError('Selected model is absent from the current LM Studio inventory')
            model=models[0]
            if action=='caps':return {'model':model,'message':'Capabilities come from the live native LM Studio inventory.'}
            if action=='context':
                maximum=min(1048576,int(model.get('max_context_length') or 16384))
                if type(value) is not int or not 4096<=value<=maximum:raise ValueError('Context must be 4096..'+str(maximum)+' for this model')
                if value>state.get('context',4096):
                    return dict(self.select(state['model'],context=value,preserve_settings=True,expected_revision=revision),message='Loading the selected model with the requested context may use more RAM/VRAM. Previous settings remain active until success. Use /llm status to check the job.')
            else:reasoning_effort(model,value)
            with self.lock:
                if self.revision!=revision or self.state.get('model')!=state.get('model') or self.loading:raise RuntimeError('Model/settings changed during settings request; retry')
                self.settings[action]=value
                self.revision+=1
            return self.status()
        else:raise ValueError('Unsupported LLM setting')
        with self.lock:self.settings[action]=value;self.revision+=1
        return self.status()

    def cloud(self):
        with self.lock:
            if self.loading:raise RuntimeError('Wait for model loading to finish before switching')
            previous=self.state
            self.state={'provider':'google'}
            try:self.persist()
            except Exception:self.state=previous;raise
            self.settings.pop('reasoning',None);self.settings.pop('context',None)
            self.revision+=1
            return self.status()

    def select(self,key,context=None,preserve_settings=False,expected_revision=None):
        if not isinstance(key,str) or not key.strip() or len(key)>256:raise ValueError('Invalid model key')
        inventory=self.models()
        matches=[m for m in inventory if m.get('key')==key or key in (m.get('variants') or [])]
        if len(matches)!=1:raise ValueError('Choose an existing downloaded LLM from /llm local')
        model=matches[0]
        if context is None:context=choose_context(model)
        if type(context) is not int or not 4096<=context<=min(1048576,int(model.get('max_context_length') or 16384)):raise ValueError('Context exceeds model capacity')
        with self.lock:
            if expected_revision is not None and self.revision!=expected_revision:raise RuntimeError('Model/settings changed during context request; retry')
            if self.loading:raise RuntimeError('Another model is already loading')
            if self.active:raise RuntimeError('Wait for the current local reply before loading another model')
            previous_selection=dict(self.state)
            self.loading=True
            self.revision+=1
            job=uuid.uuid4().hex;self.jobs[job]={'state':'loading','model':key,'context':context}
            # Keep bounded operational status only; no chat or credential data here.
            while len(self.jobs)>8:self.jobs.pop(next(iter(self.jobs)))
        def load():
            committed=False
            try:
                suitable=[i for i in (model.get('loaded_instances') or []) if key==model.get('key') or key==model.get('selected_variant')]
                suitable=[i for i in suitable
                          if int(i.get('config',{}).get('context_length',0))>=context]
                response={'instance_id':suitable[0]['id'],'load_config':suitable[0]['config']} if suitable else self.transport(
                    '/api/v1/models/load',{'model':key,'context_length':context,'echo_load_config':True},timeout=180)
                instance=response.get('instance_id')
                actual=response.get('load_config',{}).get('context_length')
                if not isinstance(instance,str) or not instance or type(actual) is not int or actual<context:
                    raise RuntimeError('LM Studio did not confirm a loaded model/context')
                with self.lock:
                    previous=self.state
                    self.state={'provider':'local','model':key,'instance':instance,'context':actual}
                    try:self.persist()
                    except Exception:self.state=previous;raise
                    if not preserve_settings:self.settings.pop('reasoning',None)
                    self.settings.pop('context',None)
                    self.revision+=1
                    committed=True
                    self.jobs[job]={'state':'loading','phase':'cleanup','model':key,'context':actual}
                old=previous_selection.get('instance')
                # The selection authorizes cleanup of that previous companion
                # instance only. Inventory evidence excludes unrelated model IDs.
                previous_models=[m for m in inventory if m.get('key')==previous_selection.get('model')
                                 or previous_selection.get('model') in (m.get('variants') or [])]
                owned=any(isinstance(i,dict) and i.get('id')==old
                          for m in previous_models for i in (m.get('loaded_instances') or []))
                cleanup='not_needed'
                if previous_selection.get('provider')=='local' and isinstance(old,str) and old and old!=instance and owned:
                    response=self.transport('/api/v1/models/unload',{'instance_id':old},timeout=15)
                    if not isinstance(response,dict) or response.get('instance_id')!=old:
                        raise RuntimeError('LM Studio did not confirm unloading the previous instance')
                    cleanup='unloaded'
                with self.lock:self.jobs[job]={'state':'ready','model':key,'context':actual,'cleanup':cleanup}
            except Exception as error:
                with self.lock:
                    if committed:
                        self.jobs[job]={'state':'ready','model':key,'context':actual,'cleanup':'failed',
                                        'warning':'New model is selected, but the previous companion instance could not be unloaded. Check loaded models in LM Studio.'}
                        logging.warning('LM Studio previous-instance cleanup failed: %s',type(error).__name__)
                    else:self.jobs[job]={'state':'failed','message':str(error)[:200]}
            finally:
                with self.lock:self.loading=False
        threading.Thread(target=load,name='Verity LM Studio load',daemon=True).start()
        return {'job_id':job,'model':key,'context':context}

    def chat(self,request):
        deadline=time.monotonic()+115
        with self.lock:
            if request.get('_verity_settings',{}).get('routing_revision',self.revision)!=self.revision:
                raise RuntimeError('Chat routing/settings changed after snapshot; submit a new request')
            if self.state.get('provider')!='local':return None
        if not self.inference.acquire(timeout=15):
            raise RuntimeError('Local model is busy; retry after the current reply')
        try:
            with self.lock:
                if request.get('_verity_settings',{}).get('routing_revision',self.revision)!=self.revision:
                    raise RuntimeError('Chat routing/settings changed after snapshot; submit a new request')
                if self.state.get('provider')!='local':
                    raise RuntimeError('Chat routing changed while waiting; submit a new request')
                if self.loading:raise RuntimeError('Local model is loading; please wait')
                state=dict(self.state);overrides=request.pop('_verity_settings',{})
                overrides.pop('routing_revision',None)
                if overrides.get('settings_model',state.get('model'))!=state.get('model'):raise RuntimeError('Model changed after request settings snapshot; retry')
                state.update(overrides);self.active=True
            if state.get('routing_error'):
                raise RuntimeError('Local routing state is invalid; select a local model or explicitly switch to cloud')
            # LM Studio instance IDs change after restart. Refresh from inventory only;
            # never reload a model here. Only unpublished invalid JSON can be repaired.
            models=self.models(timeout=min(5,max(0.001,deadline-time.monotonic())))
            matching=[m for m in models if (m.get('key')==state.get('model') or state.get('model') in (m.get('variants') or []))]
            instances=matching[0].get('loaded_instances') if len(matching)==1 else []
            loaded=[i for i in (instances or []) if isinstance(i,dict)
                      and isinstance(i.get('id'),str) and i['id']
                      and isinstance(i.get('config'),dict)
                      and type(i['config'].get('context_length')) is int
                      and i['config']['context_length']>0]
            if not loaded:
                raise RuntimeError('Selected local model is unloaded; select it again with /llm local')
            selected=next((i for i in loaded if i['id']==state.get('instance')),None)
            if selected is None:
                if state['model']!=matching[0].get('key') and state['model']!=matching[0].get('selected_variant'):
                    raise RuntimeError('Selected local variant is no longer confirmed loaded; select it again with /llm local')
                selected=max(loaded,key=lambda i:i['config']['context_length'])
            state['instance']=selected['id']
            requested_context=state.get('context',4096)
            loaded_context=selected['config']['context_length']
            state['context']=min(requested_context,loaded_context)
            if state['context']<4096:
                raise RuntimeError('Selected local model is loaded with actual context '+str(loaded_context)+'; requested context '+str(requested_context)+' cannot provide the required minimum 4096. Select/reload the model explicitly')
            if state.get('reasoning') is not None:
                state['reasoning_effort']=reasoning_effort(matching[0],state['reasoning'])
            logging.info('CHAT route provider=local model=%s instance=%s',
                         json.dumps(state['model'],ensure_ascii=True),json.dumps(state['instance'],ensure_ascii=True))
            for attempt in range(2):
                try:body=local_body(request,state,repair=attempt==1)
                except ValueError as error:
                    logging.warning('CHAT preparation_failed provider=local phase=context_budget attempt=%s context=%s loaded_context=%s requested_context=%s input_messages=%s',attempt+1,state['context'],loaded_context,requested_context,len(request['messages']))
                    if loaded_context<requested_context and str(error).startswith('Core instructions, recent conversation'):
                        raise RuntimeError('Selected local model is loaded with actual context '+str(loaded_context)+' but requested context is '+str(requested_context)+'; essential instructions, recent conversation and latest tool results do not fit. Select/reload the model explicitly or narrow the request') from None
                    raise
                remaining=deadline-time.monotonic()
                if remaining<=0:raise RuntimeError('Local reply deadline exceeded; no actions executed')
                # Leave a bounded repair opportunity within the Java 120s request.
                try:result=self.transport('/v1/chat/completions',body,timeout=min(60,remaining))
                except RuntimeError as error:
                    logging.warning('CHAT request_failed provider=local phase=inference attempt=%s error_type=%s',attempt+1,type(error).__name__)
                    raise
                choice,finish,tokens=response_details(result)
                try:response_format=parse_actions(result)
                except (KeyError,IndexError,TypeError,ValueError,AttributeError):
                    logging.warning('CHAT provider=local model=%s attempt=%s rejected finish=%s completion_tokens=%s',
                                    json.dumps(state['model'],ensure_ascii=True),attempt+1,finish,tokens)
                    # Retry only a returned content/length failure. A malformed API
                    # envelope or transport failure is not a generation repair case.
                    message=choice.get('message')
                    repairable=finish=='length' or choice.get('finish_reason')=='stop' and isinstance(message,dict) and isinstance(message.get('content'),str)
                    if attempt==0 and repairable and deadline-time.monotonic()>=1:continue
                    if finish=='length':raise RuntimeError('Local model exhausted output tokens before valid action JSON; no actions executed') from None
                    raise RuntimeError('Local model returned invalid action JSON; no actions executed') from None
                if time.monotonic()>=deadline:raise RuntimeError('Local reply deadline exceeded; no actions executed')
                result['provider']='local';result['model']=state['model'];result['instance']=state['instance']
                result['context']=state['context'];result['loaded_context']=loaded_context;result['requested_context']=requested_context
                logging.info('CHAT provider=local model=%s attempt=%s finish=%s completion_tokens=%s max_tokens=%s format=%s',
                             json.dumps(state['model'],ensure_ascii=True),attempt+1,finish,tokens,body['max_tokens'],response_format)
                break
            return result
        finally:
            with self.lock:self.active=False
            self.inference.release()
