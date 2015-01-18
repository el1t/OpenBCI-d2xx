package com.el1t.openbci_d2xx;

import android.util.Log;

import java.util.Arrays;

import ddf.minim.analysis.FFT;

/**
 * Created by Lucas on 1/17/15.
 */
public class AlphaDetector {

    private final static String TAG = "OpenBCI AlphaDetector";

    private BrainStateCallback mCallback;

    final int framesperupdate = 50; //update after this many data points.
    int nchan;
    float[][] dataBuffY_uV;
    float[][] yLittleBuff_uV;
    int framecount = 0;

    //fft constants
    float fs_Hz = 250.0f;  //sample rate used by OpenBCI board
    int Nfft = 256; //set resolution of the FFT.  Use N=256 for normal, N=512 for MU waves
    //double fft_smooth_fac = 0.75; //use value between [0 and 1].  Bigger is more smoothing.  Use 0.9 for MU waves, 0.75 for Alpha, 0.0 for no smoothing
    FFT fftBuff[];   //from the minim library
    double[] smoothFac = new double[]{0.75, 0.9, 0.95, 0.98, 0.0, 0.5};
    final int N_SMOOTHEFAC = 6;
    int smoothFac_ind = 0;

    float inband_Hz[] = {9.0f, 12.0f};  //look at energy within these frequencies
    float guard_Hz[] = {13.5f, 23.5f};  //and compare to energy within these frequencies
    double fft_det_thresh_dB = 10.0;      //how much higher does the in-band signal have to be above the guard band?
    DetectionData_FreqDomain[] detData_freqDomain; //holds data describing any detections performed in the frequency domain

    public AlphaDetector(BrainStateCallback bsc, int nchan) {
        mCallback = bsc;
        this.nchan = nchan;
        dataBuffY_uV = new float[nchan][Nfft];
        yLittleBuff_uV = new float[nchan][framesperupdate];
        fftBuff = new FFT[nchan];
        detData_freqDomain = new DetectionData_FreqDomain[nchan];
        for (int Ichan=0; Ichan < nchan; Ichan++) {
            detData_freqDomain[Ichan] = new DetectionData_FreqDomain();
        }
        //initialize the FFT objects
        for (int Ichan=0; Ichan < nchan; Ichan++) {
            fftBuff[Ichan] = new FFT(Nfft, fs_Hz);
        };  //make the FFT objects
        initializeFFTObjects(fftBuff, dataBuffY_uV, Nfft, fs_Hz);
    }

    private void initializeFFTObjects(FFT[] fftBuff, float[][] dataBuffY_uV, int N, float fs_Hz) {

        float[] fooData;
        for (int Ichan=0; Ichan < nchan; Ichan++) {
            //make the FFT objects...Following "SoundSpectrum" example that came with the Minim library
            //fftBuff[Ichan] = new FFT(Nfft, fs_Hz);  //I can't have this here...it must be in setup
            fftBuff[Ichan].window(FFT.HAMMING);

            //do the FFT on the initial data
            fooData = dataBuffY_uV[Ichan];
            fooData = Arrays.copyOfRange(fooData, fooData.length - Nfft, fooData.length);
            fftBuff[Ichan].forward(fooData); //compute FFT on this channel of data
        }
    }

    public void addFrames(float[] frames) {
        for (int Ichan = 0; Ichan < nchan; Ichan++) {
            yLittleBuff_uV[Ichan][framecount] = frames[Ichan];
        }
        if (++framecount >= framesperupdate) {
            processData();
            detectInFreqDomain(fftBuff, inband_Hz, guard_Hz, detData_freqDomain);
            framecount = 0;
        }
    }

