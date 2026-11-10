# TesteModelo

App Android de teste que renderiza cenas Three.js por código e grava em MP4.

- A cena (HTML + Three.js) roda numa WebView invisível, com a narração como relógio.
- A gravação é a tela do próprio celular em tempo real (VirtualDisplay + MediaRecorder).
- Saída: MP4 vertical 1080x1920, pronto para o TikTok.

## Estrutura

- `app/src/main/java/com/testemodelo/video/` — MainActivity, CaptureService, AudioUtil, TiktokNormalize
- `app/src/main/assets/` — three.core.js e three.module.js (Three.js embutido)
- `app/src/main/AndroidManifest.xml` — permissões de gravação de tela e foreground service
- `build.sh` — build manual (javac + d8 + aapt2, sem Gradle)

## Build

```sh
./build.sh
```

O keystore de assinatura e o `keystore.properties` não estão neste repositório.
