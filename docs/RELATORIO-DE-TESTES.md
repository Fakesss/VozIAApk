# Relatório de testes

Data: 19/10/2026. Os resultados abaixo vêm de execuções reais; os arquivos brutos (resumos, logs,
capturas de tela e os próprios arquivos exportados) estão anexados às releases do GitHub como
`device-results-*.zip`. A build **`build-5`** (código final, commit `eded98e`) repetiu toda a matriz:
**0 falhas** em Android 10, 14 e 15; a clonagem e a dublagem com modelos grandes foram executadas na
`build-3` (o job pesado agora roda sob demanda).

## Ambientes

| Ambiente | Detalhes |
|---|---|
| JVM (local e CI) | JDK 21, Robolectric (SDK 34) |
| Emulador Android 10 | API 29, imagem AOSP `default`, perfil Pixel 3a, 1080×2220, x86_64, 4 GB RAM |
| Emulador Android 14 | API 34, `google_apis`, Pixel 6, 1080×2400, x86_64, 4 GB RAM |
| Emulador Android 15 (tablet) | API 35, `google_apis`, Pixel Tablet, 2560×1600, x86_64, 4 GB RAM |
| Emulador Android 14 "pesado" | API 34, `google_apis`, Pixel 6, x86_64, **8 GB RAM**, disco 16 GB |
| Host (validação de modelos) | Linux x86_64, sherpa-onnx 1.13.8 JNI, 4 threads |

Emuladores rodaram com aceleração KVM em runners do GitHub Actions. **Nenhum celular físico ARM foi
testado** neste ambiente; o APK ARM64 foi compilado e assinado, e as bibliotecas ARM64 passaram na
verificação estática (exports JNI, isolamento de símbolos GGML, alinhamento de 16 KB).

## Testes automatizados (JVM)

155 testes, 0 falhas (`./gradlew testDebugUnitTest`): 137 herdados do upstream (API/pareamento,
segurança de rede, catálogo, licenças, downloads com retomada, dublagem, UI Robolectric) e 18 novos:

* download de modelo em pacote `.tar.bz2`: extração verificada, entradas maliciosas (`../`) ignoradas,
  SHA-256 errado do pacote e de um arquivo interno rejeitados, retomada com HTTP Range, falta de espaço
  antes de baixar, validação do catálogo pt-BR;
* janelas de transcrição cortadas no silêncio; divisão de texto para síntese;
* biblioteca: persistência após "reinício" (nova instância do banco), exclusão de projeto com relatório
  que não toca cópias exportadas, recuperação de `.part` e de tarefas interrompidas, proteção contra
  excluir projeto com tarefa ativa;
* tarefas: fala longa **retomada a partir dos checkpoints** após falha (só os trechos faltantes são
  sintetizados de novo), audiolivro com capítulos e livro completo com marcas de tempo, transcrição
  com TXT/SRT/VTT;
* nomes de arquivo seguros e numeração sem sobrescrita; importação TXT (UTF-8 e Windows-1252), capítulos
  por título e EPUB (ordem do spine);
* segmentação de frases do ditado com Whisper.

## Testes no dispositivo (emuladores)

