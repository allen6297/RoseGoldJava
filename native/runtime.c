#include "rg_value.h"

#include <ctype.h>
#include <errno.h>
#include <limits.h>
#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#if defined(_WIN32)
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <direct.h>
#include <io.h>
#include <sys/stat.h>
#else
#include <sys/stat.h>
#include <time.h>
#include <unistd.h>
#include <strings.h>
#endif

#define RG_MAX_RANGE 1000000
#define RG_MAX_SLEEP_MS 60000
#define RG_MAX_REPEAT_BYTES (16L * 1024L * 1024L)

typedef struct RGArray {
    int32_t len;
    int32_t cap;
    RGValue *items;
} RGArray;

typedef struct RGRange {
    int64_t end;
    int32_t inclusive;
} RGRange;

typedef struct RGMapEntry {
    char *key;
    RGValue value;
} RGMapEntry;

typedef struct RGMap {
    int32_t len;
    int32_t cap;
    RGMapEntry *entries;
} RGMap;

typedef struct RGStruct {
    char *name;
    int32_t len;
    int32_t is_data;
    char **fields;
    RGValue *values;
    int32_t nsigs;
    int32_t csigs;
    char **sig_names;
    struct RGSignal **sigs;
} RGStruct;

typedef void (*RGFn)(RGValue *out, RGValue *args, int32_t argc);

typedef struct RGMethodReg {
    const char *type;
    const char *method;
    RGFn fn;
} RGMethodReg;

typedef struct RGParentReg {
    const char *child;
    const char *parent;
} RGParentReg;

typedef struct RGEnum {
    char *type;
    char *variant;
    int32_t n;
    RGValue *payload;
    char **fields;
} RGEnum;

typedef struct RGEnumReg {
    const char *type;
    const char *variant;
    int32_t arity;
    const char **fields;
} RGEnumReg;

typedef struct RGFnVal {
    RGFn fn;
    int32_t arity;
    int32_t ncaps;
    RGValue *caps;
} RGFnVal;

typedef struct RGSignal {
    char *name;
    int32_t arity;
    int32_t nlisten;
    int32_t clisten;
    RGValue *listeners;
} RGSignal;

typedef struct RGTypeSigReg {
    const char *type;
    const char *name;
    int32_t arity;
} RGTypeSigReg;

typedef struct RGDeferred {
    RGSignal *sig;
    RGValue *args;
    int32_t argc;
    struct RGDeferred *next;
} RGDeferred;

enum {
    RG_FUT_PENDING = 0,
    RG_FUT_READY = 1,
    RG_FUT_FAILED = 2
};

typedef struct RGFuture {
    int32_t state;
    RGValue result;
    RGValue error;
} RGFuture;

typedef struct RGTask {
    RGFn fn;
    RGValue *args;
    int32_t argc;
    RGFuture *fut;
    struct RGTask *next;
} RGTask;

typedef struct RGTimer {
    int64_t deadline_ms;
    RGFuture *fut;
    struct RGTimer *next;
} RGTimer;

typedef struct RGJoin {
    int32_t kind;
    int32_t done;
    int32_t n;
    RGFuture *out;
    RGFuture **ins;
    struct RGJoin *next;
} RGJoin;

typedef struct RGFrameJob {
    int64_t win_id;
    RGFuture *fut;
    struct RGFrameJob *next;
} RGFrameJob;

typedef struct RGWin {
    int64_t id;
    int32_t alive;
    int32_t mapped;
    int32_t w;
    int32_t h;
    char *title;
    int32_t mx;
    int32_t my;
    int32_t down;
    int32_t click;
    int32_t rclick;
    int32_t keyp;
    int32_t scrollp;
    int32_t key_code;
    int32_t sdx;
    int32_t sdy;
    char *key_text;
    struct RGWin *next;
} RGWin;

static RGTask *g_tasks_head;
static RGTask *g_tasks_tail;
static RGTimer *g_timers;
static RGJoin *g_joins;
static RGFrameJob *g_frames;
static RGWin *g_wins;
static int64_t g_next_win = 1;
static const char *g_clip = "";
static int32_t g_argc;
static char **g_argv;
static int32_t g_rng_seeded;

void rg_set_void(RGValue *v) {
    if (v == NULL) {
        return;
    }
    v->kind = RG_VOID;
    v->i = 0;
    v->p = NULL;
}

void rg_set_bool(RGValue *v, int32_t b) {
    if (v == NULL) {
        return;
    }
    v->kind = RG_BOOL;
    v->i = b ? 1 : 0;
    v->p = NULL;
}

void rg_set_int(RGValue *v, int64_t n) {
    if (v == NULL) {
        return;
    }
    v->kind = RG_INT;
    v->i = n;
    v->p = NULL;
}

void rg_set_float(RGValue *v, double x) {
    if (v == NULL) {
        return;
    }
    v->kind = RG_FLOAT;
    memcpy(&v->i, &x, sizeof(x));
    v->p = NULL;
}

void rg_set_string(RGValue *v, const char *s) {
    if (v == NULL) {
        return;
    }
    v->kind = RG_STRING;
    v->i = 0;
    v->p = s;
}

void rg_copy(RGValue *dst, const RGValue *src) {
    if (dst == NULL) {
        return;
    }
    if (src == NULL) {
        rg_set_void(dst);
        return;
    }
    *dst = *src;
}

static void rg_die(const char *msg) {
    fprintf(stderr, "%s\n", msg);
    exit(1);
}

static RGArray *rg_alloc_array(int32_t n);
static void rg_set_array(RGValue *dest, RGArray *arr);

static RGArray *rg_as_array(const RGValue *v) {
    if (v == NULL || v->kind != RG_ARRAY || v->p == NULL) {
        return NULL;
    }
    return (RGArray *) (void *) v->p;
}

static RGRange *rg_as_range(const RGValue *v) {
    if (v == NULL || v->kind != RG_RANGE || v->p == NULL) {
        return NULL;
    }
    return (RGRange *) (void *) v->p;
}

static RGMap *rg_as_map(const RGValue *v) {
    if (v == NULL || v->kind != RG_MAP || v->p == NULL) {
        return NULL;
    }
    return (RGMap *) (void *) v->p;
}

static RGStruct *rg_as_struct(const RGValue *v) {
    if (v == NULL || v->kind != RG_STRUCT || v->p == NULL) {
        return NULL;
    }
    return (RGStruct *) (void *) v->p;
}

static RGMethodReg *g_methods;
static int32_t g_nmethods;
static int32_t g_cmethods;
static RGParentReg *g_parents;
static int32_t g_nparents;
static int32_t g_cparents;
static RGTypeSigReg *g_typesigs;
static int32_t g_ntypesigs;
static int32_t g_ctypesigs;
static RGSignal **g_gsigs;
static int32_t g_ngsigs;
static int32_t g_cgsigs;
static RGDeferred *g_deferred_head;
static RGDeferred *g_deferred_tail;
static int32_t g_emit_depth;
static RGEnumReg *g_enums;
static int32_t g_nenums;
static int32_t g_cenums;

static RGEnum *rg_as_enum(const RGValue *v) {
    if (v == NULL || v->kind != RG_ENUM || v->p == NULL) {
        return NULL;
    }
    return (RGEnum *) (void *) v->p;
}

static RGFnVal *rg_as_fn(const RGValue *v) {
    if (v == NULL || v->kind != RG_FN || v->p == NULL) {
        return NULL;
    }
    return (RGFnVal *) (void *) v->p;
}

static RGSignal *rg_as_signal(const RGValue *v) {
    if (v == NULL || v->kind != RG_SIGNAL || v->p == NULL) {
        return NULL;
    }
    return (RGSignal *) (void *) v->p;
}

static void rg_signal_method(RGValue *dest, RGValue *base, int32_t argc, const char *name);
static void rg_signal_emit_now(RGSignal *sig, RGValue *args, int32_t argc);
static RGSignal *rg_struct_signal(RGStruct *s, const char *name);
static RGFuture *rg_as_future(const RGValue *v);
static void rg_future_cancel(RGFuture *fut);
static int32_t rg_ui_alive_id(int64_t id);
static void rg_ui_call(RGValue *dest, const char *name, RGValue *args, int32_t argc);

static void rg_make_enum(RGValue *dest, const char *type, const char *variant, RGValue *base, int32_t argc);

static char *rg_strdup(const char *s) {
    if (s == NULL) {
        s = "";
    }
    size_t n = strlen(s);
    char *out = (char *) malloc(n + 1);
    if (out == NULL) {
        rg_die("out of memory");
    }
    memcpy(out, s, n + 1);
    return out;
}

static char *rg_strndup(const char *s, size_t n) {
    char *out;
    if (s == NULL) {
        s = "";
        n = 0;
    }
    out = (char *) malloc(n + 1);
    if (out == NULL) {
        rg_die("out of memory");
    }
    memcpy(out, s, n);
    out[n] = '\0';
    return out;
}

static RGMap *rg_alloc_map(int32_t cap) {
    RGMap *m = (RGMap *) malloc(sizeof(RGMap));
    if (m == NULL) {
        rg_die("out of memory");
    }
    m->len = 0;
    m->cap = cap < 0 ? 0 : cap;
    m->entries = NULL;
    if (m->cap > 0) {
        m->entries = (RGMapEntry *) calloc((size_t) m->cap, sizeof(RGMapEntry));
        if (m->entries == NULL) {
            rg_die("out of memory");
        }
    }
    return m;
}

static void rg_set_map(RGValue *dest, RGMap *m) {
    if (dest == NULL) {
        return;
    }
    dest->kind = RG_MAP;
    dest->i = 0;
    dest->p = (const char *) m;
}

static void rg_map_reserve(RGMap *m, int32_t need) {
    if (m == NULL || need <= m->cap) {
        return;
    }
    int32_t cap = m->cap < 4 ? 4 : m->cap;
    while (cap < need) {
        if (cap > (1 << 30)) {
            rg_die("out of memory");
        }
        cap *= 2;
    }
    RGMapEntry *entries = (RGMapEntry *) realloc(m->entries, (size_t) cap * sizeof(RGMapEntry));
    if (entries == NULL) {
        rg_die("out of memory");
    }
    m->entries = entries;
    m->cap = cap;
}

static int32_t rg_map_find(const RGMap *m, const char *key) {
    if (m == NULL || key == NULL) {
        return -1;
    }
    for (int32_t i = 0; i < m->len; i++) {
        if (m->entries[i].key != NULL && strcmp(m->entries[i].key, key) == 0) {
            return i;
        }
    }
    return -1;
}

static void rg_map_put(RGMap *m, const char *key, const RGValue *val) {
    int32_t i = rg_map_find(m, key);
    if (i >= 0) {
        rg_copy(&m->entries[i].value, val);
        return;
    }
    rg_map_reserve(m, m->len + 1);
    m->entries[m->len].key = rg_strdup(key);
    rg_copy(&m->entries[m->len].value, val);
    m->len++;
}

static const char *rg_method_key(const RGValue *v, char *buf, size_t n) {
    if (v == NULL) {
        rg_die("map key must be String");
    }
    if (v->kind == RG_STRING) {
        return v->p != NULL ? v->p : "";
    }
    if (v->kind == RG_INT) {
        snprintf(buf, n, "%lld", (long long) v->i);
        return buf;
    }
    if (v->kind == RG_BOOL) {
        return v->i ? "true" : "false";
    }
    rg_die("map key must be String");
    return "";
}

static RGArray *rg_array_reserve(RGArray *arr, int32_t need) {
    RGValue *items;
    int32_t cap;
    if (arr == NULL) {
        arr = rg_alloc_array(0);
    }
    if (need <= arr->cap) {
        return arr;
    }
    cap = arr->cap < 4 ? 4 : arr->cap;
    while (cap < need) {
        if (cap > (1 << 30)) {
            rg_die("out of memory");
        }
        cap *= 2;
    }
    items = (RGValue *) realloc(arr->items, (size_t) cap * sizeof(RGValue));
    if (items == NULL) {
        rg_die("out of memory");
    }
    arr->items = items;
    arr->cap = cap;
    return arr;
}

static RGArray *rg_array_push(RGArray *arr, const RGValue *v) {
    arr = rg_array_reserve(arr, (arr == NULL ? 0 : arr->len) + 1);
    rg_copy(&arr->items[arr->len], v);
    arr->len++;
    return arr;
}

static void rg_key_missing(const char *key) {
    fprintf(stderr, "key '%s' not found\n", key != NULL ? key : "");
    exit(1);
}

static RGArray *rg_alloc_array(int32_t n) {
    RGArray *arr;
    if (n < 0) {
        n = 0;
    }
    arr = (RGArray *) malloc(sizeof(RGArray));
    if (arr == NULL) {
        rg_die("out of memory");
    }
    arr->len = n;
    arr->cap = n;
    arr->items = NULL;
    if (n > 0) {
        arr->items = (RGValue *) malloc((size_t) n * sizeof(RGValue));
        if (arr->items == NULL) {
            rg_die("out of memory");
        }
        for (int32_t i = 0; i < n; i++) {
            rg_set_void(&arr->items[i]);
        }
    }
    return arr;
}

static void rg_set_array(RGValue *dest, RGArray *arr) {
    if (dest == NULL) {
        return;
    }
    dest->kind = RG_ARRAY;
    dest->i = 0;
    dest->p = (const char *) arr;
}

static void rg_index_oob(int64_t i) {
    fprintf(stderr, "index %lld out of bounds\n", (long long) i);
    exit(1);
}

static double rg_f64(const RGValue *v) {
    if (v->kind == RG_FLOAT) {
        double x;
        memcpy(&x, &v->i, sizeof(x));
        return x;
    }
    return (double) v->i;
}

static int rg_numeric(const RGValue *v) {
    return v != NULL && (v->kind == RG_INT || v->kind == RG_FLOAT);
}

static int rg_num_eq(double a, double b) {
    return a == b || fabs(a - b) < 1e-9;
}

int32_t rg_truthy(const RGValue *v) {
    if (v == NULL) {
        return 0;
    }
    switch (v->kind) {
        case RG_BOOL:
        case RG_INT:
            return v->i != 0;
        case RG_FLOAT:
            return rg_f64(v) != 0.0;
        case RG_STRING:
            return v->p != NULL && v->p[0] != '\0';
        case RG_VOID:
            return 0;
        default:
            return 1;
    }
}

