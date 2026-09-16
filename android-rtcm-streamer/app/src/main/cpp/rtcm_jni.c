#include <jni.h>
#include <string.h>
#include <math.h>
#include <stdio.h>
#include "rtklib.h"

// Back end: fill obsd_t from Kotlin-computed observables and run gen_rtcm3() to emit RTCM3 MSM7.
// One epoch in -> a concatenation of per-system MSM7 messages out (GPS 1077, GLONASS 1087,
// Galileo 1097, QZSS 1117, BeiDou 1127).
//
// Observations are passed as flat per-signal parallel arrays (one entry per satellite+signal).
// The C side groups them by satellite into obsd_t, placing each signal in the frequency slot
// given by code2idx() (so L1+L5 on one satellite share one obsd_t). gen_rtcm3() then extracts
// only the satellites of each requested system, so a mixed-system obs array needs no presorting.

// Persistent encoder state (carrier-phase continuity / lock time are kept across epochs,
// which is what a real RTCM3 stream needs). Calls come from a single thread.
static rtcm_t g_enc;
static int g_enc_ready = 0;

static void ensure_enc(void) {
    if (!g_enc_ready) {
        init_rtcm(&g_enc);
        g_enc_ready = 1;
    }
}

// --- Optional RINEX 3.04 OBS output, written from the SAME obsd_t fed to gen_rtcm3 ---
// (faithful record of the transmitted observations). Uses RTKLIB rinex.c outrnxobsh/outrnxobsb.
static FILE *g_rnx_fp = NULL;
static rnxopt_t g_rnxopt;
static int g_rnx_header_done = 0;

static void rnx_set_tobs(int sys, const char *const *types, int n) {
    g_rnxopt.nobs[sys] = n;
    for (int i = 0; i < n && i < MAXOBSTYPE; i++) {
        strncpy(g_rnxopt.tobs[sys][i], types[i], 3);
        g_rnxopt.tobs[sys][i][3] = '\0';
    }
}

// Write the current g_enc.obs epoch to the open RINEX file (header lazily on the first epoch).
static void rnx_write_epoch(void) {
    if (!g_rnx_fp) return;
    if (!g_rnx_header_done) {
        g_rnxopt.tstart = g_enc.time;
        outrnxobsh(g_rnx_fp, &g_rnxopt, &g_enc.nav);
        g_rnx_header_done = 1;
    }
    outrnxobsb(g_rnx_fp, &g_rnxopt, g_enc.obs.data, g_enc.obs.n, 0);
    fflush(g_rnx_fp);
}

// MSM7 message type per constellation, paired with the RTKLIB system flag.
typedef struct { int type; int sys; } msm_type_t;
static const msm_type_t MSM7_TYPES[] = {
    {1077, SYS_GPS}, {1087, SYS_GLO}, {1097, SYS_GAL}, {1117, SYS_QZS}, {1127, SYS_CMP},
};
static const int N_MSM7_TYPES = (int) (sizeof(MSM7_TYPES) / sizeof(MSM7_TYPES[0]));

// Fill enc->obs from one epoch of (satid, code) observables, grouping signals by satellite.
// satid/code are RINEX-3 strings, e.g. "G10"/"1C", "E05"/"5Q", "C06"/"2I", "R05"/"1C".
// fcn[] is the GLONASS frequency channel number (-7..+6); ignored for other systems. It is
// stored in enc->nav.glo_fcn (as fcn+8) so the MSM encoder can derive the G1 wavelength.
static void fill_obs(rtcm_t *enc, int week, double tow, int staid, int n,
                     const char **satid, const char **code, const double *P,
                     const double *L, const float *D, const float *S, const int *lli,
                     const int *fcn) {
    gtime_t t = gpst2time(week, tow);
    enc->staid = staid;
    enc->time = t;

    // Map satellite number -> row in enc->obs.data so repeated signals land in one obsd_t.
    int row[MAXSAT + 1];
    for (int i = 0; i <= MAXSAT; i++) row[i] = -1;

    int nobs = 0;
    for (int i = 0; i < n; i++) {
        int sat = satid2no(satid[i]);
        if (sat <= 0 || sat > MAXSAT) continue;
        uint8_t codeval = obs2code(code[i]);
        if (codeval == CODE_NONE) continue;
        int prn;
        int sys = satsys(sat, &prn);
        int j = code2idx(sys, codeval);
        if (j < 0 || j >= NFREQ + NEXOBS) continue;
        if (sys == SYS_GLO && prn >= 1 && prn <= 32) {
            enc->nav.glo_fcn[prn - 1] = fcn[i] + 8; // FCN+8 (0 = no data)
        }

        int r = row[sat];
        if (r < 0) {
            if (nobs >= MAXOBS) continue;
            r = nobs++;
            row[sat] = r;
            obsd_t *d = enc->obs.data + r;
            memset(d, 0, sizeof(*d));
            d->time = t;
            d->sat = (uint8_t) sat;
            d->rcv = 1;
        }
        obsd_t *d = enc->obs.data + r;
        d->code[j] = codeval;
        d->P[j] = P[i];
        d->L[j] = L[i];
        d->D[j] = D[i];
        d->SNR[j] = S[i];
        d->LLI[j] = (uint8_t) lli[i];
    }
    enc->obs.n = nobs;
}

