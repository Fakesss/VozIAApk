# Modelos de IA: compatibilidade com Android

Todos os modelos do celular rodam **offline na CPU** depois de baixados (nenhuma chave de API, assinatura
ou servidor). O download só começa quando você toca em **Baixar** e aceita a licença do modelo.
Por padrão exige Wi-Fi (pode liberar dados móveis em Modelos).

## Modelos disponíveis no celular

| Modelo | Função | Idiomas | Download | RAM mínima | Velocidade medida | Licença (resumo) |
|---|---|---|---|---|---|---|
| **Voz do sistema Android** | Fala | Os do mecanismo instalado (Google, Samsung…) | 0 (já vem no aparelho) | — | Tempo real | Do fabricante |
| **Piper Faber pt-BR** | Fala | Português (Brasil) | 67 MB | 2 GB | RTF 0,04 (x86_64, 4 threads) | Dataset CC0; eSpeak NG GPL-3.0; derivado da voz "lessac" (termos próprios) |
| **Supertonic 3** int8 | Fala, 10 vozes | 31 idiomas, inclusive português | 129 MB | 3 GB | RTF 0,21 (x86_64, 4 threads) | Pesos OpenRAIL-M (restrições de uso); código MIT |
| **Kokoro v1.0** multilíngue int8 | Fala, 54 vozes (3 pt-BR) | pt-BR, en-US, en-GB, es, fr, it, hi, zh | 132 MB | 3 GB | RTF 0,93 (x86_64, 4 threads) | Apache-2.0; eSpeak NG GPL-3.0 |
| Kokoro v1.1 int8 (upstream) | Fala | Inglês, chinês | 177 MB | 3 GB | — | Apache-2.0; GPL-3.0 |
| Kitten mini (upstream) | Fala | Inglês | 167 MB | 2 GB | — | Apache-2.0; GPL-3.0 |
| Whisper Tiny int8 | Transcrição/ditado | ~99 idiomas | 104 MB | 2 GB | — | Sem declaração no repositório (tratar como restrita) |
| Whisper Base int8 | Transcrição/ditado (mais preciso) | ~99 idiomas | 161 MB | 3 GB | — | Idem |
| Zipformer 20M | Ditado em tempo real | Inglês | 44 MB | 2 GB | — | Apache-2.0 |
| OmniVoice Q4_K_M | Clonagem de voz | Detecção automática (600+ idiomas no desktop) | 660 MB | 6 GB | Lenta em CPU | CC-BY-NC-4.0 (uso não comercial), Higgs Audio 2, Llama 3 |
| Qwen3 1.7B Q4_K_M | Tradução (dublagem) | 26 idiomas | 1,1 GB | 6 GB | Lenta em CPU | Apache-2.0 |

RTF = tempo de geração ÷ duração do áudio (menor é mais rápido). Medições feitas neste projeto com as
bibliotecas sherpa-onnx 1.13.8 em CPU x86_64 de 4 núcleos; celulares ARM com 2–4 threads tendem a ser
2–4× mais lentos. O áudio gerado foi conferido por transcrição de volta com Whisper (ver relatório de testes).

Os modelos Piper, Supertonic e Kokoro v1.0 vêm das releases oficiais do sherpa-onnx no GitHub
(`k2-fsa/sherpa-onnx/releases/tag/tts-models`); o app confere o SHA-256 do pacote e de cada arquivo
extraído (veja `mobile/contract/models.archive.source.json` e `scripts/archive_models.py`). Os demais
vêm do Hugging Face, com espelho `hf-mirror.com`, como no upstream.

## Requisitos de hardware

| Uso | Recomendado |
|---|---|
| Instalar e usar a voz do sistema, Piper, transcrição Tiny | Android 10+, ARM64, 3 GB de RAM, 500 MB livres |
| Supertonic/Kokoro, audiolivros longos, Whisper Base | 4 GB de RAM, 1 GB livre |
| Clonagem (OmniVoice) e dublagem completa (Whisper + Qwen3 + OmniVoice) | 8 GB de RAM, 3 GB livres, celular com refrigeração razoável |

Geração longa (audiolivros) usa CPU por vários minutos: o aparelho esquenta e consome bateria.
Recomenda-se carregador conectado para livros longos.

## Como o app usa o processador

* **Duas filas de modelos.** Um modelo grande (OmniVoice ou Qwen3) e um pequeno (Piper, Supertonic,
  Kokoro, Kitten, Whisper) podem rodar ao mesmo tempo; cada fila atende uma tarefa por vez. Assim uma
  dublagem ou clonagem não segura as falas comuns, e a memória fica limitada a um modelo de cada tipo.
* **Aceleração do processador.** O OmniVoice e o llama.cpp (Qwen3) são compilados em três versões para
  ARM64: genérica (ARMv8.0), com produto escalar e FP16 (ARMv8.2, `dotprod`) e com multiplicação de
  matrizes int8 (ARMv8.6, `i8mm`). O app lê os recursos do processador em `/proc/cpuinfo` e carrega a mais
  rápida que todos os núcleos suportam; o Snapdragon 8 Elite usa `i8mm`. Em emuladores x86_64 usa AVX2.
  A versão em uso aparece em **Ajustes → Desempenho e segundo plano**.
* **Threads.** OmniVoice e Qwen3 usam os núcleos rápidos do aparelho (até 6, pela capacidade que o kernel
  informa por núcleo); os modelos pequenos usam 2–4, e 2 quando um modelo grande está rodando ao lado.
* **Voz clonada.** A referência gravada é reduzida aos primeiros ~12 s (cortando numa pausa), porque o
  OmniVoice relê a referência inteira a cada frase.

## Motores do desktop que não rodam no celular

CosyVoice 3, IndexTTS 2.5, VoxCPM2, MOSS-TTS, dots.tts, Confucius4, PocketTTS (via PyTorch), WhisperX,
Faster-Whisper, Parakeet (NeMo), Demucs e pyannote dependem de PyTorch/CUDA/MPS e extensões Python
nativas. Não existem builds Android desses runtimes nem versões ONNX/GGUF oficiais desses pesos; portá-los
exigiria conversões não testadas e mais memória que celulares têm. Eles continuam acessíveis pelo modo
opcional **Meu computador** (parear com um VoiceStudio desktop na mesma rede), herdado do upstream —
nesse modo o celular envia texto/áudio apenas ao computador pareado pelo usuário.
