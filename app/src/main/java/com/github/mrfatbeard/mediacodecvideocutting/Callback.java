package com.github.mrfatbeard.mediacodecvideocutting;

public interface Callback {
    void onStarted();
    void onError();
    void onCompleted(String dest);
}
