package com.sreality.dashboard.util;

import java.util.List;

/**
 * Pearson product-moment correlation coefficient.
 *
 * <p>Returns {@code null} when fewer than two points are supplied, when
 * the two series have different lengths, or when either series is
 * constant (variance == 0). Matches the behavior of the Python port.</p>
 */
public final class Pearson {

    private Pearson() {}

    public static Double correlation(List<Double> xs, List<Double> ys) {
        int n = xs.size();
        if (n < 2 || n != ys.size()) return null;

        double meanX = 0, meanY = 0;
        for (int i = 0; i < n; i++) {
            meanX += xs.get(i);
            meanY += ys.get(i);
        }
        meanX /= n;
        meanY /= n;

        double sxx = 0, syy = 0, sxy = 0;
        for (int i = 0; i < n; i++) {
            double dx = xs.get(i) - meanX;
            double dy = ys.get(i) - meanY;
            sxx += dx * dx;
            syy += dy * dy;
            sxy += dx * dy;
        }
        if (sxx == 0 || syy == 0) return null;
        return sxy / Math.sqrt(sxx * syy);
    }
}