static int32_t rg_eq(const RGValue *a, const RGValue *b) {
    if (a == NULL || b == NULL) {
        return a == b;
    }
    if (rg_numeric(a) && rg_numeric(b) && a->kind != b->kind) {
        return rg_num_eq(rg_f64(a), rg_f64(b));
    }
    if (a->kind != b->kind) {
        return 0;
    }
    switch (a->kind) {
        case RG_VOID:
            return 1;
        case RG_BOOL:
        case RG_INT:
            return a->i == b->i;
        case RG_FLOAT:
            return rg_num_eq(rg_f64(a), rg_f64(b));
        case RG_STRING: {
            const char *as = a->p != NULL ? a->p : "";
            const char *bs = b->p != NULL ? b->p : "";
            return strcmp(as, bs) == 0;
        }
        case RG_RANGE: {
            RGRange *ra = rg_as_range(a);
            RGRange *rb = rg_as_range(b);
            if (ra == NULL || rb == NULL) {
                return ra == rb && a->i == b->i;
            }
            return a->i == b->i && ra->end == rb->end && ra->inclusive == rb->inclusive;
        }
        case RG_ARRAY: {
            RGArray *aa = rg_as_array(a);
            RGArray *bb = rg_as_array(b);
            if (aa == bb) {
                return 1;
            }
            if (aa == NULL || bb == NULL || aa->len != bb->len) {
                return 0;
            }
            for (int32_t i = 0; i < aa->len; i++) {
                if (!rg_eq(&aa->items[i], &bb->items[i])) {
                    return 0;
                }
            }
            return 1;
        }
        case RG_MAP: {
            RGMap *ma = rg_as_map(a);
            RGMap *mb = rg_as_map(b);
            if (ma == mb) {
                return 1;
            }
            if (ma == NULL || mb == NULL || ma->len != mb->len) {
                return 0;
            }
            for (int32_t i = 0; i < ma->len; i++) {
                const char *key = ma->entries[i].key != NULL ? ma->entries[i].key : "";
                int32_t j = rg_map_find(mb, key);
                if (j < 0 || !rg_eq(&ma->entries[i].value, &mb->entries[j].value)) {
                    return 0;
                }
            }
            return 1;
        }
        case RG_STRUCT: {
            RGStruct *sa = rg_as_struct(a);
            RGStruct *sb = rg_as_struct(b);
            if (sa == sb) {
                return 1;
            }
            if (sa == NULL || sb == NULL || sa->len != sb->len) {
                return 0;
            }
            const char *na = sa->name != NULL ? sa->name : "";
            const char *nb = sb->name != NULL ? sb->name : "";
            if (strcmp(na, nb) != 0) {
                return 0;
            }
            for (int32_t i = 0; i < sa->len; i++) {
                const char *key = sa->fields != NULL && sa->fields[i] != NULL ? sa->fields[i] : "";
                int32_t j = -1;
                for (int32_t k = 0; k < sb->len; k++) {
                    const char *ok = sb->fields != NULL && sb->fields[k] != NULL ? sb->fields[k] : "";
                    if (strcmp(key, ok) == 0) {
                        j = k;
                        break;
                    }
                }
                if (j < 0 || !rg_eq(&sa->values[i], &sb->values[j])) {
                    return 0;
                }
            }
            return 1;
        }
        case RG_ENUM_TYPE: {
            const char *as = a->p != NULL ? a->p : "";
            const char *bs = b->p != NULL ? b->p : "";
            return strcmp(as, bs) == 0;
        }
        case RG_ENUM: {
            RGEnum *ea = rg_as_enum(a);
            RGEnum *eb = rg_as_enum(b);
            if (ea == eb) {
                return 1;
            }
            if (ea == NULL || eb == NULL || ea->n != eb->n) {
                return 0;
            }
            const char *ta = ea->type != NULL ? ea->type : "";
            const char *tb = eb->type != NULL ? eb->type : "";
            const char *va = ea->variant != NULL ? ea->variant : "";
            const char *vb = eb->variant != NULL ? eb->variant : "";
            if (strcmp(ta, tb) != 0 || strcmp(va, vb) != 0) {
                return 0;
            }
            for (int32_t i = 0; i < ea->n; i++) {
                if (!rg_eq(&ea->payload[i], &eb->payload[i])) {
                    return 0;
                }
            }
            return 1;
        }
        case RG_FN:
        case RG_FUTURE:
        case RG_SIGNAL:
            return a->p == b->p;
        default:
            return 0;
    }
}

void rg_bin(RGValue *dest, const RGValue *a, const RGValue *b, int32_t op) {
    if (dest == NULL || a == NULL || b == NULL) {
        rg_die("operands must be numbers");
    }
    if (op == RG_BIN_ADD && a->kind == RG_STRING && b->kind == RG_STRING) {
        const char *as = a->p != NULL ? a->p : "";
        const char *bs = b->p != NULL ? b->p : "";
        size_t n = strlen(as) + strlen(bs);
        char *out = (char *) malloc(n + 1);
        if (out == NULL) {
            rg_die("out of memory");
        }
        memcpy(out, as, strlen(as));
        memcpy(out + strlen(as), bs, strlen(bs) + 1);
        rg_set_string(dest, out);
        return;
    }
    if (op == RG_BIN_EQ) {
        rg_set_bool(dest, rg_eq(a, b));
        return;
    }
    if (op == RG_BIN_NE) {
        rg_set_bool(dest, !rg_eq(a, b));
        return;
    }
    if (!rg_numeric(a) || !rg_numeric(b)) {
        rg_die("operands must be numbers");
    }
    int both_int = a->kind == RG_INT && b->kind == RG_INT;
    if (both_int) {
        int64_t x = a->i;
        int64_t y = b->i;
        int64_t z;
        switch (op) {
            case RG_BIN_ADD:
                if (__builtin_add_overflow(x, y, &z)) {
                    rg_die("integer overflow");
                }
                rg_set_int(dest, z);
                return;
            case RG_BIN_SUB:
                if (__builtin_sub_overflow(x, y, &z)) {
                    rg_die("integer overflow");
                }
                rg_set_int(dest, z);
                return;
            case RG_BIN_MUL:
                if (__builtin_mul_overflow(x, y, &z)) {
                    rg_die("integer overflow");
                }
                rg_set_int(dest, z);
                return;
            case RG_BIN_DIV:
                if (y == 0) {
                    rg_die("division by zero");
                }
                if (x == INT64_MIN && y == -1) {
                    rg_die("integer overflow");
                }
                rg_set_int(dest, x / y);
                return;
            case RG_BIN_MOD:
                if (y == 0) {
                    rg_die("modulo by zero");
                }
                if (x == INT64_MIN && y == -1) {
                    rg_die("integer overflow");
                }
                rg_set_int(dest, x % y);
                return;
            case RG_BIN_LT:
                rg_set_bool(dest, x < y);
                return;
            case RG_BIN_GT:
                rg_set_bool(dest, x > y);
                return;
            case RG_BIN_LE:
                rg_set_bool(dest, x <= y);
                return;
            case RG_BIN_GE:
                rg_set_bool(dest, x >= y);
                return;
            default:
                rg_die("unknown operator");
        }
    }
    double x = rg_f64(a);
    double y = rg_f64(b);
    switch (op) {
        case RG_BIN_ADD:
            rg_set_float(dest, x + y);
            return;
        case RG_BIN_SUB:
            rg_set_float(dest, x - y);
            return;
        case RG_BIN_MUL:
            rg_set_float(dest, x * y);
            return;
        case RG_BIN_DIV:
            if (y == 0.0) {
                rg_die("division by zero");
            }
            rg_set_float(dest, x / y);
            return;
        case RG_BIN_MOD:
            if (y == 0.0) {
                rg_die("modulo by zero");
            }
            rg_set_float(dest, fmod(x, y));
            return;
        case RG_BIN_LT:
            rg_set_bool(dest, x < y);
            return;
        case RG_BIN_GT:
            rg_set_bool(dest, x > y);
            return;
        case RG_BIN_LE:
            rg_set_bool(dest, x <= y);
            return;
        case RG_BIN_GE:
            rg_set_bool(dest, x >= y);
            return;
        default:
            rg_die("unknown operator");
    }
}

void rg_unary(RGValue *dest, const RGValue *src, int32_t op) {
    if (dest == NULL || src == NULL) {
        rg_die("unary '-' expects a number");
    }
    if (op == RG_UN_NEG) {
        if (src->kind == RG_FLOAT) {
            rg_set_float(dest, -rg_f64(src));
            return;
        }
        if (src->kind != RG_INT) {
            rg_die("unary '-' expects a number");
        }
        if (src->i == INT64_MIN) {
            rg_die("integer overflow");
        }
        rg_set_int(dest, -src->i);
        return;
    }
    rg_set_bool(dest, !rg_truthy(src));
}

void rg_array(RGValue *dest, RGValue *base, int32_t argc) {
    if (dest == NULL) {
        return;
    }
    if (argc < 0) {
        argc = 0;
    }
    RGArray *arr = rg_alloc_array(argc);
    for (int32_t i = 0; i < argc; i++) {
        rg_copy(&arr->items[i], base != NULL ? &base[i] : NULL);
    }
    rg_set_array(dest, arr);
}

void rg_range(RGValue *dest, const RGValue *start, const RGValue *end, int32_t inclusive) {
    if (dest == NULL) {
        return;
    }
    if (start == NULL || start->kind != RG_INT) {
        rg_die("range start must be Int");
    }
    if (end == NULL || end->kind != RG_INT) {
        rg_die("range end must be Int");
    }
    RGRange *r = (RGRange *) malloc(sizeof(RGRange));
    if (r == NULL) {
        rg_die("out of memory");
    }
    r->end = end->i;
    r->inclusive = inclusive ? 1 : 0;
    dest->kind = RG_RANGE;
    dest->i = start->i;
    dest->p = (const char *) r;
}

void rg_index_get(RGValue *dest, const RGValue *obj, const RGValue *idx) {
    if (dest == NULL) {
        return;
    }
    if (obj != NULL && obj->kind == RG_MAP) {
        if (idx == NULL || idx->kind != RG_STRING) {
            rg_die("map key must be String");
        }
        const char *key = idx->p != NULL ? idx->p : "";
        RGMap *m = rg_as_map(obj);
        int32_t i = rg_map_find(m, key);
        if (i < 0) {
            rg_key_missing(key);
        }
        rg_copy(dest, &m->entries[i].value);
        return;
    }
    if (obj != NULL && obj->kind == RG_ARRAY) {
        if (idx == NULL || idx->kind != RG_INT) {
            rg_die("index must be Int");
        }
        if (idx->i < 0) {
            rg_index_oob(idx->i);
        }
        RGArray *arr = rg_as_array(obj);
        if (arr == NULL || idx->i >= arr->len) {
            rg_index_oob(idx->i);
        }
        rg_copy(dest, &arr->items[(int32_t) idx->i]);
        return;
    }
    if (obj != NULL && obj->kind == RG_STRING) {
        if (idx == NULL || idx->kind != RG_INT) {
            rg_die("index must be Int");
        }
        if (idx->i < 0) {
            rg_index_oob(idx->i);
        }
        const char *s = obj->p != NULL ? obj->p : "";
        size_t n = strlen(s);
        if ((uint64_t) idx->i >= n) {
            rg_index_oob(idx->i);
        }
        char *ch = (char *) malloc(2);
        if (ch == NULL) {
            rg_die("out of memory");
        }
        ch[0] = s[(size_t) idx->i];
        ch[1] = '\0';
        rg_set_string(dest, ch);
        return;
    }
    rg_die("cannot index value");
}

void rg_index_set(RGValue *obj, const RGValue *idx, const RGValue *value) {
    if (obj != NULL && obj->kind == RG_MAP) {
        RGMap *m = rg_as_map(obj);
        if (m == NULL) {
            rg_die("cannot assign to value");
        }
        if (idx == NULL || idx->kind != RG_STRING) {
            rg_die("map key must be String");
        }
        rg_map_put(m, idx->p != NULL ? idx->p : "", value);
        return;
    }
    if (obj == NULL || obj->kind != RG_ARRAY) {
        rg_die("cannot assign to value");
    }
    RGArray *arr = rg_as_array(obj);
    if (arr == NULL) {
        rg_die("cannot assign to value");
    }
    if (idx == NULL || idx->kind != RG_INT || idx->i < 0 || idx->i >= arr->len) {
        rg_index_oob(idx != NULL && idx->kind == RG_INT ? idx->i : -1);
    }
    rg_copy(&arr->items[(int32_t) idx->i], value);
}

void rg_iter_items(RGValue *dest, const RGValue *iter) {
    if (dest == NULL) {
        return;
    }
    if (iter != NULL && iter->kind == RG_ARRAY) {
        RGArray *src = rg_as_array(iter);
        int32_t n = src == NULL ? 0 : src->len;
        RGArray *arr = rg_alloc_array(n);
        for (int32_t i = 0; i < n; i++) {
            rg_copy(&arr->items[i], &src->items[i]);
        }
        rg_set_array(dest, arr);
        return;
    }
    if (iter != NULL && iter->kind == RG_MAP) {
        RGMap *m = rg_as_map(iter);
        int32_t n = m == NULL ? 0 : m->len;
        RGArray *arr = rg_alloc_array(n);
        for (int32_t i = 0; i < n; i++) {
            rg_set_string(&arr->items[i], rg_strdup(m->entries[i].key));
        }
        rg_set_array(dest, arr);
        return;
    }
    if (iter != NULL && iter->kind == RG_STRING) {
        const char *s = iter->p != NULL ? iter->p : "";
        size_t n = strlen(s);
        if (n > RG_MAX_RANGE) {
            rg_die("range too large");
        }
        RGArray *arr = rg_alloc_array((int32_t) n);
        for (size_t i = 0; i < n; i++) {
            char *ch = (char *) malloc(2);
            if (ch == NULL) {
                rg_die("out of memory");
            }
            ch[0] = s[i];
            ch[1] = '\0';
            rg_set_string(&arr->items[i], ch);
        }
        rg_set_array(dest, arr);
        return;
    }
    if (iter != NULL && iter->kind == RG_INT) {
        if (iter->i <= 0) {
            rg_set_array(dest, rg_alloc_array(0));
            return;
        }
        if (iter->i > RG_MAX_RANGE) {
            rg_die("range too large");
        }
        int32_t n = (int32_t) iter->i;
        RGArray *arr = rg_alloc_array(n);
        for (int32_t i = 0; i < n; i++) {
            rg_set_int(&arr->items[i], i);
        }
        rg_set_array(dest, arr);
        return;
    }
    if (iter != NULL && iter->kind == RG_RANGE) {
        RGRange *r = rg_as_range(iter);
        if (r == NULL) {
            rg_die("cannot iterate over value");
        }
        int32_t n = 0;
        if (r->inclusive) {
            for (int64_t x = iter->i; x <= r->end; x++) {
                n++;
                if (n > RG_MAX_RANGE) {
                    rg_die("range too large");
                }
                if (x == INT64_MAX) {
                    break;
                }
            }
        } else {
            for (int64_t x = iter->i; x < r->end; x++) {
                n++;
                if (n > RG_MAX_RANGE) {
                    rg_die("range too large");
                }
            }
        }
        RGArray *arr = rg_alloc_array(n);
        int32_t i = 0;
        if (r->inclusive) {
            for (int64_t x = iter->i; x <= r->end && i < n; x++) {
                rg_set_int(&arr->items[i++], x);
                if (x == INT64_MAX) {
                    break;
                }
            }
        } else {
            for (int64_t x = iter->i; x < r->end && i < n; x++) {
                rg_set_int(&arr->items[i++], x);
            }
        }
        rg_set_array(dest, arr);
        return;
    }
    rg_die("cannot iterate over value");
}

