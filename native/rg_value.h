#ifndef RG_VALUE_H
#define RG_VALUE_H

#include <stdint.h>

enum {
    RG_VOID = 0,
    RG_BOOL = 1,
    RG_INT = 2,
    RG_FLOAT = 3,
    RG_STRING = 4,
    RG_ARRAY = 5,
    RG_RANGE = 6,
    RG_MAP = 7,
    RG_STRUCT = 8,
    RG_ENUM_TYPE = 9,
    RG_ENUM = 10,
    RG_FN = 11,
    RG_FUTURE = 12,
    RG_SIGNAL = 13
};

enum {
    RG_BIN_ADD = 1,
    RG_BIN_SUB = 2,
    RG_BIN_MUL = 3,
    RG_BIN_DIV = 4,
    RG_BIN_MOD = 5,
    RG_BIN_EQ = 6,
    RG_BIN_NE = 7,
    RG_BIN_LT = 8,
    RG_BIN_GT = 9,
    RG_BIN_LE = 10,
    RG_BIN_GE = 11
};

enum {
    RG_UN_NEG = 1,
    RG_UN_NOT = 2
};

enum {
    RG_M_LEN = 1,
    RG_M_PUSH = 2,
    RG_M_POP = 3,
    RG_M_HAS = 4,
    RG_M_KEYS = 5,
    RG_M_REMOVE = 6,
    RG_M_INSERT = 7
};

typedef struct RGValue {
    uint8_t kind;
    int64_t i;
    const char *p;
} RGValue;

void rg_set_void(RGValue *v);
void rg_set_bool(RGValue *v, int32_t b);
void rg_set_int(RGValue *v, int64_t n);
void rg_set_float(RGValue *v, double x);
void rg_set_string(RGValue *v, const char *s);
void rg_copy(RGValue *dst, const RGValue *src);
void rg_print(RGValue *base, int32_t argc);
int64_t rg_to_exit(const RGValue *v);
int32_t rg_truthy(const RGValue *v);
void rg_bin(RGValue *dest, const RGValue *a, const RGValue *b, int32_t op);
void rg_unary(RGValue *dest, const RGValue *src, int32_t op);
void rg_array(RGValue *dest, RGValue *base, int32_t argc);
void rg_range(RGValue *dest, const RGValue *start, const RGValue *end, int32_t inclusive);
void rg_index_get(RGValue *dest, const RGValue *obj, const RGValue *idx);
void rg_index_set(RGValue *obj, const RGValue *idx, const RGValue *value);
void rg_iter_items(RGValue *dest, const RGValue *iter);
void rg_len(RGValue *dest, const RGValue *src);
void rg_assert(RGValue *dest, RGValue *args, int32_t argc);
void rg_test_ok(const char *name);
void rg_test_fail(const char *name, const RGValue *err);
void rg_test_summary(int32_t passed, int32_t total);
void rg_map(RGValue *dest, RGValue *base, int32_t argc);
void rg_method(RGValue *dest, RGValue *base, int32_t argc, int32_t op);
void rg_struct(RGValue *dest, const char *name, RGValue *base, int32_t n, const char **fields, int32_t is_data);
void rg_member(RGValue *dest, const RGValue *obj, const char *name);
void rg_field_set(RGValue *obj, const char *name, const RGValue *val);
void rg_register_method(const char *type, const char *method, void (*fn)(RGValue *, RGValue *, int32_t));
void rg_register_parent(const char *child, const char *parent);
void rg_call_method(RGValue *dest, RGValue *base, int32_t argc, const char *name);
void rg_enum_type(RGValue *dest, const char *name);
void rg_register_enum(const char *type, const char *variant, int32_t arity, const char **fields);
void rg_match_lit(RGValue *dest, const RGValue *value, const RGValue *pat);
void rg_match_var(RGValue *dest, const RGValue *value, const char *name);
void rg_payload(RGValue *dest, const RGValue *value, int32_t index, const char *field);
void rg_fn_ref(RGValue *dest, void (*fn)(RGValue *, RGValue *, int32_t), int32_t arity);
void rg_closure(RGValue *dest, void (*fn)(RGValue *, RGValue *, int32_t), int32_t arity, RGValue *caps, int32_t ncaps);
void rg_call_val(RGValue *dest, RGValue *base, int32_t argc);
void rg_throw(RGValue *v);
int32_t rg_has_throw(void);
void rg_catch(RGValue *dest);
void rg_spawn(RGValue *dest, void (*fn)(RGValue *, RGValue *, int32_t), RGValue *args, int32_t argc);
void rg_await(RGValue *dest, const RGValue *src);
void rg_signal(RGValue *dest, const char *name, int32_t arity);
void rg_register_type_signal(const char *type, const char *name, int32_t arity);
void rg_flush_deferred(void);
void rg_set_argv(int32_t argc, char **argv);
void rg_host_call(RGValue *dest, const char *module, const char *name, RGValue *args, int32_t argc);

#endif
