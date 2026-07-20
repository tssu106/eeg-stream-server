package eeg.dsp;

/**
 * Digital Butterworth bandpass IIR filter, designed by bilinear-transforming an
 * analog lowpass prototype (matches scipy.signal.butter(N, [low, high], btype='bandpass', fs=fs)).
 * Applied causally (direct form II transposed), matching MNE's raw.filter(method='iir', phase='forward').
 */
public final class ButterworthFilter {

    public final double[] b;
    public final double[] a;

    private ButterworthFilter(double[] b, double[] a) {
        double a0 = a[0];
        this.b = new double[b.length];
        this.a = new double[a.length];
        for (int i = 0; i < b.length; i++) this.b[i] = b[i] / a0;
        for (int i = 0; i < a.length; i++) this.a[i] = a[i] / a0;
    }

    public static ButterworthFilter designBandpass(int order, double lowHz, double highHz, double fs) {
        Complex[] protoPoles = new Complex[order];
        for (int k = 0; k < order; k++) {
            double theta = Math.PI * (2.0 * k + 1) / (2.0 * order);
            protoPoles[k] = new Complex(-Math.sin(theta), Math.cos(theta));
        }

        double warpedLow = 2 * fs * Math.tan(Math.PI * lowHz / fs);
        double warpedHigh = 2 * fs * Math.tan(Math.PI * highHz / fs);
        double bw = warpedHigh - warpedLow;
        double wo = Math.sqrt(warpedLow * warpedHigh);
        Complex woSq = Complex.of(wo * wo);

        Complex[] pLp = new Complex[order];
        for (int i = 0; i < order; i++) pLp[i] = protoPoles[i].mul(bw / 2);

        Complex[] pBp = new Complex[order * 2];
        Complex[] zBp = new Complex[order];
        for (int i = 0; i < order; i++) {
            Complex disc = pLp[i].mul(pLp[i]).sub(woSq).sqrt();
            pBp[i] = pLp[i].add(disc);
            pBp[order + i] = pLp[i].sub(disc);
            zBp[i] = Complex.ZERO;
        }
        double kBp = Math.pow(bw, order);

        double fs2 = 2 * fs;
        Complex[] zDigital = new Complex[pBp.length];
        Complex[] pDigital = new Complex[pBp.length];
        for (int i = 0; i < zBp.length; i++) {
            zDigital[i] = Complex.of(fs2).add(zBp[i]).div(Complex.of(fs2).sub(zBp[i]));
        }
        for (int i = zBp.length; i < pBp.length; i++) {
            zDigital[i] = Complex.of(-1);
        }
        for (int i = 0; i < pBp.length; i++) {
            pDigital[i] = Complex.of(fs2).add(pBp[i]).div(Complex.of(fs2).sub(pBp[i]));
        }

        Complex numProd = Complex.ONE;
        for (Complex z : zBp) numProd = numProd.mul(Complex.of(fs2).sub(z));
        Complex denProd = Complex.ONE;
        for (Complex p : pBp) denProd = denProd.mul(Complex.of(fs2).sub(p));
        double kDigital = kBp * numProd.div(denProd).re();

        double[] bPoly = polyFromRoots(zDigital);
        double[] aPoly = polyFromRoots(pDigital);
        for (int i = 0; i < bPoly.length; i++) bPoly[i] *= kDigital;

        return new ButterworthFilter(bPoly, aPoly);
    }

    private static double[] polyFromRoots(Complex[] roots) {
        Complex[] coeffs = {Complex.ONE};
        for (Complex r : roots) {
            Complex[] next = new Complex[coeffs.length + 1];
            for (int i = 0; i < next.length; i++) next[i] = Complex.ZERO;
            for (int i = 0; i < coeffs.length; i++) {
                next[i] = next[i].add(coeffs[i]);
                next[i + 1] = next[i + 1].sub(coeffs[i].mul(r));
            }
            coeffs = next;
        }
        double[] real = new double[coeffs.length];
        for (int i = 0; i < coeffs.length; i++) real[i] = coeffs[i].re();
        return real;
    }

    /** Zero-initialized filter state (direct form II transposed delay line). */
    public double[] newState() {
        return new double[Math.max(b.length, a.length) - 1];
    }

    /** Applies the filter causally to {@code x}, reading and updating {@code state} in place. */
    public double[] apply(double[] x, double[] state) {
        int k = state.length;
        double[] y = new double[x.length];
        for (int n = 0; n < x.length; n++) {
            double xn = x[n];
            double yn = b[0] * xn + (k > 0 ? state[0] : 0);
            for (int i = 0; i < k; i++) {
                double bi = i + 1 < b.length ? b[i + 1] : 0;
                double ai = i + 1 < a.length ? a[i + 1] : 0;
                double carry = i + 1 < k ? state[i + 1] : 0;
                state[i] = bi * xn - ai * yn + carry;
            }
            y[n] = yn;
        }
        return y;
    }
}