void rg_len(RGValue *dest, const RGValue *src) {
    if (dest == NULL) {
        return;
    }
    if (src != NULL && src->kind == RG_ARRAY) {
        RGArray *arr = rg_as_array(src);
        rg_set_int(dest, arr == NULL ? 0 : arr->len);
        return;
    }
    if (src != NULL && src->kind == RG_STRING) {
        const char *s = src->p != NULL ? src->p : "";
        rg_set_int(dest, (int64_t) strlen(s));
        return;
    }
    if (src != NULL && src->kind == RG_MAP) {
        RGMap *m = rg_as_map(src);
        rg_set_int(dest, m == NULL ? 0 : m->len);
        return;
    }
    rg_die("len expects Array, String, or Map");
}

void rg_assert(RGValue *dest, RGValue *args, int32_t argc) {
    RGValue err;
    if (argc != 1) {
        rg_set_string(&err, "assert takes 1 argument");
        rg_throw(&err);
        if (dest != NULL) {
            rg_set_void(dest);
        }
        return;
    }
    if (!rg_truthy(&args[0])) {
        rg_set_string(&err, "assertion failed");
        rg_throw(&err);
        if (dest != NULL) {
            rg_set_void(dest);
        }
        return;
    }
    if (dest != NULL) {
        rg_set_void(dest);
    }
}

void rg_map(RGValue *dest, RGValue *base, int32_t argc) {
    if (dest == NULL) {
        return;
    }
    if (argc < 0 || argc % 2 != 0) {
        rg_die("invalid map literal");
    }
    RGMap *m = rg_alloc_map(argc / 2);
    for (int32_t i = 0; i < argc; i += 2) {
        RGValue *key = base != NULL ? &base[i] : NULL;
        RGValue *val = base != NULL ? &base[i + 1] : NULL;
        if (key == NULL || key->kind != RG_STRING) {
            rg_die("map key must be String");
        }
        rg_map_put(m, key->p != NULL ? key->p : "", val);
    }
    rg_set_map(dest, m);
}

void rg_method(RGValue *dest, RGValue *base, int32_t argc, int32_t op) {
    if (dest == NULL) {
        return;
    }
    if (base == NULL || argc < 1) {
        rg_die("can only call a function");
    }
    RGValue *recv = &base[0];
    int32_t nargs = argc - 1;
    if (op == RG_M_LEN) {
        if (nargs != 0) {
            rg_die("len takes 0 arguments");
        }
        rg_len(dest, recv);
        return;
    }
    if (recv->kind == RG_ARRAY) {
        RGArray *arr = rg_as_array(recv);
        if (op == RG_M_PUSH) {
            if (nargs != 1) {
                rg_die("Array.push takes 1 argument");
            }
            if (arr == NULL) {
                rg_die("cannot push on array");
            }
            arr = rg_array_reserve(arr, arr->len + 1);
            rg_copy(&arr->items[arr->len], &base[1]);
            arr->len++;
            rg_set_void(dest);
            return;
        }
        if (op == RG_M_POP) {
            if (nargs != 0) {
                rg_die("Array.pop takes 0 arguments");
            }
            if (arr == NULL || arr->len <= 0) {
                rg_die("pop from empty array");
            }
            arr->len--;
            rg_copy(dest, &arr->items[arr->len]);
            return;
        }
        rg_die("Array has no method");
    }
    if (recv->kind == RG_MAP) {
        RGMap *m = rg_as_map(recv);
        if (m == NULL) {
            rg_die("cannot call method on value");
        }
        if (op == RG_M_HAS) {
            if (nargs != 1) {
                rg_die("Map.has takes 1 argument");
            }
            char buf[64];
            const char *key = rg_method_key(&base[1], buf, sizeof(buf));
            rg_set_bool(dest, rg_map_find(m, key) >= 0);
            return;
        }
        if (op == RG_M_KEYS) {
            if (nargs != 0) {
                rg_die("Map.keys takes 0 arguments");
            }
            RGArray *arr = rg_alloc_array(m->len);
            for (int32_t i = 0; i < m->len; i++) {
                rg_set_string(&arr->items[i], rg_strdup(m->entries[i].key));
            }
            rg_set_array(dest, arr);
            return;
        }
        if (op == RG_M_REMOVE) {
            if (nargs != 1) {
                rg_die("Map.remove takes 1 argument");
            }
            char buf[64];
            const char *key = rg_method_key(&base[1], buf, sizeof(buf));
            int32_t i = rg_map_find(m, key);
            if (i < 0) {
                rg_key_missing(key);
            }
            rg_copy(dest, &m->entries[i].value);
            free(m->entries[i].key);
            for (int32_t j = i + 1; j < m->len; j++) {
                m->entries[j - 1] = m->entries[j];
            }
            m->len--;
            return;
        }
        if (op == RG_M_INSERT) {
            if (nargs != 2) {
                rg_die("Map.insert takes 2 arguments");
            }
            char buf[64];
            const char *key = rg_method_key(&base[1], buf, sizeof(buf));
            rg_map_put(m, key, &base[2]);
            rg_set_void(dest);
            return;
        }
        rg_die("Map has no method");
    }
    if (recv->kind == RG_STRING) {
        rg_die("String has no method");
    }
    rg_die("cannot call method on value");
}

static int32_t rg_struct_find(const RGStruct *s, const char *name) {
    if (s == NULL || name == NULL) {
        return -1;
    }
    for (int32_t i = 0; i < s->len; i++) {
        const char *f = s->fields != NULL && s->fields[i] != NULL ? s->fields[i] : "";
        if (strcmp(f, name) == 0) {
            return i;
        }
    }
    return -1;
}

void rg_struct(RGValue *dest, const char *name, RGValue *base, int32_t n, const char **fields, int32_t is_data) {
    if (dest == NULL) {
        return;
    }
    if (n < 0) {
        n = 0;
    }
    RGStruct *s = (RGStruct *) malloc(sizeof(RGStruct));
    if (s == NULL) {
        rg_die("out of memory");
    }
    s->name = rg_strdup(name != NULL ? name : "");
    s->len = n;
    s->is_data = is_data ? 1 : 0;
    s->fields = NULL;
    s->values = NULL;
    s->nsigs = 0;
    s->csigs = 0;
    s->sig_names = NULL;
    s->sigs = NULL;
    if (n > 0) {
        s->fields = (char **) calloc((size_t) n, sizeof(char *));
        s->values = (RGValue *) calloc((size_t) n, sizeof(RGValue));
        if (s->fields == NULL || s->values == NULL) {
            rg_die("out of memory");
        }
        for (int32_t i = 0; i < n; i++) {
            s->fields[i] = rg_strdup(fields != NULL && fields[i] != NULL ? fields[i] : "");
            rg_copy(&s->values[i], base != NULL ? &base[i] : NULL);
        }
    }
    dest->kind = RG_STRUCT;
    dest->i = 0;
    dest->p = (const char *) s;
}

void rg_member(RGValue *dest, const RGValue *obj, const char *name) {
    if (dest == NULL) {
        return;
    }
    if (name == NULL) {
        name = "";
    }
    if (obj != NULL && (obj->kind == RG_ARRAY || obj->kind == RG_MAP || obj->kind == RG_STRING)
            && strcmp(name, "len") == 0) {
        rg_len(dest, obj);
        return;
    }
    if (obj != NULL && obj->kind == RG_ENUM_TYPE) {
        rg_make_enum(dest, obj->p, name, NULL, 0);
        return;
    }
    RGStruct *s = rg_as_struct(obj);
    if (s == NULL) {
        rg_die("cannot read field on value");
    }
    int32_t i = rg_struct_find(s, name);
    if (i >= 0) {
        rg_copy(dest, &s->values[i]);
        return;
    }
    RGSignal *sig = rg_struct_signal(s, name);
    if (sig != NULL) {
        dest->kind = RG_SIGNAL;
        dest->i = 0;
        dest->p = (const char *) sig;
        return;
    }
    fprintf(stderr, "struct %s has no field '%s'\n", s->name != NULL ? s->name : "", name);
    exit(1);
}

void rg_field_set(RGValue *obj, const char *name, const RGValue *val) {
    RGStruct *s = rg_as_struct(obj);
    if (s == NULL) {
        rg_die("cannot assign field on value");
    }
    if (name == NULL) {
        name = "";
    }
    int32_t i = rg_struct_find(s, name);
    if (i < 0) {
        fprintf(stderr, "struct %s has no field '%s'\n", s->name != NULL ? s->name : "", name);
        exit(1);
    }
    if (s->is_data) {
        fprintf(stderr, "cannot assign to data field '%s'\n", name);
        exit(1);
    }
    rg_copy(&s->values[i], val);
}

void rg_register_method(const char *type, const char *method, void (*fn)(RGValue *, RGValue *, int32_t)) {
    if (fn == NULL) {
        return;
    }
    if (g_nmethods >= g_cmethods) {
        int32_t cap = g_cmethods < 8 ? 8 : g_cmethods * 2;
        RGMethodReg *next = (RGMethodReg *) realloc(g_methods, (size_t) cap * sizeof(RGMethodReg));
        if (next == NULL) {
            rg_die("out of memory");
        }
        g_methods = next;
        g_cmethods = cap;
    }
    g_methods[g_nmethods].type = type != NULL ? type : "";
    g_methods[g_nmethods].method = method != NULL ? method : "";
    g_methods[g_nmethods].fn = fn;
    g_nmethods++;
}

void rg_register_parent(const char *child, const char *parent) {
    if (child == NULL || child[0] == '\0' || parent == NULL || parent[0] == '\0') {
        return;
    }
    if (g_nparents >= g_cparents) {
        int32_t cap = g_cparents < 8 ? 8 : g_cparents * 2;
        RGParentReg *next = (RGParentReg *) realloc(g_parents, (size_t) cap * sizeof(RGParentReg));
        if (next == NULL) {
            rg_die("out of memory");
        }
        g_parents = next;
        g_cparents = cap;
    }
    g_parents[g_nparents].child = child;
    g_parents[g_nparents].parent = parent;
    g_nparents++;
}

static const char *rg_parent_of(const char *type) {
    if (type == NULL) {
        return NULL;
    }
    for (int32_t i = 0; i < g_nparents; i++) {
        const char *child = g_parents[i].child != NULL ? g_parents[i].child : "";
        if (strcmp(child, type) == 0) {
            return g_parents[i].parent;
        }
    }
    return NULL;
}

static RGFn rg_lookup_method_on(const char *type, const char *method) {
    if (type == NULL) {
        type = "";
    }
    if (method == NULL) {
        method = "";
    }
    for (int32_t i = 0; i < g_nmethods; i++) {
        const char *t = g_methods[i].type != NULL ? g_methods[i].type : "";
        const char *m = g_methods[i].method != NULL ? g_methods[i].method : "";
        if (strcmp(t, type) == 0 && strcmp(m, method) == 0) {
            return g_methods[i].fn;
        }
    }
    return NULL;
}

static RGFn rg_lookup_method(const char *type, const char *method) {
    int32_t n = 0;
    while (type != NULL && type[0] != '\0' && n < 64) {
        RGFn fn = rg_lookup_method_on(type, method);
        if (fn != NULL) {
            return fn;
        }
        type = rg_parent_of(type);
        n++;
    }
    return NULL;
}

void rg_call_method(RGValue *dest, RGValue *base, int32_t argc, const char *name) {
    if (dest == NULL) {
        return;
    }
    if (base == NULL || argc < 1) {
        rg_die("can only call a function");
    }
    if (name == NULL) {
        name = "";
    }
    RGValue *recv = &base[0];
    if (recv->kind == RG_SIGNAL) {
        rg_signal_method(dest, base, argc, name);
        return;
    }
    if (recv->kind == RG_FUTURE) {
        if (strcmp(name, "cancel") != 0) {
            rg_die("Future has no method");
        }
        if (argc != 1) {
            rg_die("Future.cancel takes 0 arguments");
        }
        rg_future_cancel(rg_as_future(recv));
        rg_set_void(dest);
        return;
    }
    if (recv->kind == RG_STRUCT) {
        RGStruct *s = rg_as_struct(recv);
        const char *type = s != NULL && s->name != NULL ? s->name : "";
        RGFn fn = rg_lookup_method(type, name);
        if (fn == NULL) {
            fprintf(stderr, "struct %s has no method '%s'\n", type, name);
            exit(1);
        }
        fn(dest, base, argc);
        return;
    }
    if (recv->kind == RG_ARRAY || recv->kind == RG_MAP || recv->kind == RG_STRING) {
        int32_t op = 0;
        if (strcmp(name, "len") == 0) {
            op = RG_M_LEN;
        } else if (strcmp(name, "push") == 0) {
            op = RG_M_PUSH;
        } else if (strcmp(name, "pop") == 0) {
            op = RG_M_POP;
        } else if (strcmp(name, "has") == 0) {
            op = RG_M_HAS;
        } else if (strcmp(name, "keys") == 0) {
            op = RG_M_KEYS;
        } else if (strcmp(name, "remove") == 0) {
            op = RG_M_REMOVE;
        } else if (strcmp(name, "insert") == 0) {
            op = RG_M_INSERT;
        }
        if (op != 0) {
            rg_method(dest, base, argc, op);
            return;
        }
    }
    if (recv->kind == RG_ENUM_TYPE) {
        rg_make_enum(dest, recv->p, name, argc > 1 ? &base[1] : NULL, argc - 1);
        return;
    }
    rg_die("cannot call method on value");
}

