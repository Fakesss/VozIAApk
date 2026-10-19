# Análise do VoiceStudio original e decisões de arquitetura

Projeto analisado: <https://github.com/debpalash/VoiceStudio> (licença AGPL-3.0), tag de referência
`v0.5.6` e o branch `feat/mobile-on-device` (commit `b3ce4993`, 18/10/2026).

## O que é o VoiceStudio desktop

| Camada | Tecnologia | Roda no Android? |
|---|---|---|
| Interface | Electron + React (`electron/`) | Não (Electron não existe no Android). |
| Backend | FastAPI em Python 3 (`backend/`), PyTorch, torchaudio, WhisperX, faster-whisper, pyannote, Demucs, PyAV/FFmpeg | Não diretamente: PyTorch CUDA/MPS, extensões nativas Python e subprocessos de engine são específicos de desktop. |
| Motores de voz | OmniVoice (padrão, k2-fsa), CosyVoice 3, IndexTTS 2.5, VoxCPM2, MOSS-TTS, dots.tts, Confucius4, Kitten, Supertonic 3, PocketTTS, sherpa-onnx, omnivoice-gguf, audio.cpp | Somente os que têm runtime nativo leve: **sherpa-onnx** (ONNX Runtime), **OmniVoice GGUF** (omnivoice.cpp/ggml) e **llama.cpp**. Os demais dependem de PyTorch/GPU de desktop. |
| Transcrição | WhisperX (padrão), Faster-Whisper, Parakeet, Moonshine, FunASR, sherpa-onnx | Whisper e Zipformer via **sherpa-onnx**. |
| Projetos/configurações | SQLite + Alembic no backend | Reimplementado com SQLite do Android. |

## A pasta `mobile`

* Na tag **v0.5.6 não existe pasta `mobile`**. Ela surgiu depois, em `main`.
* Em `main`, `mobile/android` é um **aplicativo companheiro** (Kotlin/Jetpack Compose) que só funciona
  pareado a um computador com o VoiceStudio: nenhuma inferência no celular (o README diz "No inference").
* No branch **`feat/mobile-on-device`** (ainda não mesclado no upstream) o mesmo app ganhou inferência
  local: catálogo de modelos com SHA-256, downloads retomáveis, aceite de licenças, sherpa-onnx (Kitten,
  Kokoro, Whisper, Zipformer), OmniVoice (clonagem) e llama.cpp/Qwen3 (tradução para dublagem), com
  bibliotecas JNI compiladas pelo NDK. Esse código compila e seus 137 testes JVM passam, mas o próprio
  README do upstream registra que **não havia sido executado em dispositivo**.

## Decisão: reutilizar e completar, não reescrever

Este repositório parte do código `feat/mobile-on-device` (mesmos caminhos de pastas, para facilitar
futuras sincronizações) e acrescenta o que faltava para um app Android completo:

| Lacuna no upstream | O que foi feito aqui |
|---|---|
| Nenhum motor funciona antes de baixar modelos | Voz do sistema Android (TextToSpeech offline) como motor imediato. |
| TTS do celular só em inglês/chinês (Kitten, Kokoro v1.1) | **Piper pt-BR**, **Supertonic 3** (31 idiomas, incluindo português) e **Kokoro v1.0** (pt-BR, es, fr, it, hi...), via arquivos verificados das releases do sherpa-onnx. |
| Ditado do celular só em inglês | Ditado com Whisper por frases (português e outros idiomas). |
| Áudios salvos só em pasta interna sem projetos | Biblioteca SQLite com projetos, itens, exportações, tarefas e capítulos; exportação para Downloads, pasta escolhida (SAF) ou "Salvar como". |
| Geração presa à tela | Tarefas persistentes no WorkManager com notificação, cancelamento e retomada por checkpoints. |
| Sem audiolivros | Importação TXT/MD/EPUB, capítulos, geração em segundo plano, livro completo com marcas de capítulo. |
| Transcrição de arquivo só dentro da dublagem | Tela própria com TXT, SRT e VTT. |
| Nunca testado em dispositivo | Testes instrumentados em emuladores Android 10, 14 e 15 no CI (detalhes no relatório de testes). |

## Limites técnicos que continuam valendo

* Modelos desktop pesados (CosyVoice, IndexTTS, VoxCPM2, MOSS, dots.tts, Confucius4) exigem PyTorch e
  GPUs de desktop; não há conversão ONNX/GGUF oficial para celular. Ficam disponíveis apenas pelo modo
  opcional "Meu computador" (pareamento herdado do upstream).
* Separação de vocais (Demucs) e diarização de locutores (pyannote) não foram portadas.
* Clonagem de voz com OmniVoice exige ~6 GB de RAM e é lenta na CPU do celular.
* Os pesos de cada modelo têm licença própria; o app pede aceite explícito antes do download.
