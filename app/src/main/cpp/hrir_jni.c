/* Virtual surround engine (issue #128): each input channel runs through its two ear impulses with
 * PipeWire's convolver, and the results are summed per ear. Stereo interleaved float in place. */
#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#include <spa/support/cpu.h>

#include "convolver.h"
#include "pffft.h"

#if defined(__aarch64__) || defined(__arm__)
#define EQ314_CPU_FLAG SPA_CPU_FLAG_NEON
#else
#define EQ314_CPU_FLAG SPA_CPU_FLAG_SSE
#endif

struct engine {
	struct spa_fga_dsp dsp;
	struct convolver *conv[2];	/* [0] left speaker, [1] right speaker */
	int crossfeed;			/* 0: plain stereo IR, each side to its own ear only */
	int max_frames;
	float *io;
	float *in[2];
	float *out[2][2];		/* [speaker][ear] */
};

static void engine_free(struct engine *e)
{
	if (e == NULL)
		return;
	for (int s = 0; s < 2; s++) {
		if (e->conv[s])
			convolver_free(e->conv[s]);
		free(e->in[s]);
		free(e->out[s][0]);
		free(e->out[s][1]);
	}
	free(e->io);
	free(e);
}

static float *ir_copy(JNIEnv *env, jfloatArray arr, int *len)
{
	*len = arr ? (*env)->GetArrayLength(env, arr) : 0;
	if (*len <= 0)
		return NULL;
	float *buf = malloc(*len * sizeof(float));
	if (buf)
		(*env)->GetFloatArrayRegion(env, arr, 0, *len, buf);
	return buf;
}

JNIEXPORT jlong JNICALL
Java_com_bearinmind_equalizer314_audio_HrirNative_create(JNIEnv *env, jclass clazz,
		jfloatArray ll, jfloatArray lr, jfloatArray rl, jfloatArray rr, jint maxFrames, jint headBlock)
{
	static int cpu_selected;
	struct engine *e;
	float *irs[4];
	int lens[4], ok = 1;
	(void)clazz;

	if (!cpu_selected) {
		pffft_select_cpu(EQ314_CPU_FLAG);
		cpu_selected = 1;
	}
	if (maxFrames <= 0 || headBlock <= 0)
		return 0;

	e = calloc(1, sizeof(*e));
	if (e == NULL)
		return 0;
	e->max_frames = maxFrames;
	irs[0] = ir_copy(env, ll, &lens[0]);
	irs[1] = ir_copy(env, lr, &lens[1]);
	irs[2] = ir_copy(env, rl, &lens[2]);
	irs[3] = ir_copy(env, rr, &lens[3]);
	e->crossfeed = irs[1] != NULL && irs[2] != NULL;

	if (irs[0] == NULL || irs[3] == NULL) {
		ok = 0;
	} else {
		int tail = headBlock > 4096 ? headBlock : 4096;
		struct convolver_ir left[2] = { { irs[0], lens[0] }, { irs[1], lens[1] } };
		struct convolver_ir right[2] = { { irs[2], lens[2] }, { irs[3], lens[3] } };
		if (e->crossfeed) {
			e->conv[0] = convolver_new_many(&e->dsp, headBlock, tail, left, 2);
			e->conv[1] = convolver_new_many(&e->dsp, headBlock, tail, right, 2);
		} else {
			e->conv[0] = convolver_new_many(&e->dsp, headBlock, tail, &left[0], 1);
			e->conv[1] = convolver_new_many(&e->dsp, headBlock, tail, &right[1], 1);
		}
		ok = e->conv[0] != NULL && e->conv[1] != NULL;
	}
	/* The convolver keeps its own FFT'd copy, so the impulses can go now. */
	for (int i = 0; i < 4; i++)
		free(irs[i]);

	e->io = malloc(2 * maxFrames * sizeof(float));
	for (int s = 0; s < 2 && ok; s++) {
		e->in[s] = malloc(maxFrames * sizeof(float));
		e->out[s][0] = malloc(maxFrames * sizeof(float));
		e->out[s][1] = malloc(maxFrames * sizeof(float));
		ok = e->in[s] && e->out[s][0] && e->out[s][1];
	}
	if (!ok || e->io == NULL) {
		engine_free(e);
		return 0;
	}
	return (jlong)(intptr_t)e;
}

JNIEXPORT void JNICALL
Java_com_bearinmind_equalizer314_audio_HrirNative_process(JNIEnv *env, jclass clazz,
		jlong handle, jfloatArray buf, jint frames)
{
	struct engine *e = (struct engine *)(intptr_t)handle;
	(void)clazz;
	if (e == NULL || frames <= 0)
		return;

	for (int done = 0; done < frames;) {
		int n = frames - done < e->max_frames ? frames - done : e->max_frames;
		float *io = e->io;
		(*env)->GetFloatArrayRegion(env, buf, 2 * done, 2 * n, io);
		for (int i = 0; i < n; i++) {
			e->in[0][i] = io[2 * i];
			e->in[1][i] = io[2 * i + 1];
		}
		for (int s = 0; s < 2; s++) {
			/* An all-zero impulse leaves the convolver without partitions; it then writes nothing. */
			memset(e->out[s][0], 0, n * sizeof(float));
			memset(e->out[s][1], 0, n * sizeof(float));
			convolver_run_many(e->conv[s], e->in[s], e->out[s], n);
		}
		if (e->crossfeed) {
			for (int i = 0; i < n; i++) {
				io[2 * i] = e->out[0][0][i] + e->out[1][0][i];
				io[2 * i + 1] = e->out[0][1][i] + e->out[1][1][i];
			}
		} else {
			for (int i = 0; i < n; i++) {
				io[2 * i] = e->out[0][0][i];
				io[2 * i + 1] = e->out[1][0][i];
			}
		}
		(*env)->SetFloatArrayRegion(env, buf, 2 * done, 2 * n, io);
		done += n;
	}
}

JNIEXPORT void JNICALL
Java_com_bearinmind_equalizer314_audio_HrirNative_reset(JNIEnv *env, jclass clazz, jlong handle)
{
	struct engine *e = (struct engine *)(intptr_t)handle;
	(void)env; (void)clazz;
	if (e == NULL)
		return;
	convolver_reset(e->conv[0]);
	convolver_reset(e->conv[1]);
}

JNIEXPORT void JNICALL
Java_com_bearinmind_equalizer314_audio_HrirNative_destroy(JNIEnv *env, jclass clazz, jlong handle)
{
	(void)env; (void)clazz;
	engine_free((struct engine *)(intptr_t)handle);
}