void rg_enum_type(RGValue *dest, const char *name) {
    if (dest == NULL) {
        return;
    }
    dest->kind = RG_ENUM_TYPE;
    dest->i = 0;
    dest->p = name != NULL ? name : "";
}

void rg_register_enum(const char *type, const char *variant, int32_t arity, const char **fields) {
    if (g_nenums >= g_cenums) {
        int32_t cap = g_cenums < 8 ? 8 : g_cenums * 2;
        RGEnumReg *next = (RGEnumReg *) realloc(g_enums, (size_t) cap * sizeof(RGEnumReg));
        if (next == NULL) {
            rg_die("out of memory");
        }
        g_enums = next;
        g_cenums = cap;
    }
    g_enums[g_nenums].type = type != NULL ? type : "";
    g_enums[g_nenums].variant = variant != NULL ? variant : "";
    g_enums[g_nenums].arity = arity;
    g_enums[g_nenums].fields = fields;
    g_nenums++;
}

static RGEnumReg *rg_lookup_enum(const char *type, const char *variant) {
    if (type == NULL) {
        type = "";
    }
    if (variant == NULL) {
        variant = "";
    }
    for (int32_t i = 0; i < g_nenums; i++) {
        const char *t = g_enums[i].type != NULL ? g_enums[i].type : "";
        const char *v = g_enums[i].variant != NULL ? g_enums[i].variant : "";
        if (strcmp(t, type) == 0 && strcmp(v, variant) == 0) {
            return &g_enums[i];
        }
    }
    return NULL;
}

static void rg_make_enum(RGValue *dest, const char *type, const char *variant, RGValue *base, int32_t argc) {
    if (dest == NULL) {
        return;
    }
    if (type == NULL) {
        type = "";
    }
    if (variant == NULL) {
        variant = "";
    }
    if (argc < 0) {
        argc = 0;
    }
    RGEnumReg *reg = rg_lookup_enum(type, variant);
    if (reg != NULL && argc != reg->arity) {
        fprintf(stderr, "%s.%s takes %d argument(s)\n", type, variant, (int) reg->arity);
        exit(1);
    }
    if (reg == NULL && (argc != 0 || g_nenums > 0)) {
        fprintf(stderr, "enum %s has no variant '%s'\n", type, variant);
        exit(1);
    }
    RGEnum *en = (RGEnum *) malloc(sizeof(RGEnum));
    if (en == NULL) {
        rg_die("out of memory");
    }
    en->type = rg_strdup(type);
    en->variant = rg_strdup(variant);
    en->n = argc;
    en->payload = NULL;
    en->fields = NULL;
    if (argc > 0) {
        en->payload = (RGValue *) calloc((size_t) argc, sizeof(RGValue));
        if (en->payload == NULL) {
            rg_die("out of memory");
        }
        for (int32_t i = 0; i < argc; i++) {
            rg_copy(&en->payload[i], base != NULL ? &base[i] : NULL);
        }
    }
    if (reg != NULL && reg->fields != NULL && argc > 0) {
        en->fields = (char **) calloc((size_t) argc, sizeof(char *));
        if (en->fields == NULL) {
            rg_die("out of memory");
        }
        for (int32_t i = 0; i < argc; i++) {
            en->fields[i] = rg_strdup(reg->fields[i]);
        }
    }
    dest->kind = RG_ENUM;
    dest->i = 0;
    dest->p = (const char *) en;
}

void rg_match_lit(RGValue *dest, const RGValue *value, const RGValue *pat) {
    if (dest == NULL) {
        return;
    }
    int32_t hit = 0;
    if (value != NULL && pat != NULL) {
        if (pat->kind == RG_INT) {
            hit = value->kind == RG_INT && value->i == pat->i;
        } else if (pat->kind == RG_FLOAT) {
            hit = rg_numeric(value) && rg_num_eq(rg_f64(value), rg_f64(pat));
        } else if (pat->kind == RG_STRING) {
            const char *vs = value->kind == RG_STRING && value->p != NULL ? value->p : NULL;
            const char *ps = pat->p != NULL ? pat->p : "";
            hit = vs != NULL && strcmp(vs, ps) == 0;
        } else if (pat->kind == RG_BOOL) {
            hit = value->kind == RG_BOOL && value->i == pat->i;
        }
    }
    rg_set_bool(dest, hit);
}

void rg_match_var(RGValue *dest, const RGValue *value, const char *name) {
    if (dest == NULL) {
        return;
    }
    RGEnum *en = rg_as_enum(value);
    if (en == NULL) {
        rg_die("match pattern does not match value");
    }
    if (name == NULL) {
        name = "";
    }
    const char *variant = en->variant != NULL ? en->variant : "";
    rg_set_bool(dest, strcmp(variant, name) == 0);
}

void rg_payload(RGValue *dest, const RGValue *value, int32_t index, const char *field) {
    if (dest == NULL) {
        return;
    }
    RGEnum *en = rg_as_enum(value);
    int32_t n = en == NULL ? 0 : en->n;
    RGValue *payload = en == NULL ? NULL : en->payload;
    if (field != NULL && field[0] != '\0') {
        if (en == NULL) {
            rg_die("undefined enum");
        }
        int32_t idx = -1;
        if (en->fields != NULL) {
            for (int32_t i = 0; i < n; i++) {
                const char *f = en->fields[i] != NULL ? en->fields[i] : "";
                if (strcmp(f, field) == 0) {
                    idx = i;
                    break;
                }
            }
        }
        if (idx < 0) {
            fprintf(stderr, "%s.%s has no field '%s'\n", en->type != NULL ? en->type : "",
                    en->variant != NULL ? en->variant : "", field);
            exit(1);
        }
        rg_copy(dest, &payload[idx]);
        return;
    }
    if (index < 0) {
        if (n == 1 && payload != NULL) {
            rg_copy(dest, &payload[0]);
            return;
        }
        rg_array(dest, payload, n);
        return;
    }
    if (index >= n || payload == NULL) {
        return;
    }
    rg_copy(dest, &payload[index]);
}

void rg_fn_ref(RGValue *dest, void (*fn)(RGValue *, RGValue *, int32_t), int32_t arity) {
    if (dest == NULL) {
        return;
    }
    RGFnVal *f = (RGFnVal *) malloc(sizeof(RGFnVal));
    if (f == NULL) {
        rg_die("out of memory");
    }
    f->fn = fn;
    f->arity = arity < 0 ? 0 : arity;
    f->ncaps = 0;
    f->caps = NULL;
    dest->kind = RG_FN;
    dest->i = 0;
    dest->p = (const char *) f;
}

void rg_closure(RGValue *dest, void (*fn)(RGValue *, RGValue *, int32_t), int32_t arity, RGValue *caps, int32_t ncaps) {
    if (dest == NULL) {
        return;
    }
    if (ncaps < 0) {
        ncaps = 0;
    }
    RGFnVal *f = (RGFnVal *) malloc(sizeof(RGFnVal));
    if (f == NULL) {
        rg_die("out of memory");
    }
    f->fn = fn;
    f->arity = arity < 0 ? 0 : arity;
    f->ncaps = ncaps;
    f->caps = NULL;
    if (ncaps > 0) {
        f->caps = (RGValue *) malloc((size_t) ncaps * sizeof(RGValue));
        if (f->caps == NULL) {
            rg_die("out of memory");
        }
        for (int32_t i = 0; i < ncaps; i++) {
            rg_copy(&f->caps[i], caps != NULL ? &caps[i] : NULL);
        }
    }
    dest->kind = RG_FN;
    dest->i = 0;
    dest->p = (const char *) f;
}

void rg_call_val(RGValue *dest, RGValue *base, int32_t argc) {
    if (dest == NULL) {
        return;
    }
    if (base == NULL || argc < 1) {
        rg_die("can only call a function");
    }
    RGFnVal *f = rg_as_fn(&base[0]);
    if (f == NULL || f->fn == NULL) {
        rg_die("can only call a function");
    }
    int32_t nargs = argc - 1;
    if (nargs != f->arity) {
        fprintf(stderr, "<fn> takes %d argument(s)\n", (int) f->arity);
        exit(1);
    }
    int32_t total = f->arity + f->ncaps;
    RGValue *frame = NULL;
    if (total > 0) {
        frame = (RGValue *) malloc((size_t) total * sizeof(RGValue));
        if (frame == NULL) {
            rg_die("out of memory");
        }
        for (int32_t i = 0; i < f->arity; i++) {
            rg_copy(&frame[i], &base[i + 1]);
        }
        for (int32_t i = 0; i < f->ncaps; i++) {
            rg_copy(&frame[f->arity + i], f->caps != NULL ? &f->caps[i] : NULL);
        }
    }
    f->fn(dest, frame, total);
    free(frame);
}

static int g_thrown;
static RGValue g_exc;

void rg_throw(RGValue *v) {
    g_thrown = 1;
    rg_copy(&g_exc, v);
}

int32_t rg_has_throw(void) {
    return g_thrown;
}

void rg_catch(RGValue *dest) {
    rg_copy(dest, &g_exc);
    g_thrown = 0;
    rg_set_void(&g_exc);
}

static RGFuture *rg_as_future(const RGValue *v) {
    if (v == NULL || v->kind != RG_FUTURE || v->p == NULL) {
        return NULL;
    }
    return (RGFuture *) (void *) v->p;
}

static int64_t rg_now_ms(void) {
#if defined(_WIN32)
    return (int64_t) GetTickCount64();
#else
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (int64_t) ts.tv_sec * 1000 + ts.tv_nsec / 1000000;
#endif
}

static void rg_sleep_ms(int64_t ms) {
    if (ms <= 0) {
        return;
    }
    if (ms > RG_MAX_SLEEP_MS) {
        ms = RG_MAX_SLEEP_MS;
    }
#if defined(_WIN32)
    Sleep((DWORD) ms);
#else
    {
        struct timespec ts;
        ts.tv_sec = (time_t) (ms / 1000);
        ts.tv_nsec = (long) (ms % 1000) * 1000000L;
        nanosleep(&ts, NULL);
    }
#endif
}

static void rg_check_joins(void);

static void rg_future_ready(RGFuture *fut, const RGValue *value) {
    if (fut == NULL || fut->state != RG_FUT_PENDING) {
        return;
    }
    fut->state = RG_FUT_READY;
    rg_copy(&fut->result, value);
    rg_check_joins();
}

static void rg_future_fail(RGFuture *fut, const RGValue *error) {
    if (fut == NULL || fut->state != RG_FUT_PENDING) {
        return;
    }
    fut->state = RG_FUT_FAILED;
    rg_copy(&fut->error, error);
    rg_check_joins();
}

static void rg_check_joins(void) {
    RGJoin *j;
    for (j = g_joins; j != NULL; j = j->next) {
        int32_t i;
        int32_t ready;
        if (j->done || j->out == NULL) {
            continue;
        }
        ready = 0;
        for (i = 0; i < j->n; i++) {
            RGFuture *in = j->ins != NULL ? j->ins[i] : NULL;
            if (in == NULL || in->state == RG_FUT_PENDING) {
                continue;
            }
            if (in->state == RG_FUT_FAILED) {
                j->done = 1;
                rg_future_fail(j->out, &in->error);
                break;
            }
            if (in->state == RG_FUT_READY) {
                ready++;
            }
        }
        if (j->done) {
            continue;
        }
        if (j->kind == 1) {
            for (i = 0; i < j->n; i++) {
                RGFuture *in = j->ins != NULL ? j->ins[i] : NULL;
                if (in != NULL && in->state == RG_FUT_READY) {
                    j->done = 1;
                    rg_future_ready(j->out, &in->result);
                    break;
                }
            }
        } else if (ready == j->n) {
            RGValue *items = NULL;
            RGValue arr;
            if (j->n > 0) {
                items = (RGValue *) malloc((size_t) j->n * sizeof(RGValue));
                if (items == NULL) {
                    rg_die("out of memory");
                }
                for (i = 0; i < j->n; i++) {
                    rg_copy(&items[i], &j->ins[i]->result);
                }
            }
            rg_array(&arr, items, j->n);
            j->done = 1;
            rg_future_ready(j->out, &arr);
            free(items);
        }
    }
}

static void rg_add_timer(RGFuture *fut, int64_t ms) {
    RGTimer *t = (RGTimer *) malloc(sizeof(RGTimer));
    if (t == NULL) {
        rg_die("out of memory");
    }
    if (ms < 0) {
        ms = 0;
    }
    t->deadline_ms = rg_now_ms() + ms;
    t->fut = fut;
    t->next = g_timers;
    g_timers = t;
}

static void rg_future_cancel(RGFuture *fut) {
    RGTimer **slot;
    RGValue err;
    if (fut == NULL || fut->state != RG_FUT_PENDING) {
        return;
    }
    slot = &g_timers;
    while (*slot != NULL) {
        if ((*slot)->fut == fut) {
            RGTimer *dead = *slot;
            *slot = dead->next;
            free(dead);
        } else {
            slot = &(*slot)->next;
        }
    }
    {
        RGFrameJob **fs = &g_frames;
        while (*fs != NULL) {
            if ((*fs)->fut == fut) {
                RGFrameJob *dead = *fs;
                *fs = dead->next;
                free(dead);
            } else {
                fs = &(*fs)->next;
            }
        }
    }
    rg_set_string(&err, "cancelled");
    rg_future_fail(fut, &err);
}

static int32_t rg_fire_timers(int32_t may_wait) {
    int64_t now = rg_now_ms();
    int32_t fired = 0;
    RGTimer **slot = &g_timers;
    RGValue none;
    rg_set_void(&none);
    while (*slot != NULL) {
        if ((*slot)->deadline_ms <= now) {
            RGTimer *dead = *slot;
            *slot = dead->next;
            rg_future_ready(dead->fut, &none);
            free(dead);
            fired = 1;
        } else {
            slot = &(*slot)->next;
        }
    }
    if (fired || !may_wait || g_timers == NULL) {
        return fired;
    }
    {
        int64_t next = g_timers->deadline_ms;
        RGTimer *t;
        for (t = g_timers; t != NULL; t = t->next) {
            if (t->deadline_ms < next) {
                next = t->deadline_ms;
            }
        }
        now = rg_now_ms();
        if (next > now) {
            rg_sleep_ms(next - now);
        }
    }
    return rg_fire_timers(0);
}

