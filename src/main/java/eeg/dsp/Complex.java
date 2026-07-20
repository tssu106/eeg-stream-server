package eeg.dsp;

record Complex(double re, double im) {

    static final Complex ZERO = new Complex(0, 0);
    static final Complex ONE = new Complex(1, 0);

    static Complex of(double re) {
        return new Complex(re, 0);
    }

    Complex add(Complex o) {
        return new Complex(re + o.re, im + o.im);
    }

    Complex sub(Complex o) {
        return new Complex(re - o.re, im - o.im);
    }

    Complex mul(Complex o) {
        return new Complex(re * o.re - im * o.im, re * o.im + im * o.re);
    }

    Complex mul(double s) {
        return new Complex(re * s, im * s);
    }

    Complex div(Complex o) {
        double denom = o.re * o.re + o.im * o.im;
        return new Complex((re * o.re + im * o.im) / denom, (im * o.re - re * o.im) / denom);
    }

    Complex sqrt() {
        double r = Math.hypot(re, im);
        double rePart = Math.sqrt((r + re) / 2);
        double imPart = Math.signum(im == 0 ? 1 : im) * Math.sqrt((r - re) / 2);
        return new Complex(rePart, imPart);
    }

    double abs() {
        return Math.hypot(re, im);
    }
}
