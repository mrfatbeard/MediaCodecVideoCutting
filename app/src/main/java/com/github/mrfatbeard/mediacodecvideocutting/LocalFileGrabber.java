package com.github.mrfatbeard.mediacodecvideocutting;

import android.content.Context;
import android.net.Uri;
import android.support.annotation.NonNull;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;

public class LocalFileGrabber extends AbstractFileGrabber {
    private Uri src;
    private Context context;

    @Override
    public void run() {
        File tmpFile = new File(dest);
        FileOutputStream fos = null;
        InputStream is = null;
        try {
            tmpFile.createNewFile();
            fos = new FileOutputStream(tmpFile);
            is = context.getContentResolver().openInputStream(src);

            total = is.available();

            onStarted();

            byte[] buffer = new byte[1024];
            int count;
            new UpdaterThread().start();

            while ((count = is.read(buffer)) > 0 && !(cancel)) {
                current += count;
                fos.write(buffer, 0, count);
            }
            if (!cancel) {
                onSuccess();
            } else {
                onCancelled();
            }
        } catch (IOException e) {
            error = e;
            onError();
        } finally {
            kill = true;
            if (fos != null) {
                try {
                    fos.flush();
                    fos.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
            if (is != null) {
                try {
                    is.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        if (!super.equals(o)) return false;
        LocalFileGrabber that = (LocalFileGrabber) o;
        return src.equals(that.src);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(new Object[] {super.hashCode(), src});
    }

    public static class Builder {
        private Uri uri;
        private String dest;
        private String id;
        private Context context;

        public Builder(@NonNull Context context) {
            this.context = context.getApplicationContext();
        }

        public Builder setUri(Uri uri) {
            this.uri = uri;
            this.id = uri.toString();
            return this;
        }

        public Builder setDestination(String destination) {
            this.dest = destination;
            return this;
        }

        public LocalFileGrabber build() {
            LocalFileGrabber result = new LocalFileGrabber();
            if (uri == null) {
                throw new RuntimeException("Uri not set");
            }

            if (dest == null) {
                throw new RuntimeException("Destination not set");
            }
            result.context = context;
            result.src = uri;
            result.dest = dest;
            result.id = id;

            return result;
        }
    }
}
