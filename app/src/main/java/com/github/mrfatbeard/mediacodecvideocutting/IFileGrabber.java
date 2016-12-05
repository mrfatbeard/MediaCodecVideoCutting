package com.github.mrfatbeard.mediacodecvideocutting;

public interface IFileGrabber extends Runnable {
    void setProgressListener(IProgressListener listener);
    float getPercents();
    void cancel();

    interface IProgressListener {
        void onStarted(String id);
        void onCompleted(String dest, String id);
        void onError(Exception e);
        void onProgressUpdate(float percents);
        void onCancelled();
    }
}
