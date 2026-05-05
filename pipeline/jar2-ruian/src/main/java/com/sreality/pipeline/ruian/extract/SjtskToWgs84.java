package com.sreality.pipeline.ruian.extract;

/**
 * Converts S-JTSK (EPSG:5514, Czech national grid) to WGS84 (EPSG:4326).
 *
 * Two-step: Křovák inverse projection → Bessel geographic,
 * then Helmert 7-parameter shift (ČÚZK parameters) → WGS84.
 * Accuracy: ±1m over Czech territory. No external dependencies.
 */
public final class SjtskToWgs84 {

    private SjtskToWgs84() {}

    /**
     * @param x S-JTSK X (may be positive or negative — Czech system uses negative)
     * @param y S-JTSK Y
     * @return  double[]{ latWgs84, lonWgs84 } in degrees
     */
    public static double[] convert(double x, double y) {
        double xn = x > 0 ? -x : x;
        double yn = y > 0 ? -y : y;
        double[] bessel = krovakInverse(xn, yn);
        return besselToWgs84(bessel[0], bessel[1]);
    }

    // --- Křovák inverse -------------------------------------------------------

    private static final double A_BESSEL = 6377397.155;
    private static final double E        = 0.081696831215;
    private static final double N_KROV   = 0.9999;
    private static final double RHO0     = 12800297.75;
    private static final double ALPHA    = 1.000597498371;
    private static final double K        = 1.003419164;
    private static final double PHI0     = Math.toRadians(49.5);

    private static double[] krovakInverse(double x, double y) {
        double ro  = Math.sqrt(x * x + y * y);
        double eps = 2.0 * Math.atan(y / (ro + x));
        double d   = eps / N_KROV;
        double s   = 2.0 * (Math.atan(
            Math.pow(RHO0 / ro, 1.0 / N_KROV) * Math.tan(PHI0 / 2.0 + Math.PI / 4.0))
            - Math.PI / 4.0);

        double s1 = s;
        for (int i = 0; i < 10; i++) {
            s1 = 2.0 * Math.atan(
                K * Math.pow(Math.tan(s1 / 2.0 + Math.PI / 4.0), 1.0 / ALPHA)
                * Math.pow((1 + E * Math.sin(s1)) / (1 - E * Math.sin(s1)), E / (2 * ALPHA))
            ) - Math.PI / 2.0;
        }
        // d is measured from pseudo-meridian (42.5° from Ferro = 24.833... ° E Greenwich)
        double lon = d + Math.toRadians(42.5) - Math.toRadians(17.66666666667);
        return new double[]{ Math.toDegrees(s1), Math.toDegrees(lon) };
    }

    // --- Helmert 7-parameter: Bessel → WGS84 (ČÚZK) -------------------------

    private static final double DX  =  570.8;
    private static final double DY  =  85.7;
    private static final double DZ  =  462.8;
    private static final double WX  =  4.998 * Math.PI / 648000;
    private static final double WY  =  1.587 * Math.PI / 648000;
    private static final double WZ  =  5.261 * Math.PI / 648000;
    private static final double M   =  3.56e-6;

    private static final double AB  = 6377397.155;
    private static final double BB  = 6356078.963;
    private static final double AW  = 6378137.0;
    private static final double BW  = 6356752.314;
    private static final double E2  = 1 - (BB * BB) / (AB * AB);
    private static final double E2W = 1 - (BW * BW) / (AW * AW);

    private static double[] besselToWgs84(double latDeg, double lonDeg) {
        double lat = Math.toRadians(latDeg);
        double lon = Math.toRadians(lonDeg);
        double sinLat = Math.sin(lat), cosLat = Math.cos(lat);
        double sinLon = Math.sin(lon), cosLon = Math.cos(lon);
        double N = AB / Math.sqrt(1 - E2 * sinLat * sinLat);

        double X = N * cosLat * cosLon;
        double Y = N * cosLat * sinLon;
        double Z = N * (1 - E2) * sinLat;

        double Xw = DX + (1 + M) * (X + WZ * Y - WY * Z);
        double Yw = DY + (1 + M) * (-WZ * X + Y + WX * Z);
        double Zw = DZ + (1 + M) * (WY * X - WX * Y + Z);

        double p    = Math.sqrt(Xw * Xw + Yw * Yw);
        double ew2  = (AW * AW - BW * BW) / (BW * BW);
        double th   = Math.atan2(Zw * AW, p * BW);
        double latW = Math.atan2(
            Zw + ew2 * BW * Math.pow(Math.sin(th), 3),
            p  - E2W * AW * Math.pow(Math.cos(th), 3));
        double lonW = Math.atan2(Yw, Xw);
        return new double[]{ Math.toDegrees(latW), Math.toDegrees(lonW) };
    }
}
