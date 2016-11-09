package com.example.antonprozorov.mediacodecvideocutting;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.HashMap;

public class MediaCodecHelper {

    private static final String TAG = MediaCodecHelper.class.getName();
    private static final int MAX_SAMPLE_SIZE = 256 * 1024;
    private static final boolean VERBOSE = true;

    private static final String MIME_AAC = "audio/mp4a-latm";
    private static final String MIME_H264 = "video/avc";

    public void cutVideo(String src, String dest, long fromMsecs, long toMsecs) throws IOException{
        checkInputFile(src);
        checkOutputFile(dest);
        checkTimeParams(fromMsecs, toMsecs);

        long fromUs = fromMsecs * 1000;
        long toUs = toMsecs * 1000;

        MediaExtractor extractor = new MediaExtractor();
        extractor.setDataSource(src);
        int trackCount = extractor.getTrackCount();
        for (int trackNumber = 0; trackNumber < trackCount; trackNumber++) {
            maybeDecodeTrack(extractor, trackNumber);
        }
        Log.d(TAG, "Track count: " + trackCount);

//        mux(dest, fromMsecs, toMsecs, toUs, extractor, trackCount);
        extractor.release();
        return;
    }

    private void maybeDecodeTrack(MediaExtractor extractor, int trackNumber) throws IOException {
        MediaFormat format = extractor.getTrackFormat(trackNumber);
        String mime = Optional.ofNullable(format.getString(MediaFormat.KEY_MIME)).orElse("");
        Log.d(TAG, "Mime " + mime);
        boolean isAudioTrack = mime.contains("audio");
        boolean isVideoTrack = mime.contains("video");
        if (!(isAudioTrack || isVideoTrack)) {
            Log.d(TAG, "skipping track with mime " + mime);
            return;
        }

        MediaCodec decoder = MediaCodec.createDecoderByType(mime);
        decoder.configure(format, null, null, 0);
        decoder.start();

        MediaCodec encoder = MediaCodec.createEncoderByType(mime);
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        encoder.start();
    }

    private void mux(String dest, long fromMsecs, long toMsecs, long toUs, MediaExtractor extractor, int trackCount) throws IOException {
        MediaMuxer muxer;
        muxer = new MediaMuxer(dest, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        // Set up the tracks.
        HashMap<Integer, Integer> indexMap = new HashMap<>(trackCount);
        for (int i = 0; i < trackCount; i++) {
            extractor.selectTrack(i);
            MediaFormat format = extractor.getTrackFormat(i);
            int dstIndex = muxer.addTrack(format);
            indexMap.put(i, dstIndex);
        }
        // Copy the samples from MediaExtractor to MediaMuxer.
        boolean sawEOS = false;
        int bufferSize = MAX_SAMPLE_SIZE;
        int frameCount = 0;
        int offset = 100;
        ByteBuffer dstBuf = ByteBuffer.allocate(bufferSize);
        MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
        muxer.start();
        while (!sawEOS) {
            bufferInfo.offset = offset;
            bufferInfo.size = extractor.readSampleData(dstBuf, offset);
            if (bufferInfo.size < 0) {
                if (VERBOSE) {
                    Log.d(TAG, "saw input EOS.");
                }
                sawEOS = true;
                bufferInfo.size = 0;
            } else {
                bufferInfo.presentationTimeUs = extractor.getSampleTime();
                if (bufferInfo.presentationTimeUs / 1000 >= fromMsecs && bufferInfo.presentationTimeUs / 1000 <= toMsecs) {
                    bufferInfo.flags = extractor.getSampleFlags();
                    int trackIndex = extractor.getSampleTrackIndex();
                    muxer.writeSampleData(indexMap.get(trackIndex), dstBuf,
                            bufferInfo);
                    frameCount++;
                    if (VERBOSE) {
                        Log.d(TAG, "Frame (" + frameCount + ") " +
                                "PresentationTimeUs:" + bufferInfo.presentationTimeUs +
                                " Flags:" + bufferInfo.flags +
                                " TrackIndex:" + trackIndex +
                                " Size(KB) " + bufferInfo.size / 1024);
                    }
                }
                if (bufferInfo.presentationTimeUs == toUs) {
                    Log.d(TAG, "destination time reached");
                    break;
                }
                extractor.advance();
            }
        }
        muxer.stop();
        muxer.release();
    }

    private void checkOutputFile(String dest) {
        Assertion.check(() -> dest != null);
        File output = new File(dest);
        Assertion.check(() -> !output.exists());
    }

    private void checkTimeParams(long from, long to) {
        Assertion.check(() -> to > from);
    }

    private void checkInputFile(String src) {
        Assertion.check(() -> src != null);
        File input = new File(src);
        Assertion.check(() -> input.exists() && input.canRead());
    }
}
