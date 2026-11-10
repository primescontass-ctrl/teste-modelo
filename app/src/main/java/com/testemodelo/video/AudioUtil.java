package com.testemodelo.video;

import android.content.ContentValues;
import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.media.MediaMuxer;
import android.net.Uri;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Locale;

/**
 * Utilitários de áudio e medição. Adaptado do Lab Arquiteturas: o transcode
 * MP3→AAC aqui parte de um ARQUIVO (o áudio escolhido pelo usuário), não de
 * um asset. MP3 NUNCA vai para dentro do MP4 (v0.7: IllegalStateException).
 * A medição do MP4 final é por MediaExtractor (quadros reais, não config).
 */
public class AudioUtil {

    public interface LogFn { void log(String s); }

    /** Transcodifica um MP3 em arquivo para AAC (.m4a). Devolve a duração em ms, ou -1. */
    public static long mp3FileToAac(File mp3, File outM4a, LogFn log) throws Exception {
        MediaExtractor ex = new MediaExtractor();
        ex.setDataSource(mp3.getAbsolutePath());
        int track = -1;
        MediaFormat inFmt = null;
        for (int i = 0; i < ex.getTrackCount(); i++) {
            MediaFormat f = ex.getTrackFormat(i);
            String mime = f.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("audio/")) { track = i; inFmt = f; break; }
        }
        if (track < 0) throw new Exception("Arquivo sem trilha de áudio.");
        long durationUs = inFmt.containsKey(MediaFormat.KEY_DURATION) ? inFmt.getLong(MediaFormat.KEY_DURATION) : -1;
        ex.selectTrack(track);

        MediaCodec dec = MediaCodec.createDecoderByType(inFmt.getString(MediaFormat.KEY_MIME));
        dec.configure(inFmt, null, null, 0);
        dec.start();

