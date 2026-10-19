# Como compilar

## Requisitos

* JDK 21
* Android SDK com `platforms;android-36` e `build-tools;36.0.0`
* NDK **27.1.12297006** e CMake **3.22.1** do SDK (outras versões instaladas podem ser escolhidas com
  `ANDROID_NDK_VERSION` e `ANDROID_CMAKE_VERSION`; este projeto também foi compilado com NDK 28.2 e CMake 4.1)
* Python 3, Bash e Git
* Internet na primeira compilação (dependências Gradle, AAR do sherpa-onnx e fontes do OmniVoice/llama.cpp,
  todas verificadas por SHA-256 ou commit)

## Compilação local

```sh
git clone https://github.com/Fakesss/VozIAApk.git
cd VozIAApk/mobile/android
export ANDROID_HOME=$HOME/Android/Sdk          # seu SDK
printf 'sdk.dir=%s\n' "$ANDROID_HOME" > local.properties
./gradlew assembleDebug                        # compila também as bibliotecas JNI (OmniVoice, llama.cpp)
./gradlew testDebugUnitTest                    # testes JVM
./gradlew assembleDebugAndroidTest             # APK de testes instrumentados
```

Saídas em `mobile/android/app/build/outputs/apk/debug/`:
`app-arm64-v8a-debug.apk` (celulares), `app-x86_64-debug.apk` (emuladores) e `app-universal-debug.apk`.

A versão vem de `package.json` (versão do VoiceStudio) mais o número de build do fork:
`./gradlew -PvoziaBuild=7 assembleDebug` gera a versão `0.5.7-vozia.7` (versionCode 500707). Use um número
maior a cada atualização para que ela instale por cima mantendo os dados.

## Versão de lançamento assinada (APK e AAB)

A chave de release nunca fica no repositório. Passe-a por variáveis de ambiente:

```sh
export VOZIA_KEYSTORE=/caminho/vozia-release.jks
export VOZIA_KEYSTORE_PASSWORD='...'
export VOZIA_KEY_ALIAS=vozia
./gradlew -PvoziaBuild=1 assembleRelease bundleRelease
```

Saídas: `app/build/outputs/apk/release/*.apk` e `app/build/outputs/bundle/release/app-release.aab`.
Para criar uma chave nova: `keytool -genkeypair -keystore vozia-release.jks -storetype PKCS12 -alias vozia -keyalg RSA -keysize 4096 -validity 10950`.
**Guarde a chave e a senha**: atualizações futuras só instalam por cima se forem assinadas com a mesma chave.

As builds de depuração usam `mobile/android/keystore/debug.keystore` (chave pública padrão `android`),
para que APKs de depuração de qualquer máquina ou do CI atualizem uns aos outros.

## Integração contínua (GitHub Actions)

`.github/workflows/android.yml`, a cada push:

1. compila as bibliotecas nativas com o NDK fixado, roda os testes JVM e gera APKs de depuração,
   o APK de testes e o AAB de release;
2. publica uma release `build-N` com os APKs, o AAB e `SHA256SUMS.txt`;
3. roda os testes de ponta a ponta em emuladores Android 10 (API 29, celular), 14 (API 34, celular, com
   reinício e atualização) e 15 (API 35, tablet) e anexa `device-results-*.zip` (resumo, logs,
   capturas de tela e os arquivos exportados) à mesma release.

Para que o CI também gere APK/AAB **assinados**, adicione no GitHub (Settings → Secrets → Actions) os
segredos `VOZIA_KEYSTORE_BASE64` (`base64 -w0 vozia-release.jks`), `VOZIA_KEYSTORE_PASSWORD` e
`VOZIA_KEY_ALIAS`. Com eles presentes, o passo "Signed release" gera e publica
`*-release.apk` e `*-release.aab` assinados na mesma release.

## Testes no dispositivo

Com um celular ou emulador conectado (`adb devices`):

```sh
cd mobile/android
./gradlew assembleDebug assembleDebugAndroidTest
mkdir -p dist/update && cp app/build/outputs/apk/debug/*.apk app/build/outputs/apk/androidTest/debug/*.apk dist/
./gradlew -PvoziaBuild=1001 assembleDebug && cp app/build/outputs/apk/debug/*.apk dist/update/
bash scripts/device-e2e.sh dist resultados true
```

O script instala o app, baixa o modelo Piper pt-BR (aceitando sua licença em nome do testador),
gera fala, transcreve de volta, exporta para Downloads, verifica os arquivos de fora do app, força o
fechamento, reinicia o aparelho e instala uma atualização, conferindo os dados em cada etapa.