static int32_t rg_fire_frames(void) {
    RGFrameJob *batch = g_frames;
    RGValue alive;
    if (batch == NULL) {
        return 0;
    }
    g_frames = NULL;
    while (batch != NULL) {
        RGFrameJob *next = batch->next;
        rg_set_bool(&alive, rg_ui_alive_id(batch->win_id));
        rg_future_ready(batch->fut, &alive);
        free(batch);
        batch = next;
    }
    return 1;
}

static void rg_enqueue(RGTask *t) {
    if (t == NULL) {
        return;
    }
    t->next = NULL;
    if (g_tasks_tail == NULL) {
        g_tasks_head = t;
        g_tasks_tail = t;
        return;
    }
    g_tasks_tail->next = t;
    g_tasks_tail = t;
}

static int32_t rg_pump_once(int32_t may_wait) {
    RGTask *t = g_tasks_head;
    RGValue out;
    if (t != NULL) {
        g_tasks_head = t->next;
        if (g_tasks_head == NULL) {
            g_tasks_tail = NULL;
        }
        rg_set_void(&out);
        if (t->fn != NULL) {
            t->fn(&out, t->args, t->argc);
        }
        if (t->fut != NULL && t->fut->state == RG_FUT_PENDING) {
            if (rg_has_throw()) {
                RGValue err;
                rg_catch(&err);
                rg_future_fail(t->fut, &err);
            } else {
                rg_future_ready(t->fut, &out);
            }
        }
        free(t->args);
        free(t);
        return 1;
    }
    rg_check_joins();
    if (rg_fire_frames()) {
        return 1;
    }
    if (rg_fire_timers(may_wait)) {
        return 1;
    }
    return 0;
}

void rg_spawn(RGValue *dest, void (*fn)(RGValue *, RGValue *, int32_t), RGValue *args, int32_t argc) {
    RGFuture *fut;
    RGTask *t;
    int32_t i;
    if (dest == NULL || fn == NULL) {
        rg_die("invalid spawn");
    }
    fut = (RGFuture *) malloc(sizeof(RGFuture));
    if (fut == NULL) {
        rg_die("out of memory");
    }
    fut->state = RG_FUT_PENDING;
    rg_set_void(&fut->result);
    rg_set_void(&fut->error);
    dest->kind = RG_FUTURE;
    dest->i = 0;
    dest->p = (const char *) fut;
    t = (RGTask *) malloc(sizeof(RGTask));
    if (t == NULL) {
        rg_die("out of memory");
    }
    t->fn = fn;
    t->argc = argc < 0 ? 0 : argc;
    t->fut = fut;
    t->next = NULL;
    t->args = NULL;
    if (t->argc > 0) {
        t->args = (RGValue *) malloc((size_t) t->argc * sizeof(RGValue));
        if (t->args == NULL) {
            rg_die("out of memory");
        }
        for (i = 0; i < t->argc; i++) {
            rg_copy(&t->args[i], args != NULL ? &args[i] : NULL);
        }
    }
    rg_enqueue(t);
}

static void rg_deadlock(void) {
    int32_t tasks = 0;
    int32_t timers = 0;
    int32_t joins = 0;
    RGTask *t;
    RGTimer *tm;
    RGJoin *j;
    for (t = g_tasks_head; t != NULL; t = t->next) {
        tasks++;
    }
    for (tm = g_timers; tm != NULL; tm = tm->next) {
        timers++;
    }
    fprintf(stderr, "await deadlock: Future never settles (tasks=%d timers=%d)\n", (int) tasks, (int) timers);
    for (j = g_joins; j != NULL; j = j->next) {
        int32_t i;
        joins++;
        fprintf(stderr, "  join kind=%d done=%d n=%d out=%d\n", (int) j->kind, (int) j->done, (int) j->n,
                j->out != NULL ? (int) j->out->state : -1);
        for (i = 0; i < j->n; i++) {
            RGFuture *in = j->ins != NULL ? j->ins[i] : NULL;
            fprintf(stderr, "    in[%d]=%d\n", (int) i, in != NULL ? (int) in->state : -1);
        }
    }
    if (joins == 0) {
        fprintf(stderr, "  (no joins)\n");
    }
    exit(1);
}

void rg_await(RGValue *dest, const RGValue *src) {
    RGFuture *f = rg_as_future(src);
    if (f == NULL) {
        rg_die("can only await a Future");
    }
    while (f->state == RG_FUT_PENDING) {
        if (!rg_pump_once(1)) {
            rg_deadlock();
        }
    }
    if (f->state == RG_FUT_FAILED) {
        rg_throw(&f->error);
        return;
    }
    rg_copy(dest, &f->result);
}

static int32_t rg_lookup_type_signal(const char *type, const char *name) {
    int32_t n = 0;
    if (name == NULL) {
        name = "";
    }
    while (type != NULL && type[0] != '\0' && n < 64) {
        for (int32_t i = 0; i < g_ntypesigs; i++) {
            const char *t = g_typesigs[i].type != NULL ? g_typesigs[i].type : "";
            const char *s = g_typesigs[i].name != NULL ? g_typesigs[i].name : "";
            if (strcmp(t, type) == 0 && strcmp(s, name) == 0) {
                return g_typesigs[i].arity;
            }
        }
        type = rg_parent_of(type);
        n++;
    }
    return -1;
}

void rg_register_type_signal(const char *type, const char *name, int32_t arity) {
    if (type == NULL || type[0] == '\0' || name == NULL || name[0] == '\0') {
        return;
    }
    if (g_ntypesigs >= g_ctypesigs) {
        int32_t cap = g_ctypesigs < 8 ? 8 : g_ctypesigs * 2;
        RGTypeSigReg *next = (RGTypeSigReg *) realloc(g_typesigs, (size_t) cap * sizeof(RGTypeSigReg));
        if (next == NULL) {
            rg_die("out of memory");
        }
        g_typesigs = next;
        g_ctypesigs = cap;
    }
    g_typesigs[g_ntypesigs].type = type;
    g_typesigs[g_ntypesigs].name = name;
    g_typesigs[g_ntypesigs].arity = arity < 0 ? 0 : arity;
    g_ntypesigs++;
}

static RGSignal *rg_new_signal(const char *name, int32_t arity) {
    RGSignal *sig = (RGSignal *) malloc(sizeof(RGSignal));
    if (sig == NULL) {
        rg_die("out of memory");
    }
    sig->name = rg_strdup(name != NULL ? name : "");
    sig->arity = arity < 0 ? 0 : arity;
    sig->nlisten = 0;
    sig->clisten = 0;
    sig->listeners = NULL;
    return sig;
}

void rg_signal(RGValue *dest, const char *name, int32_t arity) {
    RGSignal *sig = NULL;
    int32_t i;
    if (dest == NULL) {
        return;
    }
    if (name == NULL) {
        name = "";
    }
    for (i = 0; i < g_ngsigs; i++) {
        const char *n = g_gsigs[i] != NULL && g_gsigs[i]->name != NULL ? g_gsigs[i]->name : "";
        if (strcmp(n, name) == 0) {
            sig = g_gsigs[i];
            break;
        }
    }
    if (sig == NULL) {
        if (g_ngsigs >= g_cgsigs) {
            int32_t cap = g_cgsigs < 8 ? 8 : g_cgsigs * 2;
            RGSignal **next = (RGSignal **) realloc(g_gsigs, (size_t) cap * sizeof(RGSignal *));
            if (next == NULL) {
                rg_die("out of memory");
            }
            g_gsigs = next;
            g_cgsigs = cap;
        }
        sig = rg_new_signal(name, arity);
        g_gsigs[g_ngsigs++] = sig;
    }
    dest->kind = RG_SIGNAL;
    dest->i = 0;
    dest->p = (const char *) sig;
}

static RGSignal *rg_struct_signal(RGStruct *s, const char *name) {
    int32_t i;
    int32_t arity;
    if (s == NULL || name == NULL) {
        return NULL;
    }
    for (i = 0; i < s->nsigs; i++) {
        const char *n = s->sig_names != NULL && s->sig_names[i] != NULL ? s->sig_names[i] : "";
        if (strcmp(n, name) == 0) {
            return s->sigs[i];
        }
    }
    arity = rg_lookup_type_signal(s->name, name);
    if (arity < 0) {
        return NULL;
    }
    if (s->nsigs >= s->csigs) {
        int32_t cap = s->csigs < 4 ? 4 : s->csigs * 2;
        char **names = (char **) realloc(s->sig_names, (size_t) cap * sizeof(char *));
        RGSignal **sigs = (RGSignal **) realloc(s->sigs, (size_t) cap * sizeof(RGSignal *));
        if (names == NULL || sigs == NULL) {
            rg_die("out of memory");
        }
        s->sig_names = names;
        s->sigs = sigs;
        s->csigs = cap;
    }
    s->sig_names[s->nsigs] = rg_strdup(name);
    s->sigs[s->nsigs] = rg_new_signal(name, arity);
    s->nsigs++;
    return s->sigs[s->nsigs - 1];
}

static int32_t rg_same_fn(const RGValue *a, const RGValue *b) {
    RGFnVal *fa = rg_as_fn(a);
    RGFnVal *fb = rg_as_fn(b);
    if (fa == NULL || fb == NULL) {
        return 0;
    }
    if (fa->ncaps > 0 || fb->ncaps > 0) {
        return fa == fb;
    }
    return fa->fn == fb->fn && fa->arity == fb->arity;
}

static void rg_signal_emit_now(RGSignal *sig, RGValue *args, int32_t argc) {
    int32_t n;
    int32_t i;
    RGValue *copied;
    if (sig == NULL) {
        return;
    }
    if (g_emit_depth >= 64) {
        rg_die("signal emit nested too deeply");
    }
    n = sig->nlisten;
    copied = NULL;
    if (n > 0) {
        copied = (RGValue *) malloc((size_t) n * sizeof(RGValue));
        if (copied == NULL) {
            rg_die("out of memory");
        }
        for (i = 0; i < n; i++) {
            rg_copy(&copied[i], &sig->listeners[i]);
        }
    }
    g_emit_depth++;
    for (i = 0; i < n; i++) {
        RGValue *frame;
        RGValue out;
        int32_t j;
        frame = (RGValue *) malloc((size_t) (argc + 1) * sizeof(RGValue));
        if (frame == NULL) {
            rg_die("out of memory");
        }
        rg_copy(&frame[0], &copied[i]);
        for (j = 0; j < argc; j++) {
            rg_copy(&frame[j + 1], args != NULL ? &args[j] : NULL);
        }
        rg_set_void(&out);
        rg_call_val(&out, frame, argc + 1);
        free(frame);
        if (rg_has_throw()) {
            break;
        }
    }
    g_emit_depth--;
    free(copied);
}

static void rg_signal_listen_add(RGSignal *sig, const RGValue *fn) {
    int32_t i;
    if (sig == NULL) {
        return;
    }
    for (i = 0; i < sig->nlisten; i++) {
        if (rg_same_fn(&sig->listeners[i], fn)) {
            return;
        }
    }
    if (sig->nlisten >= sig->clisten) {
        int32_t cap = sig->clisten < 4 ? 4 : sig->clisten * 2;
        RGValue *next = (RGValue *) realloc(sig->listeners, (size_t) cap * sizeof(RGValue));
        if (next == NULL) {
            rg_die("out of memory");
        }
        sig->listeners = next;
        sig->clisten = cap;
    }
    rg_copy(&sig->listeners[sig->nlisten], fn);
    sig->nlisten++;
}

static void rg_signal_listen_remove(RGSignal *sig, const RGValue *fn) {
    int32_t i;
    if (sig == NULL) {
        return;
    }
    for (i = 0; i < sig->nlisten; i++) {
        if (rg_same_fn(&sig->listeners[i], fn)) {
            int32_t j;
            for (j = i + 1; j < sig->nlisten; j++) {
                sig->listeners[j - 1] = sig->listeners[j];
            }
            sig->nlisten--;
            return;
        }
    }
}

static void rg_signal_method(RGValue *dest, RGValue *base, int32_t argc, const char *name) {
    RGSignal *sig = rg_as_signal(&base[0]);
    int32_t nargs = argc - 1;
    if (sig == NULL) {
        rg_die("can only call signal methods on a signal");
    }
    if (strcmp(name, "connect") == 0) {
        if (nargs != 1) {
            rg_die("signal connect takes 1 argument");
        }
        if (rg_as_fn(&base[1]) == NULL) {
            rg_die("signal connect expects a function");
        }
        if (rg_as_fn(&base[1])->arity != sig->arity) {
            rg_die("signal connect expected different arity");
        }
        rg_signal_listen_add(sig, &base[1]);
        rg_set_void(dest);
        return;
    }
    if (strcmp(name, "disconnect") == 0) {
        if (nargs != 1) {
            rg_die("signal disconnect takes 1 argument");
        }
        if (rg_as_fn(&base[1]) == NULL) {
            rg_die("signal disconnect expects a function");
        }
        rg_signal_listen_remove(sig, &base[1]);
        rg_set_void(dest);
        return;
    }
    if (strcmp(name, "emit") == 0 || strcmp(name, "emit_deferred") == 0) {
        if (nargs != sig->arity) {
            rg_die("signal emit arity mismatch");
        }
        if (strcmp(name, "emit_deferred") == 0) {
            RGDeferred *item = (RGDeferred *) malloc(sizeof(RGDeferred));
            int32_t i;
            if (item == NULL) {
                rg_die("out of memory");
            }
            item->sig = sig;
            item->argc = nargs;
            item->args = NULL;
            item->next = NULL;
            if (nargs > 0) {
                item->args = (RGValue *) malloc((size_t) nargs * sizeof(RGValue));
                if (item->args == NULL) {
                    rg_die("out of memory");
                }
                for (i = 0; i < nargs; i++) {
                    rg_copy(&item->args[i], &base[i + 1]);
                }
            }
            if (g_deferred_tail == NULL) {
                g_deferred_head = item;
                g_deferred_tail = item;
            } else {
                g_deferred_tail->next = item;
                g_deferred_tail = item;
            }
            rg_set_void(dest);
            return;
        }
        rg_signal_emit_now(sig, nargs > 0 ? &base[1] : NULL, nargs);
        rg_set_void(dest);
        return;
    }
    fprintf(stderr, "signal '%s' has no method '%s'\n", sig->name != NULL ? sig->name : "", name);
    exit(1);
}

