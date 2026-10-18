#include "rg_value.h"

#include <stdlib.h>
#include <string.h>

#if defined(_WIN32)
#define RG_EXPORT __declspec(dllexport)
#else
#define RG_EXPORT __attribute__((visibility("default")))
#endif

extern void rg_init_methods(void);
extern void rg_godot_new(int64_t object_id, RGValue *dest);

static void rg_script_copy_args(RGValue *dst, const RGValue *src, int32_t n) {
    int32_t i;
    for (i = 0; i < n; i++) {
        rg_copy(&dst[i], src != NULL ? &src[i] : NULL);
    }
}

RG_EXPORT void rg_script_init(void) {
    rg_init_methods();
}

RG_EXPORT void rg_script_new(int64_t object_id, RGValue *dest) {
    if (dest == NULL) {
        return;
    }
    rg_godot_new(object_id, dest);
}

RG_EXPORT void rg_script_call(RGValue *self, const char *method, RGValue *args, int32_t argc, RGValue *dest) {
    RGValue *base;
    int32_t n;
    if (dest == NULL) {
        return;
    }
    rg_set_void(dest);
    if (self == NULL || method == NULL) {
        return;
    }
    if (argc < 0) {
        argc = 0;
    }
    n = argc + 1;
    base = (RGValue *) malloc((size_t) n * sizeof(RGValue));
    if (base == NULL) {
        return;
    }
    rg_copy(&base[0], self);
    rg_script_copy_args(&base[1], args, argc);
    rg_godot_props_begin();
    rg_call_method(dest, base, n, method);
    free(base);
}

RG_EXPORT int32_t rg_script_fn_same(const RGValue *a, const RGValue *b) {
    return rg_fn_same(a, b);
}

RG_EXPORT uint32_t rg_script_fn_hash(const RGValue *v) {
    return rg_fn_hash(v);
}

RG_EXPORT void rg_script_call_val(RGValue *fn, RGValue *args, int32_t argc, RGValue *dest) {
    RGValue *base;
    int32_t n;
    if (dest == NULL) {
        return;
    }
    rg_set_void(dest);
    if (fn == NULL) {
        return;
    }
    if (argc < 0) {
        argc = 0;
    }
    n = argc + 1;
    base = (RGValue *) malloc((size_t) n * sizeof(RGValue));
    if (base == NULL) {
        return;
    }
    rg_copy(&base[0], fn);
    rg_script_copy_args(&base[1], args, argc);
    rg_call_val(dest, base, n);
    free(base);
}

RG_EXPORT void rg_script_get(RGValue *self, const char *field, RGValue *dest) {
    if (dest == NULL) {
        return;
    }
    rg_member(dest, self, field);
}

RG_EXPORT void rg_script_set(RGValue *self, const char *field, const RGValue *value) {
    rg_field_set(self, field, value);
}

RG_EXPORT void rg_script_set_signal(RGGodotSignalFn fn) {
    rg_set_godot_signal(fn);
}

RG_EXPORT void rg_script_set_call(RGGodotCallFn fn) {
    rg_set_godot_call(fn);
}

RG_EXPORT void rg_script_set_prop(RGGodotPropFn fn) {
    rg_set_godot_prop(fn);
}

RG_EXPORT void rg_script_set_singleton(RGGodotSingletonFn fn) {
    rg_set_godot_singleton(fn);
}

RG_EXPORT void rg_script_set_static(RGGodotStaticFn fn) {
    rg_set_godot_static(fn);
}

RG_EXPORT void rg_script_set_fn(RGGodotFnFn fn) {
    rg_set_godot_fn(fn);
}

RG_EXPORT void rg_script_set_value(RGGodotValueFn fn) {
    rg_set_godot_value(fn);
}

RG_EXPORT void rg_script_fn_host(RGValue *dest, void (*fn)(RGValue *, RGValue *, int32_t), int64_t id) {
    rg_fn_host(dest, fn, id);
}

RG_EXPORT int64_t rg_script_fn_host_id(const RGValue *v) {
    return rg_fn_host_id(v);
}

RG_EXPORT const char *rg_script_type_name(const RGValue *v) {
    return rg_struct_name(v);
}

RG_EXPORT int32_t rg_script_has_field(const RGValue *v, const char *name) {
    return rg_struct_has(v, name);
}

RG_EXPORT void rg_script_new_struct(RGValue *dest, const char *type, RGValue *fields, int32_t n, const char **names) {
    if (dest == NULL) {
        return;
    }
    rg_struct(dest, type, fields, n, names, 0);
}

RG_EXPORT void rg_script_new_data(RGValue *dest, const char *type, RGValue *fields, int32_t n, const char **names) {
    if (dest == NULL) {
        return;
    }
    rg_struct(dest, type, fields, n, names, 1);
}