// Does enc->obs contain at least one satellite of the given system?
static int has_sys(const rtcm_t *enc, int sys) {
    for (int i = 0; i < enc->obs.n; i++) {
        int prn;
        if (satsys(enc->obs.data[i].sat, &prn) == sys) return 1;
    }
    return 0;
}

// Emit one MSM7 message per present constellation, concatenated into out (capacity outcap).
// Sets the MSM multiple-message (sync) bit on every message except the last. Returns bytes written.
static int emit_all_msm7(rtcm_t *enc, uint8_t *out, int outcap) {
    int present[16], np = 0;
    for (int k = 0; k < N_MSM7_TYPES; k++) {
        if (has_sys(enc, MSM7_TYPES[k].sys)) present[np++] = k;
    }
    int total = 0;
    for (int p = 0; p < np; p++) {
        int sync = (p < np - 1) ? 1 : 0;
        if (!gen_rtcm3(enc, MSM7_TYPES[present[p]].type, 0, sync)) continue;
        if (total + enc->nbyte > outcap) break;
        memcpy(out + total, enc->buff, enc->nbyte);
        total += enc->nbyte;
    }
    return total;
}

// Encode one epoch of mixed-constellation observables to concatenated RTCM3 MSM7 messages.
JNIEXPORT jbyteArray JNICALL
Java_org_furo_rtcmstreamer_rtcm_Rtcm3Encoder_encodeMsm7(
        JNIEnv *env, jobject thiz, jint week, jdouble tow, jint staid,
        jobjectArray jsatid, jobjectArray jcode, jdoubleArray jP, jdoubleArray jL,
        jfloatArray jD, jfloatArray jS, jintArray jlli, jintArray jfcn) {

    ensure_enc();
    jint n = (*env)->GetArrayLength(env, jsatid);
    if (n > MAXOBS) n = MAXOBS;

    const char *satid[MAXOBS];
    const char *code[MAXOBS];
    jstring jsat[MAXOBS];
    jstring jcod[MAXOBS];
    for (int i = 0; i < n; i++) {
        jsat[i] = (jstring) (*env)->GetObjectArrayElement(env, jsatid, i);
        jcod[i] = (jstring) (*env)->GetObjectArrayElement(env, jcode, i);
        satid[i] = (*env)->GetStringUTFChars(env, jsat[i], 0);
        code[i] = (*env)->GetStringUTFChars(env, jcod[i], 0);
    }
    jdouble *P = (*env)->GetDoubleArrayElements(env, jP, 0);
    jdouble *L = (*env)->GetDoubleArrayElements(env, jL, 0);
    jfloat *D = (*env)->GetFloatArrayElements(env, jD, 0);
    jfloat *S = (*env)->GetFloatArrayElements(env, jS, 0);
    jint *lli = (*env)->GetIntArrayElements(env, jlli, 0);
    jint *fcn = (*env)->GetIntArrayElements(env, jfcn, 0);

    fill_obs(&g_enc, week, tow, staid, n, satid, code, P, L, D, S, lli, fcn);

    rnx_write_epoch(); // RINEX OBS from the same obsd_t, if a RINEX file is open

    static uint8_t out[16384];
    int nbyte = emit_all_msm7(&g_enc, out, sizeof(out));

    (*env)->ReleaseDoubleArrayElements(env, jP, P, JNI_ABORT);
    (*env)->ReleaseDoubleArrayElements(env, jL, L, JNI_ABORT);
    (*env)->ReleaseFloatArrayElements(env, jD, D, JNI_ABORT);
    (*env)->ReleaseFloatArrayElements(env, jS, S, JNI_ABORT);
    (*env)->ReleaseIntArrayElements(env, jlli, lli, JNI_ABORT);
    (*env)->ReleaseIntArrayElements(env, jfcn, fcn, JNI_ABORT);
    for (int i = 0; i < n; i++) {
        (*env)->ReleaseStringUTFChars(env, jsat[i], satid[i]);
        (*env)->ReleaseStringUTFChars(env, jcod[i], code[i]);
        (*env)->DeleteLocalRef(env, jsat[i]);
        (*env)->DeleteLocalRef(env, jcod[i]);
    }

    jbyteArray jout = (*env)->NewByteArray(env, nbyte);
    if (nbyte > 0) {
        (*env)->SetByteArrayRegion(env, jout, 0, nbyte, (const jbyte *) out);
    }
    return jout;
}