void rg_flush_deferred(void) {
    int32_t waves = 0;
    while (g_deferred_head != NULL) {
        RGDeferred *batch;
        RGDeferred *item;
        if (++waves > 64) {
            rg_die("deferred emit nested too deeply");
        }
        batch = g_deferred_head;
        g_deferred_head = NULL;
        g_deferred_tail = NULL;
        item = batch;
        while (item != NULL) {
            RGDeferred *next = item->next;
            rg_signal_emit_now(item->sig, item->args, item->argc);
            free(item->args);
            free(item);
            item = next;
            if (rg_has_throw()) {
                while (item != NULL) {
                    next = item->next;
                    free(item->args);
                    free(item);
                    item = next;
                }
                return;
            }
        }
    }
}

static RGFuture *rg_new_future(void) {
    RGFuture *fut = (RGFuture *) malloc(sizeof(RGFuture));
    if (fut == NULL) {
        rg_die("out of memory");
    }
    fut->state = RG_FUT_PENDING;
    rg_set_void(&fut->result);
    rg_set_void(&fut->error);
    return fut;
}

static void rg_put_future(RGValue *dest, RGFuture *fut) {
    if (dest == NULL) {
        return;
    }
    dest->kind = RG_FUTURE;
    dest->i = 0;
    dest->p = (const char *) fut;
}

static void rg_host_future_join(RGValue *dest, RGValue *args, int32_t argc, int32_t race) {
    RGArray *arr;
    RGJoin *j;
    int32_t i;
    if (argc != 1) {
        rg_die("Future join takes 1 argument");
    }
    arr = rg_as_array(args);
    if (arr == NULL) {
        rg_die("Future join expects Array of Future");
    }
    for (i = 0; i < arr->len; i++) {
        if (rg_as_future(&arr->items[i]) == NULL) {
            rg_die("Future join expects Array of Future");
        }
    }
    if (arr->len == 0) {
        RGFuture *out = rg_new_future();
        rg_put_future(dest, out);
        if (race) {
            RGValue err;
            rg_set_string(&err, "Future.race on empty Array");
            rg_future_fail(out, &err);
        } else {
            RGValue empty;
            rg_array(&empty, NULL, 0);
            rg_future_ready(out, &empty);
        }
        return;
    }
    j = (RGJoin *) malloc(sizeof(RGJoin));
    if (j == NULL) {
        rg_die("out of memory");
    }
    j->kind = race ? 1 : 0;
    j->done = 0;
    j->n = arr->len;
    j->out = rg_new_future();
    j->ins = (RGFuture **) malloc((size_t) j->n * sizeof(RGFuture *));
    if (j->ins == NULL) {
        rg_die("out of memory");
    }
    for (i = 0; i < j->n; i++) {
        j->ins[i] = rg_as_future(&arr->items[i]);
    }
    j->next = g_joins;
    g_joins = j;
    rg_put_future(dest, j->out);
    rg_check_joins();
}

#define RG_UI_MAX 16384

static RGWin *rg_ui_find(int64_t id) {
    RGWin *w;
    for (w = g_wins; w != NULL; w = w->next) {
        if (w->id == id && w->alive) {
            return w;
        }
    }
    return NULL;
}

static int32_t rg_ui_alive_id(int64_t id) {
    return rg_ui_find(id) != NULL;
}

static int64_t rg_ui_int(RGValue *args, int32_t i, int32_t argc) {
    if (args == NULL || i < 0 || i >= argc || args[i].kind != RG_INT) {
        rg_die("__ui expects Int");
    }
    return args[i].i;
}

static const char *rg_ui_str(RGValue *args, int32_t i, int32_t argc) {
    if (args == NULL || i < 0 || i >= argc || args[i].kind != RG_STRING) {
        rg_die("__ui expects String");
    }
    return args[i].p != NULL ? args[i].p : "";
}

static int32_t rg_ui_bool(RGValue *args, int32_t i, int32_t argc) {
    if (args == NULL || i < 0 || i >= argc || args[i].kind != RG_BOOL) {
        rg_die("__ui expects Bool");
    }
    return args[i].i != 0;
}

static void rg_ui_call(RGValue *dest, const char *name, RGValue *args, int32_t argc) {
    RGWin *win;
    if (dest == NULL) {
        return;
    }
    if (name == NULL) {
        name = "";
    }
    if (strcmp(name, "backend") == 0) {
        rg_set_string(dest, "headless");
        return;
    }
    if (strcmp(name, "platform") == 0) {
#if defined(_WIN32)
        rg_set_string(dest, "win32");
#else
        rg_set_string(dest, "linux");
#endif
        return;
    }
    if (strcmp(name, "count") == 0) {
        int64_t n = 0;
        for (win = g_wins; win != NULL; win = win->next) {
            if (win->alive) {
                n++;
            }
        }
        rg_set_int(dest, n);
        return;
    }
    if (strcmp(name, "font_height") == 0) {
        rg_set_int(dest, 16);
        return;
    }
    if (strcmp(name, "clipboard_get") == 0) {
        rg_set_string(dest, g_clip != NULL ? g_clip : "");
        return;
    }
    if (strcmp(name, "clipboard_set") == 0) {
        g_clip = rg_strdup(rg_ui_str(args, 0, argc));
        rg_set_void(dest);
        return;
    }
    if (strcmp(name, "text_width") == 0) {
        const char *s = rg_ui_str(args, 0, argc);
        rg_set_int(dest, (int64_t) strlen(s) * 8);
        return;
    }
    if (strcmp(name, "image_width") == 0 || strcmp(name, "image_height") == 0) {
        rg_set_int(dest, 0);
        return;
    }
    if (strcmp(name, "run") == 0) {
        rg_set_void(dest);
        return;
    }
    if (strcmp(name, "wait") == 0) {
        for (win = g_wins; win != NULL; win = win->next) {
            win->alive = 0;
        }
        rg_set_void(dest);
        return;
    }
    if (strcmp(name, "open") == 0) {
        const char *title;
        int64_t w;
        int64_t h;
        RGValue err;
        if (argc != 4) {
            rg_die("__ui.open takes 4 arguments");
        }
        title = rg_ui_str(args, 0, argc);
        w = rg_ui_int(args, 1, argc);
        h = rg_ui_int(args, 2, argc);
        if (w < 1 || h < 1 || w > RG_UI_MAX || h > RG_UI_MAX) {
            rg_set_string(&err, "window size must be between 1 and 16384");
            rg_throw(&err);
            return;
        }
        if (title[0] == '\0') {
            title = "RoseGold";
        }
        win = (RGWin *) calloc(1, sizeof(RGWin));
        if (win == NULL) {
            rg_die("out of memory");
        }
        win->id = g_next_win++;
        win->alive = 1;
        win->mapped = rg_ui_bool(args, 3, argc);
        win->w = (int32_t) w;
        win->h = (int32_t) h;
        win->title = rg_strdup(title);
        win->next = g_wins;
        g_wins = win;
        rg_set_int(dest, win->id);
        return;
    }
    if (strcmp(name, "next_frame") == 0) {
        RGFrameJob *job = (RGFrameJob *) malloc(sizeof(RGFrameJob));
        RGFuture *fut;
        if (job == NULL) {
            rg_die("out of memory");
        }
        fut = rg_new_future();
        job->win_id = rg_ui_int(args, 0, argc);
        job->fut = fut;
        job->next = g_frames;
        g_frames = job;
        rg_put_future(dest, fut);
        return;
    }
    win = argc >= 1 && args != NULL && args[0].kind == RG_INT ? rg_ui_find(args[0].i) : NULL;
    if (strcmp(name, "close") == 0) {
        if (win != NULL) {
            win->alive = 0;
        }
        rg_set_void(dest);
        return;
    }
    if (strcmp(name, "alive") == 0 || strcmp(name, "poll") == 0) {
        rg_set_bool(dest, win != NULL);
        return;
    }
    if (strcmp(name, "show") == 0) {
        if (win != NULL) {
            win->mapped = 1;
        }
        rg_set_void(dest);
        return;
    }
    if (strcmp(name, "hide") == 0) {
        if (win != NULL) {
            win->mapped = 0;
        }
        rg_set_void(dest);
        return;
    }
    if (strcmp(name, "title") == 0) {
        rg_set_string(dest, win != NULL && win->title != NULL ? win->title : "");
        return;
    }
    if (strcmp(name, "set_title") == 0) {
        if (win != NULL) {
            const char *title = rg_ui_str(args, 1, argc);
            free(win->title);
            win->title = rg_strdup(title[0] == '\0' ? "RoseGold" : title);
        }
        rg_set_void(dest);
        return;
    }
    if (strcmp(name, "width") == 0) {
        rg_set_int(dest, win != NULL ? win->w : 0);
        return;
    }
    if (strcmp(name, "height") == 0) {
        rg_set_int(dest, win != NULL ? win->h : 0);
        return;
    }
    if (strcmp(name, "set_size") == 0) {
        int64_t w = rg_ui_int(args, 1, argc);
        int64_t h = rg_ui_int(args, 2, argc);
        if (w < 1 || h < 1 || w > RG_UI_MAX || h > RG_UI_MAX) {
            rg_die("window size must be between 1 and 16384");
        }
        if (win != NULL) {
            win->w = (int32_t) w;
            win->h = (int32_t) h;
        }
        rg_set_void(dest);
        return;
    }
    if (strcmp(name, "mouse_x") == 0) {
        rg_set_int(dest, win != NULL ? win->mx : 0);
        return;
    }
    if (strcmp(name, "mouse_y") == 0) {
        rg_set_int(dest, win != NULL ? win->my : 0);
        return;
    }
    if (strcmp(name, "mouse_down") == 0) {
        rg_set_bool(dest, win != NULL && win->down);
        return;
    }
    if (strcmp(name, "take_click") == 0) {
        int32_t v = win != NULL && win->click;
        if (win != NULL) {
            win->click = 0;
        }
        rg_set_bool(dest, v);
        return;
    }
    if (strcmp(name, "take_right_click") == 0) {
        int32_t v = win != NULL && win->rclick;
        if (win != NULL) {
            win->rclick = 0;
        }
        rg_set_bool(dest, v);
        return;
    }
    if (strcmp(name, "feed_click") == 0) {
        if (win != NULL) {
            win->mx = (int32_t) rg_ui_int(args, 1, argc);
            win->my = (int32_t) rg_ui_int(args, 2, argc);
            win->click = 1;
        }
        rg_set_void(dest);
        return;
    }
    if (strcmp(name, "feed_right_click") == 0) {
        if (win != NULL) {
            win->mx = (int32_t) rg_ui_int(args, 1, argc);
            win->my = (int32_t) rg_ui_int(args, 2, argc);
            win->rclick = 1;
        }
        rg_set_void(dest);
        return;
    }
    if (strcmp(name, "feed_mouse") == 0) {
        if (win != NULL) {
            win->mx = (int32_t) rg_ui_int(args, 1, argc);
            win->my = (int32_t) rg_ui_int(args, 2, argc);
        }
        rg_set_void(dest);
        return;
    }
    if (strcmp(name, "feed_down") == 0) {
        if (win != NULL) {
            win->down = rg_ui_bool(args, 1, argc);
        }
        rg_set_void(dest);
        return;
    }
    if (strcmp(name, "take_key") == 0) {
        int32_t v = win != NULL && win->keyp;
        if (win != NULL) {
            win->keyp = 0;
        }
        rg_set_bool(dest, v);
        return;
    }
    if (strcmp(name, "key_code") == 0) {
        rg_set_int(dest, win != NULL ? win->key_code : 0);
        return;
    }
    if (strcmp(name, "key_text") == 0) {
        rg_set_string(dest, win != NULL && win->key_text != NULL ? win->key_text : "");
        return;
    }
    if (strcmp(name, "feed_key") == 0) {
        if (win != NULL) {
            win->key_code = (int32_t) rg_ui_int(args, 1, argc);
            free(win->key_text);
            win->key_text = rg_strdup(rg_ui_str(args, 2, argc));
            win->keyp = 1;
        }
        rg_set_void(dest);
        return;
    }
    if (strcmp(name, "take_scroll") == 0) {
        int32_t v = win != NULL && win->scrollp;
        if (win != NULL) {
            win->scrollp = 0;
        }
        rg_set_bool(dest, v);
        return;
    }
    if (strcmp(name, "scroll_dx") == 0) {
        rg_set_int(dest, win != NULL ? win->sdx : 0);
        return;
    }
    if (strcmp(name, "scroll_dy") == 0) {
        rg_set_int(dest, win != NULL ? win->sdy : 0);
        return;
    }
    if (strcmp(name, "feed_scroll") == 0) {
        if (win != NULL) {
            int32_t dx = (int32_t) rg_ui_int(args, 1, argc);
            int32_t dy = (int32_t) rg_ui_int(args, 2, argc);
            if (!win->scrollp) {
                win->sdx = 0;
                win->sdy = 0;
            }
            win->sdx += dx;
            win->sdy += dy;
            win->scrollp = 1;
        }
        rg_set_void(dest);
        return;
    }
    if (strcmp(name, "set_frame") == 0) {
        rg_set_void(dest);
        return;
    }
    if (strcmp(name, "clear") == 0 || strcmp(name, "fill") == 0 || strcmp(name, "line") == 0
            || strcmp(name, "stroke_rect") == 0 || strcmp(name, "fill_round") == 0
            || strcmp(name, "stroke_round") == 0 || strcmp(name, "image_rgb") == 0
            || strcmp(name, "image") == 0 || strcmp(name, "clip_push") == 0 || strcmp(name, "clip_pop") == 0
            || strcmp(name, "text") == 0 || strcmp(name, "present") == 0 || strcmp(name, "cursor") == 0) {
        rg_set_void(dest);
        return;
    }
    fprintf(stderr, "unknown function __ui.%s\n", name);
    exit(1);
}

void rg_set_argv(int32_t argc, char **argv) {
    if (argc > 0 && argv != NULL) {
        g_argc = argc - 1;
        g_argv = argv + 1;
        return;
    }
    g_argc = 0;
    g_argv = NULL;
}

static uint32_t rg_rand32(void) {
    if (!g_rng_seeded) {
        srand((unsigned) (rg_now_ms() ^ 0x9e3779b9u));
        g_rng_seeded = 1;
    }
    return ((uint32_t) rand() << 16) ^ (uint32_t) rand();
}

