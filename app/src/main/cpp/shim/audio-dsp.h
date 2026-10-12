/* Stand-in for PipeWire's filter-graph audio-dsp.h: only the calls convolver.c makes, backed by pffft
 * the way PipeWire's audio-dsp-c.c does it (MIT, Copyright © 2022 Wim Taymans). */
#ifndef EQ314_AUDIO_DSP_H
#define EQ314_AUDIO_DSP_H

#include <spa/utils/defs.h>

#include "pffft.h"

struct spa_fga_dsp {
	int unused;
};

struct eq314_fft {
	PFFFT_Setup *setup;
	uint32_t size;
	float scale;
};

static inline void *spa_fga_dsp_fft_memalloc(struct spa_fga_dsp *dsp, uint32_t size, bool real)
{
	(void)dsp;
	return pffft_aligned_malloc((real ? size : size * 2) * sizeof(float));
}

static inline void spa_fga_dsp_fft_memfree(struct spa_fga_dsp *dsp, void *mem)
{
	(void)dsp;
	pffft_aligned_free(mem);
}

static inline void spa_fga_dsp_fft_memclear(struct spa_fga_dsp *dsp, void *mem, uint32_t size, bool real)
{
	(void)dsp;
	memset(mem, 0, (real ? size : size * 2) * sizeof(float));
}

static inline void *spa_fga_dsp_fft_new(struct spa_fga_dsp *dsp, uint32_t size, bool real)
{
	struct eq314_fft *info = calloc(1, sizeof(*info));
	(void)dsp;
	if (info == NULL)
		return NULL;
	info->size = size;
	info->scale = 1.0f / size;
	info->setup = pffft_new_setup(size, real ? PFFFT_REAL : PFFFT_COMPLEX);
	if (info->setup == NULL) {
		free(info);
		return NULL;
	}
	return info;
}

static inline void spa_fga_dsp_fft_free(struct spa_fga_dsp *dsp, void *fft)
{
	struct eq314_fft *info = fft;
	(void)dsp;
	pffft_destroy_setup(info->setup);
	free(info);
}

/* The inverse transform scales its (scratch) input by 1/N first, as PipeWire does. */
static inline void spa_fga_dsp_fft_run(struct spa_fga_dsp *dsp, void *fft, int direction,
		const float *src, float *dst)
{
	struct eq314_fft *info = fft;
	(void)dsp;
	if (direction < 0) {
		float *s = (float *)src;
		for (uint32_t i = 0; i < info->size; i++)
			s[i] *= info->scale;
	}
	pffft_transform(info->setup, src, dst, NULL, direction < 0 ? PFFFT_BACKWARD : PFFFT_FORWARD);
}

static inline void spa_fga_dsp_fft_cmul(struct spa_fga_dsp *dsp, void *fft,
		float *dst, const float *a, const float *b, uint32_t len)
{
	struct eq314_fft *info = fft;
	(void)dsp; (void)len;
	pffft_zconvolve(info->setup, a, b, dst, 1.0f);
}

static inline void spa_fga_dsp_fft_cmuladd(struct spa_fga_dsp *dsp, void *fft,
		float *dst, const float *src, const float *a, const float *b, uint32_t len)
{
	struct eq314_fft *info = fft;
	(void)dsp; (void)len;
	pffft_zconvolve_accumulate(info->setup, a, b, src, dst, 1.0f);
}

static inline void spa_fga_dsp_copy(struct spa_fga_dsp *dsp, float *dst, const float *src, uint32_t n)
{
	(void)dsp;
	if (dst != src)
		memcpy(dst, src, n * sizeof(float));
}

static inline void spa_fga_dsp_sum(struct spa_fga_dsp *dsp, float *dst, const float *a, const float *b, uint32_t n)
{
	(void)dsp;
	for (uint32_t i = 0; i < n; i++)
		dst[i] = a[i] + b[i];
}

#endif