// Open a RINEX 3.04 OBS file at `path`; subsequent encodeMsm7() epochs are appended to it.
// rnxopt is configured for the fixed signal set this app transmits.
JNIEXPORT void JNICALL
Java_org_furo_rtcmstreamer_rtcm_Rtcm3Encoder_rinexOpen(JNIEnv *env, jobject thiz, jstring jpath) {
    const char *path = (*env)->GetStringUTFChars(env, jpath, 0);
    if (g_rnx_fp) fclose(g_rnx_fp);
    g_rnx_fp = fopen(path, "w");
    (*env)->ReleaseStringUTFChars(env, jpath, path);
    if (!g_rnx_fp) return;

    memset(&g_rnxopt, 0, sizeof(g_rnxopt));
    g_rnxopt.rnxver = 304;
    g_rnxopt.navsys = SYS_GPS | SYS_GLO | SYS_GAL | SYS_QZS | SYS_CMP;
    g_rnxopt.obstype = OBSTYPE_ALL;
    g_rnxopt.freqtype = FREQTYPE_ALL;
    for (int i = 0; i < RNX_NUMSYS; i++) memset(g_rnxopt.mask[i], '1', MAXCODE);
    strncpy(g_rnxopt.prog, "rtcmstreamer", sizeof(g_rnxopt.prog) - 1);
    strncpy(g_rnxopt.marker, "RTCM Streamer", sizeof(g_rnxopt.marker) - 1);

    // Obs types per system, matching the codes this app emits (see SignalCodeMap / fill_obs).
    static const char *gps[] = {"C1C","L1C","D1C","S1C","C5Q","L5Q","D5Q","S5Q"};
    static const char *glo[] = {"C1C","L1C","D1C","S1C"};
    static const char *gal[] = {"C1B","L1B","D1B","S1B","C1C","L1C","D1C","S1C","C5Q","L5Q","D5Q","S5Q"};
    static const char *qzs[] = {"C1C","L1C","D1C","S1C","C5Q","L5Q","D5Q","S5Q"};
    static const char *cmp[] = {"C2I","L2I","D2I","S2I","C5P","L5P","D5P","S5P"};
    rnx_set_tobs(0, gps, 8);  // RNX_SYS_GPS
    rnx_set_tobs(1, glo, 4);  // RNX_SYS_GLO
    rnx_set_tobs(2, gal, 12); // RNX_SYS_GAL
    rnx_set_tobs(3, qzs, 8);  // RNX_SYS_QZS
    rnx_set_tobs(5, cmp, 8);  // RNX_SYS_CMP
    g_rnx_header_done = 0;
}

// Close the RINEX OBS file (if open).
JNIEXPORT void JNICALL
Java_org_furo_rtcmstreamer_rtcm_Rtcm3Encoder_rinexClose(JNIEnv *env, jobject thiz) {
    if (g_rnx_fp) {
        fclose(g_rnx_fp);
        g_rnx_fp = NULL;
    }
}