static const char *rg_arg_str(RGValue *args, int32_t i, int32_t argc, const char *who) {
    if (args == NULL || i < 0 || i >= argc || args[i].kind != RG_STRING) {
        fprintf(stderr, "%s expects String\n", who);
        exit(1);
    }
    return args[i].p != NULL ? args[i].p : "";
}

static int64_t rg_arg_int(RGValue *args, int32_t i, int32_t argc, const char *who) {
    if (args == NULL || i < 0 || i >= argc || args[i].kind != RG_INT) {
        fprintf(stderr, "%s expects Int\n", who);
        exit(1);
    }
    return args[i].i;
}

static void rg_slash(char *s) {
    for (; s != NULL && *s != '\0'; s++) {
        if (*s == '\\') {
            *s = '/';
        }
    }
}

static int32_t rg_path_abs(const char *s) {
    if (s == NULL || s[0] == '\0') {
        return 0;
    }
    if (s[0] == '/') {
        return 1;
    }
    return ((s[0] >= 'A' && s[0] <= 'Z') || (s[0] >= 'a' && s[0] <= 'z')) && s[1] == ':';
}

static int rg_hex_digit(char c) {
    return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
}

static char rg_hex_lower(char c) {
    return (c >= 'A' && c <= 'F') ? (char) (c + 32) : c;
}

static char *rg_uuid_hex32(const char *s) {
    char *hex;
    int32_t n = 0;
    hex = (char *) malloc(33);
    if (hex == NULL) {
        rg_die("out of memory");
    }
    for (; s != NULL && *s != '\0'; s++) {
        if (*s == '-') {
            continue;
        }
        if (!rg_hex_digit(*s) || n >= 32) {
            free(hex);
            return NULL;
        }
        hex[n++] = rg_hex_lower(*s);
    }
    if (n != 32) {
        free(hex);
        return NULL;
    }
    hex[32] = '\0';
    return hex;
}

static char *rg_uuid_dash(const char *hex) {
    char *out = (char *) malloc(37);
    if (out == NULL) {
        rg_die("out of memory");
    }
    memcpy(out, hex, 8);
    out[8] = '-';
    memcpy(out + 9, hex + 8, 4);
    out[13] = '-';
    memcpy(out + 14, hex + 12, 4);
    out[18] = '-';
    memcpy(out + 19, hex + 16, 4);
    out[23] = '-';
    memcpy(out + 24, hex + 20, 12);
    out[36] = '\0';
    return out;
}

static void rg_checks_call(RGValue *dest, const char *name, RGValue *args, int32_t argc) {
    if (strcmp(name, "eq") == 0 || strcmp(name, "neq") == 0) {
        int32_t same;
        if (argc != 2) {
            rg_die("checks.eq takes 2 argument(s)");
        }
        if (!rg_numeric(&args[0]) || !rg_numeric(&args[1])) {
            rg_die("checks.eq expects numbers");
        }
        same = rg_eq(&args[0], &args[1]);
        if (strcmp(name, "eq") == 0 ? !same : same) {
            rg_die("assertion failed");
        }
        rg_set_void(dest);
        return;
    }
    if (strcmp(name, "eq_string") == 0) {
        const char *a;
        const char *b;
        if (argc != 2) {
            rg_die("checks.eq_string takes 2 argument(s)");
        }
        a = rg_arg_str(args, 0, argc, "checks.eq_string");
        b = rg_arg_str(args, 1, argc, "checks.eq_string");
        if (strcmp(a, b) != 0) {
            rg_die("assertion failed");
        }
        rg_set_void(dest);
        return;
    }
    if (strcmp(name, "that") == 0 || strcmp(name, "truthy") == 0) {
        if (argc != 1) {
            rg_die("checks.that takes 1 argument(s)");
        }
        if (!rg_truthy(&args[0])) {
            rg_die("assertion failed");
        }
        rg_set_void(dest);
        return;
    }
    rg_die("unknown function checks");
}

static void rg_process_call(RGValue *dest, const char *name, RGValue *args, int32_t argc) {
    if (strcmp(name, "argc") == 0 || strcmp(name, "argv_len") == 0) {
        if (argc != 0) {
            rg_die("process.argc takes 0 arguments");
        }
        rg_set_int(dest, g_argc);
        return;
    }
    if (strcmp(name, "argv") == 0) {
        int64_t i;
        if (argc != 1 || args == NULL || args[0].kind != RG_INT) {
            rg_die("argv index must be Int");
        }
        i = args[0].i;
        if (i < 0 || i >= g_argc || g_argv == NULL) {
            rg_die("argv index out of range");
        }
        rg_set_string(dest, g_argv[i] != NULL ? g_argv[i] : "");
        return;
    }
    rg_die("unknown function process");
}

static void rg_math_call(RGValue *dest, const char *name, RGValue *args, int32_t argc) {
    if (strcmp(name, "pow") == 0) {
        int64_t base;
        int64_t exp;
        uint64_t r;
        uint64_t b;
        uint64_t e;
        if (argc != 2) {
            rg_die("__math.pow takes 2 arguments");
        }
        base = rg_arg_int(args, 0, argc, "__math.pow");
        exp = rg_arg_int(args, 1, argc, "__math.pow");
        if (exp < 0) {
            rg_set_int(dest, 0);
            return;
        }
        r = 1;
        b = (uint64_t) base;
        e = (uint64_t) exp;
        while (e != 0) {
            if ((e & 1u) != 0) {
                r *= b;
            }
            e >>= 1;
            if (e != 0) {
                b *= b;
            }
        }
        rg_set_int(dest, (int64_t) r);
        return;
    }
    if (strcmp(name, "rand_int") == 0) {
        int64_t n;
        if (argc != 1) {
            rg_die("__math.rand_int takes 1 argument");
        }
        n = rg_arg_int(args, 0, argc, "__math.rand_int");
        if (n <= 0) {
            rg_die("__math.rand_int expects n > 0");
        }
        if (n > INT_MAX) {
            n = INT_MAX;
        }
        rg_set_int(dest, (int64_t) (rg_rand32() % (uint32_t) n));
        return;
    }
    if (strcmp(name, "random") == 0) {
        if (argc != 0) {
            rg_die("__math.random takes 0 arguments");
        }
        rg_set_float(dest, (double) rg_rand32() / 4294967296.0);
        return;
    }
    if (strcmp(name, "to_float") == 0) {
        if (argc != 1) {
            rg_die("__math.to_float takes 1 argument");
        }
        rg_set_float(dest, (double) rg_arg_int(args, 0, argc, "__math.to_float"));
        return;
    }
    if (strcmp(name, "sin") == 0 || strcmp(name, "cos") == 0 || strcmp(name, "sqrt") == 0
            || strcmp(name, "floor") == 0 || strcmp(name, "ceil") == 0 || strcmp(name, "to_int") == 0) {
        double n;
        if (argc != 1) {
            rg_die("__math expects 1 argument");
        }
        if (!rg_numeric(&args[0])) {
            rg_die("__math expects Float");
        }
        n = rg_f64(&args[0]);
        if (strcmp(name, "sin") == 0) {
            rg_set_float(dest, sin(n));
            return;
        }
        if (strcmp(name, "cos") == 0) {
            rg_set_float(dest, cos(n));
            return;
        }
        if (strcmp(name, "sqrt") == 0) {
            if (n < 0.0) {
                rg_die("__math.sqrt expects n >= 0");
            }
            rg_set_float(dest, sqrt(n));
            return;
        }
        if (strcmp(name, "floor") == 0) {
            rg_set_float(dest, floor(n));
            return;
        }
        if (strcmp(name, "ceil") == 0) {
            rg_set_float(dest, ceil(n));
            return;
        }
        rg_set_int(dest, (int64_t) n);
        return;
    }
    if (strcmp(name, "atan2") == 0 || strcmp(name, "powf") == 0) {
        double a;
        double b;
        if (argc != 2) {
            rg_die("__math expects 2 arguments");
        }
        if (!rg_numeric(&args[0]) || !rg_numeric(&args[1])) {
            rg_die("__math expects Float");
        }
        a = rg_f64(&args[0]);
        b = rg_f64(&args[1]);
        if (strcmp(name, "atan2") == 0) {
            rg_set_float(dest, atan2(a, b));
            return;
        }
        rg_set_float(dest, pow(a, b));
        return;
    }
    rg_die("unknown function __math");
}

static char *rg_str_replace(const char *s, const char *from, const char *to) {
    size_t nf;
    size_t nt;
    size_t cap;
    size_t len;
    const char *p;
    char *out;
    if (from == NULL || from[0] == '\0') {
        return rg_strdup(s);
    }
    nf = strlen(from);
    nt = strlen(to);
    cap = strlen(s) + 1;
    if (cap < 16) {
        cap = 16;
    }
    out = (char *) malloc(cap);
    if (out == NULL) {
        rg_die("out of memory");
    }
    len = 0;
    p = s;
    while (*p != '\0') {
        if (strncmp(p, from, nf) == 0) {
            if (len + nt + 1 > cap) {
                while (len + nt + 1 > cap) {
                    cap *= 2;
                }
                out = (char *) realloc(out, cap);
                if (out == NULL) {
                    rg_die("out of memory");
                }
            }
            memcpy(out + len, to, nt);
            len += nt;
            p += nf;
        } else {
            if (len + 2 > cap) {
                cap *= 2;
                out = (char *) realloc(out, cap);
                if (out == NULL) {
                    rg_die("out of memory");
                }
            }
            out[len++] = *p++;
        }
    }
    out[len] = '\0';
    return out;
}

static void rg_str_call(RGValue *dest, const char *name, RGValue *args, int32_t argc) {
    if (strcmp(name, "contains") == 0) {
        const char *s;
        const char *sub;
        if (argc != 2) {
            rg_die("str.contains takes 2 arguments");
        }
        s = rg_arg_str(args, 0, argc, "__str.contains");
        sub = rg_arg_str(args, 1, argc, "__str.contains");
        rg_set_bool(dest, strstr(s, sub) != NULL);
        return;
    }
    if (strcmp(name, "starts_with") == 0) {
        const char *s;
        const char *prefix;
        size_t n;
        if (argc != 2) {
            rg_die("str.starts_with takes 2 arguments");
        }
        s = rg_arg_str(args, 0, argc, "__str.starts_with");
        prefix = rg_arg_str(args, 1, argc, "__str.starts_with");
        n = strlen(prefix);
        rg_set_bool(dest, strlen(s) >= n && strncmp(s, prefix, n) == 0);
        return;
    }
    if (strcmp(name, "ends_with") == 0) {
        const char *s;
        const char *suffix;
        size_t ns;
        size_t np;
        if (argc != 2) {
            rg_die("str.ends_with takes 2 arguments");
        }
        s = rg_arg_str(args, 0, argc, "__str.ends_with");
        suffix = rg_arg_str(args, 1, argc, "__str.ends_with");
        ns = strlen(s);
        np = strlen(suffix);
        rg_set_bool(dest, ns >= np && strcmp(s + (ns - np), suffix) == 0);
        return;
    }
    if (strcmp(name, "length") == 0) {
        if (argc != 1) {
            rg_die("str.length takes 1 argument");
        }
        rg_set_int(dest, (int64_t) strlen(rg_arg_str(args, 0, argc, "__str.length")));
        return;
    }
    if (strcmp(name, "is_empty") == 0) {
        const char *s;
        if (argc != 1) {
            rg_die("str.is_empty takes 1 argument");
        }
        s = rg_arg_str(args, 0, argc, "__str.is_empty");
        rg_set_bool(dest, s[0] == '\0');
        return;
    }
    if (strcmp(name, "repeat") == 0) {
        const char *s;
        int64_t n;
        size_t slen;
        size_t total;
        char *out;
        int64_t i;
        if (argc != 2) {
            rg_die("str.repeat takes 2 arguments");
        }
        s = rg_arg_str(args, 0, argc, "__str.repeat");
        n = rg_arg_int(args, 1, argc, "__str.repeat");
        if (n < 0) {
            n = 0;
        }
        slen = strlen(s);
        if (n > 0 && slen > 0 && (int64_t) slen > RG_MAX_REPEAT_BYTES / n) {
            rg_die("str.repeat result too large");
        }
        if (n > INT_MAX) {
            n = INT_MAX;
        }
        total = slen * (size_t) n;
        out = (char *) malloc(total + 1);
        if (out == NULL) {
            rg_die("out of memory");
        }
        for (i = 0; i < n; i++) {
            memcpy(out + (size_t) i * slen, s, slen);
        }
        out[total] = '\0';
        rg_set_string(dest, out);
        return;
    }
    if (strcmp(name, "upper") == 0 || strcmp(name, "lower") == 0) {
        const char *s;
        size_t n;
        char *out;
        size_t i;
        int32_t up;
        if (argc != 1) {
            rg_die("str.upper takes 1 argument");
        }
        s = rg_arg_str(args, 0, argc, "__str.upper");
        n = strlen(s);
        out = rg_strndup(s, n);
        up = strcmp(name, "upper") == 0;
        for (i = 0; i < n; i++) {
            unsigned char c = (unsigned char) out[i];
            out[i] = (char) (up ? toupper(c) : tolower(c));
        }
        rg_set_string(dest, out);
        return;
    }
    if (strcmp(name, "trim") == 0) {
        const char *s;
        const char *end;
        if (argc != 1) {
            rg_die("str.trim takes 1 argument");
        }
        s = rg_arg_str(args, 0, argc, "__str.trim");
        while (*s != '\0' && (unsigned char) *s <= ' ') {
            s++;
        }
        end = s + strlen(s);
        while (end > s && (unsigned char) end[-1] <= ' ') {
            end--;
        }
        rg_set_string(dest, rg_strndup(s, (size_t) (end - s)));
        return;
    }
    if (strcmp(name, "slice") == 0) {
        const char *s;
        int64_t start;
        int64_t end;
        int64_t len;
        if (argc != 3) {
            rg_die("str.slice takes 3 arguments");
        }
        s = rg_arg_str(args, 0, argc, "__str.slice");
        start = rg_arg_int(args, 1, argc, "__str.slice");
        end = rg_arg_int(args, 2, argc, "__str.slice");
        len = (int64_t) strlen(s);
        if (start < 0) {
            start = 0;
        }
        if (start > len) {
            start = len;
        }
        if (end < 0) {
            end = 0;
        }
        if (end > len) {
            end = len;
        }
        if (end < start) {
            end = start;
        }
        rg_set_string(dest, rg_strndup(s + start, (size_t) (end - start)));
        return;
    }
    if (strcmp(name, "split") == 0) {
        const char *s;
        const char *sep;
        size_t ns;
        size_t nsep;
        int32_t nparts;
        int32_t i;
        RGArray *arr;
        if (argc != 2) {
            rg_die("str.split takes 2 arguments");
        }
        s = rg_arg_str(args, 0, argc, "__str.split");
        sep = rg_arg_str(args, 1, argc, "__str.split");
        ns = strlen(s);
        nsep = strlen(sep);
        if (nsep == 0) {
            if (ns > (size_t) INT32_MAX) {
                rg_die("str.split result too large");
            }
            nparts = (int32_t) ns;
            arr = rg_alloc_array(nparts);
            for (i = 0; i < nparts; i++) {
                rg_set_string(&arr->items[i], rg_strndup(s + i, 1));
            }
            rg_set_array(dest, arr);
            return;
        }
        nparts = 1;
        {
            const char *p = s;
            const char *hit;
            while ((hit = strstr(p, sep)) != NULL) {
                nparts++;
                p = hit + nsep;
            }
        }
        arr = rg_alloc_array(nparts);
        {
            const char *p = s;
            for (i = 0; i < nparts; i++) {
                const char *hit = i + 1 == nparts ? NULL : strstr(p, sep);
                if (hit == NULL) {
                    rg_set_string(&arr->items[i], rg_strdup(p));
                    break;
                }
                rg_set_string(&arr->items[i], rg_strndup(p, (size_t) (hit - p)));
                p = hit + nsep;
            }
        }
        rg_set_array(dest, arr);
        return;
    }
    if (strcmp(name, "replace") == 0) {
        if (argc != 3) {
            rg_die("str.replace takes 3 arguments");
        }
        rg_set_string(dest, rg_str_replace(
                rg_arg_str(args, 0, argc, "__str.replace"),
                rg_arg_str(args, 1, argc, "__str.replace"),
                rg_arg_str(args, 2, argc, "__str.replace")));
        return;
    }
    if (strcmp(name, "find") == 0) {
        const char *s;
        const char *sub;
        const char *hit;
        if (argc != 2) {
            rg_die("str.find takes 2 arguments");
        }
        s = rg_arg_str(args, 0, argc, "__str.find");
        sub = rg_arg_str(args, 1, argc, "__str.find");
        hit = strstr(s, sub);
        rg_set_int(dest, hit == NULL ? -1 : (int64_t) (hit - s));
        return;
    }
    rg_die("unknown function __str");
}

