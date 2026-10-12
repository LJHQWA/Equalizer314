/* Stand-in for PipeWire's spa/utils/defs.h: only the macros convolver.c and pffft.c use. */
#ifndef EQ314_SPA_UTILS_DEFS_H
#define EQ314_SPA_UTILS_DEFS_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#define SPA_MIN(a, b)						\
({								\
	__typeof__(a) _min_a = (a);				\
	__typeof__(b) _min_b = (b);				\
	_min_a <= _min_b ? _min_a : _min_b;			\
})
#define SPA_MAX(a, b)						\
({								\
	__typeof__(a) _max_a = (a);				\
	__typeof__(b) _max_b = (b);				\
	_max_a >= _max_b ? _max_a : _max_b;			\
})
#define SPA_CLAMP(v, low, high)					\
({								\
	__typeof__(v) _v = (v);					\
	__typeof__(low) _low = (low);				\
	__typeof__(high) _high = (high);			\
	SPA_MIN(SPA_MAX(_v, _low), _high);			\
})
#define SPA_SWAP(a, b)						\
({								\
	__typeof__(a) _t = (a);					\
	(a) = (b); (b) = _t;					\
})
#define SPA_RESTRICT restrict
#define spa_memcpy memcpy

#endif
