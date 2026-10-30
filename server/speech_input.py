"""Bounded decoding policy for local push-to-talk; no model loading or cloud calls."""
VOCABULARY = ('Verity, Верити, Minecraft, Майнкрафт, ATM10 Aeronautics, Create, '
              'Mekanism, Allthemodium, Applied Energistics, Artifacts, '
              'зачарование, кирка, вечный стейк, регенерация, ночное зрение.')


def decode_options(config):
    language = config.get('stt_language', 'auto')
    if language not in ('auto', 'ru', 'en'):
        raise ValueError('stt_language must be auto, ru or en')
    beam = config.get('stt_beam_size', 5)
    if isinstance(beam, bool) or not isinstance(beam, int) or not 1 <= beam <= 8:
        raise ValueError('stt_beam_size must be an integer in 1..8')
    return dict(language=None if language == 'auto' else language, beam_size=beam,
                temperature=0.0, vad_filter=True,
                vad_parameters=dict(min_speech_duration_ms=100,
                                    min_silence_duration_ms=400, speech_pad_ms=300),
                condition_on_previous_text=False, initial_prompt=VOCABULARY)


def accepted_text(segments):
    # Low speech probability alone must not erase a quietly spoken real command.
    # Reject the combination used by Whisper for likely silence/hallucination.
    return ' '.join(s.text.strip() for s in segments
                    if s.text.strip() and not (s.no_speech_prob > .6
                    and getattr(s, 'avg_logprob', -2.0) < -1.0)).strip()