        int sampleRate = inFmt.containsKey(MediaFormat.KEY_SAMPLE_RATE) ? inFmt.getInteger(MediaFormat.KEY_SAMPLE_RATE) : 44100;
        int channels = inFmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT) ? inFmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 2;
        MediaFormat encFmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels);
        encFmt.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        encFmt.setInteger(MediaFormat.KEY_BIT_RATE, 128000);
        MediaCodec enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
        enc.configure(encFmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        enc.start();

        MediaMuxer mux = new MediaMuxer(outM4a.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        int aOut = -1;
        boolean muxStarted = false, decDone = false, encDone = false, encEosQueued = false;
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        // v1.2: pool de PCM — o decoder despeja aqui e o encoder consome em
        // pedaços do tamanho do buffer DELE (ver dreno abaixo).
        ByteBuffer pcmPool = ByteBuffer.allocate(1 << 20);
        long pcmBytesFed = 0;
        final long bytesPerSec = (long) sampleRate * channels * 2;
        try {
            while (!encDone) {
                if (!decDone) {
                    int inIdx = dec.dequeueInputBuffer(10000);
                    if (inIdx >= 0) {
                        ByteBuffer ib = dec.getInputBuffer(inIdx);
                        int n = ex.readSampleData(ib, 0);
                        if (n < 0) {
                            dec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            decDone = true;
                        } else {
                            dec.queueInputBuffer(inIdx, 0, n, ex.getSampleTime(), ex.getSampleFlags());
                            ex.advance();
                        }
                    }
                }
                int outIdx = dec.dequeueOutputBuffer(info, 10000);
                if (outIdx >= 0) {
                    boolean eos = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    if (info.size > 0) {
                        if (pcmPool.remaining() < info.size) {
                            ByteBuffer bigger = ByteBuffer.allocate((pcmPool.capacity() + info.size) * 2);
                            pcmPool.flip();
                            bigger.put(pcmPool);
                            pcmPool = bigger;
                        }
                        ByteBuffer ob = dec.getOutputBuffer(outIdx);
                        ob.position(info.offset);
                        ob.limit(info.offset + info.size);
                        pcmPool.put(ob);
                    }
                    dec.releaseOutputBuffer(outIdx, false);
                    if (eos) decDone = true;
                }
                // Dreno: entrega o PCM ao encoder em pedaços que cabem no
                // buffer dele. Um frame MP3 decodificado (1152 samples) pode
                // ser maior que a entrada do AAC — jogar inteiro causava o
                // BufferOverflowException do campo v1.1.
                pcmPool.flip();
                while (pcmPool.remaining() > 0 && !encEosQueued) {
                    int einIdx = enc.dequeueInputBuffer(0);
                    if (einIdx < 0) break;
                    ByteBuffer eb = enc.getInputBuffer(einIdx);
                    eb.clear();
                    int chunk = Math.min(pcmPool.remaining(), eb.remaining());
                    int oldLimit = pcmPool.limit();
                    pcmPool.limit(pcmPool.position() + chunk);
                    eb.put(pcmPool);
                    pcmPool.limit(oldLimit);
                    long ptsUs = pcmBytesFed * 1000000L / bytesPerSec;
                    pcmBytesFed += chunk;
                    enc.queueInputBuffer(einIdx, 0, chunk, ptsUs, 0);
                }
                if (decDone && pcmPool.remaining() == 0 && !encEosQueued) {
                    int einIdx = enc.dequeueInputBuffer(10000);
                    if (einIdx >= 0) {
                        long ptsUs = pcmBytesFed * 1000000L / bytesPerSec;
                        enc.queueInputBuffer(einIdx, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        encEosQueued = true;
                    }
                }
                pcmPool.compact();
                int eOut = enc.dequeueOutputBuffer(info, 10000);
                if (eOut >= 0) {
                    if (info.size > 0) {
                        if (!muxStarted) {
                            aOut = mux.addTrack(enc.getOutputFormat());
                            mux.start();
                            muxStarted = true;
                        }
                        ByteBuffer ob = enc.getOutputBuffer(eOut);
                        ob.position(info.offset);
                        ob.limit(info.offset + info.size);
                        mux.writeSampleData(aOut, ob, info);
                    }
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) encDone = true;
                    enc.releaseOutputBuffer(eOut, false);
                } else if (eOut == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if (!muxStarted) {
                        aOut = mux.addTrack(enc.getOutputFormat());
                        mux.start();
                        muxStarted = true;
                    }
                }
            }
        } finally {
            try { if (muxStarted) mux.stop(); } catch (Exception ignored) {}
            try { mux.release(); } catch (Exception ignored) {}
            try { dec.stop(); dec.release(); } catch (Exception ignored) {}
            try { enc.stop(); enc.release(); } catch (Exception ignored) {}
            try { ex.release(); } catch (Exception ignored) {}
        }
        if (!outM4a.exists() || outM4a.length() == 0) throw new Exception("AAC vazio após transcode.");
        if (log != null) log.log("Áudio transcodificado MP3→AAC: " + outM4a.length() + " bytes.");
        return durationUs / 1000;
    }

    /**
     * Muxa vídeo (temp, sem áudio) + AAC, deslocando o áudio pelo offset.
     * TODAS as trilhas são adicionadas ANTES do muxer.start() (Lab teste 3).
     */
    public static void muxVideoAudio(File videoTemp, File aac, File out, long audioOffsetUs, LogFn log) throws Exception {
        MediaExtractor vEx = new MediaExtractor();
        vEx.setDataSource(videoTemp.getAbsolutePath());
        int vTrack = -1;
        MediaFormat vFmt = null;
        for (int i = 0; i < vEx.getTrackCount(); i++) {
            MediaFormat f = vEx.getTrackFormat(i);
            String mime = f.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("video/")) { vTrack = i; vFmt = f; break; }
        }
        if (vTrack < 0) throw new Exception("Vídeo temporário sem trilha de vídeo.");
        vEx.selectTrack(vTrack);

        MediaExtractor aEx = new MediaExtractor();
        aEx.setDataSource(aac.getAbsolutePath());
        int aTrack = -1;
        MediaFormat aFmt = null;
        for (int i = 0; i < aEx.getTrackCount(); i++) {
            MediaFormat f = aEx.getTrackFormat(i);
            String mime = f.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("audio/")) { aTrack = i; aFmt = f; break; }
        }
        if (aTrack < 0) throw new Exception("AAC sem trilha de áudio.");
        aEx.selectTrack(aTrack);

        MediaMuxer mux = new MediaMuxer(out.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        int vOut = mux.addTrack(vFmt);
        int aOut = mux.addTrack(aFmt);
        mux.start();

        // v1.1: o buffer era fixo em 1 MB e um keyframe 1080p a 16 Mbps passa
        // disso — BufferOverflowException no finalize (campo 2026-10-29).
        // Base 8 MB, ou KEY_MAX_INPUT_SIZE da trilha se maior; se um sample
        // ainda não couber, dobra (até 64 MB) e tenta o mesmo sample de novo
        // (o extrator não avança quando o buffer estoura).
        int maxInput = vFmt.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE) ? vFmt.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) : 0;
        int cap = Math.max(8 * 1024 * 1024, maxInput);
        ByteBuffer buf = ByteBuffer.allocate(cap);
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        int vCount = 0;
        while (true) {
            int n;
            try {
                n = vEx.readSampleData(buf, 0);
            } catch (java.nio.BufferOverflowException e) {
                if (cap >= 64 * 1024 * 1024) throw e;
                cap *= 2;
                buf = ByteBuffer.allocate(cap);
                if (log != null) log.log("Mux: buffer de vídeo crescido para " + (cap / 1024 / 1024) + " MB.");
                continue;
            }
            if (n < 0) break;
            info.set(0, n, vEx.getSampleTime(), vEx.getSampleFlags());
            mux.writeSampleData(vOut, buf, info);
            vCount++;
            vEx.advance();
        }
        int aCount = 0;
        while (true) {
            int n = aEx.readSampleData(buf, 0);
            if (n < 0) break;
            long pts = aEx.getSampleTime() + audioOffsetUs;
            if (pts < 0) pts = 0;
            info.set(0, n, pts, aEx.getSampleFlags());
            mux.writeSampleData(aOut, buf, info);
            aCount++;
            aEx.advance();
        }
        try { mux.stop(); } catch (Exception ignored) {}
        mux.release();
        vEx.release();
        aEx.release();
        if (log != null) log.log("Mux: " + vCount + " quadros de vídeo + " + aCount + " amostras de áudio; offset=" + (audioOffsetUs / 1000) + " ms.");
    }

    /** Mede o MP4 final: quadros de vídeo (contagem, pts, deltas) + trilha de áudio. */
    public static String measureMp4(Context ctx, Uri uri, long fileSize) {
        StringBuilder sb = new StringBuilder();
        MediaExtractor ex = new MediaExtractor();
        try {
            ex.setDataSource(ctx, uri, null);
            for (int i = 0; i < ex.getTrackCount(); i++) {
                MediaFormat f = ex.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime == null) continue;
                if (mime.startsWith("video/")) {
                    ex.selectTrack(i);
                    ArrayList<Long> pts = new ArrayList<>();
                    // v1.1: mesmo motivo do mux — 1 MB estourava em keyframe 1080p.
                    ByteBuffer b = ByteBuffer.allocate(8 * 1024 * 1024);
                    while (true) {
                        int n = ex.readSampleData(b, 0);
                        if (n < 0) break;
                        pts.add(ex.getSampleTime());
                        ex.advance();
                    }
                    long first = pts.isEmpty() ? -1 : pts.get(0);
                    long last = pts.isEmpty() ? -1 : pts.get(pts.size() - 1);
                    long minD = Long.MAX_VALUE, maxD = 0, sumD = 0;
                    int near = 0;
                    for (int j = 1; j < pts.size(); j++) {
                        long d = pts.get(j) - pts.get(j - 1);
                        if (d < minD) minD = d;
                        if (d > maxD) maxD = d;
                        sumD += d;
                        if (d >= 28000 && d <= 38000) near++;
                    }
                    double mean = pts.size() > 1 ? (double) sumD / (pts.size() - 1) : 0;
                    double pct = pts.size() > 1 ? (double) near / (pts.size() - 1) * 100.0 : 0;
                    sb.append(String.format(Locale.US,
                            "Vídeo: %d quadros; primeiro pts=%d us; último pts=%d us; duração pts=%.3f s\n" +
                            "Delta entre quadros: min=%d us; max=%d us; média=%.1f us (%.3f fps médios); %d/%d deltas entre 28–38 ms (%.1f%% perto de 33,3 ms)\n",
                            pts.size(), first, last, (last - first) / 1e6, minD == Long.MAX_VALUE ? -1 : minD, maxD, mean,
                            mean > 0 ? 1e6 / mean : 0, near, Math.max(pts.size() - 1, 0), pct));
                    ex.unselectTrack(i);
                } else if (mime.startsWith("audio/")) {
                    long d = f.containsKey(MediaFormat.KEY_DURATION) ? f.getLong(MediaFormat.KEY_DURATION) : -1;
                    sb.append(String.format(Locale.US, "Áudio: presente (%s); duração=%.3f s\n", mime, d / 1e6));
                }
            }
        } catch (Exception e) {
            sb.append("Medição falhou: ").append(e.getClass().getSimpleName()).append(" — ").append(e.getMessage()).append("\n");
        } finally {
            try { ex.release(); } catch (Exception ignored) {}
        }
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(ctx, uri);
            String d = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            String w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
            String h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);
            sb.append("Metadados: duração=").append(d).append(" ms; resolução=").append(w).append("x").append(h)
                    .append("; tamanho=").append(fileSize).append(" bytes.\n");
        } catch (Exception e) {
            sb.append("Metadados indisponíveis: ").append(e.getMessage()).append("\n");
        } finally {
            try { r.release(); } catch (Exception ignored) {}
        }
        return sb.toString();
    }

    /** Copia um arquivo para o MediaStore.Video (DCIM/TesteModelo/) — aparece na galeria. */
    public static Uri copyVideoToGallery(Context ctx, File file, String displayName) {
        try {
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.Video.Media.DISPLAY_NAME, displayName);
            cv.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
            cv.put(MediaStore.Video.Media.RELATIVE_PATH, "DCIM/TesteModelo");
            Uri uri = ctx.getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cv);
            if (uri == null) return null;
            try (InputStream in = new FileInputStream(file);
                 OutputStream o = ctx.getContentResolver().openOutputStream(uri)) {
                if (o == null) return null;
                byte[] b = new byte[65536];
                int n;
                while ((n = in.read(b)) > 0) o.write(b, 0, n);
            }
            return uri;
        } catch (Exception e) {
            return null;
        }
    }

    /** Copia um arquivo para o MediaStore.Downloads (Download/TesteModelo/). */
    public static Uri copyToDownloads(Context ctx, File file, String displayName) {
        try {
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.Downloads.DISPLAY_NAME, displayName);
            cv.put(MediaStore.Downloads.MIME_TYPE, displayName.endsWith(".txt") ? "text/plain" : "video/mp4");
            cv.put(MediaStore.Downloads.RELATIVE_PATH, "Download/TesteModelo");
            Uri uri = ctx.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
            if (uri == null) return null;
            try (InputStream in = new FileInputStream(file);
                 OutputStream o = ctx.getContentResolver().openOutputStream(uri)) {
                if (o == null) return null;
                byte[] b = new byte[65536];
                int n;
                while ((n = in.read(b)) > 0) o.write(b, 0, n);
            }
            return uri;
        } catch (Exception e) {
            return null;
        }
    }
}
