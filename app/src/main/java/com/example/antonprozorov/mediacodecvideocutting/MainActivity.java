package com.example.antonprozorov.mediacodecvideocutting;

import android.Manifest;
import android.os.Environment;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.widget.TextView;

import com.tbruyelle.rxpermissions.RxPermissions;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private ExecutorService executor = Executors.newSingleThreadExecutor();
    private TextView message;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        message = (TextView) findViewById(R.id.message);
        RxPermissions.getInstance(this).request(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                .subscribe(granted -> {
                    if (granted) {
                        proceed();
                    } else {
                        message.setText("You must grant access to storage");
                    }
                });
    }

    private void proceed() {
        String src = Environment.getExternalStorageDirectory().getAbsolutePath() + "/video.mp4";
        String dest = Environment.getExternalStorageDirectory().getAbsolutePath() + "/cut.mp4";
        new File(dest).delete();
        long from = 15000;
        long to = 25000;

        MediaCodecHelper mch = new MediaCodecHelper();
        executor.execute(new CutRunnable(mch, src, dest, from, to));
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
                mch.cutVideo(getApplicationContext(), src, dest, from, to);
                runOnUiThread(() -> message.setText("Done"));
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }
}
