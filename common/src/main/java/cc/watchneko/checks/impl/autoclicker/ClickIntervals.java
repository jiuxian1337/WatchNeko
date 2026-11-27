package cc.watchneko.checks.impl.autoclicker;

final class ClickIntervals {
    private final double[] milliseconds;
    private final double minimumGap;
    private final double maximumGap;
    private int count;
    private boolean anchored;
    private long lastClick;

    ClickIntervals(int size, double minimumGap, double maximumGap) {
        this.milliseconds = new double[size];
        this.minimumGap = minimumGap;
        this.maximumGap = maximumGap;
    }

    int add(long now) {
        double gap = (now - lastClick) / 1_000_000.0;
        lastClick = now;
        if (!anchored || gap < minimumGap || gap > maximumGap) {
            count = 0;
            anchored = true;
            return 0;
        }
        if (count == milliseconds.length) count = 0;
        milliseconds[count++] = gap;
        return count;
    }

    double[] completedWindow() {
        return count == milliseconds.length ? milliseconds : null;
    }

    void reset() {
        anchored = false;
        count = 0;
    }

    static double deviation(double sum, double squares, int count) {
        double mean = sum / count;
        return Math.sqrt(Math.max(0, squares / count - mean * mean));
    }
}
