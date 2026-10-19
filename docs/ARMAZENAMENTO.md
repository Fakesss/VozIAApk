# Armazenamento, exportação e localização dos arquivos

## Onde cada coisa fica

| Conteúdo | Local | Acessível por outros apps? | Sobrevive a fechar/reiniciar/atualizar? |
|---|---|---|---|
| Projetos, histórico, tarefas, capítulos, registro de exportações | Banco SQLite `databases/library.db` (privado do app) | Não | Sim |
| Áudios gerados, gravações, importações, transcrições e legendas | `files/library/<projeto>/<item>.<ext>` (privado) | Não (use Exportar) | Sim |
| Modelos de IA baixados e aceites de licença | `no_backup/models/`, `no_backup/model-licences.json` | Não | Sim |
| Vozes clonadas (gravação de referência + consentimento) | `no_backup/voices/` | Não | Sim |
| Checkpoints de tarefas longas | `files/library/.tasks/<tarefa>/` | Não | Sim, até a tarefa terminar |
| Temporários (conversão M4A, compartilhamento) | `cache/` | Não | Podem ser limpos pelo sistema |
| **Exportações em Downloads** | `Download/VoiceStudio/<projeto>/<título>.wav` ou `.m4a` | **Sim** (gerenciador de arquivos, players, outros apps) | Sim, inclusive após desinstalar o app |
| **Exportações em pasta escolhida** | Pasta escolhida em Configurações → Armazenamento → Pasta de exportação (SAF) | **Sim** | Sim |
| **Salvar como…** | Local e nome escolhidos no seletor do Android | **Sim** | Sim |

O backup em nuvem do app é desativado (`allowBackup=false`): gravações de voz não saem do aparelho.
Desinstalar o app apaga os arquivos privados; as cópias exportadas permanecem.

## Garantias de gravação

* **Escrita atômica**: cada arquivo é escrito em `.part`, sincronizado (`fsync`) e renomeado; só então a
  linha do banco é gravada. Uma interrupção nunca deixa um item sem arquivo.
* **Verificação**: o SHA-256 de cada arquivo é registrado; antes de exportar, o app confere o original e,
  depois de copiar, relê a cópia exportada e compara o SHA-256. Só então informa "Salvo em: …".
* **Sem sobrescrita**: nomes seguros (sem `\ / : * ? " < > |`, sem nomes reservados) e numeração
  automática: `Livro.wav`, `Livro (2).wav`, `Livro (3).wav`…
* **Falta de espaço**: o espaço é verificado antes de baixar modelos, gravar na biblioteca e exportar;
  erros `ENOSPC` viram a mensagem "Espaço livre insuficiente", e arquivos parciais são removidos.
* **Downloads de modelos**: retomada com HTTP Range, SHA-256 de cada arquivo (e do pacote `.tar.bz2`),
  extração protegida contra caminhos maliciosos; downloads incompletos ficam retomáveis.
* **Recuperação**: ao abrir o app, arquivos `.part` interrompidos são apagados, temporários antigos
  são limpos e tarefas que estavam "processando" voltam para "aguardando" e são retomadas.
* **Exclusão**: excluir um projeto mostra quantos arquivos e quanto espaço serão apagados e avisa que as
  cópias exportadas **não** serão apagadas. Projetos com tarefas em andamento não podem ser excluídos.

## Formatos

* Áudio: WAV PCM 16 bits mono (sem perdas, com marcador de proveniência "VoiceStudio synthetic audio"
  no bloco RIFF INFO) ou M4A AAC-LC 96 kbps na exportação.
* Transcrições: `.txt`; legendas `.srt` (SubRip) e `.vtt` (WebVTT).
* Audiolivros: um WAV por capítulo e, opcionalmente, um WAV com o livro inteiro (as marcas de início de
  cada capítulo ficam nos metadados do projeto).

## Permissões

Nenhuma permissão de armazenamento é necessária no Android 10+: Downloads usa o MediaStore e as pastas
escolhidas usam o Storage Access Framework com permissão persistente concedida pelo próprio usuário.
Microfone (gravar, clonar, ditar) e notificações (aviso de tarefa concluída) são pedidos só quando usados.
