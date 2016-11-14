package com.example.antonprozorov.mediacodecvideocutting;

import android.Manifest;
import android.content.Intent;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Environment;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.view.View;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import com.jakewharton.rxbinding.view.RxView;
import com.jakewharton.rxbinding.widget.RxSeekBar;
import com.jakewharton.rxbinding.widget.RxTextView;
import com.tbruyelle.rxpermissions.RxPermissions;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import fr.enoent.videokit.Videokit;
import rx.Subscriber;
import rx.subjects.BehaviorSubject;

public class MainActivity extends AppCompatActivity {

    private static final int REQUEST_CODE_PICK_VIDEO = 666;
    private static final String TEMP_DIR = Environment.getExternalStorageDirectory().getAbsolutePath() + "/MediaCodecTemp/";
    private ExecutorService executor = Executors.newSingleThreadExecutor();
    private BehaviorSubject<Object> waitOnDestroy;
    private TextView filenameTv;
    private View selectFileBtn;
    private TextView startTextView;
    private SeekBar startSeekBar;
    private TextView endTextView;
    private SeekBar endSeekBar;
    private View ffmpegBtn;
    private View mediaCodecBtn;
    private View progressBar;
    private TextView messages;

    private String filename;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        waitOnDestroy = BehaviorSubject.create();
        setContentView(R.layout.activity_main);

        new File(TEMP_DIR).mkdirs();

        filenameTv = (TextView) findViewById(R.id.filename);
        selectFileBtn = findViewById(R.id.selectFileButton);
        startTextView = (TextView) findViewById(R.id.startTextView);
        startSeekBar = (SeekBar) findViewById(R.id.startSeekBar);
        endTextView = (TextView) findViewById(R.id.endTextView);
        endSeekBar = (SeekBar) findViewById(R.id.endSeekBar);
        ffmpegBtn = findViewById(R.id.ffButton);
        mediaCodecBtn = findViewById(R.id.mcButton);
        progressBar = findViewById(R.id.progressBar);
        messages = (TextView) findViewById(R.id.messages);

        startTextView.setVisibility(View.GONE);
        startSeekBar.setVisibility(View.GONE);
        endTextView.setVisibility(View.GONE);
        endSeekBar.setVisibility(View.GONE);
        ffmpegBtn.setVisibility(View.GONE);
        mediaCodecBtn.setVisibility(View.GONE);
        progressBar.setVisibility(View.GONE);

        RxView.clicks(selectFileBtn)
                .takeUntil(waitOnDestroy)
                .subscribe(v -> selectFile());

        RxTextView.textChanges(filenameTv)
                .takeUntil(waitOnDestroy)
                .filter(s -> fileValid(s))
                .subscribe(s -> proceed());

        RxView.clicks(mediaCodecBtn)
                .takeUntil(waitOnDestroy)
                .subscribe(v -> cutWithMediaCodec());

        RxView.clicks(ffmpegBtn)
                .takeUntil(waitOnDestroy)
                .subscribe(v -> cutWithFFMpeg());

        RxSeekBar.changes(startSeekBar)
                .takeUntil(waitOnDestroy)
                .subscribe(i -> startTextView.setText("Start " + i + " s"));

        RxSeekBar.changes(endSeekBar)
                .takeUntil(waitOnDestroy)
                .subscribe(i -> endTextView.setText("End " + i + " s, duration " + (i - startSeekBar.getProgress()) + " s"));

        RxPermissions.getInstance(this).request(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                .takeUntil(waitOnDestroy)
                .subscribe(granted -> {
                    if (!granted) {
                        Toast.makeText(this, "You must grant access to storage", Toast.LENGTH_SHORT).show();
                    }
                });
    }

    private void cutWithMediaCodec() {
        String mc = TEMP_DIR + "/media-codec.mp4";

        new File(mc).delete();
        long from = startSeekBar.getProgress() * 1000;
        long to = endSeekBar.getProgress() * 1000;

        long start = System.currentTimeMillis();
        progressBar.setVisibility(View.VISIBLE);
        MediaCodecHelper mch = new MediaCodecHelper(v -> cuttingDone("MediaCodec", start));
        executor.execute(new CutRunnable(mch, filename, mc, from, to));
    }

