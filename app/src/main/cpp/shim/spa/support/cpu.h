/* Stand-in for PipeWire's spa/support/cpu.h: the flags pffft_select_cpu() checks. */
#ifndef EQ314_SPA_SUPPORT_CPU_H
#define EQ314_SPA_SUPPORT_CPU_H

#define SPA_CPU_FLAG_SSE	(1 << 0)
#define SPA_CPU_FLAG_NEON	(1 << 1)
#define SPA_CPU_FLAG_ALTIVEC	(1 << 2)

#endif
