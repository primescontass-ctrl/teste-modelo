package com.testemodelo.video;

import android.content.Context;
import android.media.Image;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;
import android.os.Build;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * v0.8 — normalização TikTok DENTRO do app (promessa do chat, 2026-11-03).
 *
 * Problema: o MediaRecorder entrega VFR (~56 fps variável) a 16 Mbps, sem
 * faststart — arquivo de 201 MB que o TikTok/Instagram reconvertem mal.
 * Spec validado na VM (MEMORY-LOCAL.md, "Spec TikTok adotado"):
 *   MP4 +faststart (moov no início), H.264 High yuv420p bt709, 1080x1920,
 *   CFR 30, GOP 60 (keyint 2s), ~4-6 Mbps; AAC-LC 48k stereo 128k
 *   (o áudio entra depois, no muxFinal existente — aqui só o vídeo).
 *
 * Pipeline (só APIs do framework, sem ffmpeg, sem dependência nova):
 *   1. transcodeCfr: MediaCodec decode -> grade CFR 30 via Images YUV
 *      (drop de frames adiantados, dupe de slots vazios) -> MediaCodec
 *      encode H.264 High, VBR ~5 Mbps, GOP 60 -> MediaMuxer (moov no fim).
 *   2. faststart: reordena as boxes (ftyp, moov, mdat) em Java puro,
 *      corrigindo stco/co64 — moov vai para o início.
 *
 * Falha em qualquer ponto -> exceção; o chamador entrega o vídeo sem
 * normalizar (a entrega nunca quebra por causa da normalização).
 *
 * Checkpoints de teste no aparelho (Redmi Note 14, Helio G99):
 *   [1] encoder.getInputImage() não-nulo (se null, cai no fallback);
 *   [2] saída com avg_frame_rate == 30/1 (ffprobe no arquivo final);
 *   [3] moov antes do mdat (`qt-faststart`-like; players iniciam rápido);
 *   [4] tamanho final ~= duração(s) * 5 Mbps / 8 (ex.: 113 s -> ~70 MB).
 */
public final class TiktokNormalize {

    public interface Progress {
        void log(String s);
    }

    private static final String MIME = "video/avc";
    private static final int OUT_W = 1080;
    private static final int OUT_H = 1920;
    private static final int OUT_FPS = 30;
    private static final long FRAME_US = 1_000_000L / OUT_FPS; // 33333
    private static final int OUT_BITRATE = 5_000_000;          // ~5 Mbps (spec: 4-6)
    private static final int IFRAME_INTERVAL_S = 2;            // GOP 60
    private static final long CODEC_TIMEOUT_US = 10_000;

    private TiktokNormalize() {
    }

    /** Normaliza srcUri -> dstPath. Lança exceção em qualquer falha. */
    public static void normalize(Context ctx, Uri srcUri, String dstPath, Progress p) throws Exception {
        File tmp = new File(ctx.getCacheDir(), "tiktok-cfr-tmp.mp4");
        if (tmp.exists() && !tmp.delete()) throw new IOException("não limpei o tmp");
        try {
            transcodeCfr(ctx, srcUri, tmp.getAbsolutePath(), p);
            faststart(tmp.getAbsolutePath(), dstPath, p);
            verifyCfr(dstPath, p);
        } finally {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
        }
    }

    // ------------------------------------------------------------------
    // 1. Transcode VFR -> CFR 30
    // ------------------------------------------------------------------

    private static void transcodeCfr(Context ctx, Uri srcUri, String dstPath, Progress p) throws Exception {
        MediaExtractor ex = new MediaExtractor();
        ex.setDataSource(ctx, srcUri, null);
        int vTrack = -1;
        MediaFormat inFormat = null;
        for (int i = 0; i < ex.getTrackCount(); i++) {
            MediaFormat f = ex.getTrackFormat(i);
            String mime = f.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("video/")) {
                vTrack = i;
                inFormat = f;
                break;
            }
        }
        if (vTrack < 0 || inFormat == null) {
            ex.release();
            throw new IOException("sem trilha de vídeo na origem");
        }
        String inMime = inFormat.getString(MediaFormat.KEY_MIME);
        int inW = inFormat.getInteger(MediaFormat.KEY_WIDTH);
        int inH = inFormat.getInteger(MediaFormat.KEY_HEIGHT);
        if (inW != OUT_W || inH != OUT_H) {
            ex.release();
            throw new IOException("resolução inesperada: " + inW + "x" + inH);
        }
        ex.selectTrack(vTrack);