| # | Verificação | Android 10 | Android 14 | Android 15 tablet |
|---|---|---|---|---|
| 1 | Instalação e inicialização (tempo de abertura) | ✅ 2,7 s | ✅ 3,3–5,5 s | ✅ 6,5 s |
| – | Bibliotecas nativas (sherpa-onnx, OmniVoice, llama.cpp) carregam no ART | ✅ | ✅ | ✅ |
| 2 | Importação de arquivos (download e extração verificada do modelo) | ✅ 11 s | ✅ 25–41 s | ✅ 57–62 s |
| 3 | Modelo compatível carregado (Piper pt-BR, 67 MB) | ✅ | ✅ | ✅ |
| 4 | Geração real de voz (6,5 s de fala em português) | ✅ 1,5 s | ✅ 4,0 s | ✅ 3,6 s |
| – | Conferência por transcrição de volta com Whisper Tiny | — | ✅ "…teste de voz em portugues, gerado no celular…" | — |
| 4 | Geração pela interface em português (digitar → Gerar) | ✅ | ✅ | ✅ |
| 5 | Reprodução (player exibido com duração 0:04) | ✅ | ✅ | ✅ |
| 6 | Exportação para `Download/VoiceStudio/<projeto>/` (WAV e M4A), SHA-256 relido | ✅ | ✅ | ✅ |
| 6 | Não sobrescreve: segunda exportação vira `Teste de voz (2).wav` | ✅ | ✅ | ✅ |
| 7 | Arquivo exportado aberto por outro processo (`adb shell`/MediaStore), WAV válido e M4A com `ftyp` | ✅ | ✅ | ✅ |
| 8 | Persistência após fechar (force-stop) e abrir de novo | ✅ | ✅ | ✅ |
| 9 | Persistência após **reiniciar o aparelho** | — | ✅ | — |
| 10 | **Falta de espaço** (2,8 MB livres): download, gravação e exportação recusados com mensagem, sem `.part` | — | ✅ | — |
| 10 | Cancelamento de tarefa longa (estado "Cancelada", nenhum resultado parcial) | ✅ | ✅ | ✅ |
| 10 | Falha clara ao usar modelo não instalado/sem licença (`licence_required`) | ✅ | ✅ | ✅ |
| 11 | **Atualização** instalada por cima (versionCode 500704 → 501704) mantém os dados | — | ✅ | — |
| 12 | Resoluções e versões (1080×2220, 1080×2400, 2560×1600; Android 10, 14, 15) | ✅ | ✅ | ✅ |
| – | Voz do sistema Android (Google TTS en-US) gera áudio pela tarefa | n/a (imagem sem TTS) | ✅ | ✅ |

### Modelos pesados (emulador Android 14 com 8 GB de RAM)

| Verificação | Resultado |
|---|---|
| Clonagem de voz: gravação de referência → transcrição Whisper → **OmniVoice Q4_K_M** gera nova fala com a voz | ✅ (910 s incluindo ~1 GB de downloads) |
| Dublagem no celular: Whisper → **tradução Qwen3 1.7B para francês** (verificada por palavras francesas) → OmniVoice → áudio final com marcador sintético | ✅ (1.362 s incluindo ~2 GB de downloads) |

Esses tempos são de CPU x86_64 emulada sem GPU; em celulares ARM topo de linha devem ser da mesma ordem
de grandeza — clonagem e dublagem são recursos lentos no celular.

## Validação dos modelos no host

Síntese real em português com as mesmas bibliotecas sherpa-onnx 1.13.8, conferida por transcrição
Whisper: Supertonic 3 (RTF 0,21) "Olá, este é um teste de cintes de voz em português, gerado
totalmente no dispositivo pelo voice studio."; Piper Faber (RTF 0,04); Kokoro v1.0 pt-br (RTF 0,93).

## Pendente ou não executável aqui

| Item | Motivo |
|---|---|
| Teste em celular físico ARM64 | Não há dispositivo físico neste ambiente. Recomenda-se o roteiro "Primeiro uso" do README. |
| Reabrir o arquivo em um app de música específico | Emuladores sem player instalado; a leitura por outro processo (MediaStore/adb) foi verificada. O botão "Abrir em outro app" usa o seletor do Android. |
| Gravação real pelo microfone, ditado ao vivo | Emulador sem microfone; a lógica de segmentação foi testada em JVM e o ditado Zipformer/Whisper usa o mesmo motor validado na transcrição. |
| Audiolivro completo e transcrição de arquivo no dispositivo | Pipelines testados em JVM com motor simulado; os motores reais (Piper, Whisper) foram validados no dispositivo isoladamente. |
| Seleção de pasta via SAF e "Salvar como" | Exigem interação com o seletor do sistema; o código de cópia/verificação é o mesmo da exportação para Downloads. |
| Consumo de bateria e aquecimento | Não mensuráveis em emulador. |
| Exibição em Android 11–13 e 16 | Não incluídos na matriz; minSdk 29 e targetSdk 36. |