static void rg_uuid_call(RGValue *dest, const char *name, RGValue *args, int32_t argc) {
    static const char *digits = "0123456789abcdef";
    if (strcmp(name, "v4") == 0) {
        char hex[33];
        int32_t i;
        if (argc != 0) {
            rg_die("__uuid.v4 takes 0 arguments");
        }
        for (i = 0; i < 32; i++) {
            int n = (int) (rg_rand32() & 15u);
            if (i == 12) {
                n = 4;
            } else if (i == 16) {
                n = (n & 3) | 8;
            }
            hex[i] = digits[n];
        }
        hex[32] = '\0';
        rg_set_string(dest, rg_uuid_dash(hex));
        return;
    }
    if (strcmp(name, "parse") == 0 || strcmp(name, "valid") == 0) {
        const char *s;
        char *hex;
        if (argc != 1) {
            rg_die("__uuid expects 1 argument");
        }
        s = rg_arg_str(args, 0, argc, "__uuid");
        hex = rg_uuid_hex32(s);
        if (hex == NULL) {
            if (strcmp(name, "valid") == 0) {
                rg_set_bool(dest, 0);
                return;
            }
            {
                RGValue err;
                rg_set_string(&err, "invalid UUID");
                rg_throw(&err);
                rg_set_void(dest);
                return;
            }
        }
        if (strcmp(name, "valid") == 0) {
            free(hex);
            rg_set_bool(dest, 1);
            return;
        }
        rg_set_string(dest, rg_uuid_dash(hex));
        free(hex);
        return;
    }
    rg_die("unknown function __uuid");
}

static void rg_path_call(RGValue *dest, const char *name, RGValue *args, int32_t argc) {
    if (strcmp(name, "join") == 0) {
        char *a;
        char *b;
        size_t na;
        size_t nb;
        int32_t slash;
        char *out;
        if (argc != 2) {
            rg_die("__path.join takes 2 arguments");
        }
        a = rg_strdup(rg_arg_str(args, 0, argc, "__path.join"));
        b = rg_strdup(rg_arg_str(args, 1, argc, "__path.join"));
        rg_slash(a);
        rg_slash(b);
        if (rg_path_abs(b) || a[0] == '\0') {
            free(a);
            rg_set_string(dest, b);
            return;
        }
        if (b[0] == '\0') {
            free(b);
            rg_set_string(dest, a);
            return;
        }
        na = strlen(a);
        nb = strlen(b);
        slash = a[na - 1] != '/';
        out = (char *) malloc(na + (size_t) slash + nb + 1);
        if (out == NULL) {
            rg_die("out of memory");
        }
        memcpy(out, a, na);
        if (slash) {
            out[na] = '/';
        }
        memcpy(out + na + (size_t) slash, b, nb + 1);
        free(a);
        free(b);
        rg_set_string(dest, out);
        return;
    }
    if (strcmp(name, "parent") == 0) {
        char *p;
        size_t n;
        char *slash;
        if (argc != 1) {
            rg_die("__path.parent takes 1 argument");
        }
        p = rg_strdup(rg_arg_str(args, 0, argc, "__path.parent"));
        rg_slash(p);
        n = strlen(p);
        while (n > 1 && p[n - 1] == '/') {
            p[--n] = '\0';
        }
        slash = strrchr(p, '/');
        if (slash == NULL) {
            free(p);
            rg_set_string(dest, "");
            return;
        }
        if (slash == p) {
            if (slash[1] == '\0') {
                free(p);
                rg_set_string(dest, "");
                return;
            }
            p[1] = '\0';
            rg_set_string(dest, p);
            return;
        }
        *slash = '\0';
        rg_set_string(dest, p);
        return;
    }
    if (strcmp(name, "stem") == 0) {
        char *p;
        char *leaf;
        char *dot;
        size_t n;
        if (argc != 1) {
            rg_die("__path.stem takes 1 argument");
        }
        p = rg_strdup(rg_arg_str(args, 0, argc, "__path.stem"));
        rg_slash(p);
        n = strlen(p);
        while (n > 1 && p[n - 1] == '/') {
            p[--n] = '\0';
        }
        leaf = strrchr(p, '/');
        leaf = leaf == NULL ? p : leaf + 1;
        dot = strrchr(leaf, '.');
        if (dot != NULL && dot != leaf) {
            *dot = '\0';
        }
        rg_set_string(dest, rg_strdup(leaf));
        free(p);
        return;
    }
    rg_die("unknown function __path");
}

#include "host_rest.c"

void rg_host_call(RGValue *dest, const char *module, const char *name, RGValue *args, int32_t argc) {
    if (dest == NULL) {
        return;
    }
    if (module == NULL) {
        module = "";
    }
    if (name == NULL) {
        name = "";
    }
    if (strcmp(module, "Future") == 0) {
        if (strcmp(name, "all") == 0) {
            rg_host_future_join(dest, args, argc, 0);
            return;
        }
        if (strcmp(name, "race") == 0) {
            rg_host_future_join(dest, args, argc, 1);
            return;
        }
        rg_die("unknown function Future");
    }
    if (strcmp(module, "__time") == 0) {
        if (strcmp(name, "now") == 0) {
            if (argc != 0) {
                rg_die("__time.now takes 0 arguments");
            }
            rg_set_int(dest, rg_now_ms());
            return;
        }
        if (strcmp(name, "sleep") == 0 || strcmp(name, "delay") == 0) {
            int64_t ms;
            if (argc != 1 || args == NULL || args[0].kind != RG_INT) {
                rg_die("__time expects Int");
            }
            ms = args[0].i;
            if (ms < 0) {
                ms = 0;
            }
            if (ms > RG_MAX_SLEEP_MS) {
                rg_die("time duration too large");
            }
            if (strcmp(name, "delay") == 0) {
                RGFuture *fut = rg_new_future();
                rg_put_future(dest, fut);
                rg_add_timer(fut, ms);
                return;
            }
            rg_sleep_ms(ms);
            rg_set_void(dest);
            return;
        }
        rg_die("unknown function __time");
    }
    if (strcmp(module, "__ui") == 0) {
        rg_ui_call(dest, name, args, argc);
        return;
    }
    if (strcmp(module, "checks") == 0) {
        rg_checks_call(dest, name, args, argc);
        return;
    }
    if (strcmp(module, "process") == 0 || strcmp(module, "") == 0) {
        if (strcmp(module, "process") == 0
                || strcmp(name, "argv") == 0 || strcmp(name, "argv_len") == 0) {
            rg_process_call(dest, name, args, argc);
            return;
        }
    }
    if (strcmp(module, "__math") == 0) {
        rg_math_call(dest, name, args, argc);
        return;
    }
    if (strcmp(module, "__str") == 0) {
        rg_str_call(dest, name, args, argc);
        return;
    }
    if (strcmp(module, "__uuid") == 0) {
        rg_uuid_call(dest, name, args, argc);
        return;
    }
    if (strcmp(module, "__path") == 0) {
        rg_path_call(dest, name, args, argc);
        return;
    }
    if (strcmp(module, "__io") == 0) {
        rg_io_call(dest, name, args, argc);
        return;
    }
    if (strcmp(module, "__json") == 0) {
        rg_json_call(dest, name, args, argc);
        return;
    }
    if (strcmp(module, "__regex") == 0) {
        rg_regex_call(dest, name, args, argc);
        return;
    }
    fprintf(stderr, "unknown function %s.%s\n", module, name);
    exit(1);
}

static void rg_write(const RGValue *v) {
    if (v == NULL) {
        return;
    }
    switch (v->kind) {
        case RG_BOOL:
            fputs(v->i ? "true" : "false", stdout);
            break;
        case RG_INT:
            fprintf(stdout, "%lld", (long long) v->i);
            break;
        case RG_FLOAT: {
            double x;
            memcpy(&x, &v->i, sizeof(x));
            if (x == (double) (long long) x) {
                fprintf(stdout, "%lld", (long long) x);
            } else {
                fprintf(stdout, "%g", x);
            }
            break;
        }
        case RG_STRING:
            fputs(v->p != NULL ? v->p : "", stdout);
            break;
        case RG_RANGE: {
            RGRange *r = rg_as_range(v);
            fprintf(stdout, "%lld%s%lld", (long long) v->i, r != NULL && r->inclusive ? "..=" : "..",
                    r != NULL ? (long long) r->end : 0LL);
            break;
        }
        case RG_ARRAY: {
            RGArray *arr = rg_as_array(v);
            fputc('[', stdout);
            int32_t n = arr == NULL ? 0 : arr->len;
            for (int32_t i = 0; i < n; i++) {
                if (i > 0) {
                    fputs(", ", stdout);
                }
                rg_write(&arr->items[i]);
            }
            fputc(']', stdout);
            break;
        }
        case RG_MAP: {
            RGMap *m = rg_as_map(v);
            fputc('{', stdout);
            int32_t n = m == NULL ? 0 : m->len;
            for (int32_t i = 0; i < n; i++) {
                if (i > 0) {
                    fputs(", ", stdout);
                }
                fputc('"', stdout);
                fputs(m->entries[i].key != NULL ? m->entries[i].key : "", stdout);
                fputs("\": ", stdout);
                rg_write(&m->entries[i].value);
            }
            fputc('}', stdout);
            break;
        }
        case RG_STRUCT: {
            RGStruct *s = rg_as_struct(v);
            fputs(s != NULL && s->name != NULL ? s->name : "", stdout);
            fputs(" { ", stdout);
            int32_t n = s == NULL ? 0 : s->len;
            for (int32_t i = 0; i < n; i++) {
                if (i > 0) {
                    fputs(", ", stdout);
                }
                fputs(s->fields != NULL && s->fields[i] != NULL ? s->fields[i] : "", stdout);
                fputs(": ", stdout);
                rg_write(&s->values[i]);
            }
            fputs(" }", stdout);
            break;
        }
        case RG_ENUM_TYPE:
            fputs("enum ", stdout);
            fputs(v->p != NULL ? v->p : "", stdout);
            break;
        case RG_ENUM: {
            RGEnum *en = rg_as_enum(v);
            fputs(en != NULL && en->type != NULL ? en->type : "", stdout);
            fputc('.', stdout);
            fputs(en != NULL && en->variant != NULL ? en->variant : "", stdout);
            if (en != NULL && en->n > 0 && en->payload != NULL) {
                fputc('(', stdout);
                for (int32_t i = 0; i < en->n; i++) {
                    if (i > 0) {
                        fputs(", ", stdout);
                    }
                    rg_write(&en->payload[i]);
                }
                fputc(')', stdout);
            }
            break;
        }
        case RG_FN:
            fputs("<fn>", stdout);
            break;
        case RG_FUTURE:
            fputs("<Future>", stdout);
            break;
        case RG_SIGNAL: {
            RGSignal *sig = rg_as_signal(v);
            fputs(sig != NULL && sig->name != NULL ? sig->name : "<signal>", stdout);
            break;
        }
        default:
            break;
    }
}

void rg_print(RGValue *base, int32_t argc) {
    for (int32_t i = 0; i < argc; i++) {
        if (i > 0) {
            fputc(' ', stdout);
        }
        rg_write(&base[i]);
    }
    fputc('\n', stdout);
    fflush(stdout);
}

void rg_test_ok(const char *name) {
    fprintf(stdout, "ok %s\n", name != NULL ? name : "");
    fflush(stdout);
}

void rg_test_fail(const char *name, const RGValue *err) {
    fprintf(stdout, "FAIL %s: ", name != NULL ? name : "");
    if (err != NULL && err->kind == RG_STRING && err->p != NULL) {
        fputs(err->p, stdout);
    } else if (err != NULL) {
        rg_write((RGValue *) err);
    } else {
        fputs("runtime error", stdout);
    }
    fputc('\n', stdout);
    fflush(stdout);
}

void rg_test_summary(int32_t passed, int32_t total) {
    fprintf(stdout, "%d/%d tests passed\n", (int) passed, (int) total);
    fflush(stdout);
}

int64_t rg_to_exit(const RGValue *v) {
    if (v != NULL && v->kind == RG_INT) {
        return v->i;
    }
    return 0;
}
