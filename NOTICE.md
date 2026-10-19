# Aviso de modificações (AGPL-3.0, seção 5)

Este repositório é um trabalho modificado do **VoiceStudio**
(<https://github.com/debpalash/VoiceStudio>), Copyright © 2024-presente Palash Debnath e colaboradores
do VoiceStudio, licenciado sob a GNU Affero General Public License v3.0 (somente esta versão).

* Origem: branch `feat/mobile-on-device`, commit `b3ce49936d0abf25287e52200522e3e40f7913bd`
  (pastas `mobile/android`, `mobile/contract`, `mobile/native`, catálogos de idioma em
  `electron/src/renderer/src/i18n/locales`, `electron/src/shared/languages.json`,
  `electron/public/signal-field.webp`, `docs/logo.svg`, `LICENSE`, `LICENSE-NOTICE.md`, `package.json`).
* Modificado a partir de 19/10/2026 pelos colaboradores do VozIAApk. Principais mudanças: identificador e
  nome do app, motores de fala em português (Piper, Supertonic 3, Kokoro v1.0, voz do sistema), biblioteca
  SQLite de projetos, exportação verificada, tarefas WorkManager, audiolivros, transcrição de arquivos,
  ditado com Whisper, novas telas, textos em português, testes e CI com emuladores.
  O histórico completo está no `git log` deste repositório.
* Bibliotecas nativas de terceiros são compiladas a partir dos commits fixados em `mobile/native/pins.json`.
  O OmniVoice.cpp (MIT) recebe dois pequenos patches, em `mobile/native/android/patches/`: um gancho por
  passo de decodificação (progresso e cancelamento) e a escolha do número de threads pelo app.
* Todo o código-fonte correspondente, inclusive scripts de compilação das bibliotecas nativas, está neste
  repositório. Quem receber o app (inclusive pela rede) tem direito a este código sob a AGPL-3.0.

Os componentes de terceiros mantêm suas licenças; os textos estão em
`mobile/android/app/src/main/assets/licenses/`. Os pesos de modelos baixados pelo app não são
relicenciados e trazem seus próprios termos.
