package io.freemytv;

/** Size a complete frame inside the available space, without cropping or stretching. */
final class VideoFit {
    private VideoFit() {}

    static int[] size(int videoWidth, int videoHeight, int width, int height) {
        width = Math.max(1, width);
        height = Math.max(1, height);
        if (videoWidth <= 0 || videoHeight <= 0) return new int[] {width, height};
        if ((long) videoWidth * height > (long) videoHeight * width) {
            return new int[] {width, Math.max(1, (int) ((long) width * videoHeight / videoWidth))};
        }
        return new int[] {Math.max(1, (int) ((long) height * videoWidth / videoHeight)), height};
    }
}