    private void processData() {

        float prevFFTdata[] = new float[fftBuff[0].specSize()];
        double foo;

        for (int Ichan = 0; Ichan < nchan; Ichan++) {
            //append the new data to the larger data buffer...because we want the plotting routines
            //to show more than just the most recent chunk of data.  This will be our "raw" data.
            appendAndShift(dataBuffY_uV[Ichan], yLittleBuff_uV[Ichan]);
        }

        //loop over all of the channels again
        for (int Ichan = 0; Ichan < nchan; Ichan++) {
            //copy the previous FFT data...enables us to apply some smoothing to the FFT data
            for (int I = 0; I < fftBuff[Ichan].specSize(); I++)
                prevFFTdata[I] = fftBuff[Ichan].getBand(I); //copy the old spectrum values

            //prepare the data for the new FFT
            float[] fooData_raw = dataBuffY_uV[Ichan];  //use the raw data for the FFT
            //fooData_raw = Arrays.copyOfRange(fooData_raw, fooData_raw.length - Nfft, fooData_raw.length);   //trim to grab just the most recent block of data
            float meanData = mean(fooData_raw);  //compute the mean
            for (int I = 0; I < fooData_raw.length; I++)
                fooData_raw[I] -= meanData; //remove the mean (for a better looking FFT

            //compute the FFT
            fftBuff[Ichan].forward(fooData_raw); //compute FFT on this channel of data

            //    //convert fft data to uV_per_sqrtHz
            //    //final float mean_winpow_sqr = 0.3966;  //account for power lost when windowing...mean(hamming(N).^2) = 0.3966
            //    final float mean_winpow = 1.0f/sqrt(2.0f);  //account for power lost when windowing...mean(hamming(N).^2) = 0.3966
            //    final float scale_raw_to_rtHz = pow((float)fftBuff[0].specSize(),1)*fs_Hz*mean_winpow; //normalize the amplitude by the number of bins to get the correct scaling to uV/sqrt(Hz)???
            //    double foo;
            //    for (int I=0; I < fftBuff[Ichan].specSize(); I++) {  //loop over each FFT bin
            //      foo = sqrt(pow(fftBuff[Ichan].getBand(I),2)/scale_raw_to_rtHz);
            //      fftBuff[Ichan].setBand(I,(float)foo);
            //      //if ((Ichan==0) & (I > 5) & (I < 15)) println("processFreqDomain: uV/rtHz = " + I + " " + foo);
            //    }

            //average the FFT with previous FFT data so that it makes it smoother in time
            double min_val = 0.01d;
            for (int I = 0; I < fftBuff[Ichan].specSize(); I++) {   //loop over each fft bin
                if (prevFFTdata[I] < min_val)
                    prevFFTdata[I] = (float) min_val; //make sure we're not too small for the log calls
                foo = fftBuff[Ichan].getBand(I);
                if (foo < min_val) foo = min_val; //make sure this value isn't too small

                if (true) {
                    //smooth in dB power space
                    foo = (1.0d - smoothFac[smoothFac_ind]) * java.lang.Math.log(java.lang.Math.pow(foo, 2));
                    foo += smoothFac[smoothFac_ind] * java.lang.Math.log(java.lang.Math.pow((double) prevFFTdata[I], 2));
                    foo = java.lang.Math.sqrt(java.lang.Math.exp(foo)); //average in dB space
                } else {
                    //smooth (average) in linear power space
                    foo = (1.0d - smoothFac[smoothFac_ind]) * java.lang.Math.pow(foo, 2);
                    foo += smoothFac[smoothFac_ind] * java.lang.Math.pow((double) prevFFTdata[I], 2);
                    // take sqrt to be back into uV_rtHz
                    foo = java.lang.Math.sqrt(foo);
                }
                fftBuff[Ichan].setBand(I, (float) foo); //put the smoothed data back into the fftBuff data holder for use by everyone else
            }
        }
    }

