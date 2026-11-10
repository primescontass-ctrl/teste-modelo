package com.testemodelo.video;

import android.Manifest;
import android.app.Activity;
import android.app.Presentation;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.MediaMetadataRetriever;
import android.media.MediaRecorder;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.view.Display;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * App base de teste: o usuário escolhe uma cena HTML + um áudio MP3, toca em
 * CRIAR VÍDEO, e o app grava a cena (WebView sobre VirtualDisplay) com a
 * narração, muxa em AAC e salva o MP4 na galeria (DCIM/TesteModelo/).
 *
 * Blindagens herdadas do lab anterior e do Lab Arquiteturas (causas em
 * ORIGENS.md): FGS tipo MEDIA_PROJECTION; callback do MediaProjection antes
 * do VirtualDisplay; MP3 nunca entra no muxer; trilhas antes do start();
 * grava SÓ após handshake de métricas (8 s sem = FALHA, nunca vídeo em
 * branco); erro de quadro principal é fatal, sub-recurso é aviso.
 */
public class MainActivity extends Activity {

    private static final int REQ_CENA = 101;
    private static final int REQ_AUDIO = 102;
    private static final int REQ_PROJECTION = 103;
    private static final int REQ_NOTIF = 104;
    private static final int W = 1080;
    private static final int H = 1920;
    private static final int DPI = 160; // density 1.0 -> viewport CSS 1080x1920, buffer GL real (ORIGENS.md)
    private static final int BITRATE = 16_000_000;

    private TextView tvEstado, tvCena, tvAudio, tvUltimo, tvLog;
    private ProgressBar progresso;
    private Button btnCriar;
    private final StringBuilder logSb = new StringBuilder();
    private final Handler handler = new Handler(Looper.getMainLooper());

    private Uri cenaUri, audioUri;
    private String cenaName = "", audioName = "";
    private boolean running = false;
    private boolean pendingCriar = false;

    private MediaProjection projection;
    private VirtualDisplay virtualDisplay;
    private ScenePresentation presentation;
    private MediaRecorder recorder;
    private boolean recordingStarted = false;
    private Runnable progressRunnable = null;
    private boolean readyFired = false;
    private long recorderStartMs = 0;
    private Long audioStartedMs = null;
    private long audioDurationMs = 0;
    private boolean offsetFallback = false;
    private File tempVideo, aacFile, finalFile;
    private Uri galleryUri;
    private String stamp = "";