// Self-test: encode sample observables, decode them back with input_rtcm3, and compare.
// Covers GPS L1 plus a synthetic GPS L1+L5 dual-frequency satellite to exercise multi-signal
// slot packing. Returns a human-readable summary; starts with "OK" on success, "FAIL" otherwise.
JNIEXPORT jstring JNICALL
Java_org_furo_rtcmstreamer_rtcm_Rtcm3Encoder_selfTestRoundTrip(JNIEnv *env, jobject thiz) {
    // GPS L1 samples from a reference RINEX (epoch 2026/06/12 13:00:38, GPS week 2422),
    // an extra L5 signal on G10 (dual-frequency packing), and two GLONASS sats with distinct
    // FCNs (R15 fcn -7, R05 fcn +1) to exercise the FCN-dependent wavelength path.
    const char *satid[] = {"G10", "G12", "G25", "G28", "G32", "G10", "R15", "R05"};
    const char *code[]  = {"1C",  "1C",  "1C",  "1C",  "1C",  "5Q",  "1C",  "1C"};
    const double P[] = {22209006.001, 21992191.896, 19813193.887, 22142811.828, 20773019.608, 22209005.500, 21000000.000, 22000000.000};
    const double L[] = {476971.292, 497986.093, 15688.738, -466718.350, -435102.275, 356100.100, 112233.445, -98765.432};
    const float D[] = {-2196.193f, -2290.112f, -107.395f, 2116.724f, 1958.472f, -1639.5f, 1234.5f, -987.6f};
    const float S[] = {45.037f, 47.544f, 49.717f, 44.392f, 51.616f, 43.0f, 42.0f, 40.0f};
    const int lli[] = {0, 0, 0, 0, 0, 0, 0, 0};
    const int fcn[] = {0, 0, 0, 0, 0, 0, -7, 1};
    const int n = 8;
    const int week = 2422;
    const double tow = 478838.0000360;

    rtcm_t enc, dec;
    init_rtcm(&enc);
    init_rtcm(&dec);

    char out[1024];
    int len = 0;

    fill_obs(&enc, week, tow, 0, n, satid, code, P, L, D, S, lli, fcn);
    static uint8_t msg[16384];
    int nbyte = emit_all_msm7(&enc, msg, sizeof(msg));
    len += snprintf(out + len, sizeof(out) - len,
                    "obs.n=%d nbyte=%d preamble=0x%02X; ", enc.obs.n, nbyte, nbyte > 0 ? msg[0] : 0);

    int fails = 0;
    if (nbyte <= 0) {
        fails++;
    } else {
        dec.time = gpst2time(week, tow);
        for (int i = 0; i < nbyte; i++) input_rtcm3(&dec, msg[i]);
        len += snprintf(out + len, sizeof(out) - len, "decoded n=%d type=%s; ", dec.obs.n, dec.msgtype);

        for (int i = 0; i < n; i++) {
            int sat = satid2no(satid[i]);
            int prn, sys = satsys(sat, &prn);
            int j = code2idx(sys, obs2code(code[i]));
            obsd_t *d = NULL;
            for (int k = 0; k < dec.obs.n; k++) {
                if (dec.obs.data[k].sat == sat) { d = dec.obs.data + k; break; }
            }
            if (!d || j < 0) { fails++; continue; }
            double dP = d->P[j] - P[i];
            double dD = (double) d->D[j] - (double) D[i];
            double dS = (double) d->SNR[j] - (double) S[i];
            double dL = d->L[j] - L[i];
            double dLres = dL - round(dL); // phase round-trips modulo integer cycles
            if (fabs(dP) > 0.01) fails++;
            if (fabs(dLres) > 0.01) fails++;
            if (fabs(dD) > 0.05) fails++;
            if (fabs(dS) > 0.30) fails++;
        }
    }

    len += snprintf(out + len, sizeof(out) - len, fails ? "FAIL(%d)" : "OK", fails);
    // Prefix with overall status so the Kotlin side can check startsWith("OK").
    char result[1100];
    snprintf(result, sizeof(result), "%s | %s", fails ? "FAIL" : "OK", out);

    free_rtcm(&enc);
    free_rtcm(&dec);
    return (*env)->NewStringUTF(env, result);
}
