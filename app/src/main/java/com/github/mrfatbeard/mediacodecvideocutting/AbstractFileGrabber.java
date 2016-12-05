package com.github.mrfatbeard.mediacodecvideocutting;

import android.os.Handler;
import android.os.Looper;
import android.os.Message;

public abstract class AbstractFileGrabber implements IFileGrabber {
    protected IProgressListener listener;
    protected String id;
    protected String dest;
    protected long current;
    protected long total;
    protected Exception error;
    protected static final InternalHandler internalHandler = new InternalHandler();

    protected volatile boolean kill;
    protected volatile boolean cancel;

    @Override
    public void setProgressListener(IProgressListener listener) {
        this.listener = listener;
    }

    @Override
    public float getPercents() {
        return ((float) current / (float) total * 100f);
    }

    @Override
    public void cancel() {
        cancel = true;
    }

    protected void onStarted() {
        internalHandler.obtainMessage(InternalHandler.MESSAGE_STARTED, new TaskResult(this)).sendToTarget();
    }

    protected void onSuccess() {
        internalHandler.obtainMessage(InternalHandler.MESSAGE_DONE, new TaskResult(this)).sendToTarget();
    }

    protected void onError() {
        internalHandler.obtainMessage(InternalHandler.MESSAGE_ERROR, new TaskResult(this)).sendToTarget();
    }

    protected void onCancelled() {
        internalHandler.obtainMessage(InternalHandler.MESSAGE_CANCELLED, new TaskResult(this)).sendToTarget();
    }

    protected void clean() {
        listener = null;
    }

    protected static class TaskResult {
        public final AbstractFileGrabber task;
        public TaskResult(AbstractFileGrabber localFileGrabber) {
            this.task = localFileGrabber;
        }
    }

    protected class UpdaterThread extends Thread {
        @Override
        public void run() {
            while (!kill && listener != null) {
                internalHandler.obtainMessage(InternalHandler.MESSAGE_PROGRESS,
                        new TaskResult(AbstractFileGrabber.this)).sendToTarget();
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        AbstractFileGrabber that = (AbstractFileGrabber) o;
        return dest.equals(that.dest);
    }

    @Override
    public int hashCode() {
        return dest.hashCode();
    }

    private static class InternalHandler extends Handler {
        public final static int MESSAGE_STARTED = 0;
        public final static int MESSAGE_PROGRESS = 1;
        public final static int MESSAGE_DONE = 2;
        public final static int MESSAGE_ERROR = 3;
        public final static int MESSAGE_CANCELLED = 4;

        public InternalHandler() {
            super(Looper.getMainLooper());
        }

        @Override
        public void handleMessage(Message msg) {
            try {
                TaskResult result = (TaskResult) msg.obj;
                switch (msg.what) {
                    case MESSAGE_STARTED:
                        result.task.listener.onStarted(result.task.id);
                    case MESSAGE_PROGRESS:
                        result.task.listener.onProgressUpdate(result.task.getPercents());
                        break;
                    case MESSAGE_DONE:
                        result.task.listener.onCompleted(result.task.dest, result.task.id);
                        result.task.clean();
                        break;
                    case MESSAGE_ERROR:
                        result.task.listener.onError(result.task.error);
                        break;
                    case MESSAGE_CANCELLED:
                        result.task.listener.onCancelled();
                        break;
                }
            } catch (NullPointerException ignored) {

            }
        }
    }
}