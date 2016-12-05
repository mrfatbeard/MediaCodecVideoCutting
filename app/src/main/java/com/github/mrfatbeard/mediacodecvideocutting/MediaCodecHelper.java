package com.github.mrfatbeard.mediacodecvideocutting;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;
import android.os.Build;
import android.support.annotation.NonNull;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@SuppressLint("UseSparseArrays")
@TargetApi(Build.VERSION_CODES.JELLY_BEAN_MR2)
class MediaCodecHelper implements Thread.UncaughtExceptionHandler {

    private static final int I_FRAME_INTERVAL = 5;
    private static final String TAG = MediaCodecHelper.class.getName();
    private static final int MAX_SAMPLE_SIZE = 256 * 1024;
    private static final boolean VERBOSE = BuildConfig.DEBUG;
    private static final String MIME_AAC = "audio/mp4a-latm";
    private static final String MIME_H264 = "video/avc";
    private final Map<Integer, MediaCodec> encoders = new HashMap<>();
    private final Map<Integer, MediaCodec> decoders = new HashMap<>();
    private final Callback callback;
    private final Object lock = new Object();
    private volatile MediaMuxer muxer;
    private volatile long fromUs;
    private volatile long toUs;
    private volatile boolean muxerStarted;
    private volatile int preparedEncoderCount = 0;
    private volatile int doneCount = 0;
    private String dest;
    private volatile long start;
    private ExecutorService executorService;
    private CountDownLatch PROCESSING_START;

    MediaCodecHelper(Callback callback) {
        this.callback = callback;
    }

    private static void _log(String message) {
        if (VERBOSE) {
            Log.d(TAG, Thread.currentThread().getName() + ": " + message);
        }
    }