    private final BroadcastReceiver fgsReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            log("FGS pronto.");
            beginCapture();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        tvEstado = findViewById(R.id.tvEstado);
        tvCena = findViewById(R.id.tvCena);
        tvAudio = findViewById(R.id.tvAudio);
        tvUltimo = findViewById(R.id.tvUltimo);
        tvLog = findViewById(R.id.tvLog);
        progresso = findViewById(R.id.progresso);
        btnCriar = findViewById(R.id.btnCriar);
        findViewById(R.id.btnCena).setOnClickListener(v -> pickFile(REQ_CENA, "*/*"));
        findViewById(R.id.btnAudio).setOnClickListener(v -> pickFile(REQ_AUDIO, "audio/*"));
        btnCriar.setOnClickListener(v -> criar());
        findViewById(R.id.btnAbrir).setOnClickListener(v -> abrirVideo());
        findViewById(R.id.btnCopiar).setOnClickListener(v -> copiarLog());
        findViewById(R.id.btnExportar).setOnClickListener(v -> exportarLog());
        IntentFilter f = new IntentFilter(CaptureService.BROADCAST_READY);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(fgsReceiver, f, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(fgsReceiver, f);
        }
        log("Teste Modelo 1.2 pronto. Escolha a cena e o áudio.");
    }

    @Override
    protected void onDestroy() {
        try { unregisterReceiver(fgsReceiver); } catch (Exception ignored) {}
        super.onDestroy();
    }

    private void pickFile(int req, String type) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType(type);
        startActivityForResult(i, req);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_CENA || requestCode == REQ_AUDIO) {
            if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
            Uri uri = data.getData();
            try {
                getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Exception ignored) {}
            String name = displayName(uri);
            if (requestCode == REQ_CENA) {
                cenaUri = uri; cenaName = name;
                tvCena.setText("Cena: " + name);
                log("Cena escolhida: " + name);
            } else {
                audioUri = uri; audioName = name;
                tvAudio.setText("Áudio: " + name);
                log("Áudio escolhido: " + name);
            }
            return;
        }
        if (requestCode == REQ_PROJECTION) {
            if (resultCode != RESULT_OK || data == null) {
                fail("Permissão de captura negada pelo usuário.");
                return;
            }
            Intent svc = new Intent(this, CaptureService.class);
            svc.setAction(CaptureService.ACTION_START);
            svc.putExtra(CaptureService.EXTRA_RESULT_CODE, resultCode);
            svc.putExtra(CaptureService.EXTRA_RESULT_DATA, data);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(svc); else startService(svc);
            log("Serviço de captura iniciado; aguardando FGS_READY…");
            return;
        }
        if (requestCode == REQ_NOTIF) {
            if (pendingCriar) {
                pendingCriar = false;
                criar();
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_NOTIF && pendingCriar) {
            pendingCriar = false;
            criar();
        }
    }

    private String displayName(Uri uri) {
        try (Cursor c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                String n = c.getString(0);
                if (n != null) return n;
            }
        } catch (Exception ignored) {}
        String p = uri.getLastPathSegment();
        return p != null ? p : uri.toString();
    }

    private void log(String s) {
        String line = "[" + new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date()) + "] " + s;
        logSb.append(line).append("\n");
        runOnUiThread(() -> {
            tvLog.setText(logSb.toString());
        });
    }

    private void setEstado(String s) {
        runOnUiThread(() -> tvEstado.setText("Estado: " + s));
    }

    private void criar() {
        if (running) return;
        if (cenaUri == null || audioUri == null) {
            Toast.makeText(this, "Escolha a cena e o áudio primeiro.", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!audioName.toLowerCase(Locale.US).endsWith(".mp3")) {
            fail("O áudio precisa ser MP3 (contrato do app). Escolhido: " + audioName);
            return;
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            pendingCriar = true;
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
            return;
        }
        running = true;
        recordingStarted = false;
        readyFired = false;
        audioStartedMs = null;
        offsetFallback = false;
        stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
        runOnUiThread(() -> {
            progresso.setVisibility(View.VISIBLE);
            btnCriar.setEnabled(false);
        });
        setEstado("executando");
        log("=== CRIAR VÍDEO ===");
        try {
            File cenaDir = new File(getFilesDir(), "cena");
            if (!cenaDir.exists()) cenaDir.mkdirs();
            copyUriToFile(cenaUri, new File(cenaDir, "cena.html"));
            copyAssetToFile("three.module.js", new File(cenaDir, "three.module.js"));
            copyAssetToFile("three.core.js", new File(cenaDir, "three.core.js"));
            File audioFile = new File(cenaDir, "narracao.mp3");
            copyUriToFile(audioUri, audioFile);
            log("Arquivos copiados para a pasta da cena (three.module.js + three.core.js incluídos).");
            MediaMetadataRetriever r = new MediaMetadataRetriever();
            try {
                r.setDataSource(audioFile.getAbsolutePath());
                String d = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
                audioDurationMs = d != null ? Long.parseLong(d) : 0;
            } finally {
                try { r.release(); } catch (Exception ignored) {}
            }
            if (audioDurationMs <= 0) {
                fail("Não consegui medir a duração do áudio.");
                return;
            }
            log("Duração do áudio: " + audioDurationMs + " ms; gravação: " + (audioDurationMs + 1000) + " ms.");
            MediaProjectionManager mgr = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            startActivityForResult(mgr.createScreenCaptureIntent(), REQ_PROJECTION);
        } catch (Exception e) {
            fail("Falha ao preparar: " + e.getClass().getSimpleName() + " — " + e.getMessage());
        }
    }

    private void copyUriToFile(Uri uri, File out) throws Exception {
        ContentResolver cr = getContentResolver();
        try (InputStream in = cr.openInputStream(uri);
             OutputStream o = new FileOutputStream(out)) {
            if (in == null) throw new Exception("não abriu: " + uri);
            byte[] b = new byte[65536];
            int n;
            while ((n = in.read(b)) > 0) o.write(b, 0, n);
        }
    }

    private void copyAssetToFile(String asset, File out) throws Exception {
        try (InputStream in = getAssets().open(asset);
             OutputStream o = new FileOutputStream(out)) {
            byte[] b = new byte[65536];
            int n;
            while ((n = in.read(b)) > 0) o.write(b, 0, n);
        }
    }

    private void beginCapture() {
        projection = CaptureService.getProjection();
        if (projection == null) {
            fail("MediaProjection indisponível após FGS_READY.");
            return;
        }
        try {
            projection.registerCallback(new MediaProjection.Callback() {
                @Override
                public void onStop() {
                    log("MediaProjection parou.");
                }
            }, handler);
            tempVideo = new File(getCacheDir(), "teste-modelo-temp.mp4");
            recorder = new MediaRecorder();
            recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
            recorder.setVideoSize(W, H);
            recorder.setVideoFrameRate(30);
            recorder.setVideoEncodingBitRate(BITRATE);
            recorder.setOutputFile(tempVideo.getAbsolutePath());
            recorder.prepare();
            log("MediaRecorder pronto (H.264, 1080×1920, 30 fps pedidos, 16 Mbps).");
            DisplayManager dm = (DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
            virtualDisplay = projection.createVirtualDisplay("TesteModelo", W, H, DPI,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION,
                    recorder.getSurface(), null, null);
            log("VirtualDisplay criado (1080×1920, dpi=160).");
            File sceneFile = new File(new File(getFilesDir(), "cena"), "cena.html");
            presentation = new ScenePresentation(this, virtualDisplay.getDisplay(), sceneFile);
            presentation.show();
            log("WebView da cena aberta; aguardando handshake de métricas (máx. 8 s)…");
            handler.postDelayed(() -> {
                if (!readyFired && running && !recordingStarted) {
                    fail("CENA_NAO_EXECUTOU — sem handshake de métricas em 8 s. Nenhum vídeo em branco foi gravado.");
                }
            }, 8000);
        } catch (Exception e) {
            fail("Falha ao iniciar captura: " + e.getClass().getSimpleName() + " — " + e.getMessage());
        }
    }

    /** Chamado pela bridge quando a cena renderiza o primeiro quadro. */
    private void onSceneReady(String json) {
        log("Métricas WebView: " + json);
        readyFired = true;
        beginRecording();
    }

    private void beginRecording() {
        if (recordingStarted || !running) return;
        try {
            recordingStarted = true;
            recorder.start();
            recorderStartMs = System.currentTimeMillis();
            log("GRAVAÇÃO INICIADA (" + (audioDurationMs + 1000) + " ms).");
            if (presentation != null && presentation.webView != null) {
                presentation.webView.evaluateJavascript("window.playNarration && window.playNarration()", null);
            }
            handler.postDelayed(this::stopCapture, audioDurationMs + 1000);
            // v1.1: antes o log ficava mudo durante os ~172 s de gravação.
            final long totalMs = audioDurationMs + 1000;
            progressRunnable = new Runnable() {
                @Override public void run() {
                    if (!recordingStarted || recorder == null) return;
                    long el = System.currentTimeMillis() - recorderStartMs;
                    log("Gravando… " + (el / 1000) + " s / " + (totalMs / 1000) + " s.");
                    handler.postDelayed(this, 15000);
                }
            };
            handler.postDelayed(progressRunnable, 15000);
        } catch (Exception e) {
            fail("Falha ao iniciar gravação: " + e.getClass().getSimpleName() + " — " + e.getMessage());
        }
    }

    private void stopCapture() {
        if (!running) return;
        try {
            if (recorder != null) {
                try { recorder.stop(); } catch (Exception e) { log("recorder.stop: " + e.getMessage()); }
                try { recorder.release(); } catch (Exception ignored) {}
                recorder = null;
            }
        } catch (Exception ignored) {}
        try { if (virtualDisplay != null) virtualDisplay.release(); } catch (Exception ignored) {}
        virtualDisplay = null;
        try { if (presentation != null) presentation.dismiss(); } catch (Exception ignored) {}
        presentation = null;
        stopCaptureService();
        log("Gravação parada; transcodificando e muxando…");
        new Thread(this::finishVideo).start();
    }

    private void stopCaptureService() {
        Intent svc = new Intent(this, CaptureService.class);
        svc.setAction(CaptureService.ACTION_STOP);
        startService(svc);
    }

    private void finishVideo() {
        try {
            File cenaDir = new File(getFilesDir(), "cena");
            File mp3 = new File(cenaDir, "narracao.mp3");
            aacFile = new File(getCacheDir(), "teste-modelo-audio.m4a");
            AudioUtil.mp3FileToAac(mp3, aacFile, this::log);
            long offsetUs;
            if (audioStartedMs != null) {
                offsetUs = (audioStartedMs - recorderStartMs) * 1000;
                if (offsetUs < 0) offsetUs = 0;
                log("Offset áudio-vídeo (evento playing): " + (offsetUs / 1000) + " ms.");
            } else {
                offsetUs = 0;
                offsetFallback = true;
                log("Offset áudio-vídeo: 0 ms (FALLBACK instante da chamada — bridge não disparou).");
            }
            finalFile = new File(getCacheDir(), "teste-modelo-final.mp4");
            log("Entrada do mux: vídeo temp " + tempVideo.length() + " bytes; AAC " + aacFile.length() + " bytes.");
            // v1.3 — normalização TikTok DENTRO do app (VFR→CFR 30, ~5 Mbps, faststart).
            // Falha aqui não quebra a entrega: segue com o vídeo original.
            File videoForMux = tempVideo;
            try {
                File normVideo = new File(getCacheDir(), "teste-modelo-tiktok.mp4");
                TiktokNormalize.normalize(this, Uri.fromFile(tempVideo),
                        normVideo.getAbsolutePath(), s -> log("[tiktok] " + s));
                log("Normalização TikTok OK: " + normVideo.length() + " bytes.");
                videoForMux = normVideo;
            } catch (Exception e) {
                log("Normalização TikTok falhou (" + e.getMessage() + ") — seguindo com o vídeo original.");
            }
            AudioUtil.muxVideoAudio(videoForMux, aacFile, finalFile, offsetUs, this::log);
            String name = "teste-modelo-" + stamp + ".mp4";
            galleryUri = AudioUtil.copyVideoToGallery(this, finalFile, name);
            if (galleryUri == null) {
                fail("Falha ao salvar o vídeo na galeria.");
                return;
            }
            log("Vídeo na galeria: DCIM/TesteModelo/" + name);
            String medida = AudioUtil.measureMp4(this, galleryUri, finalFile.length());
            log(medida.trim());
            writeReport(name, medida, null);
            running = false;
            setEstado("concluído");
            runOnUiThread(() -> {
                progresso.setVisibility(View.GONE);
                btnCriar.setEnabled(true);
                tvUltimo.setText("Último vídeo: " + name + " (galeria)");
            });
            log("VÍDEO CONCLUÍDO — abra pela galeria ou pelo botão Abrir vídeo.");
        } catch (Exception e) {
            fail("Falha ao finalizar: " + e.getClass().getSimpleName() + " — " + e.getMessage());
        }
    }

    private void writeReport(String videoName, String medida, String erro) {
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("Teste Modelo 1.2 — relatório ").append(stamp).append("\n");
            sb.append("Cena: ").append(cenaName).append("\n");
            sb.append("Áudio: ").append(audioName).append(" (").append(audioDurationMs).append(" ms)\n");
            sb.append("Gravação: ").append(audioDurationMs + 1000).append(" ms; 1080×1920; 30 fps pedidos; 16 Mbps; VirtualDisplay dpi=160\n");
            sb.append("Offset áudio-vídeo: ").append(offsetFallback ? "FALLBACK (bridge não disparou)" : "evento playing").append("\n");
            if (videoName != null) sb.append("Vídeo: DCIM/TesteModelo/").append(videoName).append("\n");
            if (medida != null) sb.append(medida);
            if (erro != null) sb.append("ERRO: ").append(erro).append("\n");
            File rep = new File(getCacheDir(), "teste-modelo-relatorio-" + stamp + ".txt");
            try (FileOutputStream o = new FileOutputStream(rep)) {
                o.write(sb.toString().getBytes("UTF-8"));
            }
            Uri u = AudioUtil.copyToDownloads(this, rep, "teste-modelo-relatorio-" + stamp + ".txt");
            log("Relatório: " + (u != null ? "Download/TesteModelo/teste-modelo-relatorio-" + stamp + ".txt" : "falha ao exportar"));
        } catch (Exception e) {
            log("Relatório falhou: " + e.getMessage());
        }
    }

    private void fail(String msg) {
        log("FALHOU: " + msg);
        try { if (recorder != null) { try { recorder.stop(); } catch (Exception ignored) {} try { recorder.release(); } catch (Exception ignored) {} recorder = null; } } catch (Exception ignored) {}
        try { if (virtualDisplay != null) virtualDisplay.release(); } catch (Exception ignored) {}
        virtualDisplay = null;
        try { if (presentation != null) presentation.dismiss(); } catch (Exception ignored) {}
        presentation = null;
        if (running) stopCaptureService();
        writeReport(null, null, msg);
        running = false;
        setEstado("falhou");
        runOnUiThread(() -> {
            progresso.setVisibility(View.GONE);
            btnCriar.setEnabled(true);
        });
    }

    private void abrirVideo() {
        if (galleryUri == null) {
            Toast.makeText(this, "Nenhum vídeo criado ainda.", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(galleryUri, "video/mp4");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this, "Sem app para abrir o vídeo.", Toast.LENGTH_SHORT).show();
        }
    }

    private void copiarLog() {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("Teste Modelo — log", logSb.toString()));
        Toast.makeText(this, "Log copiado.", Toast.LENGTH_SHORT).show();
    }

    private void exportarLog() {
        try {
            File f = new File(getCacheDir(), "teste-modelo-log-" + stamp + ".txt");
            try (FileOutputStream o = new FileOutputStream(f)) {
                o.write(logSb.toString().getBytes("UTF-8"));
            }
            Uri u = AudioUtil.copyToDownloads(this, f, "teste-modelo-log-" + stamp + ".txt");
            Toast.makeText(this, u != null ? "Log exportado para Download/TesteModelo/." : "Falha ao exportar.", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "Falha: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    /** Presentation com WebView; a cena fala com o app pela bridge TesteBridge. */
    private class ScenePresentation extends Presentation {
        WebView webView;
        File sceneFile;

        ScenePresentation(Context ctx, Display display, File sceneFile) {
            super(ctx, display);
            this.sceneFile = sceneFile;
        }

        @Override
        protected void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            webView = new WebView(getContext());
            WebSettings ws = webView.getSettings();
            ws.setJavaScriptEnabled(true);
            ws.setDomStorageEnabled(true);
            ws.setAllowFileAccess(true);
            ws.setAllowFileAccessFromFileURLs(true);
            ws.setAllowUniversalAccessFromFileURLs(true);
            ws.setMediaPlaybackRequiresUserGesture(false);
            webView.addJavascriptInterface(new Bridge(), "TesteBridge");
            webView.setWebViewClient(new WebViewClient() {
                @Override
                public void onPageFinished(WebView view, String url) {
                    log("WebView: página carregada (aguardando handshake).");
                }

                @Override
                public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                    String msg = "WebView ERRO: " + error.getErrorCode() + " — " + error.getDescription();
                    if (request.isForMainFrame()) {
                        fail("WebView falhou no quadro principal: " + msg);
                    } else {
                        log(msg + " (sub-recurso, aviso).");
                    }
                }
            });
            setContentView(webView);
            webView.loadUrl("file://" + sceneFile.getAbsolutePath());
        }

        private class Bridge {
            @JavascriptInterface
            public void ready(final String json) {
                runOnUiThread(() -> onSceneReady(json));
            }

            @JavascriptInterface
            public void audioStarted() {
                runOnUiThread(() -> {
                    audioStartedMs = System.currentTimeMillis();
                    log("Bridge: áudio começou (evento playing).");
                });
            }
        }
    }
}
