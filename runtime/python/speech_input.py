"""Bounded decoding policy for local push-to-talk; no model loading or cloud calls."""
VOCABULARY = ('Verity, Верити, Minecraft, Майнкрафт, ATM10 Aeronautics, Create, '
              'Mekanism, Allthemodium, Applied Energistics, Artifacts, '
              'зачарование, кирка, вечный стейк, регенерация, ночное зрение.')
LANGUAGES=frozenset('auto en ru de fr es it pt nl pl cs sk ro hu da fi sv no el tr ar he hi id ms vi zh ja ko bg hr sr'.split())


def normalize_language(value):
    if not isinstance(value,str) or len(value)>35:raise ValueError('Invalid recognition language')
    value=value.strip().lower().replace('-','_')
    if value in ('zh_tw','zh_hant','zh_hant_tw','zh_hk'):value='zh'
    elif value in ('nb','nb_no','no_no'):value='no'
    elif '_' in value:value=value.split('_',1)[0]
    if value not in LANGUAGES:raise ValueError('Unsupported recognition language')
    return value


def decode_options(config):
    language = normalize_language(config.get('stt_language', 'auto'))
    beam = config.get('stt_beam_size', 5)
    if isinstance(beam, bool) or not isinstance(beam, int) or not 1 <= beam <= 8:
        raise ValueError('stt_beam_size must be an integer in 1..8')
    return dict(language=None if language == 'auto' else language, beam_size=beam,
                temperature=0.0, vad_filter=True,
                vad_parameters=dict(min_speech_duration_ms=100,
                                    min_silence_duration_ms=400, speech_pad_ms=300),
                condition_on_previous_text=False, initial_prompt=VOCABULARY if language=='ru' else 'Verity, Minecraft, ATM10 Aeronautics, Create, Mekanism, Allthemodium, Applied Energistics, Artifacts.')


def accepted_text(segments):
    # Low speech probability alone must not erase a quietly spoken real command.
    # Reject the combination used by Whisper for likely silence/hallucination.
    return ' '.join(s.text.strip() for s in segments
                    if s.text.strip() and not (s.no_speech_prob > .6
                    and getattr(s, 'avg_logprob', -2.0) < -1.0)).strip()


def model_directory(config):
    from pathlib import Path
    root=Path(__file__).resolve().parent
    local_path=lambda value: Path(value) if Path(value).is_absolute() else root/Path(value)
    value = config.get('whisper_model', 'small')
    candidate = local_path(value)
    if candidate.is_dir():
        return candidate
    directory = root / 'models/whisper' / value
    if not (directory / 'model.bin').is_file() and value in ('tiny','base','small','medium','large-v3'):
        snapshots = root / 'models/whisper' / ('models--Systran--faster-whisper-' + value) / 'snapshots'
        if snapshots.is_dir():
            for snapshot in sorted(snapshots.iterdir()):
                if all((snapshot / name).is_file() for name in ('model.bin','config.json','tokenizer.json')):
                    return snapshot
    return directory