RG_EXPORT void rg_script_new_vec2(RGValue *dest, double x, double y) {
    RGValue fields[2];
    const char *names[2];
    names[0] = "x";
    names[1] = "y";
    rg_set_float(&fields[0], x);
    rg_set_float(&fields[1], y);
    rg_script_new_struct(dest, "Vec2", fields, 2, names);
}

RG_EXPORT void rg_script_new_vec2i(RGValue *dest, int64_t x, int64_t y) {
    RGValue fields[2];
    const char *names[2];
    names[0] = "x";
    names[1] = "y";
    rg_set_int(&fields[0], x);
    rg_set_int(&fields[1], y);
    rg_script_new_struct(dest, "Vector2i", fields, 2, names);
}

RG_EXPORT void rg_script_new_vec3(RGValue *dest, double x, double y, double z) {
    RGValue fields[3];
    const char *names[3];
    names[0] = "x";
    names[1] = "y";
    names[2] = "z";
    rg_set_float(&fields[0], x);
    rg_set_float(&fields[1], y);
    rg_set_float(&fields[2], z);
    rg_script_new_struct(dest, "Vector3", fields, 3, names);
}

RG_EXPORT void rg_script_new_vec4(RGValue *dest, double x, double y, double z, double w) {
    RGValue fields[4];
    const char *names[4];
    names[0] = "x";
    names[1] = "y";
    names[2] = "z";
    names[3] = "w";
    rg_set_float(&fields[0], x);
    rg_set_float(&fields[1], y);
    rg_set_float(&fields[2], z);
    rg_set_float(&fields[3], w);
    rg_script_new_struct(dest, "Vector4", fields, 4, names);
}

RG_EXPORT void rg_script_new_vec3i(RGValue *dest, int64_t x, int64_t y, int64_t z) {
    RGValue fields[3];
    const char *names[3];
    names[0] = "x";
    names[1] = "y";
    names[2] = "z";
    rg_set_int(&fields[0], x);
    rg_set_int(&fields[1], y);
    rg_set_int(&fields[2], z);
    rg_script_new_struct(dest, "Vector3i", fields, 3, names);
}

static int64_t color_byte(double c) {
    if (c < 0.0) {
        c = 0.0;
    }
    if (c > 1.0) {
        c = 1.0;
    }
    return (int64_t) (c * 255.0);
}

static void color_hsv(double r, double g, double b, double *h, double *s, double *v) {
    double min = r < g ? r : g;
    double max = r > g ? r : g;
    double delta;
    double hue;
    if (b < min) {
        min = b;
    }
    if (b > max) {
        max = b;
    }
    delta = max - min;
    *v = max;
    *s = max == 0.0 ? 0.0 : delta / max;
    if (delta == 0.0) {
        *h = 0.0;
        return;
    }
    if (r == max) {
        hue = (g - b) / delta;
    } else if (g == max) {
        hue = 2.0 + (b - r) / delta;
    } else {
        hue = 4.0 + (r - g) / delta;
    }
    hue /= 6.0;
    if (hue < 0.0) {
        hue += 1.0;
    }
    *h = hue;
}

RG_EXPORT void rg_script_new_color(RGValue *dest, double r, double g, double b, double a) {
    RGValue fields[14];
    const char *names[14];
    double h;
    double s;
    double v;
    color_hsv(r, g, b, &h, &s, &v);
    names[0] = "r";
    names[1] = "g";
    names[2] = "b";
    names[3] = "a";
    names[4] = "r8";
    names[5] = "g8";
    names[6] = "b8";
    names[7] = "a8";
    names[8] = "h";
    names[9] = "s";
    names[10] = "v";
    names[11] = "ok_hsl_h";
    names[12] = "ok_hsl_s";
    names[13] = "ok_hsl_l";
    rg_set_float(&fields[0], r);
    rg_set_float(&fields[1], g);
    rg_set_float(&fields[2], b);
    rg_set_float(&fields[3], a);
    rg_set_int(&fields[4], color_byte(r));
    rg_set_int(&fields[5], color_byte(g));
    rg_set_int(&fields[6], color_byte(b));
    rg_set_int(&fields[7], color_byte(a));
    rg_set_float(&fields[8], h);
    rg_set_float(&fields[9], s);
    rg_set_float(&fields[10], v);
    rg_set_float(&fields[11], 0.0);
    rg_set_float(&fields[12], 0.0);
    rg_set_float(&fields[13], 0.0);
    rg_script_new_struct(dest, "Color", fields, 14, names);
}

