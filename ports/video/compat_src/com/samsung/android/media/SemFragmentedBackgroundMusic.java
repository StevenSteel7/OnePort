package com.samsung.android.media;

import java.io.FileDescriptor;

public final class SemFragmentedBackgroundMusic extends SemBackgroundMusic {
    public SemFragmentedBackgroundMusic() {}
    public int addBody(FileDescriptor descriptor, int offset, int length) { return 0; }
    public void setIntro(FileDescriptor descriptor, int offset, int length) {}
    public void setOutro(FileDescriptor descriptor, int offset, int length) {}
    public void setPlaybackRule(int start, int end, boolean looping) {}
}