    void detectInFreqDomain(FFT[] fftBuff,float[] inband_Hz, float[] guard_Hz, DetectionData_FreqDomain[] results) {
        boolean isDetected = false;
        int nchan = fftBuff.length;

        //process each channel independently
        for (int Ichan = 0; Ichan < nchan; Ichan++) {
            //process the FFT data to look for certain types of waves
            float sum_inband_uV2 = 0; //a PSD value
            float sum_guard_uV2 = 0; //a PSD value
            final float Hz_per_bin = fs_Hz / ((float)fftBuff[Ichan].specSize());
            float fft_PSDperBin[] = new float[fftBuff[Ichan].specSize()];
            float freq_Hz=0;
            float max_inband_PSD = 0.0f;
            float max_inband_freq_Hz = 0.0f;

            for (int i=0;i < fft_PSDperBin.length;i++) {
                fft_PSDperBin[i] = (float)java.lang.Math.pow(fftBuff[Ichan].getBand(i),2) * Hz_per_bin;   //convert from uV/sqrt(Hz) to PSD per bin
                freq_Hz = fftBuff[Ichan].indexToFreq(i);
                if ((freq_Hz >= inband_Hz[0]) & (freq_Hz <= inband_Hz[1])) {
                    sum_inband_uV2 += fft_PSDperBin[i];
                    if (fft_PSDperBin[i] > max_inband_PSD) {
                        max_inband_PSD = fft_PSDperBin[i];
                        max_inband_freq_Hz = freq_Hz;
                    }
                }
                if ((freq_Hz >= guard_Hz[0]) & (freq_Hz <= guard_Hz[1])) { sum_guard_uV2 += fft_PSDperBin[i]; }
            }
            float max_inband_uV_rtHz = (float)java.lang.Math.sqrt(max_inband_PSD / Hz_per_bin);

            //float inband_uV_rtHz = (float)java.lang.Math.sqrt(sum_inband_uV2 / (inband_Hz[1]-inband_Hz[0]));=
            float guard_uV_rtHz = (float)java.lang.Math.sqrt(sum_guard_uV2 / (guard_Hz[1]-guard_Hz[0]));
            //float inband_vs_guard_dB = 20.f*log10(inband_uV_rtHz / guard_uV_rtHz);
            float inband_vs_guard_dB = 20.f*log10(max_inband_uV_rtHz / guard_uV_rtHz);
            Log.d(TAG, "Chan [" + Ichan + "] Max Inband, Mean Guard (uV/sqrtHz) " + max_inband_uV_rtHz + " " + guard_uV_rtHz + ",  Inband / Guard (dB) " + inband_vs_guard_dB);
            isDetected = false;
            if (inband_vs_guard_dB > fft_det_thresh_dB) {
                isDetected = true;
            }
            results[Ichan].inband_uV = max_inband_uV_rtHz;
            results[Ichan].inband_freq_Hz = max_inband_freq_Hz;
            results[Ichan].guard_uV = guard_uV_rtHz;
            results[Ichan].thresh_uV = (float)(guard_uV_rtHz * java.lang.Math.pow(10.0,fft_det_thresh_dB / 20.0f));
            results[Ichan].isDetected = isDetected;
            mCallback.alpha(results);
        }
    }

    void appendAndShift(float[] data, float[] newData) {
        int nshift = newData.length;
        int end = data.length-nshift;
        for (int i=0; i < end; i++) {
            data[i]=data[i+nshift];  //shift data points down by 1
        }
        for (int i=0; i<nshift;i++) {
            data[end+i] = newData[i];  //append new data
        }
    }

    float mean(float[] data) {
        return mean(data,data.length);
    }

    float mean(float[] data, int Nback) {
        return sum(data,Nback)/Nback;
    }

    float sum(float[] data) {
        return sum(data, data.length);
    }

    float sum(float[] data, int Nback) {
        float sum = 0;
        if (Nback > 0) {
            for (int i=(data.length)-Nback; i < data.length; i++) {
                sum += data[i];
            }
        }
        return sum;
    }

    float log10(float val) {
        return (float)Math.log10(val);
    }

    class DetectionData_FreqDomain {
        public double inband_uV = 0.0f;
        public double inband_freq_Hz = 0.0f;
        public double guard_uV = 0.0f;
        public double thresh_uV = 0.0f;
        public boolean isDetected = false;

        DetectionData_FreqDomain() {
        }
    };
}