RG_EXPORT void rg_script_new_rect2(RGValue *dest, double px, double py, double sx, double sy) {
    RGValue fields[3];
    const char *names[3];
    names[0] = "position";
    names[1] = "size";
    names[2] = "end";
    rg_script_new_vec2(&fields[0], px, py);
    rg_script_new_vec2(&fields[1], sx, sy);
    rg_script_new_vec2(&fields[2], px + sx, py + sy);
    rg_script_new_struct(dest, "Rect2", fields, 3, names);
}

RG_EXPORT void rg_script_new_array(RGValue *dest) {
    rg_array(dest, NULL, 0);
}

RG_EXPORT void rg_script_array_push(RGValue *arr, const RGValue *item) {
    RGValue base[2];
    RGValue out;
    if (arr == NULL) {
        return;
    }
    rg_copy(&base[0], arr);
    rg_copy(&base[1], item);
    rg_method(&out, base, 2, RG_M_PUSH);
}

RG_EXPORT int32_t rg_script_len(const RGValue *v) {
    RGValue out;
    rg_len(&out, v);
    if (out.kind != RG_INT || out.i < 0 || out.i > 2147483647) {
        return 0;
    }
    return (int32_t) out.i;
}

RG_EXPORT void rg_script_array_get(RGValue *dest, const RGValue *arr, int32_t index) {
    RGValue idx;
    rg_set_int(&idx, index);
    rg_index_get(dest, arr, &idx);
}

RG_EXPORT void rg_script_new_map(RGValue *dest) {
    rg_map(dest, NULL, 0);
}

RG_EXPORT void rg_script_map_put(RGValue *map, const char *key, const RGValue *value) {
    RGValue k;
    if (map == NULL) {
        return;
    }
    rg_set_string(&k, key != NULL ? key : "");
    rg_index_set(map, &k, value);
}

RG_EXPORT void rg_script_map_key_at(RGValue *dest, const RGValue *map, int32_t index) {
    rg_map_key_at(dest, map, index);
}

RG_EXPORT void rg_script_map_get(RGValue *dest, const RGValue *map, const char *key) {
    RGValue k;
    rg_set_string(&k, key != NULL ? key : "");
    rg_index_get(dest, map, &k);
}

RG_EXPORT void rg_script_new_transform2d(RGValue *dest, double xx, double xy, double yx, double yy, double ox, double oy) {
    RGValue fields[3];
    const char *names[3];
    names[0] = "x";
    names[1] = "y";
    names[2] = "origin";
    rg_script_new_vec2(&fields[0], xx, xy);
    rg_script_new_vec2(&fields[1], yx, yy);
    rg_script_new_vec2(&fields[2], ox, oy);
    rg_script_new_struct(dest, "Transform2D", fields, 3, names);
}

RG_EXPORT void rg_script_set_slots(RGSlotGet get, RGSlotPut put, RGSlotDel del) {
    rg_object_bind_slots(get, put, del);
}

RG_EXPORT void rg_script_set_object(RGValue *v, int64_t object_id) {
    rg_set_object(v, object_id);
}

RG_EXPORT int64_t rg_script_object_id(const RGValue *v) {
    return rg_object_id(v);
}

RG_EXPORT void *rg_script_object_ptr(const RGValue *v) {
    return rg_object_ptr(v);
}

RG_EXPORT void rg_script_slot_put(int64_t id, void *ptr) {
    rg_object_slot_put(id, ptr);
}

RG_EXPORT void rg_script_slot_del(int64_t id) {
    rg_object_slot_del(id);
}

RG_EXPORT void rg_script_set_void(RGValue *v) {
    rg_set_void(v);
}

RG_EXPORT void rg_script_set_bool(RGValue *v, int32_t b) {
    rg_set_bool(v, b);
}

RG_EXPORT void rg_script_set_int(RGValue *v, int64_t n) {
    rg_set_int(v, n);
}

RG_EXPORT void rg_script_set_float(RGValue *v, double x) {
    rg_set_float(v, x);
}

RG_EXPORT void rg_script_set_string(RGValue *v, const char *s) {
    char *copy;
    if (v == NULL) {
        return;
    }
    if (s == NULL) {
        s = "";
    }
    copy = (char *) malloc(strlen(s) + 1);
    if (copy == NULL) {
        rg_set_string(v, "");
        return;
    }
    memcpy(copy, s, strlen(s) + 1);
    rg_set_string(v, copy);
}

RG_EXPORT double rg_script_get_float(const RGValue *v) {
    double x;
    if (v == NULL) {
        return 0.0;
    }
    if (v->kind == RG_FLOAT) {
        memcpy(&x, &v->i, sizeof(x));
        return x;
    }
    if (v->kind == RG_INT) {
        return (double) v->i;
    }
    return 0.0;
}

RG_EXPORT int32_t rg_script_failed(void) {
    return rg_has_throw();
}

RG_EXPORT void rg_script_clear_error(RGValue *dest) {
    rg_catch(dest);
}