    void cutVideo(@NonNull Context context, @NonNull Uri src, @NonNull String dest, long fromMsecs, long toMsecs) throws IOException {
        Log.e(TAG, "Start");
        this.dest = dest;
        callback.onStarted();
        start = System.currentTimeMillis();
        checkInputFile(src);
        checkOutputFile(dest);
        checkTimeParams(fromMsecs, toMsecs);

        fromUs = fromMsecs * 1000;
        toUs = toMsecs * 1000;

        muxer = new MediaMuxer(dest, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

        final MediaExtractor extractor = new MediaExtractor();
        extractor.setDataSource(context, src, null);

        final int trackCount = extractor.getTrackCount();
        final int processingCount = getProcessingCount(extractor, trackCount);

        PROCESSING_START = new CountDownLatch(processingCount);
        this.executorService = Executors.newFixedThreadPool(processingCount, runnable -> {
            Thread thread = new Thread(runnable);
            thread.setUncaughtExceptionHandler(MediaCodecHelper.this);
            return thread;
        });

        for (int i = 0; i < trackCount; i++) {
            MediaFormat format = extractor.getTrackFormat(i);
            final String mime = format.getString(MediaFormat.KEY_MIME);
            if (mime.contains("video") || mime.contains("audio")) {
                final int trackNumber = i;
                MediaExtractor ex = new MediaExtractor();
                ex.setDataSource(context, src, null);
                ex.selectTrack(i);
                executorService.execute(() -> {
                    try {
                        maybeProcessTrack(ex, trackNumber);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                });
            }
        }
        extractor.release();
    }

    private int getProcessingCount(MediaExtractor extractor, int trackCount) {
        int tracksForProcessing = 0;
        for (int i = 0; i < trackCount; i++) {
            MediaFormat format = extractor.getTrackFormat(i);
            final String mime = format.getString(MediaFormat.KEY_MIME);
            if (mime.contains("video") || mime.contains("audio")) {
                tracksForProcessing++;
            }
        }
        return tracksForProcessing;
    }

    private void maybeProcessTrack(MediaExtractor extractor, int trackNumber) throws IOException {
        try {
            synchronized (lock) {
                boolean codecAvailable = maybeInitCodecs(extractor, trackNumber);
                PROCESSING_START.countDown();
                if (!codecAvailable) {
                    return;
                }
            }
            Log.d(TAG, "processing track " + trackNumber);
            PROCESSING_START.await();
            cutTrack(extractor, trackNumber);
            Log.d(TAG, "track " + trackNumber + " done");
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    private void cutTrack(MediaExtractor extractor, int trackNumber) {
        final int TIMEOUT_USEC = 10000;

        MediaCodec decoder = decoders.get(trackNumber);
        MediaCodec encoder = encoders.get(trackNumber);
        decoder.start();
        encoder.start();

        ByteBuffer[] decoderInputBuffers = decoder.getInputBuffers();
        ByteBuffer[] decoderOutputBuffers = decoder.getOutputBuffers();

        ByteBuffer[] encoderInputBuffers = encoder.getInputBuffers();
        ByteBuffer[] encoderOutputBuffers = encoder.getOutputBuffers();

        boolean inputDone = false;
        boolean outputDone = false;
        boolean decoderDone = false;

        int frameCount = 0;
        int offset = 100;
        int muxerTrackIndex = -1;

        ByteBuffer extractedBuffer = ByteBuffer.allocate(MAX_SAMPLE_SIZE);
        MediaCodec.BufferInfo extractorBufferInfo = new MediaCodec.BufferInfo();
        MediaCodec.BufferInfo encoderBufferInfo = new MediaCodec.BufferInfo();
        extractor.seekTo(fromUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
        while (!outputDone) {
            _log("cut loop");

            if (!inputDone) {
                int inputBufIndex = decoder.dequeueInputBuffer(TIMEOUT_USEC);
                if (inputBufIndex >= 0) {
                    extractorBufferInfo.offset = offset;
                    extractorBufferInfo.size = extractor.readSampleData(extractedBuffer, offset);
                    if (extractorBufferInfo.size < 0) {
                        _log("saw input EOS");
                        inputDone = true;
                        extractorBufferInfo.size = 0;

                        decoder.queueInputBuffer(inputBufIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                    } else {
                        extractorBufferInfo.presentationTimeUs = extractor.getSampleTime();
                        extractorBufferInfo.flags = extractor.getSampleFlags();
                        if (extractorBufferInfo.presentationTimeUs <= toUs) {
                            ByteBuffer inputBuf = decoderInputBuffers[inputBufIndex];
                            inputBuf.clear();
                            inputBuf.put(extractedBuffer);
                            decoder.queueInputBuffer(inputBufIndex, 0, inputBuf.position(), extractorBufferInfo.presentationTimeUs, extractorBufferInfo.flags);

                            frameCount++;
                            _log("Frame (" + frameCount + ") " +
                                    "PresentationTimeUs:" + extractorBufferInfo.presentationTimeUs +
                                    " Flags:" + extractorBufferInfo.flags +
                                    " TrackIndex:" + trackNumber +
                                    " Size(KB) " + extractorBufferInfo.size / 1024);
                        } else if (extractorBufferInfo.presentationTimeUs > toUs) {
                            _log("desired time reached");
                            inputDone = true;
                            extractorBufferInfo.size = 0;
                            decoder.queueInputBuffer(inputBufIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        }
                        extractor.advance();
                    }
                } else {
                    _log("input buffer not available");
                }
            }

            // Assume output is available.  Loop until both assumptions are false.
            boolean decoderOutputAvailable = !decoderDone;
            boolean encoderOutputAvailable = true;

            LOOP:
            while (decoderOutputAvailable || encoderOutputAvailable) {
                // Start by draining any pending output from the encoder.  It's important to
                // do this before we try to stuff any more data in.
                int encoderStatus = encoder.dequeueOutputBuffer(encoderBufferInfo, TIMEOUT_USEC);
                if (encoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    // no output available yet
                    _log("no output from encoder available");
                    encoderOutputAvailable = false;
                } else if (encoderStatus == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                    encoderOutputBuffers = encoder.getOutputBuffers();
                    _log("encoder output buffers changed");
                } else if (encoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if (muxerStarted) {
                        throw new RuntimeException("encoder output format changed twice");
                    }
                    MediaFormat newFormat = encoder.getOutputFormat();
                    _log("encoder output format changed: " + newFormat);
                    muxerTrackIndex = muxer.addTrack(newFormat);
                    preparedEncoderCount++;

                    if (preparedEncoderCount == encoders.size()) {
                        _log("output format: starting muxer with " + encoders.size() + " encoders");
                        muxer.start();
                        muxerStarted = true;
                    } else {
                        synchronized (muxer) {
                            while (!muxerStarted) {
                                try {
                                    muxer.wait(100);
                                } catch (InterruptedException e) {
                                    break LOOP;
                                }
                            }
                        }
                    }
                } else if (encoderStatus < 0) {
                    throw new RuntimeException("unexpected result from encoder.dequeueOutputBuffer: " + encoderStatus);
                } else { // encoderStatus >= 0
                    ByteBuffer encodedData = encoderOutputBuffers[encoderStatus];
                    if (encodedData == null || encoderBufferInfo == null) {
                        throw new RuntimeException("encoderOutputBuffer " + encoderStatus + " was null");
                    }

                    boolean ignoreData = false;
                    if ((encoderBufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                        // The codec config data was pulled out and fed to the muxer when we got
                        // the INFO_OUTPUT_FORMAT_CHANGED status.  Ignore it.
                        _log("ignoring BUFFER_FLAG_CODEC_CONFIG");
                        encoderBufferInfo.size = 0;
                        ignoreData = true;
                    }

                    if (encoderBufferInfo.size > 0) {
                        if (!muxerStarted) {
                            throw new RuntimeException("muxer hasn't started");
                        }
                        // adjust the ByteBuffer values to match BufferInfo (not needed?)
                        encodedData.position(encoderBufferInfo.offset);
                        encodedData.limit(encoderBufferInfo.offset + encoderBufferInfo.size);

                        muxer.writeSampleData(muxerTrackIndex, encodedData, encoderBufferInfo);
                    } else {
                        if (!ignoreData) {
                            outputDone = true;
                        }
                    }

                    if (!outputDone) {
                        outputDone = (encoderBufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    }
                    encoder.releaseOutputBuffer(encoderStatus, false);
                }

                if (encoderStatus != MediaCodec.INFO_TRY_AGAIN_LATER) {
                    // Continue attempts to drain output.
                    continue;
                }

                // Encoder is drained, check to see if we've got a new frame of output from the decoder.
                if (!decoderDone) {
                    _log("getting decoder buffer");
                    int decoderStatus = decoder.dequeueOutputBuffer(encoderBufferInfo, TIMEOUT_USEC);
                    _log("got buffer");
                    if (decoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER) {
                        _log("no decoder output available");
                        decoderOutputAvailable = false;
                    } else if (decoderStatus == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                        _log("decoder output buffers changed");
                        decoderOutputBuffers = decoder.getOutputBuffers();
                    } else if (decoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        // expected before first buffer of data
                        _log("decoder output format changed: " + decoder.getOutputFormat());
                    } else if (decoderStatus < 0) {
                        throw new RuntimeException("unexpected result from decoder.dequeueOutputBuffer: " + decoderStatus);
                    } else { // decoderStatus >= 0
                        if (encoderBufferInfo.presentationTimeUs >= fromUs || (encoderBufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            int encoderInputStatus = encoder.dequeueInputBuffer(TIMEOUT_USEC);
                            if (encoderInputStatus >= 0) {
                                if (encoderBufferInfo.size > 0) {
                                    ByteBuffer decodedData = decoderOutputBuffers[decoderStatus];
                                    ByteBuffer encoderInputBuf = encoderInputBuffers[encoderInputStatus];
                                    decodedData.position(encoderBufferInfo.offset);
                                    decodedData.limit(encoderBufferInfo.size);
                                    encoderInputBuf.clear();
                                    encoderInputBuf.limit(encoderBufferInfo.size);
                                    encoderInputBuf.put(decodedData);
                                    encoder.queueInputBuffer(encoderInputStatus, 0, encoderInputBuf.position(), encoderBufferInfo.presentationTimeUs, encoderBufferInfo.flags);
                                } else {
                                    if ((encoderBufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0 || encoderBufferInfo.presentationTimeUs > toUs) {
                                        encoder.queueInputBuffer(encoderInputStatus, 0, 0, encoderBufferInfo.presentationTimeUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                                        decoderDone = true;
                                    }
                                }
                            } else {
                                _log("no encoder input available");
                            }
                        } else {
                            _log("skipping");
                        }

                        decoder.releaseOutputBuffer(decoderStatus, false);
                    }
                } else {
                    decoderOutputAvailable = false;
                }
            }
        }

        encoder.stop();
        encoder.release();
        decoder.stop();
        decoder.release();

        doneCount++;
        if (doneCount == encoders.size()) {
            Log.e(TAG, "End");
            Log.e(TAG, "Took " + (System.currentTimeMillis() - start) + " msecs");
            extractor.release();
            muxer.release();
            callback.onCompleted(dest);
        }
    }

    private boolean maybeInitCodecs(MediaExtractor extractor, int trackNumber) throws IOException {
        MediaFormat format = extractor.getTrackFormat(trackNumber);
        String mime = Optional.ofNullable(format.getString(MediaFormat.KEY_MIME)).orElse("");

        _log("Mime " + mime);

        boolean isAudioTrack = mime.contains("audio");
        if (isAudioTrack) {
            initDecoder(format, mime, trackNumber);
            initAudioEncoder(format, trackNumber);
            return true;
        }

        boolean isVideoTrack = mime.contains("video");
        if (isVideoTrack) {
            initDecoder(format, mime, trackNumber);
            initVideoEncoder(format, trackNumber);
            return true;
        }
        _log("skipping track with mime " + mime);
        return false;
    }

    private void initDecoder(@NonNull MediaFormat format, @NonNull String mime, int trackNumber) throws IOException {
        MediaCodec decoder = MediaCodec.createDecoderByType(mime);
        decoder.configure(format, null, null, 0);
        decoders.put(trackNumber, decoder);
    }

    private void initAudioEncoder(@NonNull MediaFormat format, int trackNumber) throws IOException {
        int sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE);
        int channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
        int bitRate = 320 * 1024;
        if (format.containsKey(MediaFormat.KEY_BIT_RATE)) {
            bitRate = format.getInteger(MediaFormat.KEY_BIT_RATE);
        }

        int aacProfile = 2;
        if (format.containsKey(MediaFormat.KEY_AAC_PROFILE)) {
            aacProfile = format.getInteger(MediaFormat.KEY_AAC_PROFILE);
        }

        String outMime = MIME_AAC;
        MediaFormat outFormat = MediaFormat.createAudioFormat(outMime, sampleRate, channelCount);
        outFormat.setInteger(MediaFormat.KEY_BIT_RATE, bitRate);
        outFormat.setInteger(MediaFormat.KEY_AAC_PROFILE, aacProfile);
        outFormat.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, MAX_SAMPLE_SIZE);

        MediaCodec encoder = MediaCodec.createEncoderByType(outMime);
        encoder.configure(outFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        encoders.put(trackNumber, encoder);
    }

    private void initVideoEncoder(@NonNull MediaFormat format, int trackNumber) throws IOException {
        int width = format.getInteger(MediaFormat.KEY_WIDTH);
        int height = format.getInteger(MediaFormat.KEY_HEIGHT);
        int frameRate = format.getInteger(MediaFormat.KEY_FRAME_RATE);
        int bitRate = 3 * 1024 * 1024;
        if (format.containsKey(MediaFormat.KEY_BIT_RATE)) {
            bitRate = format.getInteger(MediaFormat.KEY_BIT_RATE);
        }

        String outMime = MIME_H264;
        MediaFormat outFormat = MediaFormat.createVideoFormat(outMime, width, height);
        outFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar);
        outFormat.setInteger(MediaFormat.KEY_FRAME_RATE, frameRate);
        outFormat.setInteger(MediaFormat.KEY_BIT_RATE, bitRate);
        outFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL);
        outFormat.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 0);

        MediaCodec encoder = MediaCodec.createEncoderByType(outMime);
        encoder.configure(outFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        encoders.put(trackNumber, encoder);
    }

    private void checkOutputFile(String dest) {
        Assertion.check(() -> dest != null);
        File output = new File(dest);
        Assertion.check(() -> !output.exists());
    }

    private void checkTimeParams(long from, long to) {
        Assertion.check(() -> to > from);
    }

    private void checkInputFile(Uri src) {
        Assertion.check(() -> src != null);
        File input = new File(URI.create(String.valueOf(src)));
        Assertion.check(() -> input.exists() && input.canRead());
    }

    @Override
    public void uncaughtException(Thread thread, Throwable throwable) {
        Log.e(TAG, "Exception in worker thread: " + throwable.getMessage());
        Optional.ofNullable(callback)
                .ifPresent(Callback::onError);
    }
}