        MediaCodec decoder = MediaCodec.createDecoderByType(inMime);
        decoder.configure(inFormat, null, null, 0);

        MediaFormat encFormat = MediaFormat.createVideoFormat(MIME, OUT_W, OUT_H);
        encFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);
        encFormat.setInteger(MediaFormat.KEY_BIT_RATE, OUT_BITRATE);
        encFormat.setInteger(MediaFormat.KEY_BITRATE_MODE,
                MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR);
        encFormat.setInteger(MediaFormat.KEY_FRAME_RATE, OUT_FPS);
        encFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, IFRAME_INTERVAL_S);
        encFormat.setInteger(MediaFormat.KEY_PROFILE,
                MediaCodecInfo.CodecProfileLevel.AVCProfileHigh);
        if (Build.VERSION.SDK_INT >= 29) {
            encFormat.setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709);
            encFormat.setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO);
            encFormat.setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED);
        }
        MediaCodec encoder = MediaCodec.createEncoderByType(MIME);
        try {
            encoder.configure(encFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        } catch (Exception e) {
            // Alguns encoders recusam profile High explícito: tenta sem a chave.
            p.log("profile High recusado (" + e.getMessage() + ") — tentando default");
            encFormat.removeKey(MediaFormat.KEY_PROFILE);
            encoder.configure(encFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        }

        decoder.start();
        encoder.start();

        MediaMuxer muxer = null;
        int muxTrack = -1;
        boolean muxStarted = false;
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

        byte[] prevFrame = null; // layout canônico I420: Y + U + V planares
        long nextOutPts = 0;
        boolean extractorEos = false, decoderEos = false, decoderEosSignaled = false, encoderEos = false;
        int framesIn = 0, framesOut = 0, framesDropped = 0, framesDuped = 0;
        long lastLog = System.currentTimeMillis();

        try {
            while (!encoderEos) {
                // --- alimenta o decoder ---
                if (!extractorEos) {
                    int inIdx = decoder.dequeueInputBuffer(CODEC_TIMEOUT_US);
                    if (inIdx >= 0) {
                        ByteBuffer inBuf = decoder.getInputBuffer(inIdx);
                        if (inBuf == null) throw new IOException("decoder sem input buffer");
                        int n = ex.readSampleData(inBuf, 0);
                        if (n < 0) {
                            decoder.queueInputBuffer(inIdx, 0, 0, 0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            extractorEos = true;
                        } else {
                            decoder.queueInputBuffer(inIdx, 0, n,
                                    ex.getSampleTime(), ex.getSampleFlags());
                            ex.advance();
                        }
                    }
                }
                // --- drena o decoder -> grade CFR -> encoder ---
                if (!decoderEos) {
                    int outIdx = decoder.dequeueOutputBuffer(info, CODEC_TIMEOUT_US);
                    if (outIdx >= 0) {
                        if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            decoderEos = true;
                            decoder.releaseOutputBuffer(outIdx, false);
                        } else if (info.size > 0) {
                            Image img = decoder.getOutputImage(outIdx);
                            if (img == null) throw new IOException("decoder sem output Image");
                            byte[] frame = imageToI420(img);
                            img.close();
                            decoder.releaseOutputBuffer(outIdx, false);
                            framesIn++;
                            long inPts = info.presentationTimeUs;
                            if (prevFrame == null) {
                                // Primeiro frame: preenche do slot 0 até ele (gap inicial congelado).
                                while (nextOutPts < inPts - FRAME_US / 2) {
                                    feedEncoder(encoder, frame, nextOutPts, p);
                                    nextOutPts += FRAME_US;
                                    framesOut++;
                                    framesDuped++;
                                }
                            } else {
                                // Slots vazios entre o anterior e este: duplica o anterior.
                                while (nextOutPts < inPts - FRAME_US / 2) {
                                    feedEncoder(encoder, prevFrame, nextOutPts, p);
                                    nextOutPts += FRAME_US;
                                    framesOut++;
                                    framesDuped++;
                                }
                            }
                            if (inPts < nextOutPts - FRAME_US / 2) {
                                framesDropped++; // chegou atrasado: descarta
                            } else {
                                feedEncoder(encoder, frame, nextOutPts, p);
                                nextOutPts += FRAME_US;
                                framesOut++;
                            }
                            prevFrame = frame;
                        } else {
                            decoder.releaseOutputBuffer(outIdx, false);
                        }
                    }
                    // INFO_OUTPUT_FORMAT_CHANGED / TRY_AGAIN_LATER: ignora, próximo giro
                }
                // --- sinaliza EOS ao encoder quando o decoder secou ---
                if (decoderEos && !decoderEosSignaled) {
                    int inIdx = encoder.dequeueInputBuffer(CODEC_TIMEOUT_US);
                    if (inIdx >= 0) {
                        encoder.queueInputBuffer(inIdx, 0, 0, 0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        decoderEosSignaled = true;
                    }
                }
                // --- drena o encoder -> muxer ---
                int eOut = encoder.dequeueOutputBuffer(info, CODEC_TIMEOUT_US);
                if (eOut >= 0) {
                    ByteBuffer eBuf = encoder.getOutputBuffer(eOut);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                        info.size = 0; // csd vai no formato da trilha, não como sample
                    }
                    if (info.size > 0) {
                        if (!muxStarted) throw new IllegalStateException(
                                "encoder emitiu dados antes do formato");
                        if (eBuf == null) throw new IOException("encoder sem output buffer");
                        muxer.writeSampleData(muxTrack, eBuf, info);
                    }
                    encoder.releaseOutputBuffer(eOut, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) encoderEos = true;
                } else if (eOut == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat newFormat = encoder.getOutputFormat();
                    muxer = new MediaMuxer(dstPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
                    muxTrack = muxer.addTrack(newFormat);
                    muxer.start();
                    muxStarted = true;
                }
                long now = System.currentTimeMillis();
                if (now - lastLog > 5000) {
                    lastLog = now;
                    p.log("transcode: in=" + framesIn + " out=" + framesOut
                            + " drop=" + framesDropped + " dup=" + framesDuped);
                }
            }
        } finally {
            try {
                decoder.stop();
            } catch (Exception ignored) {
            }
            try {
                encoder.stop();
            } catch (Exception ignored) {
            }
            decoder.release();
            encoder.release();
            ex.release();
            if (muxer != null) {
                try {
                    muxer.stop();
                } catch (Exception ignored) {
                }
                muxer.release();
            }
        }
        if (!muxStarted) throw new IOException("mux nunca iniciou (encoder não emitiu formato)");
        p.log("transcode OK: in=" + framesIn + " out=" + framesOut
                + " drop=" + framesDropped + " dup=" + framesDuped);
    }

    /** Enfileira um frame I420 canônico no encoder, no pts da grade CFR. */
    private static void feedEncoder(MediaCodec encoder, byte[] i420, long ptsUs, Progress p) throws Exception {
        int idx = encoder.dequeueInputBuffer(CODEC_TIMEOUT_US);
        if (idx < 0) throw new IOException("encoder sem input buffer (timeout)");
        Image img = encoder.getInputImage(idx);
        if (img == null) throw new IOException("encoder sem input Image neste aparelho");
        try {
            i420ToImage(i420, img);
        } finally {
            img.close();
        }
        // Com input via Image, offset/size são ignorados (0, 0); o pts manda.
        encoder.queueInputBuffer(idx, 0, 0, ptsUs, 0);
    }

    // ------------------------------------------------------------------
    // Conversão Image <-> I420 canônico (Y + U + V planares, sem stride)
    // ------------------------------------------------------------------

    private static byte[] imageToI420(Image img) {
        int w = img.getWidth(), h = img.getHeight();
        byte[] out = new byte[w * h * 3 / 2];
        Image.Plane[] planes = img.getPlanes();
        copyPlaneToArray(planes[0], w, h, out, 0);
        copyPlaneToArray(planes[1], w / 2, h / 2, out, w * h);
        copyPlaneToArray(planes[2], w / 2, h / 2, out, w * h + w * h / 4);
        return out;
    }

    private static void copyPlaneToArray(Image.Plane plane, int w, int h, byte[] out, int off) {
        ByteBuffer buf = plane.getBuffer();
        int rowStride = plane.getRowStride();
        int pixStride = plane.getPixelStride();
        buf.position(0);
        byte[] row = new byte[rowStride];
        for (int y = 0; y < h; y++) {
            buf.get(row, 0, rowStride);
            int dst = off + y * w;
            for (int x = 0; x < w; x++) out[dst + x] = row[x * pixStride];
        }
        buf.position(0);
    }

    private static void i420ToImage(byte[] i420, Image img) {
        int w = img.getWidth(), h = img.getHeight();
        Image.Plane[] planes = img.getPlanes();
        copyArrayToPlane(i420, 0, planes[0], w, h);
        copyArrayToPlane(i420, w * h, planes[1], w / 2, h / 2);
        copyArrayToPlane(i420, w * h + w * h / 4, planes[2], w / 2, h / 2);
    }

    private static void copyArrayToPlane(byte[] src, int srcOff, Image.Plane plane, int w, int h) {
        ByteBuffer buf = plane.getBuffer();
        int rowStride = plane.getRowStride();
        int pixStride = plane.getPixelStride();
        buf.position(0);
        for (int y = 0; y < h; y++) {
            int srcRow = srcOff + y * w;
            int dstRow = y * rowStride;
            for (int x = 0; x < w; x++) buf.put(dstRow + x * pixStride, src[srcRow + x]);
        }
        buf.position(0);
    }

    // ------------------------------------------------------------------
    // 2. faststart: moov para o início (reordena boxes, corrige stco/co64)
    // ------------------------------------------------------------------

    private static void faststart(String srcPath, String dstPath, Progress p) throws Exception {
        RandomAccessFile raf = new RandomAccessFile(srcPath, "r");
        try {
            long fileLen = raf.length();
            List<long[]> boxes = new ArrayList<>(); // {offset, size}
            List<String> types = new ArrayList<>();
            long pos = 0;
            while (pos < fileLen) {
                raf.seek(pos);
                long size32 = readU32(raf);
                byte[] t = new byte[4];
                raf.readFully(t);
                String type = new String(t, StandardCharsets.US_ASCII);
                long size = size32;
                if (size32 == 1) size = raf.readLong();
                else if (size32 == 0) size = fileLen - pos;
                if (size < 8) throw new IOException("box inválida: " + type);
                boxes.add(new long[]{pos, size});
                types.add(type);
                pos += size;
            }
            int ftypIdx = types.indexOf("ftyp");
            int moovIdx = types.indexOf("moov");
            if (ftypIdx != 0) throw new IOException("ftyp não é a primeira box");
            if (moovIdx < 0) throw new IOException("sem box moov");
            long ftypSize = boxes.get(ftypIdx)[1];
            long moovOff = boxes.get(moovIdx)[0];
            long moovSize = boxes.get(moovIdx)[1];
            byte[] moov = new byte[(int) moovSize];
            raf.seek(moovOff);
            raf.readFully(moov);
            // "Demais" = tudo exceto ftyp e moov, na ordem original.
            long othersOldStart = Long.MAX_VALUE;
            for (int i = 0; i < boxes.size(); i++) {
                if (i == ftypIdx || i == moovIdx) continue;
                othersOldStart = Math.min(othersOldStart, boxes.get(i)[0]);
            }
            long shift = (ftypSize + moovSize) - othersOldStart;
            if (shift != 0) patchChunkOffsets(moov, shift);
            try (OutputStream out = new BufferedOutputStream(new FileOutputStream(dstPath))) {
                copyBoxBytes(raf, boxes.get(ftypIdx), out);
                out.write(moov);
                for (int i = 0; i < boxes.size(); i++) {
                    if (i == ftypIdx || i == moovIdx) continue;
                    copyBoxBytes(raf, boxes.get(i), out);
                }
            }
            p.log("faststart: moov no início (" + moovSize + " bytes), shift stco=" + shift);
        } finally {
            raf.close();
        }
        File src = new File(srcPath), dst = new File(dstPath);
        if (dst.length() != src.length()) throw new IOException("faststart: tamanho divergiu");
    }

    private static void copyBoxBytes(RandomAccessFile raf, long[] box, OutputStream out) throws IOException {
        raf.seek(box[0]);
        long left = box[1];
        byte[] buf = new byte[65536];
        while (left > 0) {
            int n = raf.read(buf, 0, (int) Math.min(buf.length, left));
            if (n < 0) throw new IOException("EOF inesperado copiando box");
            out.write(buf, 0, n);
            left -= n;
        }
    }

    private static long readU32(RandomAccessFile raf) throws IOException {
        return raf.readInt() & 0xFFFFFFFFL;
    }

    private static long u32(byte[] b, int o) {
        return ((b[o] & 0xFFL) << 24) | ((b[o + 1] & 0xFFL) << 16)
                | ((b[o + 2] & 0xFFL) << 8) | (b[o + 3] & 0xFFL);
    }

    private static long u64(byte[] b, int o) {
        return (u32(b, o) << 32) | u32(b, o + 4);
    }

    private static void w32(byte[] b, int o, long v) {
        b[o] = (byte) (v >> 24);
        b[o + 1] = (byte) (v >> 16);
        b[o + 2] = (byte) (v >> 8);
        b[o + 3] = (byte) v;
    }

    private static void w64(byte[] b, int o, long v) {
        w32(b, o, v >> 32);
        w32(b, o + 4, v);
    }

    private static boolean isContainer(String t) {
        return t.equals("moov") || t.equals("trak") || t.equals("edts")
                || t.equals("mdia") || t.equals("minf") || t.equals("dinf")
                || t.equals("stbl");
    }

    /** Soma `shift` a cada entrada stco/co64 dentro do moov (in place). */
    private static void patchChunkOffsets(byte[] moov, long shift) {
        patchChildren(moov, 8, moov.length, shift);
    }

    private static void patchChildren(byte[] buf, int start, int end, long shift) {
        int pos = start;
        while (pos + 8 <= end) {
            long size = u32(buf, pos);
            String type = new String(buf, pos + 4, 4, StandardCharsets.US_ASCII);
            int hs = 8;
            if (size == 1) {
                size = u64(buf, pos + 8);
                hs = 16;
            }
            if (size < hs || pos + size > end) break;
            if (type.equals("stco")) {
                int n = (int) u32(buf, pos + hs + 4);
                for (int i = 0; i < n; i++) {
                    int o = pos + hs + 8 + i * 4;
                    w32(buf, o, u32(buf, o) + shift);
                }
            } else if (type.equals("co64")) {
                int n = (int) u32(buf, pos + hs + 4);
                for (int i = 0; i < n; i++) {
                    int o = pos + hs + 8 + i * 8;
                    w64(buf, o, u64(buf, o) + shift);
                }
            } else if (isContainer(type)) {
                patchChildren(buf, pos + hs, (int) (pos + size), shift);
            }
            pos += (int) size;
        }
    }

    // ------------------------------------------------------------------
    // 3. Verificação pós-transcode (só log; não falha a entrega)
    // ------------------------------------------------------------------

    private static void verifyCfr(String path, Progress p) {
        MediaExtractor ex = new MediaExtractor();
        try {
            ex.setDataSource(path);
            for (int i = 0; i < ex.getTrackCount(); i++) {
                MediaFormat f = ex.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime == null || !mime.startsWith("video/")) continue;
                ex.selectTrack(i);
                long first = -1, last = -1, n = 0;
                ByteBuffer tmp = ByteBuffer.allocate(1024);
                while (true) {
                    int sz = ex.readSampleData(tmp, 0);
                    if (sz < 0) break;
                    long t = ex.getSampleTime();
                    if (first < 0) first = t;
                    last = t;
                    n++;
                    ex.advance();
                }
                double durS = (last - first) / 1_000_000.0;
                double fps = durS > 0 ? (n - 1) / durS : 0;
                p.log(String.format(java.util.Locale.US,
                        "verify: %d frames em %.1fs = %.2f fps (alvo 30.00)", n, durS, fps));
                if (Math.abs(fps - 30.0) > 1.0) p.log("AVISO: fps fora do alvo!");
            }
        } catch (Exception e) {
            p.log("verify pulado: " + e.getMessage());
        } finally {
            ex.release();
        }
    }
}
