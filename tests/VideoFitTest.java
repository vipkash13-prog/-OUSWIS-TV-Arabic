package io.freemytv;

public final class VideoFitTest {
    private static void fit(int vw, int vh, int w, int h, int ew, int eh) {
        int[] got = VideoFit.size(vw, vh, w, h);
        if (got[0] != ew || got[1] != eh) throw new AssertionError(got[0] + "x" + got[1]);
        if (got[0] > w || got[1] > h) throw new AssertionError("Cropped frame");
    }
    public static void main(String[] args) {
        fit(1920,1080,1920,1080,1920,1080);
        fit(640,480,1920,1080,1440,1080);
        fit(1920,800,1920,1080,1920,800);
        fit(1920,1080,640,320,568,320);
        fit(640,480,640,320,426,320);
        fit(0,0,1280,720,1280,720);
        System.out.println("Video fit: wide, 4:3, 16:9 and small screens preserve the complete frame.");
    }
}
