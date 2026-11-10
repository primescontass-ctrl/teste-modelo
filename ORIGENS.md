# Teste Modelo — app base (origens e contrato)

Projeto INDEPENDENTE, pacote `com.testemodelo.video`, criado 2026-10-28 para o
teste dos 2 modelos (IAAF × método próprio). NÃO é produto, NÃO é a fábrica.
Nada do lab anterior nem do Lab Arquiteturas foi modificado, movido ou
renomeado — tudo abaixo é CÓPIA, com o original verificado intacto antes e
depois (md5 em `ORIGINAIS-SNAPSHOT.md5`).

## Cópias (origem → destino)
- `lab-arquiteturas-video/app/src/main/assets/three.module.js` (md5
  `3f776a49922e8a0b0e491c2c5fe641f1`) → `app/src/main/assets/three.module.js`.
- `lab-arquiteturas-video/app/src/main/assets/three.core.js` (md5
  `6bdce60e9b22a09cbd6815f58e63f1c6`) → `app/src/main/assets/three.core.js`.
  LIÇÃO v1.0: three.core.js é carregado em runtime via three.module.js — sem
  ele a cena morre antes de qualquer código. Por isso está aqui.
- `lab-arquiteturas-video/app/src/main/java/.../CaptureService.java` →
  adaptado (pacote + nome do app) em `java/com/testemodelo/video/`.
- `lab-arquiteturas-video/app/src/main/java/.../AudioUtil.java` → adaptado:
  transcode MP3→AAC a partir de ARQUIVO (não de asset) + medição do MP4 +
  cópia para o MediaStore. Lógica de decode→encode preservada.
- `lab-arquiteturas-video/build.sh` → adaptado (pacote, versão, nome do APK).
- `MainActivity.java`: escrita do zero para este app (pickers de cena+áudio,
  Presentation+WebView, MediaRecorder, mux, galeria). Padrões herdados do lab
  anterior e do Lab Arquiteturas, todos com a causa registrada:
  FGS tipo MEDIA_PROJECTION (v0.1); callback do MediaProjection ANTES do
  VirtualDisplay (v0.2); MP3 NUNCA entra no muxer — transcode AAC antes
  (v0.7); TODAS as trilhas antes do muxer.start() (Lab teste 3); gravar SÓ
  após handshake de métricas, 8 s sem = FALHA, nunca vídeo em branco (Lab
  v1.1); erro de quadro principal é fatal, sub-recurso é aviso (Lab v1.1).

## Decisões próprias deste app
- **VirtualDisplay dpi=160 (deliberado):** com density 1.0 o viewport CSS é
  1080×1920, então o buffer GL é 1080×1920 de verdade com pixelRatio 1.
  O lab anterior usava o dpi do aparelho e o compositor ampliava o buffer
  (a causa da imagem macia da auditoria).
- **Duração da gravação = duração do áudio + 1,0 s de cauda** (a cena segura
  o quadro final após a narração, como a cena-ref do Lab).
- **Saída na galeria:** MediaStore.Video, `DCIM/TesteModelo/`, nome
  `teste-modelo-<stamp>.mp4`. Relatório em `Download/TesteModelo/`.
- **Vídeo: H.264, 1080×1920, 30 fps pedidos, 16 Mbps.** Áudio: AAC 128 kbps.
- **Sem INTERNET.** Sem assets de cena (só as libs three.js) — a cena vem do
  arquivo que o usuário escolhe.

## Contrato do arquivo de cena (o que o app espera)
- Arquivo HTML único, escolhido pelo usuário. O app copia ele para
  `<files>/cena/cena.html`, junto com `three.module.js` e `three.core.js`,
  e copia o áudio escolhido para `<files>/cena/narracao.mp3`.
- A cena deve: importar three via importmap `{"three": "./three.module.js"}`;
  ter `<audio id="voz" src="narracao.mp3">`; expor `window.playNarration()`
  (toca o áudio; o relógio da cena é `audio.currentTime`); chamar
  `window.TesteBridge.ready(JSON)` após o primeiro quadro, com
  `{w, h, dpr, canvasW, canvasH}`; no evento `playing` do áudio, chamar
  `window.TesteBridge.audioStarted()`.
- Sem o handshake `ready` em 8 s, o app FALHA sem gravar (nunca vídeo em
  branco). O offset áudio-vídeo usa o evento `playing` quando dispara;
  sem ele, usa o instante da chamada e MARCA FALLBACK no relatório.
- Áudio de entrada: MP3 (o app copia para `narracao.mp3`).

## Keystore
Própria deste projeto (`teste-modelo.keystore`). Senhas SÓ em
`keystore.properties`, nunca em chat ou relatório.