    private void cutWithFFMpeg() {
        String ff = TEMP_DIR + "/ffmpeg.mp4";

        new File(ff).delete();
        long from = startSeekBar.getProgress() * 1000;
        long to = endSeekBar.getProgress() * 1000;

        String argv[] = {"-y",
                "-i",
                filename,
                "-ss",
                StringUtils.getFfmpegTime(from),
                "-to",
                StringUtils.getFfmpegTime(to),
                "-c:a", "copy", "-qscale:v", "1",
                ff};
        progressBar.setVisibility(View.VISIBLE);
        executor.execute(() -> {
            long start = System.currentTimeMillis();
            Videokit.getInstance().process(argv);
            cuttingDone("FFMpeg", start);
        });
    }

    private void cuttingDone(String method, long start) {
        runOnUiThread(() -> {
            progressBar.setVisibility(View.GONE);
            messages.setText(messages.getText()
                    + "\n"
                    + method + " cutting done. Took " + (System.currentTimeMillis() - start) + " msecs");
        });
    }

    private void showViews() {
        startTextView.setVisibility(View.VISIBLE);
        startSeekBar.setVisibility(View.VISIBLE);
        endTextView.setVisibility(View.VISIBLE);
        endSeekBar.setVisibility(View.VISIBLE);
        ffmpegBtn.setVisibility(View.VISIBLE);
        mediaCodecBtn.setVisibility(View.VISIBLE);
    }

    private boolean fileValid(CharSequence path) {
        File file = new File(String.valueOf(path));
        return file.exists() && file.canRead();
    }

    private void selectFile() {
        Intent intent = new Intent();
        intent.setType("video/*");
        intent.setAction(Intent.ACTION_GET_CONTENT);
        intent.putExtra(Intent.EXTRA_LOCAL_ONLY, true);
        startActivityForResult(Intent.createChooser(intent, "Select video"),
                REQUEST_CODE_PICK_VIDEO);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_CODE_PICK_VIDEO && resultCode == RESULT_OK) {
            Uri uri = Optional.ofNullable(data)
                    .map(d -> d.getData())
                    .orElse(Uri.EMPTY);
            LocalFileGrabber lfg = new LocalFileGrabber.Builder(this)
                    .setUri(uri)
                    .setDestination(TEMP_DIR + "source.mp4")
                    .build();
            lfg.setProgressListener(new IFileGrabber.IProgressListener() {
                @Override
                public void onStarted(String id) {
                    progressBar.setVisibility(View.VISIBLE);
                }

                @Override
                public void onCompleted(String dest, String id) {
                    filename = dest;
                    filenameTv.setText(dest);
                    progressBar.setVisibility(View.GONE);
                }

                @Override
                public void onError(Exception e) {
                    progressBar.setVisibility(View.GONE);
                    messages.setText(messages.getText() + "\n" + "Something went wrong");
                }

                @Override
                public void onProgressUpdate(float percents) {

                }

                @Override
                public void onCancelled() {

                }
            });

            new Thread(lfg).start();
        }
    }

    private void proceed() {
        showViews();
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        retriever.setDataSource(filename);
        Long duration = Long.parseLong(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
        int startSecsMax = (int) ((duration - 1000) / 1000);
        startSeekBar.setMax(startSecsMax);
        startSeekBar.setProgress(0);
        int endSecsMax = (int) (duration / 1000);
        endSeekBar.setMax(endSecsMax);
        endSeekBar.setProgress(endSecsMax);
    }

    private class CutRunnable implements Runnable {
        private final MediaCodecHelper mch;
        private final String src;
        private final String dest;
        private final long from;
        private final long to;

        public CutRunnable(MediaCodecHelper mch, String src, String dest, long from, long to) {
            this.mch = mch;
            this.src = src;
            this.dest = dest;
            this.from = from;
            this.to = to;
        }

        @Override
        public void run() {
            try {
                mch.cutVideo(getApplicationContext(), Uri.fromFile(new File(src)), dest, from, to);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        waitOnDestroy.onNext(null);
    }
}
