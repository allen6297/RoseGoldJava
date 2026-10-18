/*
 * RoseGold scripting language for Godot 4.4+.
 * Loads an LLVM shared library produced by `llvm --godot` and runs it as a script.
 * Reload shells out to `java` (or ROSEGOLD_JAVA) with classpath ROSEGOLD_CP,
 * or the nearest parent build/classes/java/main of the .rg file.
 */
#if defined(_WIN32)
#define _CRT_SECURE_NO_WARNINGS
#define _CRT_NONSTDC_NO_DEPRECATE
#endif
#include "gdextension_interface.h"

#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#if defined(_WIN32)
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#define RG_EXPORT __declspec(dllexport)
#else
#include <dirent.h>
#include <dlfcn.h>
#include <sys/stat.h>
#define RG_EXPORT __attribute__((visibility("default")))
#endif

enum {
    RG_VOID = 0,
    RG_BOOL = 1,
    RG_INT = 2,
    RG_FLOAT = 3,
    RG_STRING = 4,
    RG_ARRAY = 5,
    RG_MAP = 7,
    RG_STRUCT = 8,
    RG_FN = 11,
    RG_OBJECT = 14
};

typedef struct RGValue {
    uint8_t kind;
    int64_t i;
    const char *p;
} RGValue;

typedef void *(*RGSlotGet)(int64_t id);
typedef void (*RGSlotPut)(int64_t id, void *ptr);
typedef void (*RGSlotDel)(int64_t id);
typedef void (*RGGodotSignalFn)(int64_t object_id, const char *name, int32_t op, const RGValue *args, int32_t argc);
typedef void (*RGGodotCallFn)(int64_t object_id, const char *name, const RGValue *args, int32_t argc, RGValue *dest);
typedef void (*RGGodotPropFn)(int64_t object_id, const char *name, RGValue *value, int32_t write);

enum { RG_GODOT_EMIT = 1, RG_GODOT_CONNECT = 2, RG_GODOT_DISCONNECT = 3 };

typedef struct ScriptApi {
    void (*init)(void);
    void (*new_inst)(int64_t object_id, RGValue *dest);
    void (*call)(RGValue *self, const char *method, RGValue *args, int32_t argc, RGValue *dest);
    void (*call_val)(RGValue *fn, RGValue *args, int32_t argc, RGValue *dest);
    int32_t (*fn_same)(const RGValue *a, const RGValue *b);
    uint32_t (*fn_hash)(const RGValue *v);
    void (*get)(RGValue *self, const char *field, RGValue *dest);
    void (*set)(RGValue *self, const char *field, const RGValue *value);
    void (*set_signal)(RGGodotSignalFn fn);
    void (*set_slots)(RGSlotGet get, RGSlotPut put, RGSlotDel del);
    void (*slot_put)(int64_t id, void *ptr);
    void (*slot_del)(int64_t id);
    void (*set_float)(RGValue *v, double x);
    double (*get_float)(const RGValue *v);
    void (*set_bool)(RGValue *v, int32_t b);
    void (*set_int)(RGValue *v, int64_t n);
    void (*set_string)(RGValue *v, const char *s);
    int32_t (*failed)(void);
    void (*set_call)(RGGodotCallFn fn);
    void (*set_prop)(RGGodotPropFn fn);
    const char *(*type_name)(const RGValue *v);
    int32_t (*has_field)(const RGValue *v, const char *name);
    void (*new_vec2)(RGValue *dest, double x, double y);
    void (*new_vec2i)(RGValue *dest, int64_t x, int64_t y);
    void (*new_vec3)(RGValue *dest, double x, double y, double z);
    void (*new_vec4)(RGValue *dest, double x, double y, double z, double w);
    void (*new_vec3i)(RGValue *dest, int64_t x, int64_t y, int64_t z);
    void (*new_color)(RGValue *dest, double r, double g, double b, double a);
    void (*new_rect2)(RGValue *dest, double px, double py, double sx, double sy);
    void (*new_transform2d)(RGValue *dest, double xx, double xy, double yx, double yy, double ox, double oy);
    void (*new_array)(RGValue *dest);
    void (*array_push)(RGValue *arr, const RGValue *item);
    int32_t (*len)(const RGValue *v);
    void (*array_get)(RGValue *dest, const RGValue *arr, int32_t index);
    void (*new_map)(RGValue *dest);
    void (*map_put)(RGValue *map, const char *key, const RGValue *value);
    void (*map_key_at)(RGValue *dest, const RGValue *map, int32_t index);
    void (*map_get)(RGValue *dest, const RGValue *map, const char *key);
    void (*new_struct)(RGValue *dest, const char *type, RGValue *fields, int32_t n, const char **names);
    void (*new_data)(RGValue *dest, const char *type, RGValue *fields, int32_t n, const char **names);
} ScriptApi;

typedef struct Member {
    char *name;
    char *type;
    double number;
    int has_number;
    int exported;
    int argc;
    char *init;
} Member;

typedef struct StructField {
    char *name;
    char *type;
} StructField;

typedef struct StructInfo {
    char *name;
    StructField *fields;
    int nfields;
    int is_data;
} StructInfo;

typedef struct ScriptData {
    int kind;
    GDExtensionObjectPtr godot;
    char *source;
    char *path;
    char *base;
    char *class_name;
    Member *methods;
    int nmethods;
    Member *vars;
    int nvars;
    Member *signals;
    int nsignals;
    StructInfo *structs;
    int nstructs;
    struct PlaceholderRef *placeholders;
    ScriptApi api;
    void *library;
    int loaded;
    GDExtensionStringNamePtr var_names;
    GDExtensionPropertyInfo *props;
} ScriptData;

typedef struct RunData {
    ScriptData *script;
    GDExtensionObjectPtr owner;
    int64_t id;
    RGValue self;
} RunData;

typedef struct PlaceholderRef {
    void *inst;
    struct PlaceholderRef *next;
} PlaceholderRef;

typedef struct Conn {
    ScriptApi api;
    RGValue fn;
    int64_t id;
    char *signal;
    struct Conn *next;
} Conn;

typedef struct Slot {
    int64_t id;
    void *ptr;
    struct Slot *next;
} Slot;

enum { KIND_LANG = 1, KIND_SCRIPT = 2, KIND_LOADER = 3, KIND_SAVER = 4, KIND_HIGHLIGHT = 5, KIND_RES = 6 };
enum { RG_HINT_RESOURCE = 17, RG_HINT_DICTIONARY = 38, RG_USAGE_DEFAULT = 6, RG_USAGE_STORAGE = 2, RG_USAGE_SCRIPT_VAR = 4102 };
enum { RG_ERR_UNAVAILABLE = 2 };

static GDExtensionInterfaceGetProcAddress g_get_proc;
static GDExtensionClassLibraryPtr g_library;
static GDExtensionInterfaceStringNewWithUtf8Chars string_new_utf8;
static GDExtensionInterfaceStringNameNewWithUtf8Chars stringname_new_utf8;
static GDExtensionInterfaceStringToUtf8Chars string_to_utf8;
static GDExtensionInterfaceVariantNewNil variant_nil;
static GDExtensionInterfaceVariantNewCopy variant_new_copy;
static GDExtensionInterfaceVariantDestroy variant_destroy;
static GDExtensionInterfaceVariantCall variant_call;
static GDExtensionInterfaceVariantCallStatic variant_call_static;
static GDExtensionInterfaceVariantGetIndexed variant_get_indexed;
static GDExtensionInterfaceVariantGetKeyed variant_get_keyed;
static GDExtensionInterfaceVariantSetKeyed variant_set_keyed;
static GDExtensionInterfaceVariantGetType variant_get_type;
static GDExtensionInterfaceVariantStringify variant_stringify;
static GDExtensionInterfaceGetVariantFromTypeConstructor get_from_type;
static GDExtensionInterfaceGetVariantToTypeConstructor get_to_type;
static GDExtensionInterfaceVariantGetPtrConstructor get_ptr_ctor;
static GDExtensionInterfaceVariantGetPtrDestructor get_ptr_dtor;
static GDExtensionInterfaceGlobalGetSingleton global_get_singleton;
static GDExtensionInterfaceClassdbRegisterExtensionClass4 classdb_register;
static GDExtensionInterfaceClassdbConstructObject2 classdb_construct;
static GDExtensionInterfaceObjectSetInstance object_set_instance;
static GDExtensionInterfaceObjectGetInstanceId object_get_id;
static GDExtensionInterfaceObjectGetInstanceFromId object_from_id;
static GDExtensionInterfaceObjectDestroy object_destroy;
static GDExtensionInterfaceScriptInstanceCreate3 script_instance_create3;
static GDExtensionInterfacePlaceHolderScriptInstanceCreate placeholder_script_instance_create;
static GDExtensionInterfacePlaceHolderScriptInstanceUpdate placeholder_script_instance_update;
static GDExtensionInterfaceCallableCustomCreate2 callable_custom_create2;
static GDExtensionInterfaceCallableCustomGetUserData callable_custom_get_userdata;
static GDExtensionInterfaceMemAlloc mem_alloc;
static GDExtensionInterfaceMemFree mem_free;
static GDExtensionInterfacePrintError print_error_fn;
static GDExtensionInterfaceVariantSet variant_set;
static GDExtensionInterfaceClassdbGetClassTag classdb_get_class_tag;
static GDExtensionVariantFromTypeConstructorFunc from_bool, from_int, from_float, from_string, from_stringname, from_object, from_vector2, from_vector2i, from_vector3, from_vector3i, from_vector4, from_vector4i, from_rect2, from_rect2i, from_transform2d, from_plane, from_quaternion, from_aabb, from_basis, from_transform3d, from_projection, from_array, from_dict, from_packed_string, from_color, from_rid, from_signal;
static GDExtensionTypeFromVariantConstructorFunc to_bool, to_int, to_float, to_string, to_object, to_vector2, to_vector2i, to_vector3, to_vector3i, to_vector4, to_vector4i, to_rect2, to_rect2i, to_transform2d, to_plane, to_quaternion, to_aabb, to_basis, to_transform3d, to_projection, to_array, to_dict, to_packed_string, to_color, to_rid, to_callable, to_signal;
static GDExtensionPtrDestructor dtor_string, dtor_stringname, dtor_array, dtor_dict, dtor_packed_string, dtor_callable, dtor_signal;
static GDExtensionVariantFromTypeConstructorFunc from_callable;
static ScriptData *g_last_script;
static GDExtensionObjectPtr g_language;
static GDExtensionObjectPtr g_loader;
static GDObjectInstanceID g_language_id;
static GDObjectInstanceID g_loader_id;
static GDObjectInstanceID g_script_ids[64];
static ScriptData *g_script_data[64];
static int g_nscript_ids;
static GDExtensionObjectPtr g_saver;
static GDObjectInstanceID g_saver_id;
static Slot *g_slots;
static Conn *g_conns;
static ScriptApi *g_api;
static GDExtensionScriptInstanceInfo3 g_instance_info;
static int g_info_ready;

static void *vbuf(void) {
    void *p = mem_alloc != NULL ? mem_alloc(40) : calloc(1, 40);
    memset(p, 0, 40);
    return p;
}

static void vfree(void *p) {
    if (p == NULL) {
        return;
    }
    if (mem_free != NULL) {
        mem_free(p);
    } else {
        free(p);
    }
}

static void sn_new(void *dest, const char *text) {
    stringname_new_utf8(dest, text != NULL ? text : "");
}

static void sn_free(void *sn) {
    if (dtor_stringname != NULL && sn != NULL) {
        dtor_stringname(sn);
    }
}

static int sn_eq(GDExtensionConstStringNamePtr name, const char *lit) {
    unsigned char local[16];
    unsigned char var[40];
    unsigned char str[16];
    char stack[160];
    char *buf;
    GDExtensionInt n;
    int ok;
    if (name == NULL || lit == NULL) {
        return 0;
    }
    sn_new(local, lit);
    ok = memcmp(name, local, sizeof(void *)) == 0;
    sn_free(local);
    if (ok) {
        return 1;
    }
    memset(var, 0, sizeof(var));
    from_stringname(var, (GDExtensionTypePtr) name);
    memset(str, 0, sizeof(str));
    variant_stringify(var, str);
    variant_destroy(var);
    n = string_to_utf8(str, NULL, 0);
    buf = n < (GDExtensionInt) sizeof(stack) ? stack : (char *) malloc((size_t) n + 1);
    if (buf == NULL) {
        dtor_string(str);
        return 0;
    }
    string_to_utf8(str, buf, n);
    buf[n] = 0;
    dtor_string(str);
    ok = strcmp(buf, lit) == 0;
    if (buf != stack) {
        free(buf);
    }
    return ok;
}

static char *string_dup(GDExtensionConstTypePtr str) {
    GDExtensionInt n;
    char *buf;
    if (str == NULL) {
        return strdup("");
    }
    n = string_to_utf8(str, NULL, 0);
    buf = (char *) malloc((size_t) n + 1);
    if (buf == NULL) {
        return strdup("");
    }
    string_to_utf8(str, buf, n);
    buf[n] = 0;
    return buf;
}

static void *var_string(const char *text) {
    void *s = vbuf();
    void *v = vbuf();
    string_new_utf8(s, text != NULL ? text : "");
    from_string(v, s);
    dtor_string(s);
    vfree(s);
    return v;
}

static void *var_bool(int b) {
    void *v = vbuf();
    uint8_t bit = b ? 1 : 0;
    from_bool(v, &bit);
    return v;
}

static void *var_int(int64_t n) {
    void *v = vbuf();
    from_int(v, &n);
    return v;
}

static void *var_float(double x) {
    void *v = vbuf();
    from_float(v, &x);
    return v;
}

static void *var_object(GDExtensionObjectPtr obj) {
    void *v = vbuf();
    from_object(v, &obj);
    return v;
}

static void *var_name(const char *text) {
    void *sn = vbuf();
    void *v = vbuf();
    sn_new(sn, text);
    from_stringname(v, sn);
    sn_free(sn);
    vfree(sn);
    return v;
}

static void call_ok(GDExtensionObjectPtr obj, const char *method, const void **args, int argc) {
    void *self;
    void *ret;
    unsigned char sn[16];
    GDExtensionCallError err;
    if (obj == NULL) {
        return;
    }
    self = var_object(obj);
    ret = vbuf();
    sn_new(sn, method);
    memset(&err, 0, sizeof(err));
    variant_call(self, sn, (const GDExtensionConstVariantPtr *) args, argc, ret, &err);
    sn_free(sn);
    variant_destroy(ret);
    vfree(ret);
    variant_destroy(self);
    vfree(self);
}

static void ret_bool(GDExtensionTypePtr r, int v) {
    *(uint8_t *) r = v ? 1 : 0;
}

static void ret_int(GDExtensionTypePtr r, int64_t v) {
    *(int64_t *) r = v;
}

static void ret_ptr(GDExtensionTypePtr r, void *p) {
    *(void **) r = p;
}

static void ret_string(GDExtensionTypePtr r, const char *text) {
    string_new_utf8(r, text != NULL ? text : "");
}

static void ret_stringname(GDExtensionTypePtr r, const char *text) {
    sn_new(r, text != NULL ? text : "");
}

static void *empty_builtin(GDExtensionVariantType type) {
    void *storage = vbuf();
    GDExtensionPtrConstructor ctor = get_ptr_ctor(type, 0);
    if (ctor != NULL) {
        ctor(storage, NULL);
    }
    return storage;
}

static void *variant_of_builtin(GDExtensionVariantType type, void *storage) {
    void *v = vbuf();
    GDExtensionVariantFromTypeConstructorFunc from = get_from_type(type);
    from(v, storage);
    return v;
}

static void push_variant(void *array_variant, void *value) {
    unsigned char sn[16];
    void *ret = vbuf();
    const void *args[1];
    GDExtensionCallError err;
    args[0] = value;
    sn_new(sn, "push_back");
    memset(&err, 0, sizeof(err));
    variant_call(array_variant, sn, (const GDExtensionConstVariantPtr *) args, 1, ret, &err);
    sn_free(sn);
    variant_destroy(ret);
    vfree(ret);
}

static void dict_put(void *dict_variant, const char *key, void *value) {
    void *k = var_string(key);
    uint8_t valid = 0;
    GDExtensionInterfaceVariantSetNamed set_named =
            (GDExtensionInterfaceVariantSetNamed) (void *) g_get_proc("variant_set_named");
    if (set_named != NULL) {
        unsigned char sn[16];
        sn_new(sn, key);
        set_named(dict_variant, sn, value, &valid);
        sn_free(sn);
    }
    variant_destroy(k);
    vfree(k);
}

static void ret_array(GDExtensionTypePtr r, void *array_variant) {
    to_array(r, array_variant);
}

static void ret_packed(GDExtensionTypePtr r, void *packed_variant) {
    to_packed_string(r, packed_variant);
}

static void *packed_with(const char *text) {
    void *storage = empty_builtin(GDEXTENSION_VARIANT_TYPE_PACKED_STRING_ARRAY);
    void *var = variant_of_builtin(GDEXTENSION_VARIANT_TYPE_PACKED_STRING_ARRAY, storage);
    void *s = var_string(text);
    GDExtensionPtrDestructor dtor = get_ptr_dtor(GDEXTENSION_VARIANT_TYPE_PACKED_STRING_ARRAY);
    push_variant(var, s);
    if (dtor != NULL) {
        dtor(storage);
    }
    vfree(storage);
    variant_destroy(s);
    vfree(s);
    return var;
}

static void ret_one_extension(GDExtensionTypePtr r) {
    void *var = packed_with("rg");
    ret_packed(r, var);
    variant_destroy(var);
    vfree(var);
}

static void ret_empty_packed(GDExtensionTypePtr r) {
    void *storage = empty_builtin(GDEXTENSION_VARIANT_TYPE_PACKED_STRING_ARRAY);
    void *var = variant_of_builtin(GDEXTENSION_VARIANT_TYPE_PACKED_STRING_ARRAY, storage);
    GDExtensionPtrDestructor dtor = get_ptr_dtor(GDEXTENSION_VARIANT_TYPE_PACKED_STRING_ARRAY);
    to_packed_string(r, var);
    if (dtor != NULL) {
        dtor(storage);
    }
    vfree(storage);
    variant_destroy(var);
    vfree(var);
}

static void ret_empty_array(GDExtensionTypePtr r) {
    void *storage = empty_builtin(GDEXTENSION_VARIANT_TYPE_ARRAY);
    void *var = variant_of_builtin(GDEXTENSION_VARIANT_TYPE_ARRAY, storage);
    GDExtensionPtrDestructor dtor = get_ptr_dtor(GDEXTENSION_VARIANT_TYPE_ARRAY);
    to_array(r, var);
    if (dtor != NULL) {
        dtor(storage);
    }
    vfree(storage);
    variant_destroy(var);
    vfree(var);
}

static void ret_empty_dict(GDExtensionTypePtr r) {
    void *storage = empty_builtin(GDEXTENSION_VARIANT_TYPE_DICTIONARY);
    void *var = variant_of_builtin(GDEXTENSION_VARIANT_TYPE_DICTIONARY, storage);
    GDExtensionPtrDestructor dtor = get_ptr_dtor(GDEXTENSION_VARIANT_TYPE_DICTIONARY);
    to_dict(r, var);
    if (dtor != NULL) {
        dtor(storage);
    }
    vfree(storage);
    variant_destroy(var);
    vfree(var);
}

static void ret_lookup_miss(GDExtensionTypePtr r) {
    void *storage = empty_builtin(GDEXTENSION_VARIANT_TYPE_DICTIONARY);
    void *var = variant_of_builtin(GDEXTENSION_VARIANT_TYPE_DICTIONARY, storage);
    void *result = var_int(RG_ERR_UNAVAILABLE);
    void *type = var_int(0);
    GDExtensionPtrDestructor dtor = get_ptr_dtor(GDEXTENSION_VARIANT_TYPE_DICTIONARY);
    dict_put(var, "result", result);
    dict_put(var, "type", type);
    to_dict(r, var);
    variant_destroy(result);
    vfree(result);
    variant_destroy(type);
    vfree(type);
    if (dtor != NULL) {
        dtor(storage);
    }
    vfree(storage);
    variant_destroy(var);
    vfree(var);
}

static int listed(const char **items, const char *name) {
    int i;
    if (items == NULL || name == NULL) {
        return 0;
    }
    for (i = 0; items[i] != NULL; i++) {
        if (strcmp(items[i], name) == 0) {
            return 1;
        }
    }
    return 0;
}

static int node2d_member(const char *name) {
    return strcmp(name, "position") == 0 || strcmp(name, "rotation") == 0 || strcmp(name, "scale") == 0
            || strcmp(name, "get_position") == 0 || strcmp(name, "set_position") == 0
            || strcmp(name, "get_rotation") == 0 || strcmp(name, "set_rotation") == 0
            || strcmp(name, "get_scale") == 0 || strcmp(name, "set_scale") == 0;
}

static void ret_lookup_hit(GDExtensionTypePtr r, int type, const char *class_name, const char *member, int location, const char *script_path, GDExtensionObjectPtr script) {
    void *storage = empty_builtin(GDEXTENSION_VARIANT_TYPE_DICTIONARY);
    void *var = variant_of_builtin(GDEXTENSION_VARIANT_TYPE_DICTIONARY, storage);
    void *result = var_int(0);
    void *ty = var_int(type);
    void *cn = var_string(class_name != NULL ? class_name : "");
    void *cm = var_string(member != NULL ? member : "");
    void *loc = var_int(location);
    void *desc = var_string("");
    void *sp = NULL;
    void *so = NULL;
    GDExtensionPtrDestructor dtor = get_ptr_dtor(GDEXTENSION_VARIANT_TYPE_DICTIONARY);
    dict_put(var, "result", result);
    dict_put(var, "type", ty);
    dict_put(var, "class_name", cn);
    dict_put(var, "class_member", cm);
    dict_put(var, "location", loc);
    dict_put(var, "description", desc);
    if (script_path != NULL && script_path[0] != 0) {
        sp = var_string(script_path);
        dict_put(var, "script_path", sp);
    }
    if (script != NULL) {
        so = var_object(script);
        dict_put(var, "script", so);
    }
    to_dict(r, var);
    variant_destroy(result);
    vfree(result);
    variant_destroy(ty);
    vfree(ty);
    variant_destroy(cn);
    vfree(cn);
    variant_destroy(cm);
    vfree(cm);
    variant_destroy(loc);
    vfree(loc);
    variant_destroy(desc);
    vfree(desc);
    if (sp != NULL) {
        variant_destroy(sp);
        vfree(sp);
    }
    if (so != NULL) {
        variant_destroy(so);
        vfree(so);
    }
    if (dtor != NULL) {
        dtor(storage);
    }
    vfree(storage);
    variant_destroy(var);
    vfree(var);
}

static void ret_complete_miss(GDExtensionTypePtr r) {
    void *storage = empty_builtin(GDEXTENSION_VARIANT_TYPE_DICTIONARY);
    void *var = variant_of_builtin(GDEXTENSION_VARIANT_TYPE_DICTIONARY, storage);
    void *result = var_int(RG_ERR_UNAVAILABLE);
    void *force = var_bool(0);
    void *hint = var_string("");
    GDExtensionPtrDestructor dtor = get_ptr_dtor(GDEXTENSION_VARIANT_TYPE_DICTIONARY);
    dict_put(var, "result", result);
    dict_put(var, "force", force);
    dict_put(var, "call_hint", hint);
    to_dict(r, var);
    variant_destroy(result);
    vfree(result);
    variant_destroy(force);
    vfree(force);
    variant_destroy(hint);
    vfree(hint);
    if (dtor != NULL) {
        dtor(storage);
    }
    vfree(storage);
    variant_destroy(var);
    vfree(var);
}

static void ret_valid_dict(GDExtensionTypePtr r) {
    void *storage = empty_builtin(GDEXTENSION_VARIANT_TYPE_DICTIONARY);
    void *var = variant_of_builtin(GDEXTENSION_VARIANT_TYPE_DICTIONARY, storage);
    void *yes = var_bool(1);
    GDExtensionPtrDestructor dtor = get_ptr_dtor(GDEXTENSION_VARIANT_TYPE_DICTIONARY);
    dict_put(var, "valid", yes);
    to_dict(r, var);
    variant_destroy(yes);
    vfree(yes);
    if (dtor != NULL) {
        dtor(storage);
    }
    vfree(storage);
    variant_destroy(var);
    vfree(var);
}

static ScriptData *script_of(GDExtensionClassInstancePtr inst) {
    return (ScriptData *) inst;
}

static void ensure_highlighter(void);
static GDExtensionObjectPtr create_highlighter(void *userdata, GDExtensionBool notify);

typedef struct HighlighterData {
    int kind;
    GDExtensionObjectPtr godot;
} HighlighterData;

typedef struct HiPalette {
    float text[4];
    float keyword[4];
    float flow[4];
    float comment[4];
    float string[4];
    float number[4];
    float type[4];
    float function[4];
    float symbol[4];
    float member[4];
    int loaded;
} HiPalette;

static HiPalette g_hi;

static const struct {
    const char *name;
    int flow;
} HI_WORDS[] = {
    {"if", 1}, {"elif", 1}, {"else", 1}, {"for", 1}, {"while", 1}, {"in", 1},
    {"match", 1}, {"switch", 1}, {"return", 1}, {"break", 1}, {"continue", 1},
    {"try", 1}, {"catch", 1}, {"throw", 1}, {"do", 1},
    {"fn", 0}, {"var", 0}, {"const", 0}, {"export", 0}, {"struct", 0}, {"data", 0}, {"class", 0},
    {"trait", 0}, {"extends", 0}, {"enum", 0}, {"mod", 0}, {"impl", 0}, {"pub", 0},
    {"private", 0}, {"protected", 0}, {"abstract", 0}, {"final", 0}, {"static", 0}, {"signal", 0},
    {"import", 0}, {"from", 0}, {"as", 0}, {"self", 0}, {"super", 0}, {"true", 0},
    {"false", 0}, {"null", 0}, {"pass", 0}, {"throws", 0}, {"async", 0}, {"await", 0}, {"spawn", 0},
    {NULL, 0}
};

static int hi_eq(const char *s, int n, const char *word) {
    int k = (int) strlen(word);
    return n == k && memcmp(s, word, (size_t) n) == 0;
}

static int hi_flow(const char *s, int n) {
    int i;
    for (i = 0; HI_WORDS[i].name != NULL; i++) {
        if (HI_WORDS[i].flow && hi_eq(s, n, HI_WORDS[i].name)) {
            return 1;
        }
    }
    return 0;
}

static int hi_keyword(const char *s, int n) {
    int i;
    for (i = 0; HI_WORDS[i].name != NULL; i++) {
        if (hi_eq(s, n, HI_WORDS[i].name)) {
            return 1;
        }
    }
    return 0;
}

static void hi_rgba(float *dst, float r, float g, float b, float a) {
    dst[0] = r;
    dst[1] = g;
    dst[2] = b;
    dst[3] = a;
}

static void ret_words(GDExtensionTypePtr r, int flow_only) {
    void *storage = empty_builtin(GDEXTENSION_VARIANT_TYPE_PACKED_STRING_ARRAY);
    void *var = variant_of_builtin(GDEXTENSION_VARIANT_TYPE_PACKED_STRING_ARRAY, storage);
    GDExtensionPtrDestructor dtor = get_ptr_dtor(GDEXTENSION_VARIANT_TYPE_PACKED_STRING_ARRAY);
    int i;
    for (i = 0; HI_WORDS[i].name != NULL; i++) {
        void *s;
        if (flow_only && !HI_WORDS[i].flow) {
            continue;
        }
        s = var_string(HI_WORDS[i].name);
        push_variant(var, s);
        variant_destroy(s);
        vfree(s);
    }
    to_packed_string(r, var);
    if (dtor != NULL) {
        dtor(storage);
    }
    vfree(storage);
    variant_destroy(var);
    vfree(var);
}

static void ret_literals(GDExtensionTypePtr r, const char **items, int n) {
    void *storage = empty_builtin(GDEXTENSION_VARIANT_TYPE_PACKED_STRING_ARRAY);
    void *var = variant_of_builtin(GDEXTENSION_VARIANT_TYPE_PACKED_STRING_ARRAY, storage);
    GDExtensionPtrDestructor dtor = get_ptr_dtor(GDEXTENSION_VARIANT_TYPE_PACKED_STRING_ARRAY);
    int i;
    for (i = 0; i < n; i++) {
        void *s = var_string(items[i]);
        push_variant(var, s);
        variant_destroy(s);
        vfree(s);
    }
    to_packed_string(r, var);
    if (dtor != NULL) {
        dtor(storage);
    }
    vfree(storage);
    variant_destroy(var);
    vfree(var);
}

enum {
    CC_CLASS = 0,
    CC_FUNCTION = 1,
    CC_SIGNAL = 2,
    CC_VARIABLE = 3,
    CC_MEMBER = 4,
    CC_KEYWORD = 10
};

static const char *NODE_FIELDS[] = {"position", "rotation", "scale", NULL};
static const char *NODE_METHODS[] = {
    "get_node", "get_node_or_null", "get_name", "set_name", "get_parent", "get_child", "get_child_count",
    "add_child", "remove_child", "queue_free", "is_inside_tree",
    "get_position", "set_position", "get_rotation", "set_rotation", "get_scale", "set_scale",
    NULL
};
static const char *RG_TYPES[] = {"Int", "Float", "String", "Bool", "Void", "Vec2", "Node", "Node2D", "Object", NULL};
static const char *RG_ANNOTATIONS[] = {"@optional", "@test", "@deprecated", "@constexpr", "@ufcs", NULL};

typedef struct CompDecl {
    char name[64];
    char hint[96];
    int kind;
    int line;
} CompDecl;

static int hi_ident_start(char c);
static int hi_ident(char c);

static int at_cursor_marker(const char *s, int i, int n) {
    return i + 2 < n && (unsigned char) s[i] == 0xEF && (unsigned char) s[i + 1] == 0xBF
            && (unsigned char) s[i + 2] == 0xBF;
}

static int cursor_index(const char *s, int n) {
    int i;
    for (i = 0; i + 2 < n; i++) {
        if (at_cursor_marker(s, i, n)) {
            return i;
        }
    }
    return n;
}

static int read_ident(const char *s, int i, int n, char *out, int cap) {
    int k = 0;
    if (out != NULL && cap > 0) {
        out[0] = 0;
    }
    if (i >= n || !hi_ident_start(s[i])) {
        return i;
    }
    while (i < n && hi_ident(s[i])) {
        if (out != NULL && k + 1 < cap) {
            out[k++] = s[i];
        }
        i++;
    }
    if (out != NULL && cap > 0) {
        out[k] = 0;
    }
    return i;
}

static int decl_keyword(const char *name) {
    return strcmp(name, "fn") == 0 || strcmp(name, "var") == 0 || strcmp(name, "const") == 0
            || strcmp(name, "signal") == 0 || strcmp(name, "class") == 0;
}

static int decl_kind(const char *kw) {
    if (strcmp(kw, "fn") == 0) {
        return CC_FUNCTION;
    }
    if (strcmp(kw, "signal") == 0) {
        return CC_SIGNAL;
    }
    if (strcmp(kw, "class") == 0) {
        return CC_CLASS;
    }
    return CC_VARIABLE;
}

static int line_of(const char *s, int index) {
    int line = 1;
    int i;
    if (s == NULL || index < 0) {
        return 1;
    }
    for (i = 0; i < index; i++) {
        if (s[i] == '\n') {
            line++;
        }
    }
    return line;
}

static void add_decl(CompDecl *decls, int *count, int cap, const char *name, int kind, const char *hint, int line, int unique) {
    int i;
    if (name == NULL || name[0] == 0 || *count >= cap) {
        return;
    }
    if (unique) {
        for (i = 0; i < *count; i++) {
            if (strcmp(decls[i].name, name) == 0) {
                return;
            }
        }
    }
    snprintf(decls[*count].name, sizeof(decls[*count].name), "%s", name);
    snprintf(decls[*count].hint, sizeof(decls[*count].hint), "%s", hint != NULL ? hint : "");
    decls[*count].kind = kind;
    decls[*count].line = line > 0 ? line : 1;
    (*count)++;
}

static void scan_params(const char *s, int i, int n, CompDecl *decls, int *count, int cap, int unique) {
    int depth = 0;
    if (i >= n || s[i] != '(') {
        return;
    }
    while (i < n) {
        if (at_cursor_marker(s, i, n)) {
            i += 3;
            continue;
        }
        if (s[i] == '(') {
            depth++;
            i++;
            continue;
        }
        if (s[i] == ')') {
            depth--;
            if (depth == 0) {
                return;
            }
            i++;
            continue;
        }
        if (depth == 1 && hi_ident_start(s[i])) {
            char name[64];
            int k;
            i = read_ident(s, i, n, name, (int) sizeof(name));
            k = i;
            while (k < n && (s[k] == ' ' || s[k] == '\t')) {
                k++;
            }
            if (k < n && s[k] == ':') {
                int at = i;
                while (at > 0 && hi_ident(s[at - 1])) {
                    at--;
                }
                add_decl(decls, count, cap, name, CC_VARIABLE, "", line_of(s, at), unique);
            }
            continue;
        }
        i++;
    }
}

static void scan_decls(const char *s, int n, CompDecl *decls, int *count, int cap, int unique) {
    int i = 0;
    int in_string = 0;
    int in_block = 0;
    int in_line = 0;
    char pending[16];
    pending[0] = 0;
    while (i < n) {
        if (at_cursor_marker(s, i, n)) {
            i += 3;
            continue;
        }
        if (in_line) {
            if (s[i] == '\n') {
                in_line = 0;
            }
            i++;
            continue;
        }
        if (in_string) {
            if (s[i] == '\\' && i + 1 < n) {
                i += 2;
                continue;
            }
            if (s[i] == '"') {
                in_string = 0;
            }
            i++;
            continue;
        }
        if (in_block) {
            if (i + 1 < n && s[i] == '#' && s[i + 1] == '/') {
                in_block = 0;
                i += 2;
                continue;
            }
            i++;
            continue;
        }
        if (s[i] == ' ' || s[i] == '\t' || s[i] == '\r' || s[i] == '\n') {
            i++;
            continue;
        }
        if ((s[i] == '/' && i + 1 < n && s[i + 1] == '/') || (s[i] == '#' && !(i + 1 < n && s[i + 1] == '/'))) {
            in_line = 1;
            pending[0] = 0;
            i++;
            continue;
        }
        if (s[i] == '/' && i + 1 < n && s[i + 1] == '#') {
            in_block = 1;
            pending[0] = 0;
            i += 2;
            continue;
        }
        if (s[i] == '"') {
            in_string = 1;
            pending[0] = 0;
            i++;
            continue;
        }
        if (hi_ident_start(s[i])) {
            char name[64];
            int next = read_ident(s, i, n, name, (int) sizeof(name));
            if (pending[0] != 0) {
                char hint[96];
                int k = next;
                hint[0] = 0;
                while (k < n && (s[k] == ' ' || s[k] == '\t')) {
                    k++;
                }
                if (strcmp(pending, "fn") == 0 && k < n && s[k] == '(') {
                    int end = k;
                    int depth = 0;
                    while (end < n) {
                        if (s[end] == '(') {
                            depth++;
                        } else if (s[end] == ')') {
                            depth--;
                            if (depth == 0) {
                                end++;
                                break;
                            }
                        }
                        end++;
                    }
                    snprintf(hint, sizeof(hint), "%s%.*s", name, end - k < 64 ? end - k : 64, s + k);
                    scan_params(s, k, n, decls, count, cap, unique);
                }
                add_decl(decls, count, cap, name, decl_kind(pending), hint, line_of(s, i), unique);
                pending[0] = 0;
            } else if (decl_keyword(name)) {
                snprintf(pending, sizeof(pending), "%s", name);
            }
            i = next;
            continue;
        }
        pending[0] = 0;
        i++;
    }
}

static char *globalize(const char *path);
static char *read_text_file(const char *path);

static int path_ieq(const char *a, const char *b) {
    if (a == NULL || b == NULL) {
        return 0;
    }
    while (*a != 0 && *b != 0) {
        char ca = *a == '/' ? '\\' : *a;
        char cb = *b == '/' ? '\\' : *b;
        if (ca >= 'A' && ca <= 'Z') {
            ca = (char) (ca - 'A' + 'a');
        }
        if (cb >= 'A' && cb <= 'Z') {
            cb = (char) (cb - 'A' + 'a');
        }
        if (ca != cb) {
            return 0;
        }
        a++;
        b++;
    }
    return *a == 0 && *b == 0;
}

static int name_is_rg(const char *name) {
    size_t n = name != NULL ? strlen(name) : 0;
    char a;
    char b;
    if (n < 3 || name[n - 3] != '.') {
        return 0;
    }
    a = name[n - 2];
    b = name[n - 1];
    if (a >= 'A' && a <= 'Z') {
        a = (char) (a - 'A' + 'a');
    }
    if (b >= 'A' && b <= 'Z') {
        b = (char) (b - 'A' + 'a');
    }
    return a == 'r' && b == 'g';
}

static int drop_last(char *path) {
    char *s;
    char *cut = NULL;
    if (path == NULL || path[0] == 0) {
        return 0;
    }
    for (s = path; *s != 0; s++) {
        if (*s == '/' || *s == '\\') {
            cut = s;
        }
    }
    if (cut == NULL || cut == path) {
        return 0;
    }
    *cut = 0;
    return 1;
}

static int is_plain_file(const char *path) {
#if defined(_WIN32)
    DWORD attr = GetFileAttributesA(path);
    return attr != INVALID_FILE_ATTRIBUTES && (attr & FILE_ATTRIBUTE_DIRECTORY) == 0;
#else
    struct stat st;
    return path != NULL && stat(path, &st) == 0 && S_ISREG(st.st_mode);
#endif
}

static int project_root_of(const char *global_file, char *root, int cap) {
    char cur[1024];
    int i;
    int n;
    if (global_file == NULL || global_file[0] == 0) {
        return 0;
    }
    n = snprintf(cur, sizeof(cur), "%s", global_file);
    if (n <= 0 || n >= (int) sizeof(cur) || !drop_last(cur)) {
        return 0;
    }
    for (i = 0; i < 8; i++) {
        char probe[1100];
        int pn = snprintf(probe, sizeof(probe), "%s%cproject.godot", cur, strchr(cur, '/') != NULL && strchr(cur, '\\') == NULL ? '/' : '\\');
        if (pn > 0 && pn < (int) sizeof(probe) && is_plain_file(probe)) {
            n = snprintf(root, (size_t) cap, "%s", cur);
            return n > 0 && n < cap;
        }
        if (!drop_last(cur)) {
            return 0;
        }
    }
    return 0;
}

static int to_res_path(const char *root, const char *full, char *out, int cap) {
    char a[1024];
    char b[1024];
    size_t n;
    const char *rel;
    int i;
    int an;
    int bn;
    an = snprintf(a, sizeof(a), "%s", root);
    bn = snprintf(b, sizeof(b), "%s", full);
    if (an <= 0 || bn <= 0 || an >= (int) sizeof(a) || bn >= (int) sizeof(b)) {
        return 0;
    }
    for (i = 0; a[i] != 0; i++) {
        if (a[i] == '/') {
            a[i] = '\\';
        }
        if (a[i] >= 'A' && a[i] <= 'Z') {
            a[i] = (char) (a[i] - 'A' + 'a');
        }
    }
    for (i = 0; b[i] != 0; i++) {
        if (b[i] == '/') {
            b[i] = '\\';
        }
        if (b[i] >= 'A' && b[i] <= 'Z') {
            b[i] = (char) (b[i] - 'A' + 'a');
        }
    }
    n = strlen(a);
    if (strncmp(b, a, n) != 0) {
        return 0;
    }
    rel = full + n;
    if (*rel == '\\' || *rel == '/') {
        rel++;
    }
    i = snprintf(out, (size_t) cap, "res://");
    if (i < 0 || i >= cap) {
        return 0;
    }
    for (; *rel != 0 && i < cap - 1; rel++) {
        out[i++] = *rel == '\\' ? '/' : *rel;
    }
    if (*rel != 0) {
        return 0;
    }
    out[i] = 0;
    return 1;
}

typedef struct ProjFind {
    const char *symbol;
    const char *skip;
    const char *root;
    int line;
    char res[768];
    int found;
} ProjFind;

static void consider_project_script(const char *full, ProjFind *find) {
    char *text;
    CompDecl decls[128];
    int decl_n = 0;
    int i;
    if (find->found || path_ieq(full, find->skip)) {
        return;
    }
    text = read_text_file(full);
    if (text == NULL) {
        return;
    }
    scan_decls(text, (int) strlen(text), decls, &decl_n, 128, 1);
    free(text);
    for (i = 0; i < decl_n; i++) {
        if (strcmp(decls[i].name, find->symbol) == 0 && decls[i].line > 0) {
            if (to_res_path(find->root, full, find->res, (int) sizeof(find->res))) {
                find->line = decls[i].line;
                find->found = 1;
            }
            return;
        }
    }
}

static void walk_project_scripts(const char *dir, ProjFind *find, int depth) {
    if (find->found || dir == NULL || depth > 12) {
        return;
    }
#if defined(_WIN32)
    {
        char pattern[1100];
        WIN32_FIND_DATAA data;
        HANDLE handle;
        if (snprintf(pattern, sizeof(pattern), "%s\\*", dir) >= (int) sizeof(pattern)) {
            return;
        }
        handle = FindFirstFileA(pattern, &data);
        if (handle == INVALID_HANDLE_VALUE) {
            return;
        }
        do {
            char child[1100];
            if (strcmp(data.cFileName, ".") == 0 || strcmp(data.cFileName, "..") == 0) {
                continue;
            }
            if (snprintf(child, sizeof(child), "%s\\%s", dir, data.cFileName) >= (int) sizeof(child)) {
                continue;
            }
            if ((data.dwFileAttributes & FILE_ATTRIBUTE_DIRECTORY) != 0) {
                if (strcmp(data.cFileName, ".godot") == 0 || strcmp(data.cFileName, ".git") == 0 || strcmp(data.cFileName, "bin") == 0) {
                    continue;
                }
                walk_project_scripts(child, find, depth + 1);
            } else if (name_is_rg(data.cFileName)) {
                consider_project_script(child, find);
            }
        } while (!find->found && FindNextFileA(handle, &data));
        FindClose(handle);
    }
#else
    {
        DIR *dp = opendir(dir);
        struct dirent *ent;
        if (dp == NULL) {
            return;
        }
        while (!find->found && (ent = readdir(dp)) != NULL) {
            char child[1100];
            if (strcmp(ent->d_name, ".") == 0 || strcmp(ent->d_name, "..") == 0) {
                continue;
            }
            if (snprintf(child, sizeof(child), "%s/%s", dir, ent->d_name) >= (int) sizeof(child)) {
                continue;
            }
            if (is_plain_file(child)) {
                if (name_is_rg(ent->d_name)) {
                    consider_project_script(child, find);
                }
            } else if (strcmp(ent->d_name, ".godot") != 0 && strcmp(ent->d_name, ".git") != 0 && strcmp(ent->d_name, "bin") != 0) {
                walk_project_scripts(child, find, depth + 1);
            }
        }
        closedir(dp);
    }
#endif
}

static GDExtensionObjectPtr load_project_script(const char *res_path) {
    unsigned char sn[16];
    GDExtensionObjectPtr loader;
    void *self;
    void *arg;
    void *ret;
    GDExtensionCallError err;
    GDExtensionObjectPtr out = NULL;
    if (res_path == NULL || res_path[0] == 0 || global_get_singleton == NULL) {
        return NULL;
    }
    sn_new(sn, "ResourceLoader");
    loader = global_get_singleton(sn);
    sn_free(sn);
    if (loader == NULL) {
        return NULL;
    }
    self = var_object(loader);
    arg = var_string(res_path);
    ret = vbuf();
    sn_new(sn, "load");
    memset(&err, 0, sizeof(err));
    variant_call(self, sn, (const GDExtensionConstVariantPtr *) &arg, 1, ret, &err);
    sn_free(sn);
    if (err.error == 0 && variant_get_type(ret) == GDEXTENSION_VARIANT_TYPE_OBJECT && to_object != NULL) {
        to_object(&out, ret);
    }
    variant_destroy(ret);
    vfree(ret);
    variant_destroy(arg);
    vfree(arg);
    variant_destroy(self);
    vfree(self);
    return out;
}

static int lookup_project_symbol(const char *open_path, const char *symbol, char *res_out, int res_cap, int *line_out) {
    char *global;
    char root[1024];
    ProjFind find;
    int n;
    if (open_path == NULL || open_path[0] == 0 || symbol == NULL) {
        return 0;
    }
    global = globalize(open_path);
    if (global == NULL || !project_root_of(global, root, (int) sizeof(root))) {
        free(global);
        return 0;
    }
    memset(&find, 0, sizeof(find));
    find.symbol = symbol;
    find.skip = global;
    find.root = root;
    walk_project_scripts(root, &find, 0);
    free(global);
    if (!find.found) {
        return 0;
    }
    n = snprintf(res_out, (size_t) res_cap, "%s", find.res);
    if (n <= 0 || n >= res_cap) {
        return 0;
    }
    *line_out = find.line;
    return 1;
}

static void ret_lookup_code(GDExtensionTypePtr r, const char *code, const char *symbol, const char *path) {
    CompDecl decls[128];
    int decl_n = 0;
    int i;
    const char *godot_class;
    char res[768];
    int line = 0;
    if (symbol == NULL || symbol[0] == 0) {
        ret_lookup_miss(r);
        return;
    }
    if (code != NULL) {
        scan_decls(code, (int) strlen(code), decls, &decl_n, 128, 1);
        for (i = 0; i < decl_n; i++) {
            if (strcmp(decls[i].name, symbol) == 0 && decls[i].line > 0) {
                ret_lookup_hit(r, 0, "", "", decls[i].line, NULL, NULL);
                return;
            }
        }
    }
    if (lookup_project_symbol(path, symbol, res, (int) sizeof(res), &line)) {
        GDExtensionObjectPtr scr = load_project_script(res);
        if (scr != NULL) {
            ret_lookup_hit(r, 0, "", "", line, res, scr);
            return;
        }
    }
    if (node2d_member(symbol)) {
        int property = strcmp(symbol, "position") == 0 || strcmp(symbol, "rotation") == 0 || strcmp(symbol, "scale") == 0;
        ret_lookup_hit(r, property ? 3 : 4, "Node2D", symbol, -1, NULL, NULL);
        return;
    }
    if (listed(NODE_METHODS, symbol)) {
        ret_lookup_hit(r, 4, "Node", symbol, -1, NULL, NULL);
        return;
    }
    godot_class = NULL;
    if (strcmp(symbol, "Vec2") == 0) {
        godot_class = "Vector2";
    } else if (strcmp(symbol, "Node") == 0 || strcmp(symbol, "Node2D") == 0 || strcmp(symbol, "Object") == 0) {
        godot_class = symbol;
    }
    if (godot_class != NULL) {
        ret_lookup_hit(r, 1, godot_class, "", -1, NULL, NULL);
        return;
    }
    ret_lookup_miss(r);
}

static int completion_blocked(const char *s, int n, int cursor) {
    int i = 0;
    int in_string = 0;
    int in_block = 0;
    int in_line = 0;
    while (i < cursor && i < n) {
        if (at_cursor_marker(s, i, n)) {
            break;
        }
        if (in_line) {
            if (s[i] == '\n') {
                in_line = 0;
            }
            i++;
            continue;
        }
        if (in_string) {
            if (s[i] == '\\' && i + 1 < n) {
                i += 2;
                continue;
            }
            if (s[i] == '"') {
                in_string = 0;
            }
            i++;
            continue;
        }
        if (in_block) {
            if (i + 1 < n && s[i] == '#' && s[i + 1] == '/') {
                in_block = 0;
                i += 2;
                continue;
            }
            i++;
            continue;
        }
        if ((s[i] == '/' && i + 1 < n && s[i + 1] == '/') || (s[i] == '#' && !(i + 1 < n && s[i + 1] == '/'))) {
            in_line = 1;
            i++;
            continue;
        }
        if (s[i] == '/' && i + 1 < n && s[i + 1] == '#') {
            in_block = 1;
            i += 2;
            continue;
        }
        if (s[i] == '"') {
            in_string = 1;
            i++;
            continue;
        }
        i++;
    }
    return in_string || in_block || in_line;
}

static int member_at_cursor(const char *s, int cursor, char *recv, int cap) {
    int i = cursor;
    int j;
    int start;
    int k = 0;
    if (recv != NULL && cap > 0) {
        recv[0] = 0;
    }
    while (i > 0 && hi_ident(s[i - 1])) {
        i--;
    }
    j = i;
    while (j > 0 && (s[j - 1] == ' ' || s[j - 1] == '\t')) {
        j--;
    }
    if (j <= 0 || s[j - 1] != '.') {
        return 0;
    }
    start = j - 1;
    while (start > 0 && hi_ident(s[start - 1])) {
        start--;
    }
    while (recv != NULL && start < j - 1 && k + 1 < cap) {
        recv[k++] = s[start++];
    }
    if (recv != NULL && cap > 0) {
        recv[k] = 0;
    }
    return 1;
}

static void call_name_at(const char *s, int cursor, char *out, int cap) {
    int depth = 0;
    int i;
    if (out != NULL && cap > 0) {
        out[0] = 0;
    }
    for (i = cursor - 1; i >= 0; i--) {
        if (s[i] == ')') {
            depth++;
        } else if (s[i] == '(') {
            int end;
            int start;
            int k = 0;
            if (depth > 0) {
                depth--;
                continue;
            }
            end = i;
            while (end > 0 && (s[end - 1] == ' ' || s[end - 1] == '\t')) {
                end--;
            }
            start = end;
            while (start > 0 && hi_ident(s[start - 1])) {
                start--;
            }
            while (out != NULL && start < end && k + 1 < cap) {
                out[k++] = s[start++];
            }
            if (out != NULL && cap > 0) {
                out[k] = 0;
            }
            return;
        }
    }
}

static const char *builtin_hint(const char *name) {
    if (strcmp(name, "get_node") == 0) {
        return "get_node(path: String) -> Node";
    }
    if (strcmp(name, "get_node_or_null") == 0) {
        return "get_node_or_null(path: String) -> Node";
    }
    if (strcmp(name, "get_name") == 0) {
        return "get_name() -> String";
    }
    if (strcmp(name, "set_name") == 0) {
        return "set_name(name: String)";
    }
    if (strcmp(name, "get_parent") == 0) {
        return "get_parent() -> Node";
    }
    if (strcmp(name, "get_child") == 0) {
        return "get_child(idx: Int) -> Node";
    }
    if (strcmp(name, "get_child_count") == 0) {
        return "get_child_count() -> Int";
    }
    if (strcmp(name, "add_child") == 0) {
        return "add_child(node: Node)";
    }
    if (strcmp(name, "remove_child") == 0) {
        return "remove_child(node: Node)";
    }
    if (strcmp(name, "queue_free") == 0) {
        return "queue_free()";
    }
    if (strcmp(name, "is_inside_tree") == 0) {
        return "is_inside_tree() -> Bool";
    }
    if (strcmp(name, "get_position") == 0) {
        return "get_position() -> Vec2";
    }
    if (strcmp(name, "set_position") == 0) {
        return "set_position(position: Vec2)";
    }
    if (strcmp(name, "get_rotation") == 0) {
        return "get_rotation() -> Float";
    }
    if (strcmp(name, "set_rotation") == 0) {
        return "set_rotation(rotation: Float)";
    }
    if (strcmp(name, "get_scale") == 0) {
        return "get_scale() -> Vec2";
    }
    if (strcmp(name, "set_scale") == 0) {
        return "set_scale(scale: Vec2)";
    }
    if (strcmp(name, "print") == 0) {
        return "print(...)";
    }
    return NULL;
}

static void add_option(void *options, const char *name, int kind) {
    void *storage;
    void *op;
    void *k;
    void *display;
    void *insert;
    void *loc;
    void *color;
    void *nil;
    float rgba[4];
    GDExtensionPtrDestructor dtor;
    if (options == NULL || name == NULL || name[0] == 0 || from_color == NULL) {
        return;
    }
    storage = empty_builtin(GDEXTENSION_VARIANT_TYPE_DICTIONARY);
    op = variant_of_builtin(GDEXTENSION_VARIANT_TYPE_DICTIONARY, storage);
    k = var_int(kind);
    display = var_string(name);
    insert = var_string(name);
    loc = var_int(0);
    color = vbuf();
    nil = vbuf();
    rgba[0] = 0.9f;
    rgba[1] = 0.9f;
    rgba[2] = 0.9f;
    rgba[3] = 1.0f;
    from_color(color, rgba);
    variant_nil(nil);
    dict_put(op, "kind", k);
    dict_put(op, "display", display);
    dict_put(op, "insert_text", insert);
    dict_put(op, "font_color", color);
    dict_put(op, "icon", nil);
    dict_put(op, "default_value", nil);
    dict_put(op, "location", loc);
    push_variant(options, op);
    variant_destroy(k);
    vfree(k);
    variant_destroy(display);
    vfree(display);
    variant_destroy(insert);
    vfree(insert);
    variant_destroy(loc);
    vfree(loc);
    variant_destroy(color);
    vfree(color);
    variant_destroy(nil);
    vfree(nil);
    dtor = get_ptr_dtor(GDEXTENSION_VARIANT_TYPE_DICTIONARY);
    if (dtor != NULL) {
        dtor(storage);
    }
    vfree(storage);
    variant_destroy(op);
    vfree(op);
}

static int already(const char names[][64], int n, const char *name) {
    int i;
    for (i = 0; i < n; i++) {
        if (strcmp(names[i], name) == 0) {
            return 1;
        }
    }
    return 0;
}

static void offer(void *options, char seen[][64], int *seen_n, int cap, const char *name, int kind) {
    if (name == NULL || name[0] == 0 || *seen_n >= cap || already(seen, *seen_n, name)) {
        return;
    }
    snprintf(seen[*seen_n], 64, "%s", name);
    (*seen_n)++;
    add_option(options, name, kind);
}

static void ret_completion(GDExtensionTypePtr r, const char *code) {
    void *storage = empty_builtin(GDEXTENSION_VARIANT_TYPE_DICTIONARY);
    void *var = variant_of_builtin(GDEXTENSION_VARIANT_TYPE_DICTIONARY, storage);
    void *opt_storage = empty_builtin(GDEXTENSION_VARIANT_TYPE_ARRAY);
    void *options = variant_of_builtin(GDEXTENSION_VARIANT_TYPE_ARRAY, opt_storage);
    void *result = var_int(0);
    void *force;
    void *hint_var;
    GDExtensionPtrDestructor dtor = get_ptr_dtor(GDEXTENSION_VARIANT_TYPE_DICTIONARY);
    GDExtensionPtrDestructor adtor = get_ptr_dtor(GDEXTENSION_VARIANT_TYPE_ARRAY);
    CompDecl decls[48];
    char seen[96][64];
    char recv[64];
    char called[64];
    const char *hint = "";
    int decl_n = 0;
    int seen_n = 0;
    int n = code != NULL ? (int) strlen(code) : 0;
    int cursor = cursor_index(code != NULL ? code : "", n);
    int member;
    int i;
    member = code != NULL && member_at_cursor(code, cursor, recv, (int) sizeof(recv));
    force = var_bool(member);
    if (code != NULL && !completion_blocked(code, n, cursor)) {
        scan_decls(code, n, decls, &decl_n, 48, 1);
        call_name_at(code, cursor, called, (int) sizeof(called));
        if (called[0] != 0) {
            hint = builtin_hint(called);
            if (hint == NULL) {
                hint = "";
                for (i = 0; i < decl_n; i++) {
                    if (decls[i].kind == CC_FUNCTION && strcmp(decls[i].name, called) == 0 && decls[i].hint[0] != 0) {
                        hint = decls[i].hint;
                        break;
                    }
                }
            }
        }
        if (member && (strcmp(recv, "position") == 0 || strcmp(recv, "scale") == 0)) {
            offer(options, seen, &seen_n, 96, "x", CC_MEMBER);
            offer(options, seen, &seen_n, 96, "y", CC_MEMBER);
        } else if (member) {
            for (i = 0; NODE_FIELDS[i] != NULL; i++) {
                offer(options, seen, &seen_n, 96, NODE_FIELDS[i], CC_MEMBER);
            }
            for (i = 0; NODE_METHODS[i] != NULL; i++) {
                offer(options, seen, &seen_n, 96, NODE_METHODS[i], CC_FUNCTION);
            }
            for (i = 0; i < decl_n; i++) {
                offer(options, seen, &seen_n, 96, decls[i].name, decls[i].kind);
            }
        } else {
            for (i = 0; HI_WORDS[i].name != NULL; i++) {
                offer(options, seen, &seen_n, 96, HI_WORDS[i].name, CC_KEYWORD);
            }
            for (i = 0; RG_ANNOTATIONS[i] != NULL; i++) {
                offer(options, seen, &seen_n, 96, RG_ANNOTATIONS[i], CC_KEYWORD);
            }
            for (i = 0; RG_TYPES[i] != NULL; i++) {
                offer(options, seen, &seen_n, 96, RG_TYPES[i], CC_CLASS);
            }
            offer(options, seen, &seen_n, 96, "print", CC_FUNCTION);
            for (i = 0; NODE_FIELDS[i] != NULL; i++) {
                offer(options, seen, &seen_n, 96, NODE_FIELDS[i], CC_MEMBER);
            }
            for (i = 0; NODE_METHODS[i] != NULL; i++) {
                offer(options, seen, &seen_n, 96, NODE_METHODS[i], CC_FUNCTION);
            }
            for (i = 0; i < decl_n; i++) {
                offer(options, seen, &seen_n, 96, decls[i].name, decls[i].kind);
            }
        }
    }
    hint_var = var_string(hint != NULL ? hint : "");
    dict_put(var, "result", result);
    dict_put(var, "force", force);
    dict_put(var, "call_hint", hint_var);
    dict_put(var, "options", options);
    to_dict(r, var);
    variant_destroy(result);
    vfree(result);
    variant_destroy(force);
    vfree(force);
    variant_destroy(hint_var);
    vfree(hint_var);
    if (adtor != NULL) {
        adtor(opt_storage);
    }
    vfree(opt_storage);
    variant_destroy(options);
    vfree(options);
    if (dtor != NULL) {
        dtor(storage);
    }
    vfree(storage);
    variant_destroy(var);
    vfree(var);
}

typedef struct ValDiag {
    int line;
    int col;
    int warning;
    char *message;
    char *file;
} ValDiag;

typedef struct ValCache {
    char *path;
    char *global;
    unsigned hash;
    int valid;
    int used;
    ValDiag *diags;
    int n;
} ValCache;

static ValCache g_val_cache[8];
static ValCache g_val_incoming;
static char *g_val_pending_src;
static char *g_val_pending_path;
static char *g_val_pending_global;
static unsigned g_val_pending_hash;
static int g_val_pending;
static volatile int g_val_stop;
static int g_val_nudge;
static int g_val_ready;
#if defined(_WIN32)
static CRITICAL_SECTION g_val_lock;
static HANDLE g_val_thread;
static HANDLE g_val_proc;
#endif

static unsigned val_hash(const char *s) {
    unsigned h = 2166136261u;
    if (s == NULL) {
        return h;
    }
    while (*s != 0) {
        h ^= (unsigned char) *s++;
        h *= 16777619u;
    }
    return h;
}

static int path_eq(const char *a, const char *b) {
    if (a == NULL || b == NULL) {
        return 0;
    }
    while (*a != 0 && *b != 0) {
        char ca = *a == '/' ? '\\' : *a;
        char cb = *b == '/' ? '\\' : *b;
        if (ca >= 'A' && ca <= 'Z') {
            ca = (char) (ca - 'A' + 'a');
        }
        if (cb >= 'A' && cb <= 'Z') {
            cb = (char) (cb - 'A' + 'a');
        }
        if (ca != cb) {
            return 0;
        }
        a++;
        b++;
    }
    return *a == 0 && *b == 0;
}

static void val_free_diags(ValDiag *diags, int n) {
    int i;
    if (diags == NULL) {
        return;
    }
    for (i = 0; i < n; i++) {
        free(diags[i].message);
        free(diags[i].file);
    }
    free(diags);
}

static void val_clear_slot(ValCache *slot) {
    if (slot == NULL) {
        return;
    }
    free(slot->path);
    free(slot->global);
    val_free_diags(slot->diags, slot->n);
    memset(slot, 0, sizeof(*slot));
}

static const char *val_skip(const char *p) {
    while (*p == ' ' || *p == '\t' || *p == '\n' || *p == '\r') {
        p++;
    }
    return p;
}

static int val_parse_string(const char **pp, char *out, int cap) {
    const char *p = *pp;
    int n = 0;
    if (*p != '"') {
        return 0;
    }
    p++;
    while (*p != 0 && *p != '"') {
        char c = *p++;
        if (c == '\\' && *p != 0) {
            char e = *p++;
            if (e == 'n') {
                c = '\n';
            } else if (e == 'r') {
                c = '\r';
            } else if (e == 't') {
                c = '\t';
            } else if (e == 'u') {
                int h = 0;
                int i;
                for (i = 0; i < 4 && *p != 0; i++) {
                    char x = *p++;
                    h <<= 4;
                    if (x >= '0' && x <= '9') {
                        h += x - '0';
                    } else if (x >= 'a' && x <= 'f') {
                        h += x - 'a' + 10;
                    } else if (x >= 'A' && x <= 'F') {
                        h += x - 'A' + 10;
                    }
                }
                c = h >= 0 && h < 128 ? (char) h : '?';
            } else {
                c = e;
            }
        }
        if (n + 1 < cap) {
            out[n++] = c;
        }
    }
    if (*p == '"') {
        p++;
    }
    out[n] = 0;
    *pp = p;
    return 1;
}

static int val_parse_json(const char *json, ValDiag **out, int *nout) {
    const char *p;
    ValDiag *diags = NULL;
    int n = 0;
    int cap = 0;
    *out = NULL;
    *nout = 0;
    if (json == NULL) {
        return 0;
    }
    p = val_skip(json);
    if (*p != '[') {
        return 0;
    }
    p++;
    while (*p != 0) {
        char file[1024];
        char severity[32];
        char message[1024];
        int line = 1;
        int col = 1;
        int warning = 0;
        ValDiag *grown;
        p = val_skip(p);
        if (*p == ']') {
            *out = diags;
            *nout = n;
            return 1;
        }
        if (*p == ',') {
            p++;
            continue;
        }
        if (*p != '{') {
            val_free_diags(diags, n);
            return 0;
        }
        p++;
        file[0] = 0;
        severity[0] = 0;
        message[0] = 0;
        while (*p != 0 && *p != '}') {
            char key[32];
            p = val_skip(p);
            if (*p == ',') {
                p++;
                continue;
            }
            if (*p == '}') {
                break;
            }
            if (!val_parse_string(&p, key, (int) sizeof(key))) {
                val_free_diags(diags, n);
                return 0;
            }
            p = val_skip(p);
            if (*p == ':') {
                p++;
            }
            p = val_skip(p);
            if (*p == '"') {
                if (strcmp(key, "file") == 0) {
                    val_parse_string(&p, file, (int) sizeof(file));
                } else if (strcmp(key, "severity") == 0) {
                    val_parse_string(&p, severity, (int) sizeof(severity));
                } else if (strcmp(key, "message") == 0) {
                    val_parse_string(&p, message, (int) sizeof(message));
                } else {
                    val_parse_string(&p, message, (int) sizeof(message));
                    message[0] = 0;
                }
            } else {
                char *end = NULL;
                int num = (int) strtol(p, &end, 10);
                if (end != NULL && end != p) {
                    p = end;
                } else if (*p != 0) {
                    p++;
                }
                if (strcmp(key, "line") == 0) {
                    line = num > 0 ? num : 1;
                } else if (strcmp(key, "col") == 0) {
                    col = num > 0 ? num : 1;
                }
            }
        }
        if (*p == '}') {
            p++;
        }
        if (n >= 48) {
            continue;
        }
        warning = severity[0] == 'w' || severity[0] == 'W';
        if (n == cap) {
            cap = cap == 0 ? 8 : cap * 2;
            grown = (ValDiag *) realloc(diags, (size_t) cap * sizeof(ValDiag));
            if (grown == NULL) {
                val_free_diags(diags, n);
                return 0;
            }
            diags = grown;
        }
        memset(&diags[n], 0, sizeof(diags[n]));
        diags[n].line = line;
        diags[n].col = col;
        diags[n].warning = warning;
        diags[n].message = strdup(message);
        diags[n].file = strdup(file);
        n++;
    }
    val_free_diags(diags, n);
    return 0;
}

static int val_find_cp(const char *rg_path, char *found, size_t cap) {
    const char *env = getenv("ROSEGOLD_CP");
    char dir[1024];
    if (env != NULL && env[0] != 0) {
        snprintf(found, cap, "%s", env);
        return 1;
    }
    found[0] = 0;
    if (rg_path == NULL) {
        return 0;
    }
    snprintf(dir, sizeof(dir), "%s", rg_path);
    while (dir[0] != 0) {
        char classes[1100];
        char resources[1100];
        char *slash;
#if defined(_WIN32)
        snprintf(classes, sizeof(classes), "%s\\build\\classes\\java\\main", dir);
        snprintf(resources, sizeof(resources), "%s\\build\\resources\\main", dir);
        if (GetFileAttributesA(classes) != INVALID_FILE_ATTRIBUTES) {
            snprintf(found, cap, "%s;%s", classes, resources);
            return 1;
        }
#else
        snprintf(classes, sizeof(classes), "%s/build/classes/java/main", dir);
        snprintf(resources, sizeof(resources), "%s/build/resources/main", dir);
        if (access(classes, 0) == 0) {
            snprintf(found, cap, "%s:%s", classes, resources);
            return 1;
        }
#endif
        slash = strrchr(dir, '\\');
        if (slash == NULL) {
            slash = strrchr(dir, '/');
        }
        if (slash == NULL) {
            break;
        }
        *slash = 0;
    }
    return 0;
}

#if defined(_WIN32)
static int val_run_java(const char *global, const char *source, ValDiag **diags, int *n, int *valid) {
    char cp[2048];
    char cmd[4096];
    const char *java = getenv("ROSEGOLD_JAVA");
    SECURITY_ATTRIBUTES sa;
    HANDLE in_r = NULL;
    HANDLE in_w = NULL;
    HANDLE out_r = NULL;
    HANDLE out_w = NULL;
    STARTUPINFOA si;
    PROCESS_INFORMATION pi;
    DWORD code = 1;
    char *buf = NULL;
    size_t len = 0;
    size_t cap = 0;
    int errors = 0;
    int i;
    *diags = NULL;
    *n = 0;
    *valid = 1;
    if (java == NULL || java[0] == 0) {
        java = "java";
    }
    if (!val_find_cp(global, cp, sizeof(cp))) {
        return 0;
    }
    snprintf(cmd, sizeof(cmd), "\"%s\" -cp \"%s\" com.rosegoldc.lang.Main check --json --stdin \"%s\"", java, cp, global != NULL ? global : "");
    memset(&sa, 0, sizeof(sa));
    sa.nLength = sizeof(sa);
    sa.bInheritHandle = 1;
    if (!CreatePipe(&in_r, &in_w, &sa, 0) || !CreatePipe(&out_r, &out_w, &sa, 0)) {
        return 0;
    }
    SetHandleInformation(in_w, HANDLE_FLAG_INHERIT, 0);
    SetHandleInformation(out_r, HANDLE_FLAG_INHERIT, 0);
    memset(&si, 0, sizeof(si));
    si.cb = sizeof(si);
    si.dwFlags = STARTF_USESTDHANDLES;
    si.hStdInput = in_r;
    si.hStdOutput = out_w;
    si.hStdError = GetStdHandle(STD_ERROR_HANDLE);
    memset(&pi, 0, sizeof(pi));
    if (!CreateProcessA(NULL, cmd, NULL, NULL, 1, CREATE_NO_WINDOW, NULL, NULL, &si, &pi)) {
        CloseHandle(in_r);
        CloseHandle(in_w);
        CloseHandle(out_r);
        CloseHandle(out_w);
        return 0;
    }
    CloseHandle(in_r);
    CloseHandle(out_w);
    CloseHandle(pi.hThread);
    EnterCriticalSection(&g_val_lock);
    g_val_proc = pi.hProcess;
    LeaveCriticalSection(&g_val_lock);
    if (source != NULL) {
        const char *left = source;
        size_t remain = strlen(source);
        while (remain > 0) {
            DWORD wrote = 0;
            DWORD chunk = remain > 1 << 20 ? 1 << 20 : (DWORD) remain;
            if (!WriteFile(in_w, left, chunk, &wrote, NULL) || wrote == 0) {
                break;
            }
            left += wrote;
            remain -= wrote;
        }
    }
    CloseHandle(in_w);
    for (;;) {
        char tmp[4096];
        DWORD got = 0;
        if (!ReadFile(out_r, tmp, sizeof(tmp), &got, NULL) || got == 0) {
            break;
        }
        if (len + got + 1 > cap) {
            size_t next = cap == 0 ? 8192 : cap * 2;
            char *grown;
            while (next < len + got + 1) {
                next *= 2;
            }
            grown = (char *) realloc(buf, next);
            if (grown == NULL) {
                break;
            }
            buf = grown;
            cap = next;
        }
        memcpy(buf + len, tmp, got);
        len += got;
        buf[len] = 0;
    }
    CloseHandle(out_r);
    WaitForSingleObject(pi.hProcess, 120000);
    GetExitCodeProcess(pi.hProcess, &code);
    EnterCriticalSection(&g_val_lock);
    if (g_val_proc == pi.hProcess) {
        g_val_proc = NULL;
    }
    LeaveCriticalSection(&g_val_lock);
    CloseHandle(pi.hProcess);
    if (buf == NULL || !val_parse_json(buf, diags, n)) {
        free(buf);
        return 0;
    }
    free(buf);
    for (i = 0; i < *n; i++) {
        if (!(*diags)[i].warning) {
            errors++;
        }
    }
    *valid = errors == 0;
    return 1;
}

static DWORD WINAPI val_thread(LPVOID unused) {
    (void) unused;
    for (;;) {
        char *src = NULL;
        char *path = NULL;
        char *global = NULL;
        unsigned hash = 0;
        ValDiag *diags = NULL;
        int n = 0;
        int valid = 1;
        int ok;
        EnterCriticalSection(&g_val_lock);
        if (g_val_stop && !g_val_pending) {
            LeaveCriticalSection(&g_val_lock);
            break;
        }
        if (!g_val_pending) {
            LeaveCriticalSection(&g_val_lock);
            Sleep(30);
            continue;
        }
        src = g_val_pending_src;
        path = g_val_pending_path;
        global = g_val_pending_global;
        hash = g_val_pending_hash;
        g_val_pending_src = NULL;
        g_val_pending_path = NULL;
        g_val_pending_global = NULL;
        g_val_pending = 0;
        LeaveCriticalSection(&g_val_lock);
        ok = val_run_java(global, src, &diags, &n, &valid);
        if (!ok && !g_val_stop) {
            diags = (ValDiag *) calloc(1, sizeof(ValDiag));
            if (diags != NULL) {
                diags[0].line = 1;
                diags[0].col = 1;
                diags[0].message = strdup("RoseGold check failed");
                diags[0].file = strdup(global != NULL ? global : "");
                n = 1;
                valid = 0;
                ok = 1;
            }
        }
        EnterCriticalSection(&g_val_lock);
        if (g_val_stop || g_val_pending) {
            val_free_diags(diags, n);
            free(src);
            free(path);
            free(global);
            LeaveCriticalSection(&g_val_lock);
            continue;
        }
        val_clear_slot(&g_val_incoming);
        if (ok) {
            g_val_incoming.path = path;
            g_val_incoming.global = global;
            g_val_incoming.hash = hash;
            g_val_incoming.valid = valid;
            g_val_incoming.diags = diags;
            g_val_incoming.n = n;
            g_val_incoming.used = 1;
            path = NULL;
            global = NULL;
        }
        free(src);
        free(path);
        free(global);
        g_val_nudge = g_val_incoming.used ? 180 : g_val_nudge;
        LeaveCriticalSection(&g_val_lock);
    }
    return 0;
}

static void val_ensure_thread(void) {
    if (g_val_thread != NULL) {
        return;
    }
    g_val_thread = CreateThread(NULL, 0, val_thread, NULL, 0, NULL);
}
#endif

static void val_init(void) {
    if (g_val_ready) {
        return;
    }
#if defined(_WIN32)
    InitializeCriticalSection(&g_val_lock);
#endif
    g_val_ready = 1;
}

static void val_shutdown(void) {
#if defined(_WIN32)
    HANDLE thread;
    HANDLE proc;
#endif
    int i;
    if (!g_val_ready) {
        return;
    }
#if defined(_WIN32)
    EnterCriticalSection(&g_val_lock);
    g_val_stop = 1;
    proc = g_val_proc;
    thread = g_val_thread;
    g_val_thread = NULL;
    LeaveCriticalSection(&g_val_lock);
    if (proc != NULL) {
        TerminateProcess(proc, 1);
    }
    if (thread != NULL) {
        WaitForSingleObject(thread, 5000);
        CloseHandle(thread);
    }
    EnterCriticalSection(&g_val_lock);
    free(g_val_pending_src);
    free(g_val_pending_path);
    free(g_val_pending_global);
    g_val_pending_src = NULL;
    g_val_pending_path = NULL;
    g_val_pending_global = NULL;
    g_val_pending = 0;
    val_clear_slot(&g_val_incoming);
    LeaveCriticalSection(&g_val_lock);
    DeleteCriticalSection(&g_val_lock);
#endif
    for (i = 0; i < 8; i++) {
        val_clear_slot(&g_val_cache[i]);
    }
    g_val_ready = 0;
}

static void val_queue(const char *path, const char *global, const char *source, unsigned hash) {
#if defined(_WIN32)
    val_init();
    EnterCriticalSection(&g_val_lock);
    free(g_val_pending_src);
    free(g_val_pending_path);
    free(g_val_pending_global);
    g_val_pending_src = strdup(source != NULL ? source : "");
    g_val_pending_path = strdup(path != NULL ? path : "");
    g_val_pending_global = strdup(global != NULL ? global : "");
    g_val_pending_hash = hash;
    g_val_pending = 1;
    LeaveCriticalSection(&g_val_lock);
    val_ensure_thread();
#else
    (void) path;
    (void) global;
    (void) source;
    (void) hash;
#endif
}

static ValCache *val_lookup(const char *path) {
    int i;
    for (i = 0; i < 8; i++) {
        if (g_val_cache[i].used && path_eq(g_val_cache[i].path, path)) {
            return &g_val_cache[i];
        }
    }
    return NULL;
}

static void val_store(ValCache *src) {
    int i;
    ValCache *slot = NULL;
    for (i = 0; i < 8; i++) {
        if (g_val_cache[i].used && path_eq(g_val_cache[i].path, src->path)) {
            slot = &g_val_cache[i];
            break;
        }
    }
    if (slot == NULL) {
        for (i = 0; i < 8; i++) {
            if (!g_val_cache[i].used) {
                slot = &g_val_cache[i];
                break;
            }
        }
    }
    if (slot == NULL) {
        slot = &g_val_cache[0];
    }
    if (slot != src) {
        val_clear_slot(slot);
        *slot = *src;
        memset(src, 0, sizeof(*src));
    }
}

/* Godot indexes column-1 inside the line and aborts when that line is empty. */
static void clamp_diag_pos(const char *source, int *line, int *col) {
    const char *s = source != NULL ? source : "";
    int nlines = 1;
    int target;
    int len;
    int at;
    int i;
    for (i = 0; s[i] != 0; i++) {
        if (s[i] == '\n') {
            nlines++;
        }
    }
    target = *line > 0 ? *line : 1;
    if (target > nlines) {
        target = nlines;
    }
    for (;;) {
        len = 0;
        at = 1;
        i = 0;
        while (s[i] != 0 && at < target) {
            if (s[i] == '\n') {
                at++;
            }
            i++;
        }
        while (s[i] != 0 && s[i] != '\n') {
            if (s[i] != '\r') {
                len++;
            }
            i++;
        }
        if (len > 0 || target <= 1) {
            break;
        }
        target--;
    }
    if (len < 1) {
        *line = 1;
        *col = 1;
        return;
    }
    if (*col < 1) {
        *col = 1;
    }
    if (*col > len) {
        *col = len;
    }
    *line = target;
}

static void *val_error_dict(const ValDiag *d, const char *show_path, const char *source) {
    int line_no = d->line;
    int col_no = d->col;
    void *storage = empty_builtin(GDEXTENSION_VARIANT_TYPE_DICTIONARY);
    void *var = variant_of_builtin(GDEXTENSION_VARIANT_TYPE_DICTIONARY, storage);
    void *line;
    void *col;
    clamp_diag_pos(source, &line_no, &col_no);
    line = var_int(line_no);
    col = var_int(col_no);
    void *message = var_string(d->message != NULL ? d->message : "");
    void *path = var_string(show_path != NULL ? show_path : "");
    GDExtensionPtrDestructor dtor = get_ptr_dtor(GDEXTENSION_VARIANT_TYPE_DICTIONARY);
    dict_put(var, "line", line);
    dict_put(var, "column", col);
    dict_put(var, "message", message);
    dict_put(var, "path", path);
    variant_destroy(line);
    vfree(line);
    variant_destroy(col);
    vfree(col);
    variant_destroy(message);
    vfree(message);
    variant_destroy(path);
    vfree(path);
    if (dtor != NULL) {
        dtor(storage);
    }
    vfree(storage);
    return var;
}

static void *val_warn_dict(const ValDiag *d, const char *source) {
    int line_no = d->line;
    int col_no = 1;
    void *storage = empty_builtin(GDEXTENSION_VARIANT_TYPE_DICTIONARY);
    void *var = variant_of_builtin(GDEXTENSION_VARIANT_TYPE_DICTIONARY, storage);
    void *line;
    clamp_diag_pos(source, &line_no, &col_no);
    line = var_int(line_no);
    void *code = var_int(0);
    void *scode = var_string("WARNING");
    void *message = var_string(d->message != NULL ? d->message : "");
    GDExtensionPtrDestructor dtor = get_ptr_dtor(GDEXTENSION_VARIANT_TYPE_DICTIONARY);
    dict_put(var, "start_line", line);
    dict_put(var, "end_line", line);
    dict_put(var, "code", code);
    dict_put(var, "string_code", scode);
    dict_put(var, "message", message);
    variant_destroy(line);
    vfree(line);
    variant_destroy(code);
    vfree(code);
    variant_destroy(scode);
    vfree(scode);
    variant_destroy(message);
    vfree(message);
    if (dtor != NULL) {
        dtor(storage);
    }
    vfree(storage);
    return var;
}

static void ret_validation(GDExtensionTypePtr r, const ValCache *slot, const char *source) {
    void *storage = empty_builtin(GDEXTENSION_VARIANT_TYPE_DICTIONARY);
    void *var = variant_of_builtin(GDEXTENSION_VARIANT_TYPE_DICTIONARY, storage);
    void *yes = var_bool(slot != NULL && slot->valid);
    void *errors_s = empty_builtin(GDEXTENSION_VARIANT_TYPE_ARRAY);
    void *errors = variant_of_builtin(GDEXTENSION_VARIANT_TYPE_ARRAY, errors_s);
    void *warn_s = empty_builtin(GDEXTENSION_VARIANT_TYPE_ARRAY);
    void *warns = variant_of_builtin(GDEXTENSION_VARIANT_TYPE_ARRAY, warn_s);
    GDExtensionPtrDestructor dtor = get_ptr_dtor(GDEXTENSION_VARIANT_TYPE_DICTIONARY);
    GDExtensionPtrDestructor adtor = get_ptr_dtor(GDEXTENSION_VARIANT_TYPE_ARRAY);
    int i;
    if (slot != NULL) {
        for (i = 0; i < slot->n; i++) {
            const char *show = slot->path;
            void *item;
            if (slot->diags[i].file != NULL && slot->diags[i].file[0] != 0
                    && !path_eq(slot->diags[i].file, slot->global)
                    && !path_eq(slot->diags[i].file, slot->path)) {
                continue;
            }
            if (slot->diags[i].warning) {
                item = val_warn_dict(&slot->diags[i], source);
                push_variant(warns, item);
            } else {
                item = val_error_dict(&slot->diags[i], show, source);
                push_variant(errors, item);
            }
            variant_destroy(item);
            vfree(item);
        }
    }
    dict_put(var, "valid", yes);
    dict_put(var, "errors", errors);
    dict_put(var, "warnings", warns);
    to_dict(r, var);
    variant_destroy(yes);
    vfree(yes);
    variant_destroy(errors);
    vfree(errors);
    variant_destroy(warns);
    vfree(warns);
    if (adtor != NULL) {
        adtor(errors_s);
        adtor(warn_s);
    }
    vfree(errors_s);
    vfree(warn_s);
    if (dtor != NULL) {
        dtor(storage);
    }
    vfree(storage);
    variant_destroy(var);
    vfree(var);
}

static GDExtensionObjectPtr val_call_obj(GDExtensionObjectPtr obj, const char *method) {
    void *self;
    void *ret;
    unsigned char sn[16];
    GDExtensionCallError err;
    GDExtensionObjectPtr out = NULL;
    if (obj == NULL) {
        return NULL;
    }
    self = var_object(obj);
    ret = vbuf();
    sn_new(sn, method);
    memset(&err, 0, sizeof(err));
    variant_call(self, sn, NULL, 0, ret, &err);
    sn_free(sn);
    if (err.error == 0 && variant_get_type(ret) == GDEXTENSION_VARIANT_TYPE_OBJECT && to_object != NULL) {
        to_object(&out, ret);
    }
    variant_destroy(ret);
    vfree(ret);
    variant_destroy(self);
    vfree(self);
    return out;
}

static int nudge_current_editor(void) {
    GDExtensionObjectPtr ei;
    GDExtensionObjectPtr script_editor;
    GDExtensionObjectPtr current;
    GDExtensionObjectPtr base;
    GDExtensionObjectPtr parent;
    unsigned char sn[16];
    void *self;
    void *ret;
    GDExtensionCallError err;
    int count = 0;
    int i;
    int started = 0;
    sn_new(sn, "EditorInterface");
    ei = global_get_singleton(sn);
    sn_free(sn);
    script_editor = val_call_obj(ei, "get_script_editor");
    current = val_call_obj(script_editor, "get_current_editor");
    base = val_call_obj(current, "get_base_editor");
    parent = val_call_obj(base, "get_parent");
    if (parent == NULL) {
        return 0;
    }
    self = var_object(parent);
    ret = vbuf();
    sn_new(sn, "get_child_count");
    memset(&err, 0, sizeof(err));
    variant_call(self, sn, NULL, 0, ret, &err);
    sn_free(sn);
    if (err.error == 0) {
        int64_t n = 0;
        to_int(&n, ret);
        count = (int) n;
    }
    variant_destroy(ret);
    vfree(ret);
    for (i = 0; i < count && !started; i++) {
        void *idx = var_int(i);
        void *child_var = vbuf();
        const void *args[1];
        GDExtensionObjectPtr child = NULL;
        args[0] = idx;
        sn_new(sn, "get_child");
        memset(&err, 0, sizeof(err));
        variant_call(self, sn, (const GDExtensionConstVariantPtr *) args, 1, child_var, &err);
        sn_free(sn);
        if (err.error == 0 && variant_get_type(child_var) == GDEXTENSION_VARIANT_TYPE_OBJECT) {
            to_object(&child, child_var);
        }
        variant_destroy(child_var);
        vfree(child_var);
        variant_destroy(idx);
        vfree(idx);
        if (child == NULL) {
            continue;
        }
        {
            void *cself = var_object(child);
            void *cret = vbuf();
            void *name = var_string("Timer");
            uint8_t yes = 0;
            const void *cargs[1];
            cargs[0] = name;
            sn_new(sn, "is_class");
            memset(&err, 0, sizeof(err));
            variant_call(cself, sn, (const GDExtensionConstVariantPtr *) cargs, 1, cret, &err);
            sn_free(sn);
            if (err.error == 0) {
                to_bool(&yes, cret);
            }
            variant_destroy(cret);
            vfree(cret);
            variant_destroy(name);
            vfree(name);
            if (yes) {
                void *sret = vbuf();
                void *delay = var_float(0.05);
                const void *sargs[1];
                sargs[0] = delay;
                sn_new(sn, "start");
                memset(&err, 0, sizeof(err));
                variant_call(cself, sn, (const GDExtensionConstVariantPtr *) sargs, 1, sret, &err);
                sn_free(sn);
                variant_destroy(sret);
                vfree(sret);
                variant_destroy(delay);
                vfree(delay);
                started = err.error == 0;
            }
            variant_destroy(cself);
            vfree(cself);
        }
    }
    variant_destroy(self);
    vfree(self);
    return started;
}

static int editor_hint(void);
static char *globalize(const char *path);

static void val_publish(void) {
#if defined(_WIN32)
    int nudge = 0;
    if (!g_val_ready) {
        return;
    }
    EnterCriticalSection(&g_val_lock);
    if (g_val_incoming.used) {
        val_store(&g_val_incoming);
        nudge = 1;
    }
    if (g_val_nudge > 0) {
        nudge = 1;
    }
    LeaveCriticalSection(&g_val_lock);
    if (!nudge) {
        return;
    }
    if (!editor_hint()) {
        g_val_nudge = 0;
        return;
    }
    if (nudge_current_editor()) {
        EnterCriticalSection(&g_val_lock);
        g_val_nudge = 0;
        LeaveCriticalSection(&g_val_lock);
        return;
    }
    EnterCriticalSection(&g_val_lock);
    if (g_val_nudge > 0) {
        g_val_nudge--;
    }
    LeaveCriticalSection(&g_val_lock);
#endif
}

static void validate_script(const GDExtensionConstTypePtr *args, GDExtensionTypePtr ret) {
    char *source;
    char *path;
    char *global;
    unsigned hash;
    ValCache *slot;
    if (!editor_hint()) {
        ret_valid_dict(ret);
        return;
    }
    source = args != NULL && args[0] != NULL ? string_dup(args[0]) : strdup("");
    path = args != NULL && args[1] != NULL ? string_dup(args[1]) : strdup("");
    if (source == NULL) {
        source = strdup("");
    }
    if (path == NULL) {
        path = strdup("");
    }
    global = globalize(path);
    if (global == NULL) {
        global = strdup(path);
    }
    hash = val_hash(source);
    slot = val_lookup(path);
    if (slot != NULL && slot->hash == hash) {
        ret_validation(ret, slot, source);
        free(source);
        free(path);
        free(global);
        return;
    }
    val_queue(path, global, source, hash);
    if (slot != NULL) {
        ret_validation(ret, slot, source);
    } else {
        ret_valid_dict(ret);
    }
    free(source);
    free(path);
    free(global);
}

static void virt_lang(GDExtensionClassInstancePtr inst, const GDExtensionConstTypePtr *args, GDExtensionTypePtr ret, const char *name) {
    (void) inst;
    (void) args;
    if (strcmp(name, "_get_name") == 0 || strcmp(name, "_get_type") == 0) {
        ret_string(ret, "RoseGold");
    } else if (strcmp(name, "_get_extension") == 0) {
        ret_string(ret, "rg");
    } else if (strcmp(name, "_get_recognized_extensions") == 0) {
        ret_one_extension(ret);
    } else if (strcmp(name, "_frame") == 0) {
        ensure_highlighter();
        val_publish();
    } else if (strcmp(name, "_init") == 0 || strcmp(name, "_finish") == 0
            || strcmp(name, "_thread_enter") == 0 || strcmp(name, "_thread_exit") == 0
            || strcmp(name, "_profiling_start") == 0 || strcmp(name, "_profiling_stop") == 0
            || strcmp(name, "_reload_all_scripts") == 0) {
        (void) ret;
    } else if (strcmp(name, "_is_using_templates") == 0 || strcmp(name, "_has_named_classes") == 0
            || strcmp(name, "_supports_builtin_mode") == 0 || strcmp(name, "_supports_documentation") == 0
            || strcmp(name, "_can_inherit_from_file") == 0 || strcmp(name, "_can_make_function") == 0
            || strcmp(name, "_overrides_external_editor") == 0) {
        ret_bool(ret, 0);
    } else if (strcmp(name, "_validate") == 0) {
        validate_script(args, ret);
    } else if (strcmp(name, "_get_reserved_words") == 0) {
        ret_words(ret, 0);
    } else if (strcmp(name, "_get_comment_delimiters") == 0) {
        const char *items[] = {"//", "#", "/# #/"};
        ret_literals(ret, items, 3);
    } else if (strcmp(name, "_get_string_delimiters") == 0) {
        const char *items[] = {"\" \""};
        ret_literals(ret, items, 1);
    } else if (strcmp(name, "_get_doc_comment_delimiters") == 0) {
        ret_empty_packed(ret);
    } else if (strcmp(name, "_get_public_functions") == 0 || strcmp(name, "_get_public_annotations") == 0
            || strcmp(name, "_debug_get_current_stack_info") == 0 || strcmp(name, "_get_built_in_templates") == 0) {
        ret_empty_array(ret);
    } else if (strcmp(name, "_lookup_code") == 0) {
        char *code = args != NULL && args[0] != NULL ? string_dup(args[0]) : strdup("");
        char *symbol = args != NULL && args[1] != NULL ? string_dup(args[1]) : strdup("");
        char *path = args != NULL && args[2] != NULL ? string_dup(args[2]) : strdup("");
        ret_lookup_code(ret, code != NULL ? code : "", symbol != NULL ? symbol : "", path != NULL ? path : "");
        free(code);
        free(symbol);
        free(path);
    } else if (strcmp(name, "_complete_code") == 0) {
        char *code = args != NULL && args[0] != NULL ? string_dup(args[0]) : strdup("");
        ret_completion(ret, code != NULL ? code : "");
        free(code);
    } else if (strcmp(name, "_get_public_constants") == 0 || strcmp(name, "_debug_get_stack_level_locals") == 0
            || strcmp(name, "_debug_get_stack_level_members") == 0 || strcmp(name, "_debug_get_globals") == 0
            || strcmp(name, "_get_global_class_name") == 0) {
        ret_empty_dict(ret);
    } else if (strcmp(name, "_find_function") == 0 || strcmp(name, "_debug_get_stack_level_count") == 0
            || strcmp(name, "_debug_get_stack_level_line") == 0 || strcmp(name, "_profiling_get_accumulated_data") == 0
            || strcmp(name, "_profiling_get_frame_data") == 0 || strcmp(name, "_preferred_file_name_casing") == 0
            || strcmp(name, "_open_in_external_editor") == 0) {
        ret_int(ret, 0);
    } else if (strcmp(name, "_make_function") == 0 || strcmp(name, "_debug_get_error") == 0
            || strcmp(name, "_debug_get_stack_level_function") == 0 || strcmp(name, "_debug_get_stack_level_source") == 0
            || strcmp(name, "_debug_parse_stack_level_expression") == 0 || strcmp(name, "_auto_indent_code") == 0
            || strcmp(name, "_validate_path") == 0) {
        ret_string(ret, "");
    } else if (strcmp(name, "_is_control_flow_keyword") == 0) {
        char *word = args != NULL && args[0] != NULL ? string_dup(args[0]) : strdup("");
        ret_bool(ret, hi_flow(word != NULL ? word : "", word != NULL ? (int) strlen(word) : 0));
        free(word);
    } else if (strcmp(name, "_handles_global_class_type") == 0) {
        ret_bool(ret, 0);
    }
}

static int has_member(Member *items, int n, const char *name) {
    int i;
    for (i = 0; i < n; i++) {
        if (items[i].name != NULL && strcmp(items[i].name, name) == 0) {
            return 1;
        }
    }
    return 0;
}

static Member *find_member(Member *items, int n, const char *name) {
    int i;
    for (i = 0; i < n; i++) {
        if (items[i].name != NULL && strcmp(items[i].name, name) == 0) {
            return &items[i];
        }
    }
    return NULL;
}

static StructInfo *struct_lookup(const char *name) {
    int s;
    int i;
    if (name == NULL || name[0] == 0) {
        return NULL;
    }
    for (s = 0; s < g_nscript_ids; s++) {
        ScriptData *script = g_script_data[s];
        if (script == NULL) {
            continue;
        }
        for (i = 0; i < script->nstructs; i++) {
            if (script->structs[i].name != NULL && strcmp(script->structs[i].name, name) == 0) {
                return &script->structs[i];
            }
        }
    }
    return NULL;
}

static void *struct_default_variant(const char *type);

static int rose_variant_type(const char *type) {
    if (type == NULL) {
        return GDEXTENSION_VARIANT_TYPE_NIL;
    }
    if (strcmp(type, "Bool") == 0) {
        return GDEXTENSION_VARIANT_TYPE_BOOL;
    }
    if (strcmp(type, "Int") == 0) {
        return GDEXTENSION_VARIANT_TYPE_INT;
    }
    if (strcmp(type, "Float") == 0) {
        return GDEXTENSION_VARIANT_TYPE_FLOAT;
    }
    if (strcmp(type, "String") == 0) {
        return GDEXTENSION_VARIANT_TYPE_STRING;
    }
    if (strcmp(type, "Vec2") == 0) {
        return GDEXTENSION_VARIANT_TYPE_VECTOR2;
    }
    if (strcmp(type, "Vector2i") == 0) {
        return GDEXTENSION_VARIANT_TYPE_VECTOR2I;
    }
    if (strcmp(type, "Vector3") == 0) {
        return GDEXTENSION_VARIANT_TYPE_VECTOR3;
    }
    if (strcmp(type, "Vector3i") == 0) {
        return GDEXTENSION_VARIANT_TYPE_VECTOR3I;
    }
    if (strcmp(type, "Vector4") == 0) {
        return GDEXTENSION_VARIANT_TYPE_VECTOR4;
    }
    if (strcmp(type, "Rect2") == 0) {
        return GDEXTENSION_VARIANT_TYPE_RECT2;
    }
    if (strcmp(type, "Rect2i") == 0) {
        return GDEXTENSION_VARIANT_TYPE_RECT2I;
    }
    if (strcmp(type, "Transform2D") == 0) {
        return GDEXTENSION_VARIANT_TYPE_TRANSFORM2D;
    }
    if (strcmp(type, "Vector4i") == 0) {
        return GDEXTENSION_VARIANT_TYPE_VECTOR4I;
    }
    if (strcmp(type, "Plane") == 0) {
        return GDEXTENSION_VARIANT_TYPE_PLANE;
    }
    if (strcmp(type, "Quaternion") == 0) {
        return GDEXTENSION_VARIANT_TYPE_QUATERNION;
    }
    if (strcmp(type, "AABB") == 0) {
        return GDEXTENSION_VARIANT_TYPE_AABB;
    }
    if (strcmp(type, "Basis") == 0) {
        return GDEXTENSION_VARIANT_TYPE_BASIS;
    }
    if (strcmp(type, "Transform3D") == 0) {
        return GDEXTENSION_VARIANT_TYPE_TRANSFORM3D;
    }
    if (strcmp(type, "Projection") == 0) {
        return GDEXTENSION_VARIANT_TYPE_PROJECTION;
    }
    if (strcmp(type, "Signal") == 0) {
        return GDEXTENSION_VARIANT_TYPE_SIGNAL;
    }
    if (strcmp(type, "Color") == 0) {
        return GDEXTENSION_VARIANT_TYPE_COLOR;
    }
    if (strcmp(type, "Array") == 0 || (strncmp(type, "Array[", 6) == 0 && type[strlen(type) - 1] == ']')) {
        return GDEXTENSION_VARIANT_TYPE_ARRAY;
    }
    if (strcmp(type, "Map") == 0) {
        return GDEXTENSION_VARIANT_TYPE_DICTIONARY;
    }
    if (struct_lookup(type) != NULL) {
        return GDEXTENSION_VARIANT_TYPE_OBJECT;
    }
    return GDEXTENSION_VARIANT_TYPE_NIL;
}

static void *member_dict(Member *m, int signal) {
    void *storage = empty_builtin(GDEXTENSION_VARIANT_TYPE_DICTIONARY);
    void *var = variant_of_builtin(GDEXTENSION_VARIANT_TYPE_DICTIONARY, storage);
    void *name = var_string(m->name);
    void *args = empty_builtin(GDEXTENSION_VARIANT_TYPE_ARRAY);
    void *args_var = variant_of_builtin(GDEXTENSION_VARIANT_TYPE_ARRAY, args);
    GDExtensionPtrDestructor dtor = get_ptr_dtor(GDEXTENSION_VARIANT_TYPE_DICTIONARY);
    GDExtensionPtrDestructor adtor = get_ptr_dtor(GDEXTENSION_VARIANT_TYPE_ARRAY);
    dict_put(var, "name", name);
    if (signal) {
        dict_put(var, "args", args_var);
    } else if (m->type != NULL && m->type[0] != 0) {
        int vty = rose_variant_type(m->type);
        void *ty = var_int(vty);
        void *usage = var_int(RG_USAGE_SCRIPT_VAR);
        dict_put(var, "type", ty);
        dict_put(var, "usage", usage);
        if (vty == GDEXTENSION_VARIANT_TYPE_OBJECT) {
            void *cls = var_string("RoseGoldResource");
            void *hint = var_int(RG_HINT_RESOURCE);
            void *hint_string = var_string("RoseGoldResource");
            dict_put(var, "class_name", cls);
            dict_put(var, "hint", hint);
            dict_put(var, "hint_string", hint_string);
            variant_destroy(cls);
            vfree(cls);
            variant_destroy(hint);
            vfree(hint);
            variant_destroy(hint_string);
            vfree(hint_string);
        } else if (vty == GDEXTENSION_VARIANT_TYPE_DICTIONARY) {
            /* Key and value are strings, so the add row is not a nil variant. */
            void *hint = var_int(RG_HINT_DICTIONARY);
            void *hint_string = var_string("4/0:;4/0:");
            dict_put(var, "hint", hint);
            dict_put(var, "hint_string", hint_string);
            variant_destroy(hint);
            vfree(hint);
            variant_destroy(hint_string);
            vfree(hint_string);
        }
        variant_destroy(ty);
        vfree(ty);
        variant_destroy(usage);
        vfree(usage);
    }
    variant_destroy(name);
    vfree(name);
    if (adtor != NULL) {
        adtor(args);
    }
    vfree(args);
    variant_destroy(args_var);
    vfree(args_var);
    if (dtor != NULL) {
        dtor(storage);
    }
    vfree(storage);
    return var;
}

static void ret_member_array(GDExtensionTypePtr r, Member *items, int n, int signal, int exports_only) {
    void *storage = empty_builtin(GDEXTENSION_VARIANT_TYPE_ARRAY);
    void *var = variant_of_builtin(GDEXTENSION_VARIANT_TYPE_ARRAY, storage);
    GDExtensionPtrDestructor dtor = get_ptr_dtor(GDEXTENSION_VARIANT_TYPE_ARRAY);
    int i;
    for (i = 0; i < n; i++) {
        void *d;
        if (exports_only && !items[i].exported) {
            continue;
        }
        d = member_dict(&items[i], signal);
        push_variant(var, d);
        variant_destroy(d);
        vfree(d);
    }
    to_array(r, var);
    if (dtor != NULL) {
        dtor(storage);
    }
    vfree(storage);
    variant_destroy(var);
    vfree(var);
}

static char *globalize(const char *path);
static char *read_text_file(const char *path);
static int write_text_file(const char *path, const char *text);
static ScriptData *script_from_object(GDExtensionObjectPtr obj);

static int compile_script(const char *rg_path, const char *dll_path);
static int load_library(ScriptData *script, const char *dll_path);
static void read_manifest(ScriptData *script, const char *json_path);

static int cache_paths(const char *global_rg, char **dll_out, char **json_out) {
    const char *root;
    char parent[1024];
    char dir[1024];
    char stem[96];
    const char *base;
    const char *dot;
    unsigned int hash = 2166136261u;
    const unsigned char *p;
    size_t stem_n;
    size_t dir_n;
    root = getenv("LOCALAPPDATA");
    if (root == NULL || root[0] == 0) {
        root = getenv("TEMP");
    }
    if (root == NULL || root[0] == 0 || global_rg == NULL) {
        return 0;
    }
    snprintf(parent, sizeof(parent), "%s\\RoseGold", root);
    snprintf(dir, sizeof(dir), "%s\\RoseGold\\godot", root);
#if defined(_WIN32)
    CreateDirectoryA(parent, NULL);
    CreateDirectoryA(dir, NULL);
#else
    mkdir(parent, 0755);
    mkdir(dir, 0755);
#endif
    for (p = (const unsigned char *) global_rg; *p != 0; p++) {
        hash ^= *p;
        hash *= 16777619u;
    }
    base = strrchr(global_rg, '\\');
    if (base == NULL) {
        base = strrchr(global_rg, '/');
    }
    base = base != NULL ? base + 1 : global_rg;
    dot = strrchr(base, '.');
    stem_n = dot != NULL && dot != base ? (size_t) (dot - base) : strlen(base);
    if (stem_n >= sizeof(stem)) {
        stem_n = sizeof(stem) - 1;
    }
    memcpy(stem, base, stem_n);
    stem[stem_n] = 0;
    dir_n = strlen(dir);
    *dll_out = (char *) malloc(dir_n + stem_n + 24);
    *json_out = (char *) malloc(dir_n + stem_n + 24);
    if (*dll_out == NULL || *json_out == NULL) {
        free(*dll_out);
        free(*json_out);
        *dll_out = NULL;
        *json_out = NULL;
        return 0;
    }
    snprintf(*dll_out, dir_n + stem_n + 24, "%s\\%08x_%s.dll", dir, hash, stem);
    snprintf(*json_out, dir_n + stem_n + 24, "%s\\%08x_%s.json", dir, hash, stem);
#if !defined(_WIN32)
    {
        char *s;
        for (s = *dll_out; *s != 0; s++) {
            if (*s == '\\') {
                *s = '/';
            }
        }
        for (s = *json_out; *s != 0; s++) {
            if (*s == '\\') {
                *s = '/';
            }
        }
    }
#endif
    return 1;
}

static int editor_hint(void);

static int file_stamp(const char *path, unsigned long long *out) {
#if defined(_WIN32)
    WIN32_FILE_ATTRIBUTE_DATA info;
    if (path == NULL || !GetFileAttributesExA(path, GetFileExInfoStandard, &info)) {
        return 0;
    }
    if (info.nFileSizeHigh == 0 && info.nFileSizeLow == 0) {
        return 0;
    }
    *out = ((unsigned long long) info.ftLastWriteTime.dwHighDateTime << 32)
            | (unsigned long long) info.ftLastWriteTime.dwLowDateTime;
    return 1;
#else
    struct stat st;
    if (path == NULL || stat(path, &st) != 0 || st.st_size <= 0) {
        return 0;
    }
    *out = (unsigned long long) st.st_mtime;
    return 1;
#endif
}

/* True when the cached library and manifest are at least as new as the script. */
static int outputs_fresh(const char *src, const char *dll, const char *json) {
    unsigned long long src_m;
    unsigned long long dll_m;
    unsigned long long json_m;
    if (!file_stamp(src, &src_m) || !file_stamp(dll, &dll_m) || !file_stamp(json, &json_m)) {
        return 0;
    }
    return dll_m >= src_m && json_m >= src_m;
}

static int g_ensuring;

static void ensure_script(ScriptData *script) {
    char *global;
    char *dll = NULL;
    char *json = NULL;
    int fresh;
    int editing;
    if (script == NULL || g_ensuring) {
        return;
    }
    g_ensuring = 1;
    editing = editor_hint();
    if (script->loaded && editing) {
        g_ensuring = 0;
        return;
    }
    global = globalize(script->path != NULL ? script->path : "");
    if (global == NULL || global[0] == 0 || !cache_paths(global, &dll, &json)) {
        free(global);
        free(dll);
        free(json);
        g_ensuring = 0;
        return;
    }
    fresh = outputs_fresh(global, dll, json);
    if (script->loaded && fresh) {
        free(global);
        free(dll);
        free(json);
        g_ensuring = 0;
        return;
    }
    if (script->loaded) {
#if defined(_WIN32)
        FreeLibrary((HMODULE) script->library);
#else
        dlclose(script->library);
#endif
        script->library = NULL;
        script->loaded = 0;
    }
    /*
     * Typechecking the generated engine stubs blocks the editor for a long time.
     * Opening a scene reuses the cached library. Play compiles when the script is newer.
     */
    if (!fresh && !editing) {
        compile_script(global, dll);
    }
    load_library(script, dll);
    read_manifest(script, json);
    free(global);
    free(dll);
    free(json);
    g_ensuring = 0;
}

static int editor_hint(void) {
    unsigned char sn[16];
    unsigned char method[16];
    GDExtensionObjectPtr engine;
    void *self;
    void *ret;
    GDExtensionCallError err;
    uint8_t bit = 0;
    if (global_get_singleton == NULL) {
        return 0;
    }
    sn_new(sn, "Engine");
    engine = global_get_singleton(sn);
    sn_free(sn);
    if (engine == NULL) {
        return 0;
    }
    self = var_object(engine);
    ret = vbuf();
    sn_new(method, "is_editor_hint");
    memset(&err, 0, sizeof(err));
    variant_call(self, method, NULL, 0, ret, &err);
    sn_free(method);
    if (err.error == 0) {
        to_bool(&bit, ret);
    }
    variant_destroy(ret);
    vfree(ret);
    variant_destroy(self);
    vfree(self);
    return bit ? 1 : 0;
}

static int hi_ident_start(char c) {
    return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_';
}

static int hi_ident(char c) {
    return hi_ident_start(c) || (c >= '0' && c <= '9');
}

static int hi_starts(const char *s, int i, int n, const char *lit) {
    int k = (int) strlen(lit);
    return i + k <= n && memcmp(s + i, lit, (size_t) k) == 0;
}

static void hi_mark(void *dict, int column, const float rgba[4]) {
    unsigned char storage[16];
    void *color;
    void *key;
    void *info_storage;
    void *info;
    void *name;
    uint8_t valid = 0;
    GDExtensionPtrDestructor dtor;
    if (dict == NULL || variant_set == NULL || from_color == NULL || from_int == NULL || column < 0) {
        return;
    }
    memcpy(storage, rgba, sizeof(storage));
    color = vbuf();
    from_color(color, storage);
    key = var_int((int64_t) column);
    info_storage = empty_builtin(GDEXTENSION_VARIANT_TYPE_DICTIONARY);
    info = variant_of_builtin(GDEXTENSION_VARIANT_TYPE_DICTIONARY, info_storage);
    name = var_string("color");
    variant_set(info, name, color, &valid);
    variant_set(dict, key, info, &valid);
    variant_destroy(name);
    vfree(name);
    variant_destroy(color);
    vfree(color);
    variant_destroy(key);
    vfree(key);
    dtor = get_ptr_dtor(GDEXTENSION_VARIANT_TYPE_DICTIONARY);
    if (dtor != NULL) {
        dtor(info_storage);
    }
    vfree(info_storage);
    variant_destroy(info);
    vfree(info);
}

static int hi_call(const char *s, int i, int n) {
    while (i < n && (s[i] == ' ' || s[i] == '\t')) {
        i++;
    }
    return i < n && s[i] == '(';
}

static void hi_scan(const char *s, int *in_string, int *in_block, void *dict) {
    int n = s != NULL ? (int) strlen(s) : 0;
    int i = 0;
    if (dict != NULL) {
        const float *start = g_hi.text;
        if (in_string != NULL && *in_string) {
            start = g_hi.string;
        } else if (in_block != NULL && *in_block) {
            start = g_hi.comment;
        }
        hi_mark(dict, 0, start);
    }
    while (i < n) {
        if (in_string != NULL && *in_string) {
            if (s[i] == '\\' && i + 1 < n) {
                i += 2;
                continue;
            }
            if (s[i] == '"') {
                *in_string = 0;
                i++;
                if (dict != NULL) {
                    hi_mark(dict, i, g_hi.text);
                }
                continue;
            }
            i++;
            continue;
        }
        if (in_block != NULL && *in_block) {
            if (hi_starts(s, i, n, "#/")) {
                i += 2;
                *in_block = 0;
                if (dict != NULL) {
                    hi_mark(dict, i, g_hi.text);
                }
                continue;
            }
            i++;
            continue;
        }
        if (s[i] == ' ' || s[i] == '\t' || s[i] == '\r') {
            i++;
            continue;
        }
        if (hi_starts(s, i, n, "//") || (s[i] == '#' && !hi_starts(s, i, n, "#/"))) {
            if (dict != NULL) {
                hi_mark(dict, i, g_hi.comment);
            }
            break;
        }
        if (hi_starts(s, i, n, "/#")) {
            if (dict != NULL) {
                hi_mark(dict, i, g_hi.comment);
            }
            if (in_block != NULL) {
                *in_block = 1;
            }
            i += 2;
            continue;
        }
        if (s[i] == '"') {
            if (dict != NULL) {
                hi_mark(dict, i, g_hi.string);
            }
            if (in_string != NULL) {
                *in_string = 1;
            }
            i++;
            continue;
        }
        if (s[i] >= '0' && s[i] <= '9') {
            if (dict != NULL) {
                hi_mark(dict, i, g_hi.number);
            }
            while (i < n && ((s[i] >= '0' && s[i] <= '9') || s[i] == '.')) {
                i++;
            }
            if (dict != NULL) {
                hi_mark(dict, i, g_hi.text);
            }
            continue;
        }
        if (s[i] == '@') {
            int start = i;
            i++;
            while (i < n && hi_ident(s[i])) {
                i++;
            }
            if (dict != NULL) {
                hi_mark(dict, start, g_hi.keyword);
                hi_mark(dict, i, g_hi.text);
            }
            continue;
        }
        if (hi_ident_start(s[i])) {
            int start = i;
            int len;
            const float *color = g_hi.text;
            while (i < n && hi_ident(s[i])) {
                i++;
            }
            len = i - start;
            if (hi_flow(s + start, len)) {
                color = g_hi.flow;
            } else if (hi_keyword(s + start, len)) {
                color = g_hi.keyword;
            } else if (hi_call(s, i, n)) {
                color = g_hi.function;
            } else if (s[start] >= 'A' && s[start] <= 'Z') {
                color = g_hi.type;
            } else if (start > 0 && s[start - 1] == '.') {
                color = g_hi.member;
            }
            if (dict != NULL) {
                hi_mark(dict, start, color);
                hi_mark(dict, i, g_hi.text);
            }
            continue;
        }
        if (dict != NULL) {
            hi_mark(dict, i, g_hi.symbol);
            hi_mark(dict, i + 1, g_hi.text);
        }
        i++;
    }
}

static GDExtensionObjectPtr hi_object(GDExtensionObjectPtr obj, const char *method) {
    void *self;
    void *ret;
    unsigned char sn[16];
    GDExtensionCallError err;
    GDExtensionObjectPtr out = NULL;
    if (obj == NULL) {
        return NULL;
    }
    self = var_object(obj);
    ret = vbuf();
    sn_new(sn, method);
    memset(&err, 0, sizeof(err));
    variant_call(self, sn, NULL, 0, ret, &err);
    sn_free(sn);
    if (err.error == 0 && variant_get_type(ret) == GDEXTENSION_VARIANT_TYPE_OBJECT && to_object != NULL) {
        to_object(&out, ret);
    }
    variant_destroy(ret);
    vfree(ret);
    variant_destroy(self);
    vfree(self);
    return out;
}

static char *hi_line(GDExtensionObjectPtr edit, int64_t line) {
    void *self;
    void *arg;
    void *ret;
    const void *args[1];
    unsigned char str[16];
    unsigned char sn[16];
    GDExtensionCallError err;
    char *text = NULL;
    if (edit == NULL) {
        return strdup("");
    }
    self = var_object(edit);
    arg = var_int(line);
    ret = vbuf();
    args[0] = arg;
    sn_new(sn, "get_line");
    memset(&err, 0, sizeof(err));
    variant_call(self, sn, (const GDExtensionConstVariantPtr *) args, 1, ret, &err);
    sn_free(sn);
    if (err.error == 0 && to_string != NULL) {
        memset(str, 0, sizeof(str));
        to_string(str, ret);
        text = string_dup(str);
        if (dtor_string != NULL) {
            dtor_string(str);
        }
    }
    variant_destroy(arg);
    vfree(arg);
    variant_destroy(ret);
    vfree(ret);
    variant_destroy(self);
    vfree(self);
    return text != NULL ? text : strdup("");
}

static int64_t hi_line_count(GDExtensionObjectPtr edit) {
    void *self;
    void *ret;
    unsigned char sn[16];
    GDExtensionCallError err;
    int64_t n = 0;
    if (edit == NULL) {
        return 0;
    }
    self = var_object(edit);
    ret = vbuf();
    sn_new(sn, "get_line_count");
    memset(&err, 0, sizeof(err));
    variant_call(self, sn, NULL, 0, ret, &err);
    sn_free(sn);
    if (err.error == 0 && to_int != NULL) {
        to_int(&n, ret);
    }
    variant_destroy(ret);
    vfree(ret);
    variant_destroy(self);
    vfree(self);
    return n;
}

static int hi_setting(GDExtensionObjectPtr settings, const char *key, float *out) {
    void *self;
    void *arg;
    void *ret;
    const void *args[1];
    unsigned char sn[16];
    unsigned char buf[16];
    GDExtensionCallError err;
    int ok = 0;
    if (settings == NULL || to_color == NULL) {
        return 0;
    }
    self = var_object(settings);
    arg = var_string(key);
    ret = vbuf();
    args[0] = arg;
    sn_new(sn, "get_setting");
    memset(&err, 0, sizeof(err));
    variant_call(self, sn, (const GDExtensionConstVariantPtr *) args, 1, ret, &err);
    sn_free(sn);
    if (err.error == 0 && variant_get_type(ret) == GDEXTENSION_VARIANT_TYPE_COLOR) {
        to_color(buf, ret);
        memcpy(out, buf, sizeof(buf));
        ok = 1;
    }
    variant_destroy(arg);
    vfree(arg);
    variant_destroy(ret);
    vfree(ret);
    variant_destroy(self);
    vfree(self);
    return ok;
}

static void hi_load_colors(void) {
    unsigned char sn[16];
    GDExtensionObjectPtr editor;
    GDExtensionObjectPtr settings;
    hi_rgba(g_hi.text, 0.875f, 0.875f, 0.875f, 1.0f);
    hi_rgba(g_hi.keyword, 1.0f, 0.44f, 0.52f, 1.0f);
    hi_rgba(g_hi.flow, 1.0f, 0.55f, 0.8f, 1.0f);
    hi_rgba(g_hi.comment, 0.8f, 0.8f, 0.8f, 0.5f);
    hi_rgba(g_hi.string, 1.0f, 0.93f, 0.63f, 1.0f);
    hi_rgba(g_hi.number, 0.63f, 1.0f, 0.88f, 1.0f);
    hi_rgba(g_hi.type, 0.56f, 1.0f, 0.86f, 1.0f);
    hi_rgba(g_hi.function, 0.34f, 0.7f, 1.0f, 1.0f);
    hi_rgba(g_hi.symbol, 0.67f, 0.79f, 1.0f, 1.0f);
    hi_rgba(g_hi.member, 0.68f, 0.78f, 1.0f, 1.0f);
    g_hi.loaded = 1;
    sn_new(sn, "EditorInterface");
    editor = global_get_singleton != NULL ? global_get_singleton(sn) : NULL;
    sn_free(sn);
    settings = hi_object(editor, "get_editor_settings");
    if (settings == NULL) {
        return;
    }
    hi_setting(settings, "text_editor/theme/highlighting/text_color", g_hi.text);
    hi_setting(settings, "text_editor/theme/highlighting/keyword_color", g_hi.keyword);
    hi_setting(settings, "text_editor/theme/highlighting/control_flow_keyword_color", g_hi.flow);
    hi_setting(settings, "text_editor/theme/highlighting/comment_color", g_hi.comment);
    hi_setting(settings, "text_editor/theme/highlighting/string_color", g_hi.string);
    hi_setting(settings, "text_editor/theme/highlighting/number_color", g_hi.number);
    hi_setting(settings, "text_editor/theme/highlighting/engine_type_color", g_hi.type);
    hi_setting(settings, "text_editor/theme/highlighting/function_color", g_hi.function);
    hi_setting(settings, "text_editor/theme/highlighting/symbol_color", g_hi.symbol);
    hi_setting(settings, "text_editor/theme/highlighting/member_variable_color", g_hi.member);
}

static void virt_highlight(GDExtensionClassInstancePtr inst, const GDExtensionConstTypePtr *args, GDExtensionTypePtr ret, const char *name) {
    if (strcmp(name, "_get_name") == 0) {
        ret_string(ret, "RoseGold");
    } else if (strcmp(name, "_get_supported_languages") == 0) {
        const char *items[] = {"RoseGold"};
        ret_literals(ret, items, 1);
    } else if (strcmp(name, "_create") == 0) {
        ret_ptr(ret, create_highlighter(NULL, 1));
    } else if (strcmp(name, "_update_cache") == 0) {
        hi_load_colors();
    } else if (strcmp(name, "_get_line_syntax_highlighting") == 0) {
        HighlighterData *data = (HighlighterData *) inst;
        void *storage = empty_builtin(GDEXTENSION_VARIANT_TYPE_DICTIONARY);
        void *var = variant_of_builtin(GDEXTENSION_VARIANT_TYPE_DICTIONARY, storage);
        GDExtensionPtrDestructor dtor = get_ptr_dtor(GDEXTENSION_VARIANT_TYPE_DICTIONARY);
        GDExtensionObjectPtr edit;
        int64_t target = args != NULL && args[0] != NULL ? *(int64_t *) args[0] : 0;
        int64_t count;
        int64_t line;
        int in_string = 0;
        int in_block = 0;
        if (!g_hi.loaded) {
            hi_load_colors();
        }
        edit = data != NULL ? hi_object(data->godot, "get_text_edit") : NULL;
        count = hi_line_count(edit);
        if (target < 0) {
            target = 0;
        }
        for (line = 0; line <= target && line < count; line++) {
            char *text = hi_line(edit, line);
            hi_scan(text, &in_string, &in_block, line == target ? var : NULL);
            free(text);
        }
        to_dict(ret, var);
        if (dtor != NULL) {
            dtor(storage);
        }
        vfree(storage);
        variant_destroy(var);
        vfree(var);
    }
}

static void *fresh_builtin(GDExtensionVariantType type);
static int scan_ident(char c);

static int lit_skip(const char *s, int i) {
    while (s[i] == ' ' || s[i] == '\t' || s[i] == '\n' || s[i] == '\r') {
        i++;
    }
    return i;
}

static void *lit_parse(const char *s, int *io) {
    int i;
    if (s == NULL || io == NULL) {
        return NULL;
    }
    i = lit_skip(s, *io);
    if (s[i] == '"') {
        char buf[1024];
        int k = 0;
        i++;
        while (s[i] != 0 && s[i] != '"') {
            char c = s[i++];
            if (c == '\\' && s[i] != 0) {
                char e = s[i++];
                if (e == 'n') {
                    c = '\n';
                } else if (e == 't') {
                    c = '\t';
                } else {
                    c = e;
                }
            }
            if (k + 1 < (int) sizeof(buf)) {
                buf[k++] = c;
            }
        }
        if (s[i] == '"') {
            i++;
        }
        buf[k] = 0;
        *io = i;
        return var_string(buf);
    }
    if (s[i] == '{') {
        void *dict = fresh_builtin(GDEXTENSION_VARIANT_TYPE_DICTIONARY);
        i++;
        for (;;) {
            void *key;
            void *val;
            uint8_t valid = 0;
            i = lit_skip(s, i);
            if (s[i] == 0 || s[i] == '}') {
                break;
            }
            *io = i;
            key = lit_parse(s, io);
            i = lit_skip(s, *io);
            if (s[i] == ':') {
                i++;
            }
            *io = lit_skip(s, i);
            val = lit_parse(s, io);
            i = *io;
            if (key != NULL && val != NULL && variant_set_keyed != NULL) {
                variant_set_keyed(dict, key, val, &valid);
            }
            if (key != NULL) {
                variant_destroy(key);
                vfree(key);
            }
            if (val != NULL) {
                variant_destroy(val);
                vfree(val);
            }
            i = lit_skip(s, i);
            if (s[i] == ',') {
                i++;
                continue;
            }
            break;
        }
        if (s[i] == '}') {
            i++;
        }
        *io = i;
        return dict;
    }
    if (s[i] == '[') {
        void *arr = fresh_builtin(GDEXTENSION_VARIANT_TYPE_ARRAY);
        i++;
        for (;;) {
            void *val;
            i = lit_skip(s, i);
            if (s[i] == 0 || s[i] == ']') {
                break;
            }
            *io = i;
            val = lit_parse(s, io);
            i = *io;
            if (val != NULL) {
                push_variant(arr, val);
                variant_destroy(val);
                vfree(val);
            }
            i = lit_skip(s, i);
            if (s[i] == ',') {
                i++;
                continue;
            }
            break;
        }
        if (s[i] == ']') {
            i++;
        }
        *io = i;
        return arr;
    }
    if (strncmp(s + i, "true", 4) == 0 && !scan_ident(s[i + 4])) {
        *io = i + 4;
        return var_bool(1);
    }
    if (strncmp(s + i, "false", 5) == 0 && !scan_ident(s[i + 5])) {
        *io = i + 5;
        return var_bool(0);
    }
    if (s[i] == '-' || (s[i] >= '0' && s[i] <= '9')) {
        char *end = NULL;
        double n = strtod(s + i, &end);
        int as_float = 0;
        const char *p;
        if (end != NULL && end != s + i) {
            for (p = s + i; p < end; p++) {
                if (*p == '.' || *p == 'e' || *p == 'E') {
                    as_float = 1;
                }
            }
            *io = (int) (end - s);
            if (as_float) {
                return var_float(n);
            }
            return var_int((int64_t) n);
        }
    }
    *io = i;
    return NULL;
}

static void *member_default(Member *m) {
    if (m != NULL && m->type != NULL && struct_lookup(m->type) != NULL) {
        return struct_default_variant(m->type);
    }
    if (m != NULL && m->type != NULL && strcmp(m->type, "Float") == 0) {
        return var_float(m->has_number ? m->number : 0.0);
    }
    if (m != NULL && m->type != NULL && strcmp(m->type, "Int") == 0) {
        return var_int(m->has_number ? (int64_t) m->number : 0);
    }
    if (m != NULL && m->type != NULL && strcmp(m->type, "Bool") == 0) {
        return var_bool(m->has_number && m->number != 0.0);
    }
    if (m != NULL && m->type != NULL && (strcmp(m->type, "Array") == 0 || (strncmp(m->type, "Array[", 6) == 0 && m->type[strlen(m->type) - 1] == ']'))) {
        if (m->init != NULL && m->init[0] == '[') {
            int at = 0;
            void *parsed = lit_parse(m->init, &at);
            if (parsed != NULL) {
                return parsed;
            }
        }
        return fresh_builtin(GDEXTENSION_VARIANT_TYPE_ARRAY);
    }
    if (m != NULL && m->type != NULL && strcmp(m->type, "Map") == 0) {
        if (m->init != NULL && m->init[0] == '{') {
            int at = 0;
            void *parsed = lit_parse(m->init, &at);
            if (parsed != NULL) {
                return parsed;
            }
        }
        return fresh_builtin(GDEXTENSION_VARIANT_TYPE_DICTIONARY);
    }
    return var_string("");
}

static int g_refreshing;

static void scan_structs(ScriptData *script);
static void scan_exports(ScriptData *script);

static void refresh_placeholders(ScriptData *script) {
    void *props_storage;
    void *props_var;
    void *props_out;
    void *values_storage;
    void *values_var;
    void *values_out;
    GDExtensionPtrDestructor adtor;
    GDExtensionPtrDestructor ddtor;
    PlaceholderRef *it;
    int i;
    if (g_refreshing || script == NULL || placeholder_script_instance_update == NULL || script->placeholders == NULL) {
        return;
    }
    scan_structs(script);
    scan_exports(script);
    g_refreshing = 1;
    ensure_script(script);
    props_storage = empty_builtin(GDEXTENSION_VARIANT_TYPE_ARRAY);
    props_var = variant_of_builtin(GDEXTENSION_VARIANT_TYPE_ARRAY, props_storage);
    values_storage = empty_builtin(GDEXTENSION_VARIANT_TYPE_DICTIONARY);
    values_var = variant_of_builtin(GDEXTENSION_VARIANT_TYPE_DICTIONARY, values_storage);
    for (i = 0; i < script->nvars; i++) {
        void *info;
        void *value;
        if (!script->vars[i].exported) {
            continue;
        }
        info = member_dict(&script->vars[i], 0);
        value = member_default(&script->vars[i]);
        push_variant(props_var, info);
        dict_put(values_var, script->vars[i].name, value);
        variant_destroy(info);
        vfree(info);
        variant_destroy(value);
        vfree(value);
    }
    props_out = empty_builtin(GDEXTENSION_VARIANT_TYPE_ARRAY);
    values_out = empty_builtin(GDEXTENSION_VARIANT_TYPE_DICTIONARY);
    to_array(props_out, props_var);
    to_dict(values_out, values_var);
    for (it = script->placeholders; it != NULL; it = it->next) {
        placeholder_script_instance_update(it->inst, props_out, values_out);
    }
    adtor = get_ptr_dtor(GDEXTENSION_VARIANT_TYPE_ARRAY);
    ddtor = get_ptr_dtor(GDEXTENSION_VARIANT_TYPE_DICTIONARY);
    if (adtor != NULL) {
        adtor(props_storage);
        adtor(props_out);
    }
    if (ddtor != NULL) {
        ddtor(values_storage);
        ddtor(values_out);
    }
    vfree(props_storage);
    vfree(props_out);
    vfree(values_storage);
    vfree(values_out);
    variant_destroy(props_var);
    vfree(props_var);
    variant_destroy(values_var);
    vfree(values_var);
    g_refreshing = 0;
}

static void drop_placeholder(ScriptData *script, void *inst) {
    PlaceholderRef **cur;
    if (script == NULL) {
        return;
    }
    cur = &script->placeholders;
    while (*cur != NULL) {
        if ((*cur)->inst == inst) {
            PlaceholderRef *dead = *cur;
            *cur = dead->next;
            free(dead);
            return;
        }
        cur = &(*cur)->next;
    }
}

static void *make_placeholder(ScriptData *script, GDExtensionObjectPtr owner) {
    void *ph;
    PlaceholderRef *ref;
    if (placeholder_script_instance_create == NULL || script == NULL || script->godot == NULL || g_language == NULL || owner == NULL) {
        return NULL;
    }
    ph = placeholder_script_instance_create(g_language, script->godot, owner);
    if (ph == NULL) {
        return NULL;
    }
    ref = (PlaceholderRef *) calloc(1, sizeof(PlaceholderRef));
    if (ref != NULL) {
        ref->inst = ph;
        ref->next = script->placeholders;
        script->placeholders = ref;
    }
    refresh_placeholders(script);
    return ph;
}

static char *stringname_text(const GDExtensionConstTypePtr *args) {
    unsigned char var[40];
    unsigned char str[16];
    char *text;
    if (args == NULL || args[0] == NULL || from_stringname == NULL) {
        return strdup("");
    }
    memset(var, 0, sizeof(var));
    from_stringname(var, (GDExtensionTypePtr) args[0]);
    memset(str, 0, sizeof(str));
    variant_stringify(var, str);
    variant_destroy(var);
    text = string_dup(str);
    dtor_string(str);
    return text != NULL ? text : strdup("");
}

static void virt_script(GDExtensionClassInstancePtr inst, const GDExtensionConstTypePtr *args, GDExtensionTypePtr ret, const char *name) {
    ScriptData *script = script_of(inst);
    if (strcmp(name, "_can_instantiate") == 0 || strcmp(name, "_is_valid") == 0 || strcmp(name, "_has_source_code") == 0) {
        ret_bool(ret, 1);
    } else if (strcmp(name, "_editor_can_reload_from_file") == 0) {
        ret_bool(ret, 1);
    } else if (strcmp(name, "_is_tool") == 0 || strcmp(name, "_is_abstract") == 0
            || strcmp(name, "_is_placeholder_fallback_enabled") == 0) {
        ret_bool(ret, 0);
    } else if (strcmp(name, "_get_instance_base_type") == 0) {
        ensure_script(script);
        ret_stringname(ret, script != NULL && script->base != NULL && script->base[0] != 0 ? script->base : "Node2D");
    } else if (strcmp(name, "_get_source_code") == 0) {
        ret_string(ret, script != NULL && script->source != NULL ? script->source : "");
    } else if (strcmp(name, "_get_global_name") == 0 || strcmp(name, "_get_doc_class_name") == 0) {
        ret_stringname(ret, "");
    } else if (strcmp(name, "_inherits_script") == 0) {
        ret_bool(ret, 0);
    } else if (strcmp(name, "_placeholder_erased") == 0) {
        void *ph = args != NULL && args[0] != NULL ? *(void **) args[0] : NULL;
        drop_placeholder(script, ph);
        (void) ret;
    } else if (strcmp(name, "_get_class_icon_path") == 0) {
        ret_string(ret, "");
    } else if (strcmp(name, "_get_language") == 0) {
        ret_ptr(ret, g_language);
    } else if (strcmp(name, "_get_base_script") == 0) {
        ret_ptr(ret, NULL);
    } else if (strcmp(name, "_reload") == 0) {
        if (script != NULL) {
            if (script->path != NULL && script->path[0] != 0) {
                free(script->source);
                script->source = read_text_file(script->path);
            }
            script->loaded = 0;
            if (script->library != NULL) {
#if defined(_WIN32)
                FreeLibrary((HMODULE) script->library);
#else
                dlclose(script->library);
#endif
                script->library = NULL;
            }
        }
        ensure_script(script);
        ret_int(ret, 0);
    } else if (strcmp(name, "_update_exports") == 0) {
        refresh_placeholders(script);
        (void) ret;
    } else if (strcmp(name, "_has_static_method") == 0) {
        ret_bool(ret, 0);
    } else if (strcmp(name, "_has_method") == 0) {
        char *m;
        unsigned char var[40];
        unsigned char str[16];
        int yes = 0;
        /* args[0] is a StringName. Reading it as a String walks a garbage length. */
        memset(var, 0, sizeof(var));
        from_stringname(var, (GDExtensionTypePtr) args[0]);
        memset(str, 0, sizeof(str));
        variant_stringify(var, str);
        variant_destroy(var);
        m = string_dup(str);
        dtor_string(str);
        if (script != NULL) {
            ensure_script(script);
            yes = has_member(script->methods, script->nmethods, m);
        }
        free(m);
        ret_bool(ret, yes);
    } else if (strcmp(name, "_has_script_signal") == 0) {
        char *m;
        unsigned char var[40];
        unsigned char str[16];
        int yes = 0;
        memset(var, 0, sizeof(var));
        from_stringname(var, (GDExtensionTypePtr) args[0]);
        memset(str, 0, sizeof(str));
        variant_stringify(var, str);
        variant_destroy(var);
        m = string_dup(str);
        dtor_string(str);
        if (script != NULL) {
            ensure_script(script);
            yes = has_member(script->signals, script->nsignals, m);
        }
        free(m);
        ret_bool(ret, yes);
    } else if (strcmp(name, "_get_script_method_list") == 0) {
        ensure_script(script);
        ret_member_array(ret, script != NULL ? script->methods : NULL, script != NULL ? script->nmethods : 0, 0, 0);
    } else if (strcmp(name, "_get_script_property_list") == 0) {
        ensure_script(script);
        scan_exports(script);
        ret_member_array(ret, script != NULL ? script->vars : NULL, script != NULL ? script->nvars : 0, 0, 1);
    } else if (strcmp(name, "_get_script_signal_list") == 0) {
        ensure_script(script);
        ret_member_array(ret, script != NULL ? script->signals : NULL, script != NULL ? script->nsignals : 0, 1, 0);
    } else if (strcmp(name, "_get_members") == 0 || strcmp(name, "_get_documentation") == 0) {
        ret_empty_array(ret);
    } else if (strcmp(name, "_get_constants") == 0 || strcmp(name, "_get_method_info") == 0) {
        ret_empty_dict(ret);
    } else if (strcmp(name, "_placeholder_instance_create") == 0) {
        GDExtensionObjectPtr owner = args != NULL && args[0] != NULL ? *(GDExtensionObjectPtr *) args[0] : NULL;
        ret_ptr(ret, make_placeholder(script, owner));
    } else if (strcmp(name, "_get_member_line") == 0) {
        ret_int(ret, -1);
    } else if (strcmp(name, "_has_property_default_value") == 0) {
        char *field = stringname_text(args);
        Member *var = NULL;
        if (script != NULL) {
            ensure_script(script);
            scan_exports(script);
            var = find_member(script->vars, script->nvars, field);
        }
        ret_bool(ret, var != NULL && var->exported && (var->has_number || (var->init != NULL && var->init[0] != 0)));
        free(field);
    } else if (strcmp(name, "_get_property_default_value") == 0) {
        char *field = stringname_text(args);
        Member *var = NULL;
        if (script != NULL) {
            ensure_script(script);
            scan_exports(script);
            var = find_member(script->vars, script->nvars, field);
        }
        if (var != NULL && var->exported && (var->has_number || (var->init != NULL && var->init[0] != 0))) {
            void *value = member_default(var);
            memcpy(ret, value, 24);
            vfree(value);
        } else {
            variant_nil(ret);
        }
        free(field);
    } else if (strcmp(name, "_get_rpc_config") == 0
            || strcmp(name, "_get_script_method_argument_count") == 0) {
        variant_nil(ret);
    } else if (strcmp(name, "_instance_has") == 0) {
        ret_bool(ret, 0);
    }
}

static void virt_loader(GDExtensionClassInstancePtr inst, const GDExtensionConstTypePtr *args, GDExtensionTypePtr ret, const char *name) {
    (void) inst;
    if (strcmp(name, "_get_recognized_extensions") == 0) {
        ret_one_extension(ret);
    } else if (strcmp(name, "_handles_type") == 0) {
        int yes = sn_eq(args[0], "Script") || sn_eq(args[0], "RoseGoldScript");
        ret_bool(ret, yes);
    } else if (strcmp(name, "_get_resource_type") == 0) {
        ret_string(ret, "Script");
    } else if (strcmp(name, "_recognize_path") == 0 || strcmp(name, "_exists") == 0) {
        ret_bool(ret, 0);
    } else if (strcmp(name, "_get_resource_script_class") == 0) {
        ret_string(ret, "");
    } else if (strcmp(name, "_get_resource_uid") == 0) {
        ret_int(ret, -1);
    } else if (strcmp(name, "_get_dependencies") == 0 || strcmp(name, "_get_classes_used") == 0) {
        ret_one_extension(ret);
    } else if (strcmp(name, "_rename_dependencies") == 0) {
        ret_int(ret, 0);
    }
}

static void virt_saver(GDExtensionClassInstancePtr inst, const GDExtensionConstTypePtr *args, GDExtensionTypePtr ret, const char *name);
static void virt_highlight(GDExtensionClassInstancePtr inst, const GDExtensionConstTypePtr *args, GDExtensionTypePtr ret, const char *name);

static void dispatch(int kind, GDExtensionClassInstancePtr inst, const GDExtensionConstTypePtr *args, GDExtensionTypePtr ret, const char *name) {
    if (kind == KIND_LANG) {
        virt_lang(inst, args, ret, name);
    } else if (kind == KIND_SCRIPT) {
        virt_script(inst, args, ret, name);
    } else if (kind == KIND_LOADER) {
        virt_loader(inst, args, ret, name);
    } else if (kind == KIND_SAVER) {
        virt_saver(inst, args, ret, name);
    } else if (kind == KIND_HIGHLIGHT) {
        virt_highlight(inst, args, ret, name);
    }
}

#define VIRT(KIND, METHOD) \
    static void virt_##KIND##_##METHOD(GDExtensionClassInstancePtr inst, const GDExtensionConstTypePtr *args, GDExtensionTypePtr ret) { \
        dispatch(KIND, inst, args, ret, #METHOD); \
    }

VIRT(KIND_LANG, _get_name)
VIRT(KIND_LANG, _get_type)
VIRT(KIND_LANG, _get_extension)
VIRT(KIND_LANG, _get_recognized_extensions)
VIRT(KIND_LANG, _validate)
VIRT(KIND_LANG, _init)
VIRT(KIND_LANG, _finish)
VIRT(KIND_LANG, _frame)
VIRT(KIND_LANG, _thread_enter)
VIRT(KIND_LANG, _thread_exit)
VIRT(KIND_LANG, _get_reserved_words)
VIRT(KIND_LANG, _get_comment_delimiters)
VIRT(KIND_LANG, _get_string_delimiters)
VIRT(KIND_LANG, _get_public_functions)
VIRT(KIND_LANG, _get_public_constants)
VIRT(KIND_LANG, _get_public_annotations)
VIRT(KIND_LANG, _get_built_in_templates)
VIRT(KIND_LANG, _debug_get_current_stack_info)
VIRT(KIND_LANG, _get_global_class_name)
VIRT(KIND_LANG, _handles_global_class_type)
VIRT(KIND_LANG, _is_control_flow_keyword)
VIRT(KIND_LANG, _supports_documentation)
VIRT(KIND_LANG, _supports_builtin_mode)
VIRT(KIND_LANG, _is_using_templates)
VIRT(KIND_LANG, _has_named_classes)
VIRT(KIND_LANG, _can_inherit_from_file)
VIRT(KIND_LANG, _can_make_function)
VIRT(KIND_LANG, _overrides_external_editor)
VIRT(KIND_LANG, _complete_code)
VIRT(KIND_LANG, _lookup_code)
VIRT(KIND_LANG, _auto_indent_code)
VIRT(KIND_LANG, _validate_path)
VIRT(KIND_LANG, _profiling_start)
VIRT(KIND_LANG, _profiling_stop)
VIRT(KIND_LANG, _profiling_get_accumulated_data)
VIRT(KIND_LANG, _profiling_get_frame_data)
VIRT(KIND_LANG, _reload_all_scripts)
VIRT(KIND_SCRIPT, _can_instantiate)
VIRT(KIND_SCRIPT, _get_instance_base_type)
VIRT(KIND_SCRIPT, _get_base_script)
VIRT(KIND_SCRIPT, _get_global_name)
VIRT(KIND_SCRIPT, _inherits_script)
VIRT(KIND_SCRIPT, _get_source_code)
VIRT(KIND_SCRIPT, _has_source_code)
VIRT(KIND_SCRIPT, _reload)
VIRT(KIND_SCRIPT, _get_language)
VIRT(KIND_SCRIPT, _has_method)
VIRT(KIND_SCRIPT, _has_static_method)
VIRT(KIND_SCRIPT, _update_exports)
VIRT(KIND_SCRIPT, _has_script_signal)
VIRT(KIND_SCRIPT, _get_script_property_list)
VIRT(KIND_SCRIPT, _get_script_signal_list)
VIRT(KIND_SCRIPT, _get_script_method_list)
VIRT(KIND_SCRIPT, _is_valid)
VIRT(KIND_SCRIPT, _is_tool)
VIRT(KIND_SCRIPT, _is_abstract)
VIRT(KIND_SCRIPT, _is_placeholder_fallback_enabled)
VIRT(KIND_SCRIPT, _editor_can_reload_from_file)
VIRT(KIND_SCRIPT, _placeholder_instance_create)
VIRT(KIND_SCRIPT, _get_members)
VIRT(KIND_SCRIPT, _get_documentation)
VIRT(KIND_SCRIPT, _get_doc_class_name)
VIRT(KIND_SCRIPT, _get_class_icon_path)
VIRT(KIND_SCRIPT, _get_member_line)
VIRT(KIND_SCRIPT, _get_script_method_argument_count)
VIRT(KIND_SCRIPT, _instance_has)
VIRT(KIND_SCRIPT, _placeholder_erased)
VIRT(KIND_SCRIPT, _get_constants)
VIRT(KIND_SCRIPT, _get_method_info)
VIRT(KIND_SCRIPT, _has_property_default_value)
VIRT(KIND_SCRIPT, _get_property_default_value)
VIRT(KIND_SCRIPT, _get_rpc_config)
VIRT(KIND_LOADER, _get_recognized_extensions)
VIRT(KIND_LOADER, _handles_type)
VIRT(KIND_LOADER, _get_resource_type)
VIRT(KIND_SAVER, _save)
VIRT(KIND_SAVER, _set_uid)
VIRT(KIND_SAVER, _recognize)
VIRT(KIND_SAVER, _get_recognized_extensions)
VIRT(KIND_SAVER, _recognize_path)
VIRT(KIND_HIGHLIGHT, _get_name)
VIRT(KIND_HIGHLIGHT, _get_supported_languages)
VIRT(KIND_HIGHLIGHT, _create)
VIRT(KIND_HIGHLIGHT, _update_cache)
VIRT(KIND_HIGHLIGHT, _get_line_syntax_highlighting)

static void virt_KIND_LANG__create_script(GDExtensionClassInstancePtr inst, const GDExtensionConstTypePtr *args, GDExtensionTypePtr ret);
static void virt_KIND_LANG__make_template(GDExtensionClassInstancePtr inst, const GDExtensionConstTypePtr *args, GDExtensionTypePtr ret);
static void virt_KIND_SCRIPT__instance_create(GDExtensionClassInstancePtr inst, const GDExtensionConstTypePtr *args, GDExtensionTypePtr ret);
static void virt_KIND_SCRIPT__set_source(GDExtensionClassInstancePtr inst, const GDExtensionConstTypePtr *args, GDExtensionTypePtr ret);
static void virt_KIND_LOADER__load(GDExtensionClassInstancePtr inst, const GDExtensionConstTypePtr *args, GDExtensionTypePtr ret);

static GDExtensionClassCallVirtual pick(int kind, const char *name) {
    if (kind == KIND_LANG) {
        if (strcmp(name, "_get_name") == 0) return virt_KIND_LANG__get_name;
        if (strcmp(name, "_get_type") == 0) return virt_KIND_LANG__get_type;
        if (strcmp(name, "_get_extension") == 0) return virt_KIND_LANG__get_extension;
        if (strcmp(name, "_get_recognized_extensions") == 0) return virt_KIND_LANG__get_recognized_extensions;
        if (strcmp(name, "_validate") == 0) return virt_KIND_LANG__validate;
        if (strcmp(name, "_create_script") == 0) return virt_KIND_LANG__create_script;
        if (strcmp(name, "_make_template") == 0) return virt_KIND_LANG__make_template;
        if (strcmp(name, "_init") == 0) return virt_KIND_LANG__init;
        if (strcmp(name, "_finish") == 0) return virt_KIND_LANG__finish;
        if (strcmp(name, "_frame") == 0) return virt_KIND_LANG__frame;
        if (strcmp(name, "_get_reserved_words") == 0) return virt_KIND_LANG__get_reserved_words;
        if (strcmp(name, "_get_comment_delimiters") == 0) return virt_KIND_LANG__get_comment_delimiters;
        if (strcmp(name, "_get_string_delimiters") == 0) return virt_KIND_LANG__get_string_delimiters;
        if (strcmp(name, "_get_public_functions") == 0) return virt_KIND_LANG__get_public_functions;
        if (strcmp(name, "_get_public_constants") == 0) return virt_KIND_LANG__get_public_constants;
        if (strcmp(name, "_get_public_annotations") == 0) return virt_KIND_LANG__get_public_annotations;
        if (strcmp(name, "_debug_get_current_stack_info") == 0) return virt_KIND_LANG__debug_get_current_stack_info;
        if (strcmp(name, "_get_global_class_name") == 0) return virt_KIND_LANG__get_global_class_name;
        if (strcmp(name, "_handles_global_class_type") == 0) return virt_KIND_LANG__handles_global_class_type;
        if (strcmp(name, "_profiling_get_accumulated_data") == 0) return virt_KIND_LANG__profiling_get_accumulated_data;
        if (strcmp(name, "_profiling_get_frame_data") == 0) return virt_KIND_LANG__profiling_get_frame_data;
        if (strcmp(name, "_thread_enter") == 0) return virt_KIND_LANG__thread_enter;
        if (strcmp(name, "_thread_exit") == 0) return virt_KIND_LANG__thread_exit;
        if (strcmp(name, "_get_built_in_templates") == 0) return virt_KIND_LANG__get_built_in_templates;
        if (strcmp(name, "_is_control_flow_keyword") == 0) return virt_KIND_LANG__is_control_flow_keyword;
        if (strcmp(name, "_supports_documentation") == 0) return virt_KIND_LANG__supports_documentation;
        if (strcmp(name, "_supports_builtin_mode") == 0) return virt_KIND_LANG__supports_builtin_mode;
        if (strcmp(name, "_is_using_templates") == 0) return virt_KIND_LANG__is_using_templates;
        if (strcmp(name, "_has_named_classes") == 0) return virt_KIND_LANG__has_named_classes;
        if (strcmp(name, "_can_inherit_from_file") == 0) return virt_KIND_LANG__can_inherit_from_file;
        if (strcmp(name, "_can_make_function") == 0) return virt_KIND_LANG__can_make_function;
        if (strcmp(name, "_overrides_external_editor") == 0) return virt_KIND_LANG__overrides_external_editor;
        if (strcmp(name, "_complete_code") == 0) return virt_KIND_LANG__complete_code;
        if (strcmp(name, "_lookup_code") == 0) return virt_KIND_LANG__lookup_code;
        if (strcmp(name, "_auto_indent_code") == 0) return virt_KIND_LANG__auto_indent_code;
        if (strcmp(name, "_validate_path") == 0) return virt_KIND_LANG__validate_path;
        if (strcmp(name, "_profiling_start") == 0) return virt_KIND_LANG__profiling_start;
        if (strcmp(name, "_profiling_stop") == 0) return virt_KIND_LANG__profiling_stop;
        if (strcmp(name, "_reload_all_scripts") == 0) return virt_KIND_LANG__reload_all_scripts;
    } else if (kind == KIND_SCRIPT) {
        if (strcmp(name, "_can_instantiate") == 0) return virt_KIND_SCRIPT__can_instantiate;
        if (strcmp(name, "_get_instance_base_type") == 0) return virt_KIND_SCRIPT__get_instance_base_type;
        if (strcmp(name, "_get_base_script") == 0) return virt_KIND_SCRIPT__get_base_script;
        if (strcmp(name, "_get_global_name") == 0) return virt_KIND_SCRIPT__get_global_name;
        if (strcmp(name, "_inherits_script") == 0) return virt_KIND_SCRIPT__inherits_script;
        if (strcmp(name, "_instance_create") == 0) return virt_KIND_SCRIPT__instance_create;
        if (strcmp(name, "_placeholder_instance_create") == 0) return virt_KIND_SCRIPT__placeholder_instance_create;
        if (strcmp(name, "_get_source_code") == 0) return virt_KIND_SCRIPT__get_source_code;
        if (strcmp(name, "_has_source_code") == 0) return virt_KIND_SCRIPT__has_source_code;
        if (strcmp(name, "_set_source_code") == 0) return virt_KIND_SCRIPT__set_source;
        if (strcmp(name, "_reload") == 0) return virt_KIND_SCRIPT__reload;
        if (strcmp(name, "_get_language") == 0) return virt_KIND_SCRIPT__get_language;
        if (strcmp(name, "_has_method") == 0) return virt_KIND_SCRIPT__has_method;
        if (strcmp(name, "_has_static_method") == 0) return virt_KIND_SCRIPT__has_static_method;
        if (strcmp(name, "_update_exports") == 0) return virt_KIND_SCRIPT__update_exports;
        if (strcmp(name, "_has_script_signal") == 0) return virt_KIND_SCRIPT__has_script_signal;
        if (strcmp(name, "_get_script_property_list") == 0) return virt_KIND_SCRIPT__get_script_property_list;
        if (strcmp(name, "_get_script_signal_list") == 0) return virt_KIND_SCRIPT__get_script_signal_list;
        if (strcmp(name, "_get_script_method_list") == 0) return virt_KIND_SCRIPT__get_script_method_list;
        if (strcmp(name, "_is_valid") == 0) return virt_KIND_SCRIPT__is_valid;
        if (strcmp(name, "_is_tool") == 0) return virt_KIND_SCRIPT__is_tool;
        if (strcmp(name, "_is_abstract") == 0) return virt_KIND_SCRIPT__is_abstract;
        if (strcmp(name, "_is_placeholder_fallback_enabled") == 0) return virt_KIND_SCRIPT__is_placeholder_fallback_enabled;
        if (strcmp(name, "_editor_can_reload_from_file") == 0) return virt_KIND_SCRIPT__editor_can_reload_from_file;
        if (strcmp(name, "_get_members") == 0) return virt_KIND_SCRIPT__get_members;
        if (strcmp(name, "_get_documentation") == 0) return virt_KIND_SCRIPT__get_documentation;
        if (strcmp(name, "_get_doc_class_name") == 0) return virt_KIND_SCRIPT__get_doc_class_name;
        if (strcmp(name, "_get_class_icon_path") == 0) return virt_KIND_SCRIPT__get_class_icon_path;
        if (strcmp(name, "_get_member_line") == 0) return virt_KIND_SCRIPT__get_member_line;
        if (strcmp(name, "_get_script_method_argument_count") == 0) return virt_KIND_SCRIPT__get_script_method_argument_count;
        if (strcmp(name, "_instance_has") == 0) return virt_KIND_SCRIPT__instance_has;
        if (strcmp(name, "_placeholder_erased") == 0) return virt_KIND_SCRIPT__placeholder_erased;
        if (strcmp(name, "_get_constants") == 0) return virt_KIND_SCRIPT__get_constants;
        if (strcmp(name, "_get_method_info") == 0) return virt_KIND_SCRIPT__get_method_info;
        if (strcmp(name, "_has_property_default_value") == 0) return virt_KIND_SCRIPT__has_property_default_value;
        if (strcmp(name, "_get_property_default_value") == 0) return virt_KIND_SCRIPT__get_property_default_value;
        if (strcmp(name, "_get_rpc_config") == 0) return virt_KIND_SCRIPT__get_rpc_config;
    } else if (kind == KIND_LOADER) {
        if (strcmp(name, "_get_recognized_extensions") == 0) return virt_KIND_LOADER__get_recognized_extensions;
        if (strcmp(name, "_handles_type") == 0) return virt_KIND_LOADER__handles_type;
        if (strcmp(name, "_get_resource_type") == 0) return virt_KIND_LOADER__get_resource_type;
        if (strcmp(name, "_load") == 0) return virt_KIND_LOADER__load;
    } else if (kind == KIND_SAVER) {
        if (strcmp(name, "_save") == 0) return virt_KIND_SAVER__save;
        if (strcmp(name, "_set_uid") == 0) return virt_KIND_SAVER__set_uid;
        if (strcmp(name, "_recognize") == 0) return virt_KIND_SAVER__recognize;
        if (strcmp(name, "_get_recognized_extensions") == 0) return virt_KIND_SAVER__get_recognized_extensions;
        if (strcmp(name, "_recognize_path") == 0) return virt_KIND_SAVER__recognize_path;
    } else if (kind == KIND_HIGHLIGHT) {
        if (strcmp(name, "_get_name") == 0) return virt_KIND_HIGHLIGHT__get_name;
        if (strcmp(name, "_get_supported_languages") == 0) return virt_KIND_HIGHLIGHT__get_supported_languages;
        if (strcmp(name, "_create") == 0) return virt_KIND_HIGHLIGHT__create;
        if (strcmp(name, "_update_cache") == 0) return virt_KIND_HIGHLIGHT__update_cache;
        if (strcmp(name, "_get_line_syntax_highlighting") == 0) return virt_KIND_HIGHLIGHT__get_line_syntax_highlighting;
    }
    return NULL;
}

static GDExtensionClassCallVirtual get_virtual(void *userdata, GDExtensionConstStringNamePtr name, uint32_t hash) {
    int kind = (int) (intptr_t) userdata;
    static const char *names[] = {
        "_get_name", "_get_type", "_get_extension", "_get_recognized_extensions", "_validate", "_create_script", "_make_template",
        "_init", "_finish", "_frame", "_thread_enter", "_thread_exit",
        "_get_reserved_words", "_get_comment_delimiters", "_get_string_delimiters",
        "_get_public_functions", "_get_public_constants", "_get_public_annotations", "_get_built_in_templates",
        "_debug_get_current_stack_info", "_get_global_class_name", "_handles_global_class_type",
        "_is_control_flow_keyword", "_supports_documentation", "_supports_builtin_mode", "_is_using_templates",
        "_has_named_classes", "_can_inherit_from_file", "_can_make_function", "_overrides_external_editor",
        "_complete_code", "_lookup_code", "_auto_indent_code", "_validate_path",
        "_profiling_start", "_profiling_stop", "_profiling_get_accumulated_data", "_profiling_get_frame_data",
        "_reload_all_scripts",
        "_can_instantiate", "_get_instance_base_type", "_get_base_script", "_get_global_name", "_inherits_script", "_instance_create", "_placeholder_instance_create",
        "_get_source_code", "_has_source_code", "_set_source_code", "_reload",
        "_get_language", "_has_method", "_has_static_method", "_update_exports", "_has_script_signal", "_get_script_property_list", "_get_script_signal_list",
        "_get_script_method_list", "_is_valid", "_is_tool", "_is_abstract", "_is_placeholder_fallback_enabled",
        "_editor_can_reload_from_file", "_get_members", "_get_documentation", "_get_doc_class_name",
        "_get_class_icon_path", "_get_member_line", "_get_script_method_argument_count", "_instance_has", "_placeholder_erased",
        "_get_constants", "_get_method_info", "_has_property_default_value", "_get_property_default_value", "_get_rpc_config",
        "_handles_type", "_get_resource_type", "_load",
        "_save", "_set_uid", "_recognize", "_recognize_path",
        "_get_supported_languages", "_create", "_update_cache", "_get_line_syntax_highlighting"
    };
    size_t i;
    (void) hash;
    for (i = 0; i < sizeof(names) / sizeof(names[0]); i++) {
        if (sn_eq(name, names[i])) {
            return pick(kind, names[i]);
        }
    }
    return NULL;
}

static GDExtensionObjectPtr make_object(const char *parent, const char *mine, int kind, void *data, GDExtensionBool notify) {
    unsigned char parent_sn[16];
    unsigned char mine_sn[16];
    GDExtensionObjectPtr obj;
    sn_new(parent_sn, parent);
    obj = classdb_construct(parent_sn);
    sn_free(parent_sn);
    if (obj == NULL) {
        return NULL;
    }
    sn_new(mine_sn, mine);
    object_set_instance(obj, mine_sn, data);
    sn_free(mine_sn);
    if (notify) {
        void *n = var_int(0);
        const void *args[1];
        args[0] = n;
        call_ok(obj, "notification", args, 1);
        variant_destroy(n);
        vfree(n);
    }
    (void) kind;
    return obj;
}

static GDExtensionObjectPtr create_lang(void *userdata, GDExtensionBool notify) {
    ScriptData *data = (ScriptData *) calloc(1, sizeof(ScriptData));
    (void) userdata;
    data->kind = KIND_LANG;
    return make_object("ScriptLanguageExtension", "RoseGoldLanguage", KIND_LANG, data, notify);
}

static GDExtensionObjectPtr create_script_obj(void *userdata, GDExtensionBool notify) {
    ScriptData *data = (ScriptData *) calloc(1, sizeof(ScriptData));
    GDExtensionObjectPtr obj;
    (void) userdata;
    data->kind = KIND_SCRIPT;
    obj = make_object("ScriptExtension", "RoseGoldScript", KIND_SCRIPT, data, notify);
    data->godot = obj;
    g_last_script = data;
    if (obj != NULL && object_get_id != NULL && g_nscript_ids < 64) {
        g_script_ids[g_nscript_ids] = object_get_id(obj);
        g_script_data[g_nscript_ids] = data;
        g_nscript_ids++;
    }
    return obj;
}

static GDExtensionObjectPtr create_loader(void *userdata, GDExtensionBool notify) {
    ScriptData *data = (ScriptData *) calloc(1, sizeof(ScriptData));
    (void) userdata;
    data->kind = KIND_LOADER;
    return make_object("ResourceFormatLoader", "RoseGoldLoader", KIND_LOADER, data, notify);
}

static GDExtensionObjectPtr create_saver(void *userdata, GDExtensionBool notify) {
    ScriptData *data = (ScriptData *) calloc(1, sizeof(ScriptData));
    (void) userdata;
    data->kind = KIND_SAVER;
    return make_object("ResourceFormatSaver", "RoseGoldSaver", KIND_SAVER, data, notify);
}

static GDExtensionObjectPtr create_highlighter(void *userdata, GDExtensionBool notify) {
    HighlighterData *data = (HighlighterData *) calloc(1, sizeof(HighlighterData));
    GDExtensionObjectPtr obj;
    (void) userdata;
    if (data == NULL) {
        return NULL;
    }
    data->kind = KIND_HIGHLIGHT;
    obj = make_object("EditorSyntaxHighlighter", "RoseGoldHighlighter", KIND_HIGHLIGHT, data, notify);
    if (obj == NULL) {
        free(data);
        return NULL;
    }
    data->godot = obj;
    return obj;
}

static int g_hi_reg;

static void drop_one_ref(GDExtensionObjectPtr obj);

static void ensure_highlighter(void) {
    unsigned char sn[16];
    GDExtensionObjectPtr editor;
    GDExtensionObjectPtr scripts;
    GDExtensionObjectPtr hi;
    void *hi_var;
    const void *args[1];
    if (g_hi_reg) {
        return;
    }
    if (!editor_hint()) {
        g_hi_reg = 1;
        return;
    }
    sn_new(sn, "EditorInterface");
    editor = global_get_singleton != NULL ? global_get_singleton(sn) : NULL;
    sn_free(sn);
    scripts = hi_object(editor, "get_script_editor");
    if (scripts == NULL) {
        return;
    }
    hi = create_highlighter(NULL, 1);
    if (hi == NULL) {
        return;
    }
    hi_var = var_object(hi);
    args[0] = hi_var;
    call_ok(scripts, "register_syntax_highlighter", args, 1);
    variant_destroy(hi_var);
    vfree(hi_var);
    drop_one_ref(hi);
    g_hi_reg = 1;
}

static void forget_script(ScriptData *data) {
    int i;
    for (i = 0; i < g_nscript_ids; i++) {
        if (g_script_data[i] == data) {
            g_nscript_ids--;
            g_script_ids[i] = g_script_ids[g_nscript_ids];
            g_script_data[i] = g_script_data[g_nscript_ids];
            g_script_ids[g_nscript_ids] = 0;
            g_script_data[g_nscript_ids] = NULL;
            return;
        }
    }
}

static void free_members(Member *items, int n) {
    int i;
    for (i = 0; i < n; i++) {
        free(items[i].name);
        free(items[i].type);
        free(items[i].init);
    }
    free(items);
}

typedef struct ResField {
    char *name;
    char *type;
    void *value;
} ResField;

typedef struct ResData {
    int kind;
    char *struct_name;
    ResField *fields;
    int nfields;
    GDObjectInstanceID id;
} ResData;

static ResData *g_res[256];
static GDObjectInstanceID g_res_ids[256];
static int g_nres;
static GDObjectInstanceID g_owned_ids[256];
static int g_nowned;

static void res_track(ResData *res, GDExtensionObjectPtr obj) {
    if (res == NULL || obj == NULL || object_get_id == NULL || g_nres >= 256) {
        return;
    }
    res->id = object_get_id(obj);
    g_res_ids[g_nres] = res->id;
    g_res[g_nres] = res;
    g_nres++;
}

static void res_forget(ResData *res) {
    int i;
    for (i = 0; i < g_nres; i++) {
        if (g_res[i] == res) {
            g_nres--;
            g_res[i] = g_res[g_nres];
            g_res_ids[i] = g_res_ids[g_nres];
            return;
        }
    }
}

static ResData *res_from_object(GDExtensionObjectPtr obj) {
    GDObjectInstanceID id;
    int i;
    if (obj == NULL || object_get_id == NULL) {
        return NULL;
    }
    id = object_get_id(obj);
    for (i = 0; i < g_nres; i++) {
        if (g_res_ids[i] == id) {
            return g_res[i];
        }
    }
    return NULL;
}

static void res_free_fields(ResData *res) {
    int i;
    if (res == NULL) {
        return;
    }
    for (i = 0; i < res->nfields; i++) {
        free(res->fields[i].name);
        free(res->fields[i].type);
        if (res->fields[i].value != NULL) {
            variant_destroy(res->fields[i].value);
            vfree(res->fields[i].value);
        }
    }
    free(res->fields);
    res->fields = NULL;
    res->nfields = 0;
}

static void res_free(ResData *res) {
    if (res == NULL) {
        return;
    }
    res_forget(res);
    res_free_fields(res);
    free(res->struct_name);
    free(res);
}

static void *variant_copy(GDExtensionConstVariantPtr src) {
    void *dst = vbuf();
    if (dst == NULL) {
        return NULL;
    }
    if (variant_new_copy != NULL && src != NULL) {
        variant_new_copy(dst, src);
    } else {
        variant_nil(dst);
    }
    return dst;
}

static void *zero_variant(const char *type) {
    if (type != NULL && strcmp(type, "Float") == 0) {
        return var_float(0);
    }
    if (type != NULL && strcmp(type, "Int") == 0) {
        return var_int(0);
    }
    if (type != NULL && strcmp(type, "Bool") == 0) {
        return var_bool(0);
    }
    if (type != NULL && strcmp(type, "Vec2") == 0 && from_vector2 != NULL) {
        float xy[2] = {0, 0};
        void *var = vbuf();
        from_vector2(var, xy);
        return var;
    }
    if (type != NULL && strcmp(type, "Vector2i") == 0 && from_vector2i != NULL) {
        int32_t xy[2] = {0, 0};
        void *var = vbuf();
        from_vector2i(var, xy);
        return var;
    }
    if (type != NULL && strcmp(type, "Vector3") == 0 && from_vector3 != NULL) {
        float xyz[3] = {0, 0, 0};
        void *var = vbuf();
        from_vector3(var, xyz);
        return var;
    }
    if (type != NULL && strcmp(type, "Vector3i") == 0 && from_vector3i != NULL) {
        int32_t xyz[3] = {0, 0, 0};
        void *var = vbuf();
        from_vector3i(var, xyz);
        return var;
    }
    if (type != NULL && strcmp(type, "Vector4") == 0 && from_vector4 != NULL) {
        float xyzw[4] = {0, 0, 0, 0};
        void *var = vbuf();
        from_vector4(var, xyzw);
        return var;
    }
    if (type != NULL && strcmp(type, "Rect2") == 0 && from_rect2 != NULL) {
        float v4[4] = {0, 0, 0, 0};
        void *var = vbuf();
        from_rect2(var, v4);
        return var;
    }
    if (type != NULL && strcmp(type, "Rect2i") == 0 && from_rect2i != NULL) {
        int32_t v4[4] = {0, 0, 0, 0};
        void *var = vbuf();
        from_rect2i(var, v4);
        return var;
    }
    if (type != NULL && strcmp(type, "Transform2D") == 0 && from_transform2d != NULL) {
        float v6[6] = {1, 0, 0, 1, 0, 0};
        void *var = vbuf();
        from_transform2d(var, v6);
        return var;
    }
    if (type != NULL && strcmp(type, "Vector4i") == 0 && from_vector4i != NULL) {
        int32_t xyzw[4] = {0, 0, 0, 0};
        void *var = vbuf();
        from_vector4i(var, xyzw);
        return var;
    }
    if (type != NULL && strcmp(type, "Plane") == 0 && from_plane != NULL) {
        float v4[4] = {0, 0, 1, 0};
        void *var = vbuf();
        from_plane(var, v4);
        return var;
    }
    if (type != NULL && strcmp(type, "Quaternion") == 0 && from_quaternion != NULL) {
        float v4[4] = {0, 0, 0, 1};
        void *var = vbuf();
        from_quaternion(var, v4);
        return var;
    }
    if (type != NULL && strcmp(type, "AABB") == 0 && from_aabb != NULL) {
        float v6[6] = {0, 0, 0, 0, 0, 0};
        void *var = vbuf();
        from_aabb(var, v6);
        return var;
    }
    if (type != NULL && strcmp(type, "Basis") == 0 && from_basis != NULL) {
        float rows[9] = {1, 0, 0, 0, 1, 0, 0, 0, 1};
        void *var = vbuf();
        from_basis(var, rows);
        return var;
    }
    if (type != NULL && strcmp(type, "Transform3D") == 0 && from_transform3d != NULL) {
        float v12[12] = {1, 0, 0, 0, 1, 0, 0, 0, 1, 0, 0, 0};
        void *var = vbuf();
        from_transform3d(var, v12);
        return var;
    }
    if (type != NULL && strcmp(type, "Projection") == 0 && from_projection != NULL) {
        float cols[16] = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
        void *var = vbuf();
        from_projection(var, cols);
        return var;
    }
    if (type != NULL && strcmp(type, "Color") == 0 && from_color != NULL) {
        float rgba[4] = {1, 1, 1, 1};
        void *var = vbuf();
        from_color(var, rgba);
        return var;
    }
    return var_string("");
}

static void res_add_field(ResData *res, const char *name, const char *type, void *value) {
    ResField *grown;
    if (res == NULL || name == NULL) {
        if (value != NULL) {
            variant_destroy(value);
            vfree(value);
        }
        return;
    }
    grown = (ResField *) realloc(res->fields, (size_t) (res->nfields + 1) * sizeof(ResField));
    if (grown == NULL) {
        if (value != NULL) {
            variant_destroy(value);
            vfree(value);
        }
        return;
    }
    res->fields = grown;
    res->fields[res->nfields].name = strdup(name);
    res->fields[res->nfields].type = strdup(type != NULL ? type : "");
    res->fields[res->nfields].value = value != NULL ? value : var_string("");
    res->nfields++;
}

static GDExtensionObjectPtr bind_res(ResData *data, GDExtensionBool notify);

static int g_struct_default_depth;

static void *struct_default_variant(const char *type) {
    StructInfo *info = struct_lookup(type);
    ResData *data;
    GDExtensionObjectPtr obj;
    int i;
    if (info == NULL || g_struct_default_depth >= 4) {
        return var_string("");
    }
    g_struct_default_depth++;
    data = (ResData *) calloc(1, sizeof(ResData));
    if (data == NULL) {
        g_struct_default_depth--;
        return var_string("");
    }
    data->kind = KIND_RES;
    data->struct_name = strdup(info->name != NULL ? info->name : "");
    for (i = 0; i < info->nfields; i++) {
        const char *ft = info->fields[i].type != NULL ? info->fields[i].type : "";
        void *value;
        if (struct_lookup(ft) != NULL && strcmp(ft, type) != 0) {
            value = struct_default_variant(ft);
        } else {
            value = zero_variant(ft);
        }
        res_add_field(data, info->fields[i].name, ft, value);
    }
    g_struct_default_depth--;
    obj = bind_res(data, 1);
    if (obj == NULL) {
        return var_string("");
    }
    {
        void *var = var_object(obj);
        drop_one_ref(obj);
        return var;
    }
}

static GDExtensionObjectPtr bind_res(ResData *data, GDExtensionBool notify) {
    GDExtensionObjectPtr obj = make_object("Resource", "RoseGoldResource", KIND_RES, data, notify);
    if (obj == NULL) {
        res_free(data);
        return NULL;
    }
    res_track(data, obj);
    return obj;
}

static GDExtensionObjectPtr create_res(void *userdata, GDExtensionBool notify) {
    ResData *data = (ResData *) calloc(1, sizeof(ResData));
    (void) userdata;
    if (data == NULL) {
        return NULL;
    }
    data->kind = KIND_RES;
    data->struct_name = strdup("");
    return bind_res(data, notify);
}

static char *sn_text(GDExtensionConstStringNamePtr name) {
    unsigned char var[40];
    unsigned char str[16];
    char *text;
    if (name == NULL || from_stringname == NULL) {
        return strdup("");
    }
    memset(var, 0, sizeof(var));
    from_stringname(var, (GDExtensionTypePtr) name);
    memset(str, 0, sizeof(str));
    variant_stringify(var, str);
    variant_destroy(var);
    text = string_dup(str);
    dtor_string(str);
    return text != NULL ? text : strdup("");
}

static const char *type_name_of_variant(GDExtensionConstVariantPtr value) {
    GDExtensionVariantType ty = value != NULL ? variant_get_type(value) : GDEXTENSION_VARIANT_TYPE_NIL;
    if (ty == GDEXTENSION_VARIANT_TYPE_BOOL) {
        return "Bool";
    }
    if (ty == GDEXTENSION_VARIANT_TYPE_INT) {
        return "Int";
    }
    if (ty == GDEXTENSION_VARIANT_TYPE_FLOAT) {
        return "Float";
    }
    if (ty == GDEXTENSION_VARIANT_TYPE_STRING) {
        return "String";
    }
    if (ty == GDEXTENSION_VARIANT_TYPE_VECTOR2) {
        return "Vec2";
    }
    if (ty == GDEXTENSION_VARIANT_TYPE_VECTOR2I) {
        return "Vector2i";
    }
    if (ty == GDEXTENSION_VARIANT_TYPE_VECTOR3) {
        return "Vector3";
    }
    if (ty == GDEXTENSION_VARIANT_TYPE_VECTOR3I) {
        return "Vector3i";
    }
    if (ty == GDEXTENSION_VARIANT_TYPE_VECTOR4) {
        return "Vector4";
    }
    if (ty == GDEXTENSION_VARIANT_TYPE_RECT2) {
        return "Rect2";
    }
    if (ty == GDEXTENSION_VARIANT_TYPE_RECT2I) {
        return "Rect2i";
    }
    if (ty == GDEXTENSION_VARIANT_TYPE_TRANSFORM2D) {
        return "Transform2D";
    }
    if (ty == GDEXTENSION_VARIANT_TYPE_COLOR) {
        return "Color";
    }
    if (ty == GDEXTENSION_VARIANT_TYPE_OBJECT && to_object != NULL) {
        GDExtensionObjectPtr obj = NULL;
        ResData *nested;
        to_object(&obj, (GDExtensionVariantPtr) value);
        nested = res_from_object(obj);
        if (nested != NULL && nested->struct_name != NULL && nested->struct_name[0] != 0) {
            return nested->struct_name;
        }
    }
    return "";
}

static GDExtensionBool res_set(GDExtensionClassInstancePtr inst, GDExtensionConstStringNamePtr name, GDExtensionConstVariantPtr value) {
    ResData *res = (ResData *) inst;
    char *field;
    int i;
    if (res == NULL || res->kind != KIND_RES || value == NULL) {
        return 0;
    }
    if (sn_eq(name, "resource_name") || sn_eq(name, "resource_path") || sn_eq(name, "resource_local_to_scene")
            || sn_eq(name, "resource_scene_unique_id") || sn_eq(name, "script")) {
        return 0;
    }
    if (sn_eq(name, "struct_type")) {
        unsigned char str[16];
        char *text;
        memset(str, 0, sizeof(str));
        variant_stringify(value, str);
        text = string_dup(str);
        dtor_string(str);
        free(res->struct_name);
        res->struct_name = text != NULL ? text : strdup("");
        return 1;
    }
    field = sn_text(name);
    for (i = 0; i < res->nfields; i++) {
        if (res->fields[i].name != NULL && strcmp(res->fields[i].name, field) == 0) {
            if (res->fields[i].value != NULL) {
                variant_destroy(res->fields[i].value);
                vfree(res->fields[i].value);
            }
            res->fields[i].value = variant_copy(value);
            free(field);
            return 1;
        }
    }
    res_add_field(res, field, type_name_of_variant(value), variant_copy(value));
    free(field);
    return 1;
}

static GDExtensionBool res_get(GDExtensionClassInstancePtr inst, GDExtensionConstStringNamePtr name, GDExtensionVariantPtr ret) {
    ResData *res = (ResData *) inst;
    int i;
    if (res == NULL || res->kind != KIND_RES || ret == NULL) {
        return 0;
    }
    if (sn_eq(name, "struct_type")) {
        void *text = var_string(res->struct_name != NULL ? res->struct_name : "");
        if (variant_new_copy != NULL) {
            variant_new_copy(ret, text);
        } else {
            variant_nil(ret);
        }
        variant_destroy(text);
        vfree(text);
        return 1;
    }
    for (i = 0; i < res->nfields; i++) {
        if (res->fields[i].name != NULL && sn_eq(name, res->fields[i].name)) {
            if (res->fields[i].value != NULL && variant_new_copy != NULL) {
                variant_new_copy(ret, res->fields[i].value);
            } else {
                variant_nil(ret);
            }
            return 1;
        }
    }
    return 0;
}

static void *res_sn(const char *text) {
    unsigned char *sn = (unsigned char *) calloc(1, 16);
    if (sn != NULL) {
        sn_new(sn, text != NULL ? text : "");
    }
    return sn;
}

static void *res_gdstr(const char *text) {
    unsigned char *str = (unsigned char *) calloc(1, 16);
    if (str != NULL && string_new_utf8 != NULL) {
        string_new_utf8(str, text != NULL ? text : "");
    }
    return str;
}

static const GDExtensionPropertyInfo *res_props(GDExtensionClassInstancePtr inst, uint32_t *count) {
    ResData *res = (ResData *) inst;
    GDExtensionPropertyInfo *list;
    int n;
    int i;
    if (count != NULL) {
        *count = 0;
    }
    if (res == NULL || res->kind != KIND_RES) {
        return NULL;
    }
    n = res->nfields + 1;
    list = (GDExtensionPropertyInfo *) calloc((size_t) n, sizeof(GDExtensionPropertyInfo));
    if (list == NULL) {
        return NULL;
    }
    list[0].type = GDEXTENSION_VARIANT_TYPE_STRING;
    list[0].name = res_sn("struct_type");
    list[0].class_name = res_sn("");
    list[0].hint = 0;
    list[0].hint_string = res_gdstr("");
    list[0].usage = RG_USAGE_STORAGE;
    for (i = 0; i < res->nfields; i++) {
        int vty = rose_variant_type(res->fields[i].type);
        list[i + 1].type = (GDExtensionVariantType) vty;
        list[i + 1].name = res_sn(res->fields[i].name);
        list[i + 1].class_name = res_sn(vty == GDEXTENSION_VARIANT_TYPE_OBJECT ? "RoseGoldResource" : "");
        list[i + 1].hint = vty == GDEXTENSION_VARIANT_TYPE_OBJECT ? RG_HINT_RESOURCE : 0;
        list[i + 1].hint_string = res_gdstr(vty == GDEXTENSION_VARIANT_TYPE_OBJECT ? "RoseGoldResource" : "");
        list[i + 1].usage = RG_USAGE_DEFAULT;
    }
    if (count != NULL) {
        *count = (uint32_t) n;
    }
    return list;
}

static void res_props_free(GDExtensionClassInstancePtr inst, const GDExtensionPropertyInfo *list, uint32_t count) {
    uint32_t i;
    (void) inst;
    if (list == NULL) {
        return;
    }
    for (i = 0; i < count; i++) {
        if (list[i].name != NULL) {
            sn_free((void *) list[i].name);
            free((void *) list[i].name);
        }
        if (list[i].class_name != NULL) {
            sn_free((void *) list[i].class_name);
            free((void *) list[i].class_name);
        }
        if (list[i].hint_string != NULL) {
            if (dtor_string != NULL) {
                dtor_string((void *) list[i].hint_string);
            }
            free((void *) list[i].hint_string);
        }
    }
    free((void *) list);
}

static void clear_structs(ScriptData *script) {
    int i;
    int j;
    if (script == NULL) {
        return;
    }
    for (i = 0; i < script->nstructs; i++) {
        for (j = 0; j < script->structs[i].nfields; j++) {
            free(script->structs[i].fields[j].name);
            free(script->structs[i].fields[j].type);
        }
        free(script->structs[i].fields);
        free(script->structs[i].name);
    }
    free(script->structs);
    script->structs = NULL;
    script->nstructs = 0;
}

static void add_struct_field(ScriptData *script, const char *sname, const char *fname, const char *ftype, int is_data) {
    StructInfo *info = NULL;
    StructField *grown;
    int i;
    if (script == NULL || sname == NULL || sname[0] == 0) {
        return;
    }
    for (i = 0; i < script->nstructs; i++) {
        if (script->structs[i].name != NULL && strcmp(script->structs[i].name, sname) == 0) {
            info = &script->structs[i];
            break;
        }
    }
    if (info == NULL) {
        StructInfo *block = (StructInfo *) realloc(script->structs, (size_t) (script->nstructs + 1) * sizeof(StructInfo));
        if (block == NULL) {
            return;
        }
        script->structs = block;
        info = &script->structs[script->nstructs];
        memset(info, 0, sizeof(*info));
        info->name = strdup(sname);
        info->is_data = is_data ? 1 : 0;
        script->nstructs++;
    } else if (is_data) {
        info->is_data = 1;
    }
    if (fname == NULL || fname[0] == 0) {
        return;
    }
    grown = (StructField *) realloc(info->fields, (size_t) (info->nfields + 1) * sizeof(StructField));
    if (grown == NULL) {
        return;
    }
    info->fields = grown;
    info->fields[info->nfields].name = strdup(fname);
    info->fields[info->nfields].type = strdup(ftype != NULL ? ftype : "");
    info->nfields++;
}

static int scan_ident_start(char c) {
    return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_';
}

static int scan_ident(char c) {
    return scan_ident_start(c) || (c >= '0' && c <= '9');
}

static int scan_skip(const char *s, int i, int n) {
    while (i < n) {
        if (s[i] == ' ' || s[i] == '\t' || s[i] == '\r' || s[i] == '\n') {
            i++;
            continue;
        }
        if (s[i] == '/' && i + 1 < n && s[i + 1] == '/') {
            while (i < n && s[i] != '\n') {
                i++;
            }
            continue;
        }
        if (s[i] == '#' && !(i + 1 < n && s[i + 1] == '/')) {
            while (i < n && s[i] != '\n') {
                i++;
            }
            continue;
        }
        if (s[i] == '/' && i + 1 < n && s[i + 1] == '#') {
            i += 2;
            while (i + 1 < n && !(s[i] == '#' && s[i + 1] == '/')) {
                i++;
            }
            if (i + 1 < n) {
                i += 2;
            }
            continue;
        }
        break;
    }
    return i;
}

static int scan_word(const char *s, int i, int n, char *out, int cap) {
    int k = 0;
    i = scan_skip(s, i, n);
    if (i >= n || !scan_ident_start(s[i])) {
        if (cap > 0) {
            out[0] = 0;
        }
        return i;
    }
    while (i < n && scan_ident(s[i])) {
        if (k < cap - 1) {
            out[k++] = s[i];
        }
        i++;
    }
    out[k] = 0;
    return i;
}

static int scan_skip_member(const char *s, int i, int n) {
    int depth = 0;
    int in_string = 0;
    while (i < n) {
        if (in_string) {
            if (s[i] == '\\' && i + 1 < n) {
                i += 2;
                continue;
            }
            if (s[i] == '"') {
                in_string = 0;
            }
            i++;
            continue;
        }
        if (s[i] == '"') {
            in_string = 1;
            i++;
            continue;
        }
        if (s[i] == '{') {
            depth++;
        } else if (s[i] == '}') {
            if (depth == 0) {
                return i;
            }
            depth--;
            if (depth == 0) {
                return i + 1;
            }
        } else if (s[i] == ';' && depth == 0) {
            return i + 1;
        }
        i++;
    }
    return i;
}

static void scan_structs(ScriptData *script) {
    const char *s;
    int n;
    int i = 0;
    int depth = 0;
    int in_string = 0;
    if (script == NULL || script->source == NULL || script->source[0] == 0) {
        return;
    }
    clear_structs(script);
    s = script->source;
    n = (int) strlen(s);
    while (i < n) {
        char word[64];
        if (in_string) {
            if (s[i] == '\\' && i + 1 < n) {
                i += 2;
                continue;
            }
            if (s[i] == '"') {
                in_string = 0;
            }
            i++;
            continue;
        }
        i = scan_skip(s, i, n);
        if (i >= n) {
            break;
        }
        if (s[i] == '"') {
            in_string = 1;
            i++;
            continue;
        }
        if (s[i] == '{') {
            depth++;
            i++;
            continue;
        }
        if (s[i] == '}') {
            if (depth > 0) {
                depth--;
            }
            i++;
            continue;
        }
        if (depth == 0 && scan_ident_start(s[i])) {
            int next = scan_word(s, i, n, word, (int) sizeof(word));
            if (strcmp(word, "struct") == 0 || strcmp(word, "data") == 0) {
                int as_data = strcmp(word, "data") == 0;
                char sname[64];
                char fname[64];
                char ftype[96];
                int body;
                next = scan_word(s, next, n, sname, (int) sizeof(sname));
                body = 0;
                while (next < n && body == 0) {
                    next = scan_skip(s, next, n);
                    if (next < n && s[next] == '{') {
                        body = 1;
                        next++;
                        break;
                    }
                    if (next < n && scan_ident_start(s[next])) {
                        next = scan_word(s, next, n, fname, (int) sizeof(fname));
                        continue;
                    }
                    if (next < n) {
                        next++;
                    }
                }
                add_struct_field(script, sname, "", "", as_data);
                while (next < n) {
                    int look;
                    next = scan_skip(s, next, n);
                    if (next >= n) {
                        break;
                    }
                    if (s[next] == '}') {
                        next++;
                        break;
                    }
                    next = scan_word(s, next, n, fname, (int) sizeof(fname));
                    if (fname[0] == 0) {
                        break;
                    }
                    look = scan_skip(s, next, n);
                    if (look < n && s[look] == ':') {
                        int t = 0;
                        look++;
                        while (look < n && s[look] != ';' && t < (int) sizeof(ftype) - 1) {
                            if (s[look] != ' ' && s[look] != '\t' && s[look] != '\r' && s[look] != '\n') {
                                ftype[t++] = s[look];
                            }
                            look++;
                        }
                        ftype[t] = 0;
                        add_struct_field(script, sname, fname, ftype, as_data);
                        next = look < n && s[look] == ';' ? look + 1 : look;
                    } else {
                        next = scan_skip_member(s, next, n);
                    }
                }
            }
            i = next;
            continue;
        }
        i++;
    }
}

static int scan_type_name(const char *s, int i, int n, char *out, int cap) {
    int k = 0;
    i = scan_skip(s, i, n);
    if (i >= n || !scan_ident_start(s[i])) {
        if (cap > 0) {
            out[0] = 0;
        }
        return i;
    }
    while (i < n && (scan_ident(s[i]) || s[i] == '[' || s[i] == ']')) {
        if (k + 1 < cap) {
            out[k++] = s[i];
        }
        i++;
    }
    if (cap > 0) {
        out[k] = 0;
    }
    return i;
}

static int skip_stmt(const char *s, int i, int n) {
    int depth = 0;
    int in_string = 0;
    while (i < n) {
        if (in_string) {
            if (s[i] == '\\' && i + 1 < n) {
                i += 2;
                continue;
            }
            if (s[i] == '"') {
                in_string = 0;
            }
            i++;
            continue;
        }
        if (s[i] == '"') {
            in_string = 1;
            i++;
            continue;
        }
        if (s[i] == '{') {
            depth++;
            i++;
            continue;
        }
        if (s[i] == '}') {
            if (depth == 0) {
                return i;
            }
            depth--;
            i++;
            continue;
        }
        if (s[i] == ';' && depth == 0) {
            return i + 1;
        }
        i++;
    }
    return i;
}

static Member *add_export_var(ScriptData *script, const char *name, const char *type) {
    Member *hit;
    Member *items;
    if (script == NULL || name == NULL || name[0] == 0) {
        return NULL;
    }
    hit = find_member(script->vars, script->nvars, name);
    if (hit == NULL) {
        items = (Member *) realloc(script->vars, (size_t) (script->nvars + 1) * sizeof(Member));
        if (items == NULL) {
            return NULL;
        }
        script->vars = items;
        hit = &items[script->nvars];
        memset(hit, 0, sizeof(Member));
        hit->name = strdup(name != NULL ? name : "");
        hit->type = strdup("");
        script->nvars++;
    }
    hit->exported = 1;
    if (type != NULL && type[0] != 0) {
        free(hit->type);
        hit->type = strdup(type);
    }
    return hit;
}

static void scan_exports(ScriptData *script) {
    const char *s;
    int n;
    int i = 0;
    int depth = 0;
    int in_string = 0;
    int v;
    if (script == NULL) {
        return;
    }
    if ((script->source == NULL || script->source[0] == 0) && script->path != NULL && script->path[0] != 0) {
        free(script->source);
        script->source = read_text_file(script->path);
    }
    if (script->source == NULL || script->source[0] == 0) {
        return;
    }
    for (v = 0; v < script->nvars; v++) {
        script->vars[v].exported = 0;
    }
    s = script->source;
    n = (int) strlen(s);
    while (i < n) {
        char word[64];
        if (in_string) {
            if (s[i] == '\\' && i + 1 < n) {
                i += 2;
                continue;
            }
            if (s[i] == '"') {
                in_string = 0;
            }
            i++;
            continue;
        }
        i = scan_skip(s, i, n);
        if (i >= n) {
            break;
        }
        if (s[i] == '"') {
            in_string = 1;
            i++;
            continue;
        }
        if (s[i] == '{') {
            depth++;
            i++;
            continue;
        }
        if (s[i] == '}') {
            if (depth > 0) {
                depth--;
            }
            i++;
            continue;
        }
        if (scan_ident_start(s[i])) {
            int next = scan_word(s, i, n, word, (int) sizeof(word));
            if (depth == 1 && strcmp(word, "export") == 0) {
                char name[64];
                char ty[96];
                next = scan_word(s, next, n, word, (int) sizeof(word));
                if (strcmp(word, "var") == 0) {
                    Member *hit;
                    next = scan_word(s, next, n, name, (int) sizeof(name));
                    next = scan_skip(s, next, n);
                    ty[0] = 0;
                    if (next < n && s[next] == ':') {
                        next++;
                        next = scan_type_name(s, next, n, ty, (int) sizeof(ty));
                    }
                    hit = add_export_var(script, name, ty);
                    next = scan_skip(s, next, n);
                    if (hit != NULL) {
                        free(hit->init);
                        hit->init = NULL;
                        hit->has_number = 0;
                    }
                    if (hit != NULL && next < n && s[next] == '=') {
                        int start;
                        int endi;
                        const char *num;
                        char *end = NULL;
                        next++;
                        next = scan_skip(s, next, n);
                        start = next;
                        num = s + next;
                        if (next + 4 <= n && strncmp(num, "true", 4) == 0 && (next + 4 >= n || !scan_ident(s[next + 4]))) {
                            hit->has_number = 1;
                            hit->number = 1;
                        } else if (next + 5 <= n && strncmp(num, "false", 5) == 0 && (next + 5 >= n || !scan_ident(s[next + 5]))) {
                            hit->has_number = 1;
                            hit->number = 0;
                        } else if (next < n && (s[next] == '-' || (s[next] >= '0' && s[next] <= '9'))) {
                            hit->number = strtod(num, &end);
                            if (end != NULL && end != num) {
                                hit->has_number = 1;
                            }
                        }
                        next = skip_stmt(s, start, n);
                        endi = next;
                        if (endi > start && s[endi - 1] == ';') {
                            endi--;
                        }
                        while (endi > start && (s[endi - 1] == ' ' || s[endi - 1] == '\t' || s[endi - 1] == '\r' || s[endi - 1] == '\n')) {
                            endi--;
                        }
                        if (endi > start) {
                            int len = endi - start;
                            char *text = (char *) malloc((size_t) len + 1);
                            if (text != NULL) {
                                memcpy(text, s + start, (size_t) len);
                                text[len] = 0;
                                hit->init = text;
                            }
                        }
                    } else {
                        next = skip_stmt(s, next, n);
                    }
                }
            }
            i = next;
            continue;
        }
        i++;
    }
}

static void rg_from_variant(RGValue *dest, GDExtensionConstVariantPtr v, ScriptApi *api);
static void *variant_from_rg(const RGValue *v);

static void fill_struct_from_res(RGValue *dest, ResData *res, ScriptApi *api) {
    RGValue fields[32];
    const char *names[32];
    int n = 0;
    int i;
    if (dest == NULL || res == NULL || api == NULL || api->new_struct == NULL) {
        if (dest != NULL) {
            memset(dest, 0, sizeof(*dest));
        }
        return;
    }
    for (i = 0; i < res->nfields && n < 32; i++) {
        memset(&fields[n], 0, sizeof(fields[n]));
        rg_from_variant(&fields[n], res->fields[i].value, api);
        names[n] = res->fields[i].name != NULL ? res->fields[i].name : "";
        n++;
    }
    {
        StructInfo *info = struct_lookup(res->struct_name);
        if (info != NULL && info->is_data && api->new_data != NULL) {
            api->new_data(dest, res->struct_name != NULL ? res->struct_name : "", fields, n, names);
        } else {
            api->new_struct(dest, res->struct_name != NULL ? res->struct_name : "", fields, n, names);
        }
    }
}

static int g_conv_depth;

static void *resource_from_struct(const RGValue *v, const char *type) {
    StructInfo *info;
    ResData *data;
    GDExtensionObjectPtr obj;
    int i;
    if (g_conv_depth >= 8 || g_api == NULL || g_api->get == NULL) {
        void *out = vbuf();
        variant_nil(out);
        return out;
    }
    info = struct_lookup(type);
    if (info == NULL) {
        void *out = vbuf();
        variant_nil(out);
        return out;
    }
    g_conv_depth++;
    data = (ResData *) calloc(1, sizeof(ResData));
    if (data == NULL) {
        g_conv_depth--;
        return var_string("");
    }
    data->kind = KIND_RES;
    data->struct_name = strdup(info->name != NULL ? info->name : "");
    for (i = 0; i < info->nfields; i++) {
        RGValue slot;
        memset(&slot, 0, sizeof(slot));
        g_api->get((RGValue *) v, info->fields[i].name, &slot);
        res_add_field(data, info->fields[i].name, info->fields[i].type, variant_from_rg(&slot));
    }
    obj = bind_res(data, 1);
    g_conv_depth--;
    if (obj == NULL) {
        return var_string("");
    }
    {
        void *var = var_object(obj);
        drop_one_ref(obj);
        return var;
    }
}

static void free_instance(void *userdata, GDExtensionClassInstancePtr inst) {
    ScriptData *script;
    (void) userdata;
    script = (ScriptData *) inst;
    if (script == NULL) {
        return;
    }
    if (script->kind == KIND_RES) {
        res_free((ResData *) inst);
        return;
    }
    if (script->kind == KIND_SCRIPT) {
        forget_script(script);
    }
    if (script->kind != KIND_SCRIPT) {
        free(script);
        return;
    }
    while (script->placeholders != NULL) {
        PlaceholderRef *dead = script->placeholders;
        script->placeholders = dead->next;
        free(dead);
    }
    free(script->source);
    free(script->path);
    free(script->base);
    free(script->class_name);
    free_members(script->methods, script->nmethods);
    free_members(script->vars, script->nvars);
    free_members(script->signals, script->nsignals);
    clear_structs(script);
    if (script->library != NULL) {
#if defined(_WIN32)
        FreeLibrary((HMODULE) script->library);
#else
        dlclose(script->library);
#endif
    }
    free(script);
}

static void register_class(const char *name, const char *parent, int kind, GDExtensionClassCreateInstance2 create) {
    unsigned char name_sn[16];
    unsigned char parent_sn[16];
    GDExtensionClassCreationInfo4 info;
    memset(&info, 0, sizeof(info));
    info.is_exposed = 1;
    info.create_instance_func = create;
    info.free_instance_func = free_instance;
    info.get_virtual_func = get_virtual;
    info.class_userdata = (void *) (intptr_t) kind;
    sn_new(name_sn, name);
    sn_new(parent_sn, parent);
    classdb_register(g_library, name_sn, parent_sn, &info);
    sn_free(name_sn);
    sn_free(parent_sn);
}

static Slot *slot_find(int64_t id);

static void *slot_get(int64_t id) {
    Slot *s = slot_find(id);
    return s != NULL ? s->ptr : NULL;
}

static Slot *slot_find(int64_t id) {
    Slot *s;
    for (s = g_slots; s != NULL; s = s->next) {
        if (s->id == id) {
            return s;
        }
    }
    return NULL;
}

static void slot_put(int64_t id, void *ptr) {
    Slot *s = slot_find(id);
    if (s != NULL) {
        s->ptr = ptr;
        return;
    }
    s = (Slot *) calloc(1, sizeof(Slot));
    s->id = id;
    s->ptr = ptr;
    s->next = g_slots;
    g_slots = s;
}

static void slot_del(int64_t id) {
    Slot **cur = &g_slots;
    while (*cur != NULL) {
        if ((*cur)->id == id) {
            Slot *dead = *cur;
            *cur = dead->next;
            free(dead);
            return;
        }
        cur = &(*cur)->next;
    }
}

static double variant_as_float(GDExtensionConstVariantPtr v) {
    GDExtensionVariantType ty = variant_get_type(v);
    if (ty == GDEXTENSION_VARIANT_TYPE_FLOAT) {
        double x = 0;
        to_float(&x, (GDExtensionVariantPtr) v);
        return x;
    }
    if (ty == GDEXTENSION_VARIANT_TYPE_INT) {
        int64_t n = 0;
        to_int(&n, (GDExtensionVariantPtr) v);
        return (double) n;
    }
    return 0;
}

enum { RG_CONV_DEPTH = 8, RG_CONV_LEN = 1024 };

static void rg_from_variant(RGValue *dest, GDExtensionConstVariantPtr v, ScriptApi *api);
static void *variant_from_rg(const RGValue *v);
static void *callable_from_fn(const RGValue *fn);

static void **g_callables;
static int g_ncallables;
static void (*g_fn_host)(RGValue *, void (*)(RGValue *, RGValue *, int32_t), int64_t);
static int64_t (*g_fn_host_id)(const RGValue *);

static void free_callables(void) {
    int i;
    for (i = 0; i < g_ncallables; i++) {
        if (g_callables[i] != NULL) {
            variant_destroy(g_callables[i]);
            vfree(g_callables[i]);
            g_callables[i] = NULL;
        }
    }
    free(g_callables);
    g_callables = NULL;
    g_ncallables = 0;
}

static int64_t box_callable(GDExtensionConstVariantPtr v) {
    void *copy;
    void **next;
    if (v == NULL || variant_new_copy == NULL) {
        return 0;
    }
    copy = vbuf();
    if (copy == NULL) {
        return 0;
    }
    variant_new_copy(copy, v);
    next = (void **) realloc(g_callables, (size_t) (g_ncallables + 1) * sizeof(void *));
    if (next == NULL) {
        variant_destroy(copy);
        vfree(copy);
        return 0;
    }
    g_callables = next;
    g_callables[g_ncallables++] = copy;
    return g_ncallables;
}

static void call_on_variant(void *self, const char *name, const RGValue *args, int argc, RGValue *dest) {
    void **vars;
    const void **ptrs;
    void *ret;
    unsigned char sn[16];
    GDExtensionCallError err;
    int i;
    if (dest != NULL) {
        memset(dest, 0, sizeof(*dest));
    }
    if (self == NULL || name == NULL || variant_call == NULL) {
        return;
    }
    if (argc < 0) {
        argc = 0;
    }
    if (argc > 16) {
        argc = 16;
    }
    vars = (void **) calloc((size_t) argc + 1, sizeof(void *));
    ptrs = (const void **) calloc((size_t) argc + 1, sizeof(void *));
    if (vars == NULL || ptrs == NULL) {
        free(vars);
        free(ptrs);
        return;
    }
    for (i = 0; i < argc; i++) {
        vars[i] = variant_from_rg(args != NULL ? &args[i] : NULL);
        ptrs[i] = vars[i];
    }
    ret = vbuf();
    sn_new(sn, name);
    memset(&err, 0, sizeof(err));
    variant_call(self, sn, (const GDExtensionConstVariantPtr *) ptrs, argc, ret, &err);
    sn_free(sn);
    if (err.error == 0 && dest != NULL) {
        rg_from_variant(dest, ret, g_api);
    }
    variant_destroy(ret);
    vfree(ret);
    for (i = 0; i < argc; i++) {
        variant_destroy(vars[i]);
        vfree(vars[i]);
    }
    free(vars);
    free(ptrs);
}

static void engine_callable_invoke(RGValue *dest, RGValue *args, int32_t argc) {
    int64_t id;
    if (argc < 1) {
        if (dest != NULL) {
            memset(dest, 0, sizeof(*dest));
        }
        return;
    }
    id = args[argc - 1].i;
    if (id <= 0 || id > g_ncallables) {
        if (dest != NULL) {
            memset(dest, 0, sizeof(*dest));
        }
        return;
    }
    call_on_variant(g_callables[id - 1], "call", args, argc - 1, dest);
}

static void on_godot_fn(const RGValue *fn, const char *name, const RGValue *args, int32_t argc, RGValue *dest) {
    void *self = variant_from_rg(fn);
    call_on_variant(self, name, args, argc, dest);
    variant_destroy(self);
    vfree(self);
}

static int64_t variant_size(GDExtensionConstVariantPtr v) {
    unsigned char sn[16];
    void *ret = vbuf();
    GDExtensionCallError err;
    int64_t n = 0;
    sn_new(sn, "size");
    memset(&err, 0, sizeof(err));
    variant_call((GDExtensionVariantPtr) v, sn, NULL, 0, ret, &err);
    sn_free(sn);
    if (err.error == 0 && to_int != NULL) {
        to_int(&n, ret);
    }
    variant_destroy(ret);
    vfree(ret);
    if (n < 0) {
        n = 0;
    }
    if (n > RG_CONV_LEN) {
        n = RG_CONV_LEN;
    }
    return n;
}

static void *indexed_get(GDExtensionConstVariantPtr v, int64_t index) {
    void *elem = vbuf();
    uint8_t valid = 0;
    uint8_t oob = 0;
    if (variant_get_indexed == NULL) {
        variant_nil(elem);
        return elem;
    }
    variant_get_indexed(v, (GDExtensionInt) index, elem, &valid, &oob);
    if (!valid) {
        variant_nil(elem);
    }
    return elem;
}

static void *keyed_get(GDExtensionConstVariantPtr v, GDExtensionConstVariantPtr key) {
    void *elem = vbuf();
    uint8_t valid = 0;
    if (variant_get_keyed == NULL) {
        variant_nil(elem);
        return elem;
    }
    variant_get_keyed(v, key, elem, &valid);
    if (!valid) {
        variant_nil(elem);
    }
    return elem;
}

static char *variant_text(GDExtensionConstVariantPtr v) {
    unsigned char str[16];
    char *text;
    memset(str, 0, sizeof(str));
    if (variant_get_type(v) == GDEXTENSION_VARIANT_TYPE_STRING && to_string != NULL) {
        to_string(str, (GDExtensionVariantPtr) v);
    } else {
        variant_stringify((GDExtensionVariantPtr) v, str);
    }
    text = string_dup(str);
    if (dtor_string != NULL) {
        dtor_string(str);
    }
    return text;
}

static void *fresh_builtin(GDExtensionVariantType type) {
    void *storage = empty_builtin(type);
    void *var = variant_of_builtin(type, storage);
    GDExtensionPtrDestructor dtor = get_ptr_dtor(type);
    if (dtor != NULL) {
        dtor(storage);
    }
    vfree(storage);
    return var;
}

static int is_packed_type(int ty) {
    return ty >= GDEXTENSION_VARIANT_TYPE_PACKED_BYTE_ARRAY && ty <= GDEXTENSION_VARIANT_TYPE_PACKED_VECTOR4_ARRAY;
}

static void *pack_from_array(void *array_variant, GDExtensionVariantType packed) {
    int64_t n;
    int64_t i;
    void *var = fresh_builtin(packed);
    n = variant_size(array_variant);
    for (i = 0; i < n; i++) {
        void *elem = indexed_get(array_variant, i);
        push_variant(var, elem);
        variant_destroy(elem);
        vfree(elem);
    }
    return var;
}

static void *rid_from_int(void *int_variant) {
    int64_t n = 0;
    uint64_t id;
    void *var = vbuf();
    if (to_int != NULL) {
        to_int(&n, int_variant);
    }
    id = (uint64_t) n;
    if (from_rid != NULL) {
        from_rid(var, &id);
    } else {
        variant_nil(var);
    }
    return var;
}

static void fill_array(RGValue *dest, GDExtensionConstVariantPtr v, ScriptApi *api) {
    int64_t n;
    int64_t i;
    api->new_array(dest);
    if (variant_get_indexed == NULL || variant_call == NULL) {
        return;
    }
    n = variant_size(v);
    for (i = 0; i < n; i++) {
        void *elem = indexed_get(v, i);
        RGValue slot;
        memset(&slot, 0, sizeof(slot));
        rg_from_variant(&slot, elem, api);
        api->array_push(dest, &slot);
        variant_destroy(elem);
        vfree(elem);
    }
}

static void fill_map(RGValue *dest, GDExtensionConstVariantPtr v, ScriptApi *api) {
    unsigned char sn[16];
    void *keys = vbuf();
    GDExtensionCallError err;
    int64_t n;
    int64_t i;
    api->new_map(dest);
    if (variant_call == NULL || variant_get_indexed == NULL || variant_get_keyed == NULL) {
        vfree(keys);
        return;
    }
    sn_new(sn, "keys");
    memset(&err, 0, sizeof(err));
    variant_call((GDExtensionVariantPtr) v, sn, NULL, 0, keys, &err);
    sn_free(sn);
    if (err.error != 0) {
        variant_destroy(keys);
        vfree(keys);
        return;
    }
    n = variant_size(keys);
    for (i = 0; i < n; i++) {
        void *key = indexed_get(keys, i);
        void *elem = keyed_get(v, key);
        char *text = variant_text(key);
        RGValue slot;
        memset(&slot, 0, sizeof(slot));
        rg_from_variant(&slot, elem, api);
        api->map_put(dest, text, &slot);
        free(text);
        variant_destroy(elem);
        vfree(elem);
        variant_destroy(key);
        vfree(key);
    }
    variant_destroy(keys);
    vfree(keys);
}

static void put_vec3(RGValue *dest, ScriptApi *api, double x, double y, double z) {
    if (api->new_vec3 != NULL) {
        api->new_vec3(dest, x, y, z);
        return;
    }
    memset(dest, 0, sizeof(*dest));
}

static void put_vec4(RGValue *dest, ScriptApi *api, double x, double y, double z, double w) {
    if (api->new_vec4 != NULL) {
        api->new_vec4(dest, x, y, z, w);
        return;
    }
    memset(dest, 0, sizeof(*dest));
}

static void put_vec2i(RGValue *dest, ScriptApi *api, int64_t x, int64_t y) {
    if (api->new_vec2i != NULL) {
        api->new_vec2i(dest, x, y);
        return;
    }
    if (api->new_struct != NULL && api->set_int != NULL) {
        RGValue fields[2];
        const char *names[2];
        names[0] = "x";
        names[1] = "y";
        memset(fields, 0, sizeof(fields));
        api->set_int(&fields[0], x);
        api->set_int(&fields[1], y);
        api->new_struct(dest, "Vector2i", fields, 2, names);
        return;
    }
    memset(dest, 0, sizeof(*dest));
}

static void put_struct(RGValue *dest, ScriptApi *api, const char *type, RGValue *fields, int n, const char **names) {
    if (api->new_struct != NULL) {
        api->new_struct(dest, type, fields, n, names);
        return;
    }
    memset(dest, 0, sizeof(*dest));
}

static void rect2i_to_rg(RGValue *dest, int32_t x, int32_t y, int32_t w, int32_t h, ScriptApi *api) {
    RGValue fields[3];
    const char *names[3];
    names[0] = "position";
    names[1] = "size";
    names[2] = "end";
    memset(fields, 0, sizeof(fields));
    put_vec2i(&fields[0], api, x, y);
    put_vec2i(&fields[1], api, w, h);
    put_vec2i(&fields[2], api, (int64_t) x + (int64_t) w, (int64_t) y + (int64_t) h);
    put_struct(dest, api, "Rect2i", fields, 3, names);
}

/* Godot stores a Basis as three rows. The x, y, and z fields are the columns. */
static void basis_columns(const float *rows, RGValue *x, RGValue *y, RGValue *z, ScriptApi *api) {
    put_vec3(x, api, rows[0], rows[3], rows[6]);
    put_vec3(y, api, rows[1], rows[4], rows[7]);
    put_vec3(z, api, rows[2], rows[5], rows[8]);
}

static void signal_to_rg(RGValue *dest, GDExtensionConstVariantPtr v, ScriptApi *api) {
    unsigned char sn[16];
    GDExtensionCallError err;
    void *namev;
    void *idv;
    char *text = NULL;
    int64_t id = 0;
    RGValue fields[2];
    const char *names[2];
    if (api->new_struct == NULL || api->set_string == NULL || api->set_int == NULL || variant_call == NULL) {
        return;
    }
    namev = vbuf();
    idv = vbuf();
    if (namev == NULL || idv == NULL) {
        vfree(namev);
        vfree(idv);
        return;
    }
    sn_new(sn, "get_name");
    memset(&err, 0, sizeof(err));
    variant_call((GDExtensionVariantPtr) v, sn, NULL, 0, namev, &err);
    sn_free(sn);
    if (err.error == 0) {
        text = variant_text(namev);
    }
    sn_new(sn, "get_object_id");
    memset(&err, 0, sizeof(err));
    variant_call((GDExtensionVariantPtr) v, sn, NULL, 0, idv, &err);
    sn_free(sn);
    if (err.error == 0 && to_int != NULL) {
        to_int(&id, idv);
    }
    names[0] = "name";
    names[1] = "object_id";
    memset(fields, 0, sizeof(fields));
    api->set_string(&fields[0], text != NULL ? text : "");
    api->set_int(&fields[1], id);
    api->new_struct(dest, "Signal", fields, 2, names);
    free(text);
    variant_destroy(namev);
    vfree(namev);
    variant_destroy(idv);
    vfree(idv);
}

static void rg_from_variant(RGValue *dest, GDExtensionConstVariantPtr v, ScriptApi *api) {
    GDExtensionVariantType ty;
    memset(dest, 0, sizeof(*dest));
    if (g_conv_depth >= RG_CONV_DEPTH) {
        dest->kind = RG_VOID;
        return;
    }
    g_conv_depth++;
    ty = variant_get_type(v);
    if (ty == GDEXTENSION_VARIANT_TYPE_BOOL) {
        uint8_t b = 0;
        to_bool(&b, (GDExtensionVariantPtr) v);
        api->set_bool(dest, b);
    } else if (ty == GDEXTENSION_VARIANT_TYPE_INT) {
        int64_t n = 0;
        to_int(&n, (GDExtensionVariantPtr) v);
        api->set_int(dest, n);
    } else if (ty == GDEXTENSION_VARIANT_TYPE_FLOAT) {
        api->set_float(dest, variant_as_float(v));
    } else if (ty == GDEXTENSION_VARIANT_TYPE_STRING
            || ty == GDEXTENSION_VARIANT_TYPE_STRING_NAME
            || ty == GDEXTENSION_VARIANT_TYPE_NODE_PATH) {
        unsigned char str[16];
        char *text;
        memset(str, 0, sizeof(str));
        if (ty == GDEXTENSION_VARIANT_TYPE_STRING) {
            to_string(str, (GDExtensionVariantPtr) v);
        } else {
            variant_stringify((GDExtensionVariantPtr) v, str);
        }
        text = string_dup(str);
        api->set_string(dest, text);
        free(text);
        dtor_string(str);
    } else if (ty == GDEXTENSION_VARIANT_TYPE_VECTOR2 && api->new_vec2 != NULL && to_vector2 != NULL) {
        unsigned char vec[16];
        float xy[2];
        to_vector2(vec, (GDExtensionVariantPtr) v);
        memcpy(xy, vec, sizeof(xy));
        api->new_vec2(dest, (double) xy[0], (double) xy[1]);
    } else if (ty == GDEXTENSION_VARIANT_TYPE_VECTOR2I && api->new_vec2i != NULL && to_vector2i != NULL) {
        unsigned char vec[16];
        int32_t xy[2];
        to_vector2i(vec, (GDExtensionVariantPtr) v);
        memcpy(xy, vec, sizeof(xy));
        api->new_vec2i(dest, xy[0], xy[1]);
    } else if (ty == GDEXTENSION_VARIANT_TYPE_VECTOR3 && api->new_vec3 != NULL && to_vector3 != NULL) {
        unsigned char vec[16];
        float xyz[3];
        to_vector3(vec, (GDExtensionVariantPtr) v);
        memcpy(xyz, vec, sizeof(xyz));
        api->new_vec3(dest, (double) xyz[0], (double) xyz[1], (double) xyz[2]);
    } else if (ty == GDEXTENSION_VARIANT_TYPE_VECTOR4 && api->new_vec4 != NULL && to_vector4 != NULL) {
        unsigned char vec[16];
        float xyzw[4];
        to_vector4(vec, (GDExtensionVariantPtr) v);
        memcpy(xyzw, vec, sizeof(xyzw));
        api->new_vec4(dest, (double) xyzw[0], (double) xyzw[1], (double) xyzw[2], (double) xyzw[3]);
    } else if (ty == GDEXTENSION_VARIANT_TYPE_VECTOR3I && api->new_vec3i != NULL && to_vector3i != NULL) {
        unsigned char vec[16];
        int32_t xyz[3];
        to_vector3i(vec, (GDExtensionVariantPtr) v);
        memcpy(xyz, vec, sizeof(xyz));
        api->new_vec3i(dest, xyz[0], xyz[1], xyz[2]);
    } else if (ty == GDEXTENSION_VARIANT_TYPE_RECT2 && api->new_rect2 != NULL && to_rect2 != NULL) {
        unsigned char buf[16];
        float v4[4];
        to_rect2(buf, (GDExtensionVariantPtr) v);
        memcpy(v4, buf, sizeof(v4));
        api->new_rect2(dest, (double) v4[0], (double) v4[1], (double) v4[2], (double) v4[3]);
    } else if (ty == GDEXTENSION_VARIANT_TYPE_RECT2I && api != NULL && to_rect2i != NULL) {
        unsigned char buf[16];
        int32_t v4[4];
        to_rect2i(buf, (GDExtensionVariantPtr) v);
        memcpy(v4, buf, sizeof(v4));
        rect2i_to_rg(dest, v4[0], v4[1], v4[2], v4[3], api);
    } else if (ty == GDEXTENSION_VARIANT_TYPE_TRANSFORM2D && api->new_transform2d != NULL && to_transform2d != NULL) {
        unsigned char buf[24];
        float v6[6];
        to_transform2d(buf, (GDExtensionVariantPtr) v);
        memcpy(v6, buf, sizeof(v6));
        api->new_transform2d(dest, (double) v6[0], (double) v6[1], (double) v6[2], (double) v6[3], (double) v6[4], (double) v6[5]);
    } else if (ty == GDEXTENSION_VARIANT_TYPE_COLOR && api->new_color != NULL && to_color != NULL) {
        unsigned char buf[16];
        float rgba[4];
        to_color(buf, (GDExtensionVariantPtr) v);
        memcpy(rgba, buf, sizeof(rgba));
        api->new_color(dest, (double) rgba[0], (double) rgba[1], (double) rgba[2], (double) rgba[3]);
    } else if (ty == GDEXTENSION_VARIANT_TYPE_VECTOR4I && api->new_struct != NULL && api->set_int != NULL && to_vector4i != NULL) {
        unsigned char buf[16];
        int32_t xyzw[4];
        RGValue fields[4];
        const char *names[4];
        to_vector4i(buf, (GDExtensionVariantPtr) v);
        memcpy(xyzw, buf, sizeof(xyzw));
        names[0] = "x";
        names[1] = "y";
        names[2] = "z";
        names[3] = "w";
        memset(fields, 0, sizeof(fields));
        api->set_int(&fields[0], xyzw[0]);
        api->set_int(&fields[1], xyzw[1]);
        api->set_int(&fields[2], xyzw[2]);
        api->set_int(&fields[3], xyzw[3]);
        put_struct(dest, api, "Vector4i", fields, 4, names);
    } else if (ty == GDEXTENSION_VARIANT_TYPE_PLANE && to_plane != NULL) {
        unsigned char buf[16];
        float v4[4];
        RGValue fields[5];
        const char *names[5];
        to_plane(buf, (GDExtensionVariantPtr) v);
        memcpy(v4, buf, sizeof(v4));
        names[0] = "x";
        names[1] = "y";
        names[2] = "z";
        names[3] = "d";
        names[4] = "normal";
        memset(fields, 0, sizeof(fields));
        api->set_float(&fields[0], (double) v4[0]);
        api->set_float(&fields[1], (double) v4[1]);
        api->set_float(&fields[2], (double) v4[2]);
        api->set_float(&fields[3], (double) v4[3]);
        put_vec3(&fields[4], api, (double) v4[0], (double) v4[1], (double) v4[2]);
        put_struct(dest, api, "Plane", fields, 5, names);
    } else if (ty == GDEXTENSION_VARIANT_TYPE_QUATERNION && api->new_struct != NULL && api->set_float != NULL && to_quaternion != NULL) {
        unsigned char buf[16];
        float v4[4];
        RGValue fields[4];
        const char *names[4];
        to_quaternion(buf, (GDExtensionVariantPtr) v);
        memcpy(v4, buf, sizeof(v4));
        names[0] = "x";
        names[1] = "y";
        names[2] = "z";
        names[3] = "w";
        memset(fields, 0, sizeof(fields));
        api->set_float(&fields[0], (double) v4[0]);
        api->set_float(&fields[1], (double) v4[1]);
        api->set_float(&fields[2], (double) v4[2]);
        api->set_float(&fields[3], (double) v4[3]);
        put_struct(dest, api, "Quaternion", fields, 4, names);
    } else if (ty == GDEXTENSION_VARIANT_TYPE_AABB && to_aabb != NULL) {
        unsigned char buf[32];
        float v6[6];
        RGValue fields[3];
        const char *names[3];
        to_aabb(buf, (GDExtensionVariantPtr) v);
        memcpy(v6, buf, sizeof(v6));
        names[0] = "position";
        names[1] = "size";
        names[2] = "end";
        memset(fields, 0, sizeof(fields));
        put_vec3(&fields[0], api, (double) v6[0], (double) v6[1], (double) v6[2]);
        put_vec3(&fields[1], api, (double) v6[3], (double) v6[4], (double) v6[5]);
        put_vec3(&fields[2], api, (double) (v6[0] + v6[3]), (double) (v6[1] + v6[4]), (double) (v6[2] + v6[5]));
        put_struct(dest, api, "AABB", fields, 3, names);
    } else if (ty == GDEXTENSION_VARIANT_TYPE_BASIS && to_basis != NULL) {
        unsigned char buf[64];
        float rows[9];
        RGValue fields[3];
        const char *names[3];
        to_basis(buf, (GDExtensionVariantPtr) v);
        memcpy(rows, buf, sizeof(rows));
        names[0] = "x";
        names[1] = "y";
        names[2] = "z";
        basis_columns(rows, &fields[0], &fields[1], &fields[2], api);
        put_struct(dest, api, "Basis", fields, 3, names);
    } else if (ty == GDEXTENSION_VARIANT_TYPE_TRANSFORM3D && to_transform3d != NULL) {
        unsigned char buf[80];
        float raw[12];
        RGValue basis_fields[3];
        RGValue fields[2];
        const char *basis_names[3];
        const char *names[2];
        to_transform3d(buf, (GDExtensionVariantPtr) v);
        memcpy(raw, buf, sizeof(raw));
        basis_names[0] = "x";
        basis_names[1] = "y";
        basis_names[2] = "z";
        basis_columns(raw, &basis_fields[0], &basis_fields[1], &basis_fields[2], api);
        names[0] = "basis";
        names[1] = "origin";
        memset(fields, 0, sizeof(fields));
        put_struct(&fields[0], api, "Basis", basis_fields, 3, basis_names);
        put_vec3(&fields[1], api, (double) raw[9], (double) raw[10], (double) raw[11]);
        put_struct(dest, api, "Transform3D", fields, 2, names);
    } else if (ty == GDEXTENSION_VARIANT_TYPE_PROJECTION && to_projection != NULL) {
        unsigned char buf[80];
        float cols[16];
        RGValue fields[4];
        const char *names[4];
        int i;
        to_projection(buf, (GDExtensionVariantPtr) v);
        memcpy(cols, buf, sizeof(cols));
        names[0] = "x";
        names[1] = "y";
        names[2] = "z";
        names[3] = "w";
        for (i = 0; i < 4; i++) {
            put_vec4(&fields[i], api, (double) cols[i * 4], (double) cols[i * 4 + 1], (double) cols[i * 4 + 2], (double) cols[i * 4 + 3]);
        }
        put_struct(dest, api, "Projection", fields, 4, names);
    } else if (ty == GDEXTENSION_VARIANT_TYPE_SIGNAL) {
        signal_to_rg(dest, v, api);
    } else if (ty == GDEXTENSION_VARIANT_TYPE_RID && to_rid != NULL && api->set_int != NULL) {
        uint64_t id = 0;
        to_rid(&id, (GDExtensionVariantPtr) v);
        api->set_int(dest, (int64_t) id);
    } else if (ty == GDEXTENSION_VARIANT_TYPE_CALLABLE && to_callable != NULL && callable_custom_get_userdata != NULL) {
        unsigned char buf[40];
        Conn *c;
        memset(buf, 0, sizeof(buf));
        to_callable(buf, (GDExtensionVariantPtr) v);
        c = (Conn *) callable_custom_get_userdata(buf, g_library);
        if (c != NULL && (g_fn_host_id == NULL || g_fn_host_id(&c->fn) < 0)) {
            *dest = c->fn;
        } else if (g_fn_host != NULL) {
            int64_t id = box_callable(v);
            if (id > 0) {
                g_fn_host(dest, engine_callable_invoke, id);
            }
        }
        if (dtor_callable != NULL) {
            dtor_callable(buf);
        }
    } else if ((ty == GDEXTENSION_VARIANT_TYPE_ARRAY || is_packed_type(ty)) && api->new_array != NULL && api->array_push != NULL) {
        fill_array(dest, v, api);
    } else if (ty == GDEXTENSION_VARIANT_TYPE_DICTIONARY && api->new_map != NULL && api->map_put != NULL) {
        fill_map(dest, v, api);
    } else if (ty == GDEXTENSION_VARIANT_TYPE_OBJECT && to_object != NULL) {
        GDExtensionObjectPtr obj = NULL;
        ResData *res;
        to_object(&obj, (GDExtensionVariantPtr) v);
        res = res_from_object(obj);
        if (res != NULL && api->new_struct != NULL) {
            fill_struct_from_res(dest, res, api);
        } else if (obj != NULL && api->slot_put != NULL && object_get_id != NULL) {
            int64_t id = (int64_t) object_get_id(obj);
            api->slot_put(id, obj);
            slot_put(id, obj);
            dest->kind = RG_OBJECT;
            dest->i = id;
        }
    } else {
        dest->kind = RG_VOID;
    }
    g_conv_depth--;
}

static double struct_float(RGValue *obj, const char *field) {
    RGValue slot;
    memset(&slot, 0, sizeof(slot));
    if (obj == NULL || g_api == NULL || g_api->get == NULL || g_api->get_float == NULL) {
        return 0.0;
    }
    g_api->get(obj, field, &slot);
    return g_api->get_float(&slot);
}

static void struct_vec2(RGValue *obj, const char *field, float *x, float *y) {
    RGValue slot;
    memset(&slot, 0, sizeof(slot));
    *x = 0;
    *y = 0;
    if (obj == NULL || g_api == NULL || g_api->get == NULL) {
        return;
    }
    g_api->get(obj, field, &slot);
    *x = (float) struct_float(&slot, "x");
    *y = (float) struct_float(&slot, "y");
}

static void struct_vec3(RGValue *obj, const char *field, float *x, float *y, float *z) {
    RGValue slot;
    memset(&slot, 0, sizeof(slot));
    *x = 0;
    *y = 0;
    *z = 0;
    if (obj == NULL || g_api == NULL || g_api->get == NULL) {
        return;
    }
    g_api->get(obj, field, &slot);
    *x = (float) struct_float(&slot, "x");
    *y = (float) struct_float(&slot, "y");
    *z = (float) struct_float(&slot, "z");
}

static void struct_vec4(RGValue *obj, const char *field, float *x, float *y, float *z, float *w) {
    RGValue slot;
    memset(&slot, 0, sizeof(slot));
    *x = 0;
    *y = 0;
    *z = 0;
    *w = 0;
    if (obj == NULL || g_api == NULL || g_api->get == NULL) {
        return;
    }
    g_api->get(obj, field, &slot);
    *x = (float) struct_float(&slot, "x");
    *y = (float) struct_float(&slot, "y");
    *z = (float) struct_float(&slot, "z");
    *w = (float) struct_float(&slot, "w");
}

static int64_t struct_int(RGValue *obj, const char *field) {
    RGValue slot;
    memset(&slot, 0, sizeof(slot));
    if (obj == NULL || g_api == NULL || g_api->get == NULL) {
        return 0;
    }
    g_api->get(obj, field, &slot);
    if (slot.kind == RG_INT || slot.kind == RG_BOOL) {
        return slot.i;
    }
    if (g_api->get_float != NULL) {
        return (int64_t) g_api->get_float(&slot);
    }
    return 0;
}

static void basis_rows(RGValue *basis, float *rows) {
    float x[3];
    float y[3];
    float z[3];
    struct_vec3(basis, "x", &x[0], &x[1], &x[2]);
    struct_vec3(basis, "y", &y[0], &y[1], &y[2]);
    struct_vec3(basis, "z", &z[0], &z[1], &z[2]);
    rows[0] = x[0];
    rows[1] = y[0];
    rows[2] = z[0];
    rows[3] = x[1];
    rows[4] = y[1];
    rows[5] = z[1];
    rows[6] = x[2];
    rows[7] = y[2];
    rows[8] = z[2];
}

static void *variant_from_signal(const RGValue *v) {
    GDExtensionPtrConstructor ctor;
    unsigned char sig[32];
    unsigned char sn[16];
    RGValue name;
    RGValue idv;
    const char *text = "";
    void *obj = NULL;
    const void *args[2];
    void *var;
    if (from_signal == NULL || get_ptr_ctor == NULL || object_from_id == NULL || g_api == NULL || g_api->get == NULL) {
        return var_string("");
    }
    ctor = get_ptr_ctor(GDEXTENSION_VARIANT_TYPE_SIGNAL, 2);
    if (ctor == NULL) {
        return var_string("");
    }
    memset(&name, 0, sizeof(name));
    memset(&idv, 0, sizeof(idv));
    g_api->get((RGValue *) v, "name", &name);
    g_api->get((RGValue *) v, "object_id", &idv);
    if (name.kind == RG_STRING && name.p != NULL) {
        text = name.p;
    }
    if (idv.kind == RG_INT) {
        obj = object_from_id(idv.i);
    }
    sn_new(sn, text);
    args[0] = &obj;
    args[1] = sn;
    memset(sig, 0, sizeof(sig));
    ctor(sig, args);
    var = vbuf();
    from_signal(var, sig);
    if (dtor_signal != NULL) {
        dtor_signal(sig);
    }
    sn_free(sn);
    return var;
}

static void *variant_from_rg(const RGValue *v) {
    if (v == NULL || v->kind == RG_VOID) {
        void *out = vbuf();
        variant_nil(out);
        return out;
    }
    if (v->kind == RG_BOOL) {
        return var_bool(v->i != 0);
    }
    if (v->kind == RG_INT) {
        return var_int(v->i);
    }
    if (v->kind == RG_FLOAT) {
        double x = 0;
        memcpy(&x, &v->i, sizeof(x));
        return var_float(x);
    }
    if (v->kind == RG_STRING) {
        return var_string(v->p != NULL ? v->p : "");
    }
    if (v->kind == RG_OBJECT) {
        GDExtensionObjectPtr obj = (GDExtensionObjectPtr) slot_get(v->i);
        if (obj == NULL) {
            void *out = vbuf();
            variant_nil(out);
            return out;
        }
        return var_object(obj);
    }
    if ((v->kind == RG_ARRAY || v->kind == RG_MAP) && g_conv_depth >= RG_CONV_DEPTH) {
        void *out = vbuf();
        variant_nil(out);
        return out;
    }
    if (v->kind == RG_ARRAY && g_api != NULL && g_api->len != NULL && g_api->array_get != NULL && from_array != NULL) {
        int32_t n = g_api->len(v);
        int32_t i;
        void *var = fresh_builtin(GDEXTENSION_VARIANT_TYPE_ARRAY);
        if (n > RG_CONV_LEN) {
            n = RG_CONV_LEN;
        }
        g_conv_depth++;
        for (i = 0; i < n; i++) {
            RGValue slot;
            void *elem;
            memset(&slot, 0, sizeof(slot));
            g_api->array_get(&slot, v, i);
            elem = variant_from_rg(&slot);
            push_variant(var, elem);
            variant_destroy(elem);
            vfree(elem);
        }
        g_conv_depth--;
        return var;
    }
    if (v->kind == RG_MAP && g_conv_depth < RG_CONV_DEPTH && g_api != NULL && g_api->len != NULL && g_api->map_key_at != NULL && g_api->map_get != NULL && from_dict != NULL && variant_set_keyed != NULL) {
        int32_t n = g_api->len(v);
        int32_t i;
        void *var = fresh_builtin(GDEXTENSION_VARIANT_TYPE_DICTIONARY);
        if (n > RG_CONV_LEN) {
            n = RG_CONV_LEN;
        }
        g_conv_depth++;
        for (i = 0; i < n; i++) {
            RGValue key;
            RGValue slot;
            void *elem;
            void *kvar;
            uint8_t valid = 0;
            memset(&key, 0, sizeof(key));
            memset(&slot, 0, sizeof(slot));
            g_api->map_key_at(&key, v, i);
            if (key.kind != RG_STRING || key.p == NULL) {
                continue;
            }
            g_api->map_get(&slot, v, key.p);
            elem = variant_from_rg(&slot);
            kvar = var_string(key.p);
            variant_set_keyed(var, kvar, elem, &valid);
            variant_destroy(kvar);
            vfree(kvar);
            variant_destroy(elem);
            vfree(elem);
        }
        g_conv_depth--;
        return var;
    }
    if (v->kind == RG_FN && g_fn_host_id != NULL && variant_new_copy != NULL) {
        int64_t id = g_fn_host_id(v);
        if (id > 0 && id <= g_ncallables && g_callables[id - 1] != NULL) {
            void *copy = vbuf();
            variant_new_copy(copy, g_callables[id - 1]);
            return copy;
        }
    }
    if (v->kind == RG_FN && g_api != NULL && g_api->call_val != NULL) {
        return callable_from_fn(v);
    }
    if (v->kind == RG_STRUCT && g_api != NULL && g_api->type_name != NULL && g_api->get != NULL) {
        const char *ty = g_api->type_name(v);
        RGValue x;
        RGValue y;
        RGValue z;
        memset(&x, 0, sizeof(x));
        memset(&y, 0, sizeof(y));
        memset(&z, 0, sizeof(z));
        if (ty != NULL && strcmp(ty, "Vec2") == 0 && from_vector2 != NULL) {
            float xy[2];
            void *var = vbuf();
            g_api->get((RGValue *) v, "x", &x);
            g_api->get((RGValue *) v, "y", &y);
            xy[0] = (float) g_api->get_float(&x);
            xy[1] = (float) g_api->get_float(&y);
            from_vector2(var, xy);
            return var;
        }
        if (ty != NULL && strcmp(ty, "Vector2i") == 0 && from_vector2i != NULL) {
            int32_t xy[2];
            void *var = vbuf();
            g_api->get((RGValue *) v, "x", &x);
            g_api->get((RGValue *) v, "y", &y);
            xy[0] = (int32_t) x.i;
            xy[1] = (int32_t) y.i;
            from_vector2i(var, xy);
            return var;
        }
        if (ty != NULL && strcmp(ty, "Vector3") == 0 && from_vector3 != NULL) {
            float xyz[3];
            void *var = vbuf();
            g_api->get((RGValue *) v, "x", &x);
            g_api->get((RGValue *) v, "y", &y);
            g_api->get((RGValue *) v, "z", &z);
            xyz[0] = (float) g_api->get_float(&x);
            xyz[1] = (float) g_api->get_float(&y);
            xyz[2] = (float) g_api->get_float(&z);
            from_vector3(var, xyz);
            return var;
        }
        if (ty != NULL && strcmp(ty, "Vector4") == 0 && from_vector4 != NULL) {
            float xyzw[4];
            void *var = vbuf();
            xyzw[0] = (float) struct_float((RGValue *) v, "x");
            xyzw[1] = (float) struct_float((RGValue *) v, "y");
            xyzw[2] = (float) struct_float((RGValue *) v, "z");
            xyzw[3] = (float) struct_float((RGValue *) v, "w");
            from_vector4(var, xyzw);
            return var;
        }
        if (ty != NULL && strcmp(ty, "Vector3i") == 0 && from_vector3i != NULL) {
            int32_t xyz[3];
            void *var = vbuf();
            g_api->get((RGValue *) v, "x", &x);
            g_api->get((RGValue *) v, "y", &y);
            g_api->get((RGValue *) v, "z", &z);
            xyz[0] = (int32_t) x.i;
            xyz[1] = (int32_t) y.i;
            xyz[2] = (int32_t) z.i;
            from_vector3i(var, xyz);
            return var;
        }
        if (ty != NULL && strcmp(ty, "Rect2") == 0 && from_rect2 != NULL) {
            float v4[4];
            void *var = vbuf();
            RGValue position;
            RGValue size;
            memset(&position, 0, sizeof(position));
            memset(&size, 0, sizeof(size));
            g_api->get((RGValue *) v, "position", &position);
            g_api->get((RGValue *) v, "size", &size);
            v4[0] = (float) struct_float(&position, "x");
            v4[1] = (float) struct_float(&position, "y");
            v4[2] = (float) struct_float(&size, "x");
            v4[3] = (float) struct_float(&size, "y");
            from_rect2(var, v4);
            return var;
        }
        if (ty != NULL && strcmp(ty, "Rect2i") == 0 && from_rect2i != NULL) {
            int32_t v4[4];
            void *var = vbuf();
            RGValue position;
            RGValue size;
            memset(&position, 0, sizeof(position));
            memset(&size, 0, sizeof(size));
            g_api->get((RGValue *) v, "position", &position);
            g_api->get((RGValue *) v, "size", &size);
            v4[0] = (int32_t) struct_int(&position, "x");
            v4[1] = (int32_t) struct_int(&position, "y");
            v4[2] = (int32_t) struct_int(&size, "x");
            v4[3] = (int32_t) struct_int(&size, "y");
            from_rect2i(var, v4);
            return var;
        }
        if (ty != NULL && strcmp(ty, "Transform2D") == 0 && from_transform2d != NULL) {
            float v6[6];
            void *var = vbuf();
            struct_vec2((RGValue *) v, "x", &v6[0], &v6[1]);
            struct_vec2((RGValue *) v, "y", &v6[2], &v6[3]);
            struct_vec2((RGValue *) v, "origin", &v6[4], &v6[5]);
            from_transform2d(var, v6);
            return var;
        }
        if (ty != NULL && strcmp(ty, "Color") == 0 && from_color != NULL) {
            float rgba[4];
            void *var = vbuf();
            rgba[0] = (float) struct_float((RGValue *) v, "r");
            rgba[1] = (float) struct_float((RGValue *) v, "g");
            rgba[2] = (float) struct_float((RGValue *) v, "b");
            rgba[3] = (float) struct_float((RGValue *) v, "a");
            from_color(var, rgba);
            return var;
        }
        if (ty != NULL && strcmp(ty, "Vector4i") == 0 && from_vector4i != NULL) {
            int32_t xyzw[4];
            void *var = vbuf();
            xyzw[0] = (int32_t) struct_int((RGValue *) v, "x");
            xyzw[1] = (int32_t) struct_int((RGValue *) v, "y");
            xyzw[2] = (int32_t) struct_int((RGValue *) v, "z");
            xyzw[3] = (int32_t) struct_int((RGValue *) v, "w");
            from_vector4i(var, xyzw);
            return var;
        }
        if (ty != NULL && strcmp(ty, "Plane") == 0 && from_plane != NULL) {
            float v4[4];
            void *var = vbuf();
            v4[0] = (float) struct_float((RGValue *) v, "x");
            v4[1] = (float) struct_float((RGValue *) v, "y");
            v4[2] = (float) struct_float((RGValue *) v, "z");
            v4[3] = (float) struct_float((RGValue *) v, "d");
            from_plane(var, v4);
            return var;
        }
        if (ty != NULL && strcmp(ty, "Quaternion") == 0 && from_quaternion != NULL) {
            float v4[4];
            void *var = vbuf();
            v4[0] = (float) struct_float((RGValue *) v, "x");
            v4[1] = (float) struct_float((RGValue *) v, "y");
            v4[2] = (float) struct_float((RGValue *) v, "z");
            v4[3] = (float) struct_float((RGValue *) v, "w");
            from_quaternion(var, v4);
            return var;
        }
        if (ty != NULL && strcmp(ty, "AABB") == 0 && from_aabb != NULL) {
            float v6[6];
            void *var = vbuf();
            struct_vec3((RGValue *) v, "position", &v6[0], &v6[1], &v6[2]);
            struct_vec3((RGValue *) v, "size", &v6[3], &v6[4], &v6[5]);
            from_aabb(var, v6);
            return var;
        }
        if (ty != NULL && strcmp(ty, "Basis") == 0 && from_basis != NULL) {
            float rows[9];
            void *var = vbuf();
            basis_rows((RGValue *) v, rows);
            from_basis(var, rows);
            return var;
        }
        if (ty != NULL && strcmp(ty, "Transform3D") == 0 && from_transform3d != NULL) {
            float raw[12];
            RGValue basis;
            void *var = vbuf();
            memset(&basis, 0, sizeof(basis));
            g_api->get((RGValue *) v, "basis", &basis);
            basis_rows(&basis, raw);
            struct_vec3((RGValue *) v, "origin", &raw[9], &raw[10], &raw[11]);
            from_transform3d(var, raw);
            return var;
        }
        if (ty != NULL && strcmp(ty, "Projection") == 0 && from_projection != NULL) {
            float cols[16];
            void *var = vbuf();
            struct_vec4((RGValue *) v, "x", &cols[0], &cols[1], &cols[2], &cols[3]);
            struct_vec4((RGValue *) v, "y", &cols[4], &cols[5], &cols[6], &cols[7]);
            struct_vec4((RGValue *) v, "z", &cols[8], &cols[9], &cols[10], &cols[11]);
            struct_vec4((RGValue *) v, "w", &cols[12], &cols[13], &cols[14], &cols[15]);
            from_projection(var, cols);
            return var;
        }
        if (ty != NULL && strcmp(ty, "Signal") == 0 && from_signal != NULL) {
            return variant_from_signal(v);
        }
        if (ty != NULL && struct_lookup(ty) != NULL) {
            return resource_from_struct(v, ty);
        }
    }
    return var_string("");
}

static void on_signal(int64_t id, const char *name, int32_t op, const RGValue *args, int32_t argc) {
    GDExtensionObjectPtr obj = (GDExtensionObjectPtr) slot_get(id);
    if (obj == NULL || name == NULL) {
        return;
    }
    if (op == RG_GODOT_EMIT) {
        void **vars = (void **) calloc((size_t) argc + 1, sizeof(void *));
        const void **ptrs = (const void **) calloc((size_t) argc + 1, sizeof(void *));
        int i;
        vars[0] = var_string(name);
        ptrs[0] = vars[0];
        for (i = 0; i < argc; i++) {
            vars[i + 1] = variant_from_rg(args != NULL ? &args[i] : NULL);
            ptrs[i + 1] = vars[i + 1];
        }
        call_ok(obj, "emit_signal", ptrs, argc + 1);
        for (i = 0; i < argc + 1; i++) {
            variant_destroy(vars[i]);
            vfree(vars[i]);
        }
        free(vars);
        free(ptrs);
        return;
    }
}

static void conn_call(void *userdata, const GDExtensionConstVariantPtr *args, GDExtensionInt argc, GDExtensionVariantPtr ret, GDExtensionCallError *err) {
    Conn *c = (Conn *) userdata;
    RGValue stack[8];
    RGValue out;
    int i;
    int n = (int) argc;
    if (n > 8) {
        n = 8;
    }
    memset(&out, 0, sizeof(out));
    for (i = 0; i < n; i++) {
        rg_from_variant(&stack[i], args[i], &c->api);
    }
    c->api.call_val(&c->fn, n > 0 ? stack : NULL, n, &out);
    if (ret != NULL) {
        variant_nil(ret);
    }
    if (err != NULL) {
        memset(err, 0, sizeof(*err));
    }
}

static GDExtensionBool conn_valid(void *userdata) {
    return userdata != NULL;
}

static void conn_free(void *userdata) {
    Conn *c = (Conn *) userdata;
    if (c == NULL) {
        return;
    }
    free(c->signal);
    free(c);
}

static uint32_t conn_hash(void *userdata) {
    Conn *c = (Conn *) userdata;
    if (c == NULL) {
        return 0;
    }
    if (c->api.fn_hash != NULL) {
        return c->api.fn_hash(&c->fn);
    }
    return (uint32_t) (uintptr_t) c->fn.p;
}

static GDExtensionBool conn_equal(void *a, void *b) {
    Conn *ca = (Conn *) a;
    Conn *cb = (Conn *) b;
    if (ca == NULL || cb == NULL) {
        return 0;
    }
    if (ca->api.fn_same != NULL) {
        return ca->api.fn_same(&ca->fn, &cb->fn) ? 1 : 0;
    }
    return ca->fn.kind == cb->fn.kind && ca->fn.p == cb->fn.p;
}

static void *callable_from_fn(const RGValue *fn) {
    Conn *c;
    GDExtensionCallableCustomInfo2 info;
    unsigned char callable[40];
    void *cv;
    if (fn == NULL || g_api == NULL || callable_custom_create2 == NULL || from_callable == NULL) {
        void *out = vbuf();
        variant_nil(out);
        return out;
    }
    c = (Conn *) calloc(1, sizeof(Conn));
    if (c == NULL) {
        void *out = vbuf();
        variant_nil(out);
        return out;
    }
    c->api = *g_api;
    c->fn = *fn;
    memset(&info, 0, sizeof(info));
    info.callable_userdata = c;
    info.token = g_library;
    info.call_func = conn_call;
    info.is_valid_func = conn_valid;
    info.free_func = conn_free;
    info.hash_func = conn_hash;
    info.equal_func = conn_equal;
    memset(callable, 0, sizeof(callable));
    callable_custom_create2(callable, &info);
    cv = vbuf();
    from_callable(cv, callable);
    if (dtor_callable != NULL) {
        dtor_callable(callable);
    }
    return cv;
}

static void finish_connect(int64_t id, const char *name, const RGValue *fn) {
    Conn *c;
    GDExtensionCallableCustomInfo2 info;
    unsigned char callable[40];
    void *sig;
    void *cv;
    const void *call_args[2];
    GDExtensionObjectPtr obj;
    if (g_api == NULL || fn == NULL) {
        return;
    }
    obj = (GDExtensionObjectPtr) slot_get(id);
    if (obj == NULL) {
        return;
    }
    c = (Conn *) calloc(1, sizeof(Conn));
    c->api = *g_api;
    c->fn = *fn;
    c->id = id;
    c->signal = strdup(name != NULL ? name : "");
    c->next = g_conns;
    g_conns = c;
    memset(&info, 0, sizeof(info));
    info.callable_userdata = c;
    info.token = g_library;
    info.call_func = conn_call;
    info.get_argument_count_func = NULL;
    memset(callable, 0, sizeof(callable));
    callable_custom_create2(callable, &info);
    cv = vbuf();
    from_callable(cv, callable);
    sig = var_string(c->signal);
    call_args[0] = sig;
    call_args[1] = cv;
    call_ok(obj, "connect", call_args, 2);
    variant_destroy(sig);
    vfree(sig);
    variant_destroy(cv);
    vfree(cv);
    dtor_callable(callable);
}

static void on_signal_full(int64_t id, const char *name, int32_t op, const RGValue *args, int32_t argc) {
    if (op == RG_GODOT_CONNECT) {
        finish_connect(id, name, args);
        return;
    }
    if (op == RG_GODOT_DISCONNECT) {
        Conn **cur = &g_conns;
        while (*cur != NULL) {
            Conn *c = *cur;
            if (c->id == id && c->signal != NULL && name != NULL && strcmp(c->signal, name) == 0
                    && args != NULL && c->fn.kind == args[0].kind && c->fn.i == args[0].i && c->fn.p == args[0].p) {
                GDExtensionObjectPtr obj = (GDExtensionObjectPtr) slot_get(id);
                *cur = c->next;
                if (obj != NULL) {
                    void *sig = var_string(c->signal);
                    const void *call_args[1];
                    call_args[0] = sig;
                    call_ok(obj, "disconnect", call_args, 1);
                    variant_destroy(sig);
                    vfree(sig);
                }
                free(c->signal);
                free(c);
                return;
            }
            cur = &(*cur)->next;
        }
        return;
    }
    on_signal(id, name, op, args, argc);
}

static int node_has_field(RunData *run, const char *field) {
    if (run == NULL || run->script == NULL || !run->script->loaded) {
        return 0;
    }
    if (run->script->api.has_field == NULL) {
        return strcmp(field, "position") == 0;
    }
    return run->script->api.has_field(&run->self, field);
}

static int node_get_prop(GDExtensionObjectPtr owner, const char *prop, void *out) {
    void *key;
    const void *args[1];
    unsigned char sn[16];
    void *self;
    GDExtensionCallError err;
    key = var_string(prop);
    args[0] = key;
    self = var_object(owner);
    sn_new(sn, "get");
    memset(&err, 0, sizeof(err));
    variant_call(self, sn, (const GDExtensionConstVariantPtr *) args, 1, out, &err);
    sn_free(sn);
    variant_destroy(self);
    vfree(self);
    variant_destroy(key);
    vfree(key);
    return err.error == 0;
}

static void node_set_prop(GDExtensionObjectPtr owner, const char *prop, void *value) {
    void *key = var_string(prop);
    const void *args[2];
    args[0] = key;
    args[1] = value;
    call_ok(owner, "set", args, 2);
    variant_destroy(key);
    vfree(key);
}

static void sync_vec2_from(RunData *run, const char *field) {
    void *prop;
    unsigned char vec[16];
    float xy[2];
    RGValue slot;
    RGValue x;
    RGValue y;
    if (!node_has_field(run, field) || to_vector2 == NULL) {
        return;
    }
    prop = vbuf();
    if (!node_get_prop(run->owner, field, prop) || variant_get_type(prop) != GDEXTENSION_VARIANT_TYPE_VECTOR2) {
        variant_destroy(prop);
        vfree(prop);
        return;
    }
    to_vector2(vec, prop);
    memcpy(xy, vec, sizeof(xy));
    variant_destroy(prop);
    vfree(prop);
    run->script->api.get(&run->self, field, &slot);
    run->script->api.set_float(&x, (double) xy[0]);
    run->script->api.set_float(&y, (double) xy[1]);
    run->script->api.set(&slot, "x", &x);
    run->script->api.set(&slot, "y", &y);
}

static void sync_vec2_to(RunData *run, const char *field) {
    RGValue slot;
    RGValue x;
    RGValue y;
    float xy[2];
    void *vec_var;
    if (!node_has_field(run, field) || from_vector2 == NULL) {
        return;
    }
    run->script->api.get(&run->self, field, &slot);
    run->script->api.get(&slot, "x", &x);
    run->script->api.get(&slot, "y", &y);
    xy[0] = (float) run->script->api.get_float(&x);
    xy[1] = (float) run->script->api.get_float(&y);
    vec_var = vbuf();
    from_vector2(vec_var, xy);
    node_set_prop(run->owner, field, vec_var);
    variant_destroy(vec_var);
    vfree(vec_var);
}

static void sync_float_from(RunData *run, const char *field) {
    void *prop;
    RGValue slot;
    if (!node_has_field(run, field)) {
        return;
    }
    prop = vbuf();
    if (!node_get_prop(run->owner, field, prop)) {
        variant_destroy(prop);
        vfree(prop);
        return;
    }
    if (variant_get_type(prop) != GDEXTENSION_VARIANT_TYPE_FLOAT && variant_get_type(prop) != GDEXTENSION_VARIANT_TYPE_INT) {
        variant_destroy(prop);
        vfree(prop);
        return;
    }
    run->script->api.set_float(&slot, variant_as_float(prop));
    run->script->api.set(&run->self, field, &slot);
    variant_destroy(prop);
    vfree(prop);
}

static void sync_float_to(RunData *run, const char *field) {
    RGValue slot;
    void *prop;
    if (!node_has_field(run, field)) {
        return;
    }
    run->script->api.get(&run->self, field, &slot);
    prop = var_float(run->script->api.get_float(&slot));
    node_set_prop(run->owner, field, prop);
    variant_destroy(prop);
    vfree(prop);
}

static void sync_from_node(RunData *run) {
    if (run == NULL || run->owner == NULL || !run->script->loaded) {
        return;
    }
    sync_vec2_from(run, "position");
    sync_float_from(run, "rotation");
    sync_vec2_from(run, "scale");
}

static void sync_to_node(RunData *run) {
    if (run == NULL || run->owner == NULL || !run->script->loaded) {
        return;
    }
    sync_vec2_to(run, "position");
    sync_float_to(run, "rotation");
    sync_vec2_to(run, "scale");
}

static void on_godot_call(int64_t id, const char *name, const RGValue *args, int32_t argc, RGValue *dest) {
    GDExtensionObjectPtr obj;
    void **vars;
    const void **ptrs;
    void *self;
    void *ret;
    unsigned char sn[16];
    GDExtensionCallError err;
    int i;
    if (dest != NULL) {
        memset(dest, 0, sizeof(*dest));
    }
    obj = (GDExtensionObjectPtr) slot_get(id);
    if (obj == NULL || name == NULL || g_api == NULL) {
        return;
    }
    if (argc < 0) {
        argc = 0;
    }
    if (argc > 16) {
        argc = 16;
    }
    vars = (void **) calloc((size_t) argc + 1, sizeof(void *));
    ptrs = (const void **) calloc((size_t) argc + 1, sizeof(void *));
    if (vars == NULL || ptrs == NULL) {
        free(vars);
        free(ptrs);
        return;
    }
    for (i = 0; i < argc; i++) {
        vars[i] = variant_from_rg(args != NULL ? &args[i] : NULL);
        ptrs[i] = vars[i];
    }
    self = var_object(obj);
    ret = vbuf();
    sn_new(sn, name);
    for (;;) {
        int arg;
        memset(&err, 0, sizeof(err));
        variant_call(self, sn, argc > 0 ? (const GDExtensionConstVariantPtr *) ptrs : NULL, argc, ret, &err);
        arg = err.argument;
        if (err.error != GDEXTENSION_CALL_ERROR_INVALID_ARGUMENT || arg < 0 || arg >= argc) {
            break;
        }
        if (is_packed_type(err.expected)) {
            if (vars[arg] == NULL || variant_get_type(vars[arg]) != GDEXTENSION_VARIANT_TYPE_ARRAY) {
                break;
            }
            {
                void *packed = pack_from_array(vars[arg], (GDExtensionVariantType) err.expected);
                variant_destroy(vars[arg]);
                vfree(vars[arg]);
                vars[arg] = packed;
                ptrs[arg] = packed;
            }
        } else if (err.expected == GDEXTENSION_VARIANT_TYPE_RID && from_rid != NULL) {
            if (vars[arg] == NULL || variant_get_type(vars[arg]) != GDEXTENSION_VARIANT_TYPE_INT) {
                break;
            }
            {
                void *rid = rid_from_int(vars[arg]);
                variant_destroy(vars[arg]);
                vfree(vars[arg]);
                vars[arg] = rid;
                ptrs[arg] = rid;
            }
        } else {
            break;
        }
        variant_destroy(ret);
        vfree(ret);
        ret = vbuf();
    }
    sn_free(sn);
    variant_destroy(self);
    vfree(self);
    if (err.error == 0 && dest != NULL) {
        rg_from_variant(dest, ret, g_api);
    }
    variant_destroy(ret);
    vfree(ret);
    for (i = 0; i < argc; i++) {
        variant_destroy(vars[i]);
        vfree(vars[i]);
    }
    free(vars);
    free(ptrs);
}

static void own_returned_object(void *ret) {
    GDExtensionObjectPtr obj;
    void *self;
    void *ok;
    unsigned char sn[16];
    GDExtensionCallError err;
    int64_t id;
    if (ret == NULL || variant_get_type == NULL || to_object == NULL || variant_get_type(ret) != GDEXTENSION_VARIANT_TYPE_OBJECT) {
        return;
    }
    obj = NULL;
    to_object(&obj, ret);
    if (obj == NULL) {
        return;
    }
    self = var_object(obj);
    ok = vbuf();
    sn_new(sn, "reference");
    memset(&err, 0, sizeof(err));
    variant_call(self, sn, NULL, 0, ok, &err);
    sn_free(sn);
    variant_destroy(ok);
    vfree(ok);
    variant_destroy(self);
    vfree(self);
    if (err.error != 0 || object_get_id == NULL || g_nowned >= 256) {
        return;
    }
    id = (int64_t) object_get_id(obj);
    if (id != 0) {
        g_owned_ids[g_nowned++] = id;
    }
}

static int builtin_value_type(int vty) {
    switch (vty) {
    case GDEXTENSION_VARIANT_TYPE_VECTOR2:
    case GDEXTENSION_VARIANT_TYPE_VECTOR2I:
    case GDEXTENSION_VARIANT_TYPE_VECTOR3:
    case GDEXTENSION_VARIANT_TYPE_VECTOR3I:
    case GDEXTENSION_VARIANT_TYPE_VECTOR4:
    case GDEXTENSION_VARIANT_TYPE_VECTOR4I:
    case GDEXTENSION_VARIANT_TYPE_RECT2:
    case GDEXTENSION_VARIANT_TYPE_RECT2I:
    case GDEXTENSION_VARIANT_TYPE_TRANSFORM2D:
    case GDEXTENSION_VARIANT_TYPE_TRANSFORM3D:
    case GDEXTENSION_VARIANT_TYPE_PLANE:
    case GDEXTENSION_VARIANT_TYPE_QUATERNION:
    case GDEXTENSION_VARIANT_TYPE_AABB:
    case GDEXTENSION_VARIANT_TYPE_BASIS:
    case GDEXTENSION_VARIANT_TYPE_PROJECTION:
    case GDEXTENSION_VARIANT_TYPE_COLOR:
        return 1;
    default:
        return 0;
    }
}

static void on_builtin_static(int vty, const char *method, const RGValue *args, int argc, RGValue *dest) {
    unsigned char sn[16];
    void **vars;
    const void **ptrs;
    void *ret;
    GDExtensionCallError err;
    int i;
    if (dest != NULL) {
        memset(dest, 0, sizeof(*dest));
    }
    if (method == NULL || variant_call_static == NULL) {
        return;
    }
    if (argc < 0) {
        argc = 0;
    }
    if (argc > 16) {
        argc = 16;
    }
    vars = (void **) calloc((size_t) argc + 1, sizeof(void *));
    ptrs = (const void **) calloc((size_t) argc + 1, sizeof(void *));
    if (vars == NULL || ptrs == NULL) {
        free(vars);
        free(ptrs);
        return;
    }
    for (i = 0; i < argc; i++) {
        vars[i] = variant_from_rg(args != NULL ? &args[i] : NULL);
        ptrs[i] = vars[i];
    }
    ret = vbuf();
    sn_new(sn, method);
    memset(&err, 0, sizeof(err));
    variant_call_static((GDExtensionVariantType) vty, sn, (const GDExtensionConstVariantPtr *) ptrs, argc, ret, &err);
    sn_free(sn);
    if (err.error == 0 && dest != NULL && g_api != NULL) {
        rg_from_variant(dest, ret, g_api);
    }
    variant_destroy(ret);
    vfree(ret);
    for (i = 0; i < argc; i++) {
        variant_destroy(vars[i]);
        vfree(vars[i]);
    }
    free(vars);
    free(ptrs);
}

static int32_t on_godot_value(const char *type, const RGValue *self, const char *name, const RGValue *args, int32_t argc, RGValue *dest) {
    void *var;
    if (!builtin_value_type(rose_variant_type(type))) {
        if (dest != NULL) {
            memset(dest, 0, sizeof(*dest));
        }
        return 0;
    }
    var = variant_from_rg(self);
    call_on_variant(var, name, args, argc, dest);
    variant_destroy(var);
    vfree(var);
    return 1;
}

static void on_godot_static(const char *class_name, const char *method, const RGValue *args, int32_t argc, RGValue *dest) {
    unsigned char sn[16];
    GDExtensionObjectPtr classdb;
    void *self;
    void *ret;
    void **vars;
    const void **ptrs;
    GDExtensionCallError err;
    int i;
    int n;
    if (dest != NULL) {
        memset(dest, 0, sizeof(*dest));
    }
    if (class_name == NULL || method == NULL) {
        return;
    }
    if (argc < 0) {
        argc = 0;
    }
    if (argc > 16) {
        argc = 16;
    }
    if (builtin_value_type(rose_variant_type(class_name)) && variant_call_static != NULL) {
        on_builtin_static(rose_variant_type(class_name), method, args, argc, dest);
        return;
    }
    if (global_get_singleton == NULL || from_stringname == NULL || variant_call == NULL) {
        return;
    }
    sn_new(sn, "ClassDB");
    classdb = global_get_singleton(sn);
    sn_free(sn);
    if (classdb == NULL) {
        return;
    }
    n = argc + 2;
    vars = (void **) calloc((size_t) n, sizeof(void *));
    ptrs = (const void **) calloc((size_t) n, sizeof(void *));
    if (vars == NULL || ptrs == NULL) {
        free(vars);
        free(ptrs);
        return;
    }
    vars[0] = vbuf();
    sn_new(sn, class_name);
    from_stringname(vars[0], sn);
    sn_free(sn);
    vars[1] = vbuf();
    sn_new(sn, method);
    from_stringname(vars[1], sn);
    sn_free(sn);
    for (i = 0; i < argc; i++) {
        vars[i + 2] = variant_from_rg(args != NULL ? &args[i] : NULL);
    }
    for (i = 0; i < n; i++) {
        ptrs[i] = vars[i];
    }
    self = var_object(classdb);
    ret = vbuf();
    sn_new(sn, "class_call_static");
    memset(&err, 0, sizeof(err));
    variant_call(self, sn, (const GDExtensionConstVariantPtr *) ptrs, n, ret, &err);
    sn_free(sn);
    variant_destroy(self);
    vfree(self);
    if (err.error == 0 && dest != NULL && g_api != NULL) {
        own_returned_object(ret);
        rg_from_variant(dest, ret, g_api);
    }
    variant_destroy(ret);
    vfree(ret);
    for (i = 0; i < n; i++) {
        variant_destroy(vars[i]);
        vfree(vars[i]);
    }
    free(vars);
    free(ptrs);
}

static void fill_vec2(RGValue *dest, float x, float y) {
    RGValue fx;
    RGValue fy;
    const char *ty;
    if (g_api == NULL || dest == NULL) {
        return;
    }
    ty = g_api->type_name != NULL ? g_api->type_name(dest) : "";
    if (dest->kind == RG_STRUCT && ty != NULL && strcmp(ty, "Vec2") == 0 && g_api->set != NULL) {
        g_api->set_float(&fx, (double) x);
        g_api->set_float(&fy, (double) y);
        g_api->set(dest, "x", &fx);
        g_api->set(dest, "y", &fy);
        return;
    }
    if (g_api->new_vec2 != NULL) {
        g_api->new_vec2(dest, (double) x, (double) y);
    }
}

static void on_singleton(RGValue *dest, const char *name) {
    unsigned char sn[16];
    GDExtensionObjectPtr obj;
    int64_t id;
    if (dest == NULL) {
        return;
    }
    dest->kind = RG_VOID;
    dest->i = 0;
    dest->p = NULL;
    if (name == NULL || name[0] == 0 || global_get_singleton == NULL || object_get_id == NULL) {
        return;
    }
    sn_new(sn, name);
    obj = global_get_singleton(sn);
    sn_free(sn);
    if (obj == NULL) {
        return;
    }
    id = (int64_t) object_get_id(obj);
    slot_put(id, obj);
    if (g_api != NULL && g_api->slot_put != NULL) {
        g_api->slot_put(id, obj);
    }
    dest->kind = RG_OBJECT;
    dest->i = id;
}

static void on_godot_prop(int64_t id, const char *name, RGValue *value, int32_t write) {
    GDExtensionObjectPtr obj;
    void *prop;
    unsigned char vec[16];
    float xy[2];
    int vec_prop;
    if (value == NULL || name == NULL || g_api == NULL) {
        return;
    }
    obj = (GDExtensionObjectPtr) slot_get(id);
    if (obj == NULL) {
        return;
    }
    vec_prop = strcmp(name, "position") == 0 || strcmp(name, "scale") == 0;
    if (write) {
        void *var = variant_from_rg(value);
        node_set_prop(obj, name, var);
        variant_destroy(var);
        vfree(var);
        return;
    }
    prop = vbuf();
    if (!node_get_prop(obj, name, prop)) {
        variant_destroy(prop);
        vfree(prop);
        return;
    }
    if (vec_prop && variant_get_type(prop) == GDEXTENSION_VARIANT_TYPE_VECTOR2 && to_vector2 != NULL) {
        to_vector2(vec, prop);
        memcpy(xy, vec, sizeof(xy));
        fill_vec2(value, xy[0], xy[1]);
    } else if (strcmp(name, "rotation") == 0 && g_api->set_float != NULL
            && (variant_get_type(prop) == GDEXTENSION_VARIANT_TYPE_FLOAT
                    || variant_get_type(prop) == GDEXTENSION_VARIANT_TYPE_INT)) {
        g_api->set_float(value, variant_as_float(prop));
    } else {
        rg_from_variant(value, prop, g_api);
    }
    variant_destroy(prop);
    vfree(prop);
}

static GDExtensionBool inst_set(GDExtensionScriptInstanceDataPtr p, GDExtensionConstStringNamePtr name, GDExtensionConstVariantPtr value) {
    RunData *run = (RunData *) p;
    unsigned char var[40];
    unsigned char str[16];
    char *field;
    RGValue rg;
    if (run == NULL || !run->script->loaded) {
        return 0;
    }
    memset(var, 0, sizeof(var));
    from_stringname(var, (GDExtensionTypePtr) name);
    memset(str, 0, sizeof(str));
    variant_stringify(var, str);
    variant_destroy(var);
    field = string_dup(str);
    dtor_string(str);
    if (!has_member(run->script->vars, run->script->nvars, field)) {
        free(field);
        return 0;
    }
    rg_from_variant(&rg, value, &run->script->api);
    run->script->api.set(&run->self, field, &rg);
    free(field);
    return 1;
}

static GDExtensionBool inst_get(GDExtensionScriptInstanceDataPtr p, GDExtensionConstStringNamePtr name, GDExtensionVariantPtr ret) {
    RunData *run = (RunData *) p;
    unsigned char var[40];
    unsigned char str[16];
    char *field;
    RGValue rg;
    void *boxed;
    if (run == NULL || !run->script->loaded) {
        return 0;
    }
    memset(var, 0, sizeof(var));
    from_stringname(var, (GDExtensionTypePtr) name);
    memset(str, 0, sizeof(str));
    variant_stringify(var, str);
    variant_destroy(var);
    field = string_dup(str);
    dtor_string(str);
    if (!has_member(run->script->vars, run->script->nvars, field)) {
        free(field);
        return 0;
    }
    run->script->api.get(&run->self, field, &rg);
    boxed = variant_from_rg(&rg);
    memcpy(ret, boxed, 24);
    vfree(boxed);
    free(field);
    return 1;
}

static void inst_call(GDExtensionScriptInstanceDataPtr p, GDExtensionConstStringNamePtr method, const GDExtensionConstVariantPtr *args, GDExtensionInt argc, GDExtensionVariantPtr ret, GDExtensionCallError *err) {
    RunData *run = (RunData *) p;
    unsigned char var[40];
    unsigned char str[16];
    char *name;
    RGValue stack[8];
    RGValue out;
    int n;
    int i;
    if (err != NULL) {
        memset(err, 0, sizeof(*err));
    }
    if (ret != NULL) {
        variant_nil(ret);
    }
    if (run == NULL || !run->script->loaded) {
        if (err != NULL) {
            err->error = GDEXTENSION_CALL_ERROR_INVALID_METHOD;
        }
        return;
    }
    memset(var, 0, sizeof(var));
    from_stringname(var, (GDExtensionTypePtr) method);
    memset(str, 0, sizeof(str));
    variant_stringify(var, str);
    variant_destroy(var);
    name = string_dup(str);
    dtor_string(str);
    if (run->script == NULL || !has_member(run->script->methods, run->script->nmethods, name)) {
        if (err != NULL) {
            err->error = GDEXTENSION_CALL_ERROR_INVALID_METHOD;
        }
        free(name);
        return;
    }
    n = (int) argc;
    if (n > 8) {
        n = 8;
    }
    for (i = 0; i < n; i++) {
        rg_from_variant(&stack[i], args[i], &run->script->api);
    }
    g_api = &run->script->api;
    sync_from_node(run);
    memset(&out, 0, sizeof(out));
    run->script->api.call(&run->self, name, n > 0 ? stack : NULL, n, &out);
    sync_to_node(run);
    if (ret != NULL) {
        void *boxed = variant_from_rg(&out);
        variant_destroy(ret);
        memcpy(ret, boxed, 24);
        vfree(boxed);
    }
    free(name);
}

static GDExtensionBool inst_has_method(GDExtensionScriptInstanceDataPtr p, GDExtensionConstStringNamePtr name) {
    RunData *run = (RunData *) p;
    unsigned char var[40];
    unsigned char str[16];
    char *m;
    int yes;
    if (run == NULL) {
        return 0;
    }
    memset(var, 0, sizeof(var));
    from_stringname(var, (GDExtensionTypePtr) name);
    memset(str, 0, sizeof(str));
    variant_stringify(var, str);
    variant_destroy(var);
    m = string_dup(str);
    dtor_string(str);
    yes = has_member(run->script->methods, run->script->nmethods, m);
    free(m);
    return yes;
}

static GDExtensionObjectPtr inst_owner(GDExtensionScriptInstanceDataPtr p) {
    RunData *run = (RunData *) p;
    return run != NULL ? run->owner : NULL;
}

static GDExtensionObjectPtr inst_script(GDExtensionScriptInstanceDataPtr p) {
    RunData *run = (RunData *) p;
    if (run == NULL || run->script == NULL) {
        return NULL;
    }
    return run->script->godot;
}

static void inst_free(GDExtensionScriptInstanceDataPtr p) {
    RunData *run = (RunData *) p;
    if (run == NULL) {
        return;
    }
    if (run->script != NULL && run->script->loaded && run->script->api.slot_del != NULL) {
        run->script->api.slot_del(run->id);
    }
    slot_del(run->id);
    free(run);
}

static void prepare_info(void) {
    if (g_info_ready) {
        return;
    }
    memset(&g_instance_info, 0, sizeof(g_instance_info));
    g_instance_info.set_func = inst_set;
    g_instance_info.get_func = inst_get;
    g_instance_info.get_owner_func = inst_owner;
    g_instance_info.has_method_func = inst_has_method;
    g_instance_info.call_func = inst_call;
    g_instance_info.get_script_func = inst_script;
    g_instance_info.free_func = inst_free;
    g_info_ready = 1;
}

static void *virt_instance_create_impl(ScriptData *script, GDExtensionObjectPtr owner) {
    RunData *run;
    int64_t id;
    if (editor_hint()) {
        return make_placeholder(script, owner);
    }
    ensure_script(script);
    if (script == NULL || !script->loaded || owner == NULL) {
        return NULL;
    }
    id = (int64_t) object_get_id(owner);
    slot_put(id, owner);
    if (script->api.slot_put != NULL) {
        script->api.slot_put(id, owner);
    }
    run = (RunData *) calloc(1, sizeof(RunData));
    run->script = script;
    run->owner = owner;
    run->id = id;
    g_api = &script->api;
    script->api.new_inst(id, &run->self);
    if (has_member(script->methods, script->nmethods, "_process")) {
        void *yes = var_bool(1);
        const void *args[1];
        args[0] = yes;
        call_ok(owner, "set_process", args, 1);
        variant_destroy(yes);
        vfree(yes);
    }
    if (script->nsignals > 0) {
        int i;
        for (i = 0; i < script->nsignals; i++) {
            void *info = member_dict(&script->signals[i], 1);
            const void *sig_args[1];
            sig_args[0] = info;
            call_ok(owner, "add_user_signal", sig_args, 1);
            variant_destroy(info);
            vfree(info);
        }
    }
    prepare_info();
    return script_instance_create3(&g_instance_info, run);
}

static void virt_KIND_SCRIPT__instance_create(GDExtensionClassInstancePtr inst, const GDExtensionConstTypePtr *args, GDExtensionTypePtr ret) {
    ScriptData *script = script_of(inst);
    GDExtensionObjectPtr owner = args != NULL && args[0] != NULL ? *(GDExtensionObjectPtr *) args[0] : NULL;
    ret_ptr(ret, virt_instance_create_impl(script, owner));
}

static void virt_KIND_LANG__create_script(GDExtensionClassInstancePtr inst, const GDExtensionConstTypePtr *args, GDExtensionTypePtr ret) {
    (void) inst;
    (void) args;
    ret_ptr(ret, create_script_obj(NULL, 1));
}

static int ident_ok(const char *s) {
    int i;
    if (s == NULL || s[0] == 0 || !hi_ident_start(s[0])) {
        return 0;
    }
    for (i = 1; s[i] != 0; i++) {
        if (!hi_ident(s[i])) {
            return 0;
        }
    }
    return 1;
}

static int text_blank(const char *s) {
    if (s == NULL) {
        return 1;
    }
    while (*s == ' ' || *s == '\t' || *s == '\n' || *s == '\r') {
        s++;
    }
    return *s == 0;
}

static char *subst_token(char *src, const char *key, const char *val) {
    size_t klen;
    size_t vlen;
    size_t n;
    size_t count = 0;
    const char *p;
    char *out;
    char *dst;
    if (src == NULL || key == NULL || key[0] == 0 || val == NULL) {
        return src;
    }
    klen = strlen(key);
    vlen = strlen(val);
    for (p = src; (p = strstr(p, key)) != NULL; p += klen) {
        count++;
    }
    if (count == 0) {
        return src;
    }
    n = strlen(src) + count * (vlen - klen);
    out = (char *) malloc(n + 1);
    if (out == NULL) {
        return src;
    }
    dst = out;
    p = src;
    while (*p != 0) {
        if (strncmp(p, key, klen) == 0) {
            memcpy(dst, val, vlen);
            dst += vlen;
            p += klen;
        } else {
            *dst++ = *p++;
        }
    }
    *dst = 0;
    free(src);
    return out;
}

static void virt_KIND_LANG__make_template(GDExtensionClassInstancePtr inst, const GDExtensionConstTypePtr *args, GDExtensionTypePtr ret) {
    char *content = args != NULL && args[0] != NULL ? string_dup(args[0]) : strdup("");
    char *class_name = args != NULL && args[1] != NULL ? string_dup(args[1]) : strdup("");
    char *base_name = args != NULL && args[2] != NULL ? string_dup(args[2]) : strdup("");
    const char *cls = ident_ok(class_name) ? class_name : "Script";
    const char *base = ident_ok(base_name) ? base_name : "Node";
    char *source;
    GDExtensionObjectPtr obj;
    ScriptData *data;
    (void) inst;
    if (text_blank(content)) {
        size_t n = strlen(cls) + strlen(base) + 48;
        source = (char *) malloc(n);
        if (source != NULL) {
            snprintf(source, n, "class %s extends %s {\n\tfn _ready() {\n\t}\n}\n", cls, base);
        }
    } else {
        source = content;
        content = NULL;
        source = subst_token(source, "%CLASS%", cls);
        source = subst_token(source, "%BASE%", base);
        source = subst_token(source, "%TS%", "\t");
        source = subst_token(source, "%VOID_RETURN%", "");
    }
    obj = create_script_obj(NULL, 1);
    data = obj != NULL ? g_last_script : NULL;
    if (data != NULL) {
        free(data->source);
        data->source = source != NULL ? source : strdup("");
        source = NULL;
        free(data->base);
        data->base = strdup(base);
        free(data->class_name);
        data->class_name = strdup(cls);
    }
    free(source);
    free(content);
    free(class_name);
    free(base_name);
    ret_ptr(ret, obj);
}

static void virt_KIND_SCRIPT__set_source(GDExtensionClassInstancePtr inst, const GDExtensionConstTypePtr *args, GDExtensionTypePtr ret) {
    ScriptData *script = script_of(inst);
    (void) ret;
    if (script == NULL || args == NULL) {
        return;
    }
    free(script->source);
    script->source = string_dup(args[0]);
}

static void virt_KIND_LOADER__load(GDExtensionClassInstancePtr inst, const GDExtensionConstTypePtr *args, GDExtensionTypePtr ret) {
    char *path;
    GDExtensionObjectPtr obj;
    ScriptData *data;
    (void) inst;
    variant_nil(ret);
    if (args == NULL) {
        return;
    }
    path = string_dup(args[0]);
    obj = create_script_obj(NULL, 1);
    if (obj == NULL) {
        free(path);
        return;
    }
    data = g_last_script;
    if (data != NULL) {
        free(data->path);
        data->path = strdup(path != NULL ? path : "");
        free(data->source);
        data->source = read_text_file(data->path);
    }
    {
        void *p = var_string(path);
        const void *call_args[1];
        call_args[0] = p;
        call_ok(obj, "set_path", call_args, 1);
        variant_destroy(p);
        vfree(p);
    }
    variant_destroy(ret);
    from_object(ret, &obj);
    free(path);
}

static char *globalize(const char *path) {
    GDExtensionObjectPtr ps;
    unsigned char sn[16];
    void *self;
    void *arg;
    void *ret;
    unsigned char str[16];
    char *out;
    GDExtensionCallError err;
    if (path == NULL) {
        return strdup("");
    }
    if (strncmp(path, "res://", 6) != 0 && strncmp(path, "user://", 7) != 0) {
        return strdup(path);
    }
    sn_new(sn, "ProjectSettings");
    ps = global_get_singleton(sn);
    sn_free(sn);
    if (ps == NULL) {
        return strdup(path);
    }
    self = var_object(ps);
    arg = var_string(path);
    ret = vbuf();
    sn_new(sn, "globalize_path");
    memset(&err, 0, sizeof(err));
    variant_call(self, sn, (const GDExtensionConstVariantPtr *) &arg, 1, ret, &err);
    sn_free(sn);
    memset(str, 0, sizeof(str));
    to_string(str, ret);
    out = string_dup(str);
    dtor_string(str);
    variant_destroy(ret);
    vfree(ret);
    variant_destroy(arg);
    vfree(arg);
    variant_destroy(self);
    vfree(self);
    return out;
}

static char *read_text_file(const char *path) {
    char *global;
    FILE *file;
    long n;
    char *buf;
    size_t got;
    global = globalize(path);
    if (global == NULL || global[0] == 0) {
        free(global);
        return strdup("");
    }
    file = fopen(global, "rb");
    free(global);
    if (file == NULL) {
        return strdup("");
    }
    if (fseek(file, 0, SEEK_END) != 0) {
        fclose(file);
        return strdup("");
    }
    n = ftell(file);
    if (n < 0 || fseek(file, 0, SEEK_SET) != 0) {
        fclose(file);
        return strdup("");
    }
    buf = (char *) malloc((size_t) n + 1);
    if (buf == NULL) {
        fclose(file);
        return strdup("");
    }
    got = fread(buf, 1, (size_t) n, file);
    fclose(file);
    buf[got] = 0;
    return buf;
}

static int write_text_file(const char *path, const char *text) {
    char *global;
    FILE *file;
    size_t n;
    size_t got;
    global = globalize(path);
    if (global == NULL || global[0] == 0) {
        free(global);
        return 0;
    }
    file = fopen(global, "wb");
    free(global);
    if (file == NULL) {
        return 0;
    }
    n = text != NULL ? strlen(text) : 0;
    got = n == 0 ? 0 : fwrite(text, 1, n, file);
    fclose(file);
    return got == n;
}

static int ends_with_rg(const char *path) {
    size_t n;
    char a;
    char b;
    if (path == NULL) {
        return 0;
    }
    n = strlen(path);
    if (n < 3 || path[n - 3] != '.') {
        return 0;
    }
    a = path[n - 2];
    b = path[n - 1];
    if (a >= 'A' && a <= 'Z') {
        a = (char) (a - 'A' + 'a');
    }
    if (b >= 'A' && b <= 'Z') {
        b = (char) (b - 'A' + 'a');
    }
    return a == 'r' && b == 'g';
}

static ScriptData *script_from_object(GDExtensionObjectPtr obj) {
    GDObjectInstanceID id;
    int i;
    if (obj == NULL || object_get_id == NULL) {
        return NULL;
    }
    id = object_get_id(obj);
    for (i = 0; i < g_nscript_ids; i++) {
        if (g_script_ids[i] == id) {
            return g_script_data[i];
        }
    }
    return NULL;
}

static GDExtensionObjectPtr arg_object(const GDExtensionConstTypePtr *args, int index) {
    if (args == NULL || args[index] == NULL) {
        return NULL;
    }
    return *(GDExtensionObjectPtr *) args[index];
}

static void virt_saver(GDExtensionClassInstancePtr inst, const GDExtensionConstTypePtr *args, GDExtensionTypePtr ret, const char *name) {
    (void) inst;
    if (strcmp(name, "_save") == 0) {
        GDExtensionObjectPtr res = arg_object(args, 0);
        char *path = args != NULL ? string_dup((GDExtensionConstTypePtr) args[1]) : strdup("");
        ScriptData *script = script_from_object(res);
        int ok = 0;
        if (script != NULL) {
            if (path[0] == 0 && script->path != NULL) {
                free(path);
                path = strdup(script->path);
            }
            ok = write_text_file(path, script->source != NULL ? script->source : "");
        }
        free(path);
        ret_int(ret, ok ? 0 : 13);
    } else if (strcmp(name, "_set_uid") == 0) {
        ret_int(ret, 0);
    } else if (strcmp(name, "_recognize") == 0) {
        ret_bool(ret, script_from_object(arg_object(args, 0)) != NULL);
    } else if (strcmp(name, "_get_recognized_extensions") == 0) {
        if (script_from_object(arg_object(args, 0)) != NULL) {
            ret_one_extension(ret);
        } else {
            ret_empty_packed(ret);
        }
    } else if (strcmp(name, "_recognize_path") == 0) {
        char *path = args != NULL ? string_dup((GDExtensionConstTypePtr) args[1]) : strdup("");
        int yes = script_from_object(arg_object(args, 0)) != NULL && ends_with_rg(path);
        free(path);
        ret_bool(ret, yes);
    }
}

static int compile_script(const char *rg_path, const char *dll_path) {
    const char *cp = getenv("ROSEGOLD_CP");
    const char *java = getenv("ROSEGOLD_JAVA");
    char found[1024];
    char cmd[4096];
#if defined(_WIN32)
    STARTUPINFOA si;
    PROCESS_INFORMATION pi;
    DWORD code = 1;
#endif
    if (java == NULL || java[0] == 0) {
        java = "java";
    }
    if (cp == NULL || cp[0] == 0) {
        char dir[1024];
        size_t n = strlen(rg_path);
        if (n >= sizeof(dir)) {
            return 0;
        }
        memcpy(dir, rg_path, n + 1);
        found[0] = 0;
        while (dir[0] != 0) {
            char classes[1100];
            char resources[1100];
            char *slash;
            snprintf(classes, sizeof(classes), "%s\\build\\classes\\java\\main", dir);
            snprintf(resources, sizeof(resources), "%s\\build\\resources\\main", dir);
#if defined(_WIN32)
            if (GetFileAttributesA(classes) != INVALID_FILE_ATTRIBUTES) {
                snprintf(found, sizeof(found), "%s;%s", classes, resources);
                break;
            }
#else
            snprintf(classes, sizeof(classes), "%s/build/classes/java/main", dir);
            snprintf(resources, sizeof(resources), "%s/build/resources/main", dir);
            if (access(classes, 0) == 0) {
                snprintf(found, sizeof(found), "%s:%s", classes, resources);
                break;
            }
#endif
            slash = strrchr(dir, '\\');
            if (slash == NULL) {
                slash = strrchr(dir, '/');
            }
            if (slash == NULL) {
                break;
            }
            *slash = 0;
        }
        cp = found;
    }
    if (cp == NULL || cp[0] == 0) {
        if (print_error_fn != NULL) {
            print_error_fn("ROSEGOLD_CP is unset and build/classes/java/main was not found", "rosegold", __FILE__, __LINE__, 1);
        }
        return 0;
    }
    snprintf(cmd, sizeof(cmd), "\"%s\" -cp \"%s\" com.rosegoldc.lang.Main llvm --godot \"%s\" -o \"%s\"",
            java, cp, rg_path, dll_path);
#if defined(_WIN32)
    memset(&si, 0, sizeof(si));
    si.cb = sizeof(si);
    memset(&pi, 0, sizeof(pi));
    if (!CreateProcessA(NULL, cmd, NULL, NULL, 0, CREATE_NO_WINDOW, NULL, NULL, &si, &pi)) {
        return 0;
    }
    WaitForSingleObject(pi.hProcess, 120000);
    GetExitCodeProcess(pi.hProcess, &code);
    CloseHandle(pi.hProcess);
    CloseHandle(pi.hThread);
    return code == 0;
#else
    return system(cmd) == 0;
#endif
}

static void *sym(void *lib, const char *name) {
#if defined(_WIN32)
    return (void *) GetProcAddress((HMODULE) lib, name);
#else
    return dlsym(lib, name);
#endif
}

static int load_library(ScriptData *script, const char *dll_path) {
    void *lib;
    ScriptApi api;
    if (script == NULL || dll_path == NULL) {
        return 0;
    }
#if defined(_WIN32)
    lib = (void *) LoadLibraryA(dll_path);
#else
    lib = dlopen(dll_path, RTLD_NOW);
#endif
    if (lib == NULL) {
        return 0;
    }
    memset(&api, 0, sizeof(api));
    api.init = sym(lib, "rg_script_init");
    api.new_inst = sym(lib, "rg_script_new");
    api.call = sym(lib, "rg_script_call");
    api.call_val = sym(lib, "rg_script_call_val");
    api.fn_same = sym(lib, "rg_script_fn_same");
    api.fn_hash = sym(lib, "rg_script_fn_hash");
    api.get = sym(lib, "rg_script_get");
    api.set = sym(lib, "rg_script_set");
    api.set_signal = sym(lib, "rg_script_set_signal");
    api.set_slots = sym(lib, "rg_script_set_slots");
    api.slot_put = sym(lib, "rg_script_slot_put");
    api.slot_del = sym(lib, "rg_script_slot_del");
    api.set_float = sym(lib, "rg_script_set_float");
    api.get_float = sym(lib, "rg_script_get_float");
    api.set_bool = sym(lib, "rg_script_set_bool");
    api.set_int = sym(lib, "rg_script_set_int");
    api.set_string = sym(lib, "rg_script_set_string");
    api.failed = sym(lib, "rg_script_failed");
    api.set_call = sym(lib, "rg_script_set_call");
    api.set_prop = sym(lib, "rg_script_set_prop");
    api.type_name = sym(lib, "rg_script_type_name");
    api.has_field = sym(lib, "rg_script_has_field");
    api.new_vec2 = sym(lib, "rg_script_new_vec2");
    api.new_vec2i = sym(lib, "rg_script_new_vec2i");
    api.new_vec3 = sym(lib, "rg_script_new_vec3");
    api.new_vec4 = sym(lib, "rg_script_new_vec4");
    api.new_vec3i = sym(lib, "rg_script_new_vec3i");
    api.new_color = sym(lib, "rg_script_new_color");
    api.new_rect2 = sym(lib, "rg_script_new_rect2");
    api.new_transform2d = sym(lib, "rg_script_new_transform2d");
    api.new_array = sym(lib, "rg_script_new_array");
    api.array_push = sym(lib, "rg_script_array_push");
    api.len = sym(lib, "rg_script_len");
    api.array_get = sym(lib, "rg_script_array_get");
    api.new_map = sym(lib, "rg_script_new_map");
    api.map_put = sym(lib, "rg_script_map_put");
    api.map_key_at = sym(lib, "rg_script_map_key_at");
    api.map_get = sym(lib, "rg_script_map_get");
    api.new_struct = sym(lib, "rg_script_new_struct");
    api.new_data = sym(lib, "rg_script_new_data");
    if (api.init == NULL || api.new_inst == NULL || api.call == NULL || api.get == NULL || api.set == NULL) {
#if defined(_WIN32)
        FreeLibrary((HMODULE) lib);
#else
        dlclose(lib);
#endif
        return 0;
    }
    api.init();
    if (api.set_slots != NULL) {
        api.set_slots(slot_get, slot_put, slot_del);
    }
    if (api.set_signal != NULL) {
        api.set_signal(on_signal_full);
    }
    if (api.set_call != NULL) {
        api.set_call(on_godot_call);
    }
    if (api.set_prop != NULL) {
        api.set_prop(on_godot_prop);
    }
    {
        void (*set_singleton)(void (*)(RGValue *, const char *));
        set_singleton = sym(lib, "rg_script_set_singleton");
        if (set_singleton != NULL) {
            set_singleton(on_singleton);
        }
    }
    {
        void (*set_static)(void (*)(const char *, const char *, const RGValue *, int32_t, RGValue *));
        set_static = sym(lib, "rg_script_set_static");
        if (set_static != NULL) {
            set_static(on_godot_static);
        }
    }
    {
        void (*set_fn)(void (*)(const RGValue *, const char *, const RGValue *, int32_t, RGValue *));
        g_fn_host = sym(lib, "rg_script_fn_host");
        g_fn_host_id = sym(lib, "rg_script_fn_host_id");
        set_fn = sym(lib, "rg_script_set_fn");
        if (set_fn != NULL) {
            set_fn(on_godot_fn);
        }
    }
    {
        void (*set_value)(int32_t (*)(const char *, const RGValue *, const char *, const RGValue *, int32_t, RGValue *));
        set_value = sym(lib, "rg_script_set_value");
        if (set_value != NULL) {
            set_value(on_godot_value);
        }
    }
    script->api = api;
    script->library = lib;
    script->loaded = 1;
    g_api = &script->api;
    return 1;
}

static char *json_unescape(const char *start, const char *end) {
    char *out = (char *) malloc((size_t) (end - start) + 1);
    char *w = out;
    const char *p = start;
    if (out == NULL) {
        return strdup("");
    }
    while (p < end) {
        if (*p == '\\' && p + 1 < end) {
            p++;
            if (*p == 'n') {
                *w++ = '\n';
            } else if (*p == 'r') {
                *w++ = '\r';
            } else if (*p == 't') {
                *w++ = '\t';
            } else {
                *w++ = *p;
            }
            p++;
        } else {
            *w++ = *p++;
        }
    }
    *w = 0;
    return out;
}

static char *json_find_string(const char *json, const char *key) {
    char pat[64];
    const char *hit;
    const char *start;
    const char *end;
    snprintf(pat, sizeof(pat), "\"%s\"", key);
    hit = strstr(json, pat);
    if (hit == NULL) {
        return NULL;
    }
    hit = strchr(hit + strlen(pat), '"');
    if (hit == NULL) {
        return NULL;
    }
    start = hit + 1;
    end = start;
    while (*end != 0 && *end != '"') {
        if (*end == '\\' && end[1] != 0) {
            end += 2;
        } else {
            end++;
        }
    }
    return json_unescape(start, end);
}

static void read_manifest(ScriptData *script, const char *json_path) {
    FILE *f;
    char *buf;
    long n;
    char *base;
    char *cls;
    if (script == NULL || json_path == NULL) {
        return;
    }
    f = fopen(json_path, "rb");
    if (f == NULL) {
        scan_exports(script);
        return;
    }
    fseek(f, 0, SEEK_END);
    n = ftell(f);
    fseek(f, 0, SEEK_SET);
    buf = (char *) malloc((size_t) n + 1);
    if (buf == NULL) {
        fclose(f);
        return;
    }
    if (fread(buf, 1, (size_t) n, f) != (size_t) n) {
        free(buf);
        fclose(f);
        return;
    }
    buf[n] = 0;
    fclose(f);
    base = json_find_string(buf, "base");
    cls = json_find_string(buf, "class");
    free(script->base);
    free(script->class_name);
    script->base = base != NULL ? base : strdup("Node2D");
    script->class_name = cls != NULL ? cls : strdup("");
    free_members(script->methods, script->nmethods);
    free_members(script->vars, script->nvars);
    free_members(script->signals, script->nsignals);
    script->methods = NULL;
    script->vars = NULL;
    script->signals = NULL;
    script->nmethods = 0;
    script->nvars = 0;
    script->nsignals = 0;
    {
        const char *sections[] = {"methods", "vars", "signals"};
        int s;
        for (s = 0; s < 3; s++) {
            char pat[32];
            const char *hit;
            const char *p;
            Member *items = NULL;
            int count = 0;
            snprintf(pat, sizeof(pat), "\"%s\"", sections[s]);
            hit = strstr(buf, pat);
            if (hit == NULL) {
                continue;
            }
            p = strchr(hit, '[');
            if (p == NULL) {
                continue;
            }
            p++;
            while (*p != 0 && *p != ']') {
                const char *obj;
                const char *name_key;
                if (*p != '{') {
                    p++;
                    continue;
                }
                obj = p;
                name_key = strstr(obj, "\"name\"");
                if (name_key != NULL && name_key < strchr(obj, '}')) {
                    const char *q = strchr(name_key + 6, '"');
                    const char *e;
                    Member m;
                    memset(&m, 0, sizeof(m));
                    if (q != NULL) {
                        q++;
                        e = q;
                        while (*e != 0 && *e != '"') {
                            e++;
                        }
                        m.name = json_unescape(q, e);
                    }
                    {
                        const char *tk = strstr(obj, "\"type\"");
                        const char *endobj = strchr(obj, '}');
                        if (tk != NULL && endobj != NULL && tk < endobj) {
                            const char *q2 = strchr(tk + 6, '"');
                            if (q2 != NULL && q2 < endobj) {
                                const char *e2;
                                q2++;
                                e2 = q2;
                                while (*e2 != 0 && *e2 != '"') {
                                    e2++;
                                }
                                m.type = json_unescape(q2, e2);
                            }
                        }
                    }
                    if (s == 1) {
                        const char *endobj = strchr(obj, '}');
                        const char *dk = strstr(obj, "\"default\"");
                        if (dk != NULL && endobj != NULL && dk < endobj) {
                            const char *p = dk + 9;
                            while (p < endobj && (*p == ' ' || *p == '\t' || *p == ':')) {
                                p++;
                            }
                            if (p + 4 <= endobj && strncmp(p, "true", 4) == 0) {
                                m.has_number = 1;
                                m.number = 1;
                            } else if (p + 5 <= endobj && strncmp(p, "false", 5) == 0) {
                                m.has_number = 1;
                                m.number = 0;
                            } else if (*p == '-' || (*p >= '0' && *p <= '9')) {
                                m.number = strtod(p, NULL);
                                m.has_number = 1;
                            }
                        }
                        {
                            const char *ek = strstr(obj, "\"export\"");
                            if (ek != NULL && endobj != NULL && ek < endobj) {
                                const char *ep = ek + 8;
                                while (ep < endobj && (*ep == ' ' || *ep == '\t' || *ep == ':')) {
                                    ep++;
                                }
                                if (ep + 4 <= endobj && strncmp(ep, "true", 4) == 0) {
                                    m.exported = 1;
                                }
                            }
                        }
                    }
                    items = (Member *) realloc(items, (size_t) (count + 1) * sizeof(Member));
                    items[count++] = m;
                }
                p = strchr(obj, '}');
                if (p == NULL) {
                    break;
                }
                p++;
            }
            if (s == 0) {
                script->methods = items;
                script->nmethods = count;
            } else if (s == 1) {
                script->vars = items;
                script->nvars = count;
            } else {
                script->signals = items;
                script->nsignals = count;
            }
        }
    }
    {
        const char *hit = strstr(buf, "\"structs\"");
        const char *p = hit != NULL ? strchr(hit, '[') : NULL;
        clear_structs(script);
        if (p != NULL) {
            p++;
            while (*p != 0 && *p != ']') {
                const char *obj;
                const char *endobj;
                const char *sk;
                const char *nk;
                const char *tk;
                char *sname = NULL;
                char *fname = NULL;
                char *ftype = NULL;
                if (*p != '{') {
                    p++;
                    continue;
                }
                obj = p;
                endobj = strchr(obj, '}');
                if (endobj == NULL) {
                    break;
                }
                sk = strstr(obj, "\"struct\"");
                nk = strstr(obj, "\"name\"");
                tk = strstr(obj, "\"type\"");
                if (sk != NULL && sk < endobj) {
                    const char *q = strchr(sk + 8, '"');
                    if (q != NULL && q < endobj) {
                        const char *e = q + 1;
                        q++;
                        while (*e != 0 && *e != '"') {
                            e++;
                        }
                        sname = json_unescape(q, e);
                    }
                }
                if (nk != NULL && nk < endobj) {
                    const char *q = strchr(nk + 6, '"');
                    if (q != NULL && q < endobj) {
                        const char *e;
                        q++;
                        e = q;
                        while (*e != 0 && *e != '"') {
                            e++;
                        }
                        fname = json_unescape(q, e);
                    }
                }
                if (tk != NULL && tk < endobj) {
                    const char *q = strchr(tk + 6, '"');
                    if (q != NULL && q < endobj) {
                        const char *e;
                        q++;
                        e = q;
                        while (*e != 0 && *e != '"') {
                            e++;
                        }
                        ftype = json_unescape(q, e);
                    }
                }
                if (sname != NULL) {
                    const char *dk = strstr(obj, "\"data\"");
                    int as_data = 0;
                    if (dk != NULL && dk < endobj) {
                        const char *ep = dk + 6;
                        while (ep < endobj && (*ep == ' ' || *ep == '\t' || *ep == ':')) {
                            ep++;
                        }
                        if (ep + 4 <= endobj && strncmp(ep, "true", 4) == 0) {
                            as_data = 1;
                        }
                    }
                    add_struct_field(script, sname, fname != NULL ? fname : "", ftype != NULL ? ftype : "", as_data);
                }
                free(sname);
                free(fname);
                free(ftype);
                p = endobj + 1;
            }
        }
        if (script->source != NULL && script->source[0] != 0) {
            scan_structs(script);
            scan_exports(script);
        }
    }
    free(buf);
}

static void register_resource(void) {
    unsigned char name_sn[16];
    unsigned char parent_sn[16];
    GDExtensionClassCreationInfo4 info;
    memset(&info, 0, sizeof(info));
    info.is_exposed = 1;
    info.set_func = res_set;
    info.get_func = res_get;
    info.get_property_list_func = res_props;
    info.free_property_list_func = res_props_free;
    info.create_instance_func = create_res;
    info.free_instance_func = free_instance;
    info.class_userdata = (void *) (intptr_t) KIND_RES;
    sn_new(name_sn, "RoseGoldResource");
    sn_new(parent_sn, "Resource");
    classdb_register(g_library, name_sn, parent_sn, &info);
    sn_free(name_sn);
    sn_free(parent_sn);
}

static void *load_proc(const char *name) {
    return (void *) g_get_proc(name);
}

static void initialize(void *userdata, GDExtensionInitializationLevel level) {
    GDExtensionObjectPtr engine;
    GDExtensionObjectPtr loader_singleton;
    unsigned char sn[16];
    void *lang_var;
    void *loader_var;
    const void *args[1];
    (void) userdata;
    if (level == GDEXTENSION_INITIALIZATION_EDITOR) {
        unsigned char sn[16];
        void *tag = NULL;
        if (classdb_get_class_tag == NULL) {
            return;
        }
        sn_new(sn, "EditorSyntaxHighlighter");
        tag = classdb_get_class_tag(sn);
        sn_free(sn);
        if (tag == NULL) {
            return;
        }
        register_class("RoseGoldHighlighter", "EditorSyntaxHighlighter", KIND_HIGHLIGHT, create_highlighter);
        return;
    }
    if (level != GDEXTENSION_INITIALIZATION_SCENE) {
        return;
    }
    register_class("RoseGoldLanguage", "ScriptLanguageExtension", KIND_LANG, create_lang);
    register_class("RoseGoldScript", "ScriptExtension", KIND_SCRIPT, create_script_obj);
    register_class("RoseGoldLoader", "ResourceFormatLoader", KIND_LOADER, create_loader);
    register_class("RoseGoldSaver", "ResourceFormatSaver", KIND_SAVER, create_saver);
    register_resource();
    g_language = create_lang(NULL, 1);
    g_loader = create_loader(NULL, 1);
    g_saver = create_saver(NULL, 1);
    g_language_id = g_language != NULL && object_get_id != NULL ? object_get_id(g_language) : 0;
    g_loader_id = g_loader != NULL && object_get_id != NULL ? object_get_id(g_loader) : 0;
    g_saver_id = g_saver != NULL && object_get_id != NULL ? object_get_id(g_saver) : 0;
    sn_new(sn, "Engine");
    engine = global_get_singleton(sn);
    sn_free(sn);
    lang_var = var_object(g_language);
    args[0] = lang_var;
    call_ok(engine, "register_script_language", args, 1);
    variant_destroy(lang_var);
    vfree(lang_var);
    sn_new(sn, "ResourceLoader");
    loader_singleton = global_get_singleton(sn);
    sn_free(sn);
    loader_var = var_object(g_loader);
    args[0] = loader_var;
    call_ok(loader_singleton, "add_resource_format_loader", args, 1);
    variant_destroy(loader_var);
    vfree(loader_var);
    sn_new(sn, "ResourceSaver");
    loader_singleton = global_get_singleton(sn);
    sn_free(sn);
    loader_var = var_object(g_saver);
    args[0] = loader_var;
    call_ok(loader_singleton, "add_resource_format_saver", args, 1);
    variant_destroy(loader_var);
    vfree(loader_var);
}

/* construct_object3 returns a RefCounted with refcount 1 that the caller owns.
 * A temporary Variant adds one ref, unreference drops one, and destroying the
 * Variant drops the other, so the net is the owned ref. The last ref deletes
 * the object while the extension class is still alive. */
static void drop_one_ref(GDExtensionObjectPtr obj) {
    void *self;
    void *ret;
    unsigned char sn[16];
    GDExtensionCallError err;
    if (obj == NULL) {
        return;
    }
    self = var_object(obj);
    ret = vbuf();
    sn_new(sn, "unreference");
    memset(&err, 0, sizeof(err));
    variant_call(self, sn, NULL, 0, ret, &err);
    sn_free(sn);
    variant_destroy(ret);
    vfree(ret);
    variant_destroy(self);
    vfree(self);
}

static GDExtensionObjectPtr still_alive(GDObjectInstanceID id) {
    if (id == 0 || object_from_id == NULL) {
        return NULL;
    }
    return object_from_id(id);
}

static void deinitialize(void *userdata, GDExtensionInitializationLevel level) {
    GDExtensionObjectPtr engine;
    GDExtensionObjectPtr loader_singleton;
    GDExtensionObjectPtr obj;
    unsigned char sn[16];
    void *var;
    const void *args[1];
    GDObjectInstanceID pending[64];
    int pending_count;
    int i;
    (void) userdata;
    if (level != GDEXTENSION_INITIALIZATION_SCENE) {
        return;
    }
    free_callables();
    val_shutdown();
    {
        GDObjectInstanceID held[256];
        int held_count = g_nres;
        int h;
        if (held_count > 256) {
            held_count = 256;
        }
        for (h = 0; h < held_count; h++) {
            held[h] = g_res_ids[h];
        }
        for (h = 0; h < held_count; h++) {
            drop_one_ref(still_alive(held[h]));
        }
    }
    {
        GDObjectInstanceID held[256];
        int held_count = g_nowned;
        int h;
        if (held_count > 256) {
            held_count = 256;
        }
        for (h = 0; h < held_count; h++) {
            held[h] = g_owned_ids[h];
        }
        g_nowned = 0;
        for (h = 0; h < held_count; h++) {
            drop_one_ref(still_alive(held[h]));
        }
    }
    pending_count = g_nscript_ids;
    if (pending_count > 64) {
        pending_count = 64;
    }
    for (i = 0; i < pending_count; i++) {
        pending[i] = g_script_ids[i];
    }
    for (i = 0; i < pending_count; i++) {
        drop_one_ref(still_alive(pending[i]));
    }
    obj = still_alive(g_loader_id);
    if (obj != NULL) {
        sn_new(sn, "ResourceLoader");
        loader_singleton = global_get_singleton(sn);
        sn_free(sn);
        var = var_object(obj);
        args[0] = var;
        call_ok(loader_singleton, "remove_resource_format_loader", args, 1);
        variant_destroy(var);
        vfree(var);
        drop_one_ref(still_alive(g_loader_id));
    }
    g_loader = NULL;
    g_loader_id = 0;
    obj = still_alive(g_saver_id);
    if (obj != NULL) {
        sn_new(sn, "ResourceSaver");
        loader_singleton = global_get_singleton(sn);
        sn_free(sn);
        var = var_object(obj);
        args[0] = var;
        call_ok(loader_singleton, "remove_resource_format_saver", args, 1);
        variant_destroy(var);
        vfree(var);
        drop_one_ref(still_alive(g_saver_id));
    }
    g_saver = NULL;
    g_saver_id = 0;
    obj = still_alive(g_language_id);
    if (obj != NULL) {
        sn_new(sn, "Engine");
        engine = global_get_singleton(sn);
        sn_free(sn);
        var = var_object(obj);
        args[0] = var;
        call_ok(engine, "unregister_script_language", args, 1);
        variant_destroy(var);
        vfree(var);
        if (object_destroy != NULL && still_alive(g_language_id) != NULL) {
            object_destroy(obj);
        }
    }
    g_language = NULL;
    g_language_id = 0;
}

RG_EXPORT GDExtensionBool rosegold_library_init(GDExtensionInterfaceGetProcAddress p_get_proc_address, GDExtensionClassLibraryPtr p_library, GDExtensionInitialization *r_initialization) {
    g_get_proc = p_get_proc_address;
    g_library = p_library;
    string_new_utf8 = (GDExtensionInterfaceStringNewWithUtf8Chars) load_proc("string_new_with_utf8_chars");
    stringname_new_utf8 = (GDExtensionInterfaceStringNameNewWithUtf8Chars) load_proc("string_name_new_with_utf8_chars");
    string_to_utf8 = (GDExtensionInterfaceStringToUtf8Chars) load_proc("string_to_utf8_chars");
    variant_nil = (GDExtensionInterfaceVariantNewNil) load_proc("variant_new_nil");
    variant_new_copy = (GDExtensionInterfaceVariantNewCopy) load_proc("variant_new_copy");
    variant_destroy = (GDExtensionInterfaceVariantDestroy) load_proc("variant_destroy");
    variant_call = (GDExtensionInterfaceVariantCall) load_proc("variant_call");
    variant_call_static = (GDExtensionInterfaceVariantCallStatic) load_proc("variant_call_static");
    variant_get_indexed = (GDExtensionInterfaceVariantGetIndexed) load_proc("variant_get_indexed");
    variant_get_keyed = (GDExtensionInterfaceVariantGetKeyed) load_proc("variant_get_keyed");
    variant_set_keyed = (GDExtensionInterfaceVariantSetKeyed) load_proc("variant_set_keyed");
    variant_get_type = (GDExtensionInterfaceVariantGetType) load_proc("variant_get_type");
    variant_stringify = (GDExtensionInterfaceVariantStringify) load_proc("variant_stringify");
    get_from_type = (GDExtensionInterfaceGetVariantFromTypeConstructor) load_proc("get_variant_from_type_constructor");
    get_to_type = (GDExtensionInterfaceGetVariantToTypeConstructor) load_proc("get_variant_to_type_constructor");
    get_ptr_ctor = (GDExtensionInterfaceVariantGetPtrConstructor) load_proc("variant_get_ptr_constructor");
    get_ptr_dtor = (GDExtensionInterfaceVariantGetPtrDestructor) load_proc("variant_get_ptr_destructor");
    global_get_singleton = (GDExtensionInterfaceGlobalGetSingleton) load_proc("global_get_singleton");
    classdb_register = (GDExtensionInterfaceClassdbRegisterExtensionClass4) load_proc("classdb_register_extension_class4");
    classdb_construct = (GDExtensionInterfaceClassdbConstructObject2) load_proc("classdb_construct_object3");
    object_set_instance = (GDExtensionInterfaceObjectSetInstance) load_proc("object_set_instance");
    object_get_id = (GDExtensionInterfaceObjectGetInstanceId) load_proc("object_get_instance_id");
    object_from_id = (GDExtensionInterfaceObjectGetInstanceFromId) load_proc("object_get_instance_from_id");
    object_destroy = (GDExtensionInterfaceObjectDestroy) load_proc("object_destroy");
    script_instance_create3 = (GDExtensionInterfaceScriptInstanceCreate3) load_proc("script_instance_create3");
    placeholder_script_instance_create = (GDExtensionInterfacePlaceHolderScriptInstanceCreate) load_proc("placeholder_script_instance_create");
    placeholder_script_instance_update = (GDExtensionInterfacePlaceHolderScriptInstanceUpdate) load_proc("placeholder_script_instance_update");
    callable_custom_create2 = (GDExtensionInterfaceCallableCustomCreate2) load_proc("callable_custom_create2");
    callable_custom_get_userdata = (GDExtensionInterfaceCallableCustomGetUserData) load_proc("callable_custom_get_userdata");
    mem_alloc = (GDExtensionInterfaceMemAlloc) load_proc("mem_alloc");
    mem_free = (GDExtensionInterfaceMemFree) load_proc("mem_free");
    print_error_fn = (GDExtensionInterfacePrintError) load_proc("print_error");
    variant_set = (GDExtensionInterfaceVariantSet) load_proc("variant_set");
    classdb_get_class_tag = (GDExtensionInterfaceClassdbGetClassTag) load_proc("classdb_get_class_tag");
    from_bool = get_from_type(GDEXTENSION_VARIANT_TYPE_BOOL);
    from_int = get_from_type(GDEXTENSION_VARIANT_TYPE_INT);
    from_float = get_from_type(GDEXTENSION_VARIANT_TYPE_FLOAT);
    from_string = get_from_type(GDEXTENSION_VARIANT_TYPE_STRING);
    from_stringname = get_from_type(GDEXTENSION_VARIANT_TYPE_STRING_NAME);
    from_object = get_from_type(GDEXTENSION_VARIANT_TYPE_OBJECT);
    from_vector2 = get_from_type(GDEXTENSION_VARIANT_TYPE_VECTOR2);
    from_vector2i = get_from_type(GDEXTENSION_VARIANT_TYPE_VECTOR2I);
    from_vector3 = get_from_type(GDEXTENSION_VARIANT_TYPE_VECTOR3);
    from_vector3i = get_from_type(GDEXTENSION_VARIANT_TYPE_VECTOR3I);
    from_vector4 = get_from_type(GDEXTENSION_VARIANT_TYPE_VECTOR4);
    from_vector4i = get_from_type(GDEXTENSION_VARIANT_TYPE_VECTOR4I);
    from_rect2 = get_from_type(GDEXTENSION_VARIANT_TYPE_RECT2);
    from_rect2i = get_from_type(GDEXTENSION_VARIANT_TYPE_RECT2I);
    from_transform2d = get_from_type(GDEXTENSION_VARIANT_TYPE_TRANSFORM2D);
    from_plane = get_from_type(GDEXTENSION_VARIANT_TYPE_PLANE);
    from_quaternion = get_from_type(GDEXTENSION_VARIANT_TYPE_QUATERNION);
    from_aabb = get_from_type(GDEXTENSION_VARIANT_TYPE_AABB);
    from_basis = get_from_type(GDEXTENSION_VARIANT_TYPE_BASIS);
    from_transform3d = get_from_type(GDEXTENSION_VARIANT_TYPE_TRANSFORM3D);
    from_projection = get_from_type(GDEXTENSION_VARIANT_TYPE_PROJECTION);
    from_signal = get_from_type(GDEXTENSION_VARIANT_TYPE_SIGNAL);
    from_array = get_from_type(GDEXTENSION_VARIANT_TYPE_ARRAY);
    from_dict = get_from_type(GDEXTENSION_VARIANT_TYPE_DICTIONARY);
    from_packed_string = get_from_type(GDEXTENSION_VARIANT_TYPE_PACKED_STRING_ARRAY);
    from_color = get_from_type(GDEXTENSION_VARIANT_TYPE_COLOR);
    from_callable = get_from_type(GDEXTENSION_VARIANT_TYPE_CALLABLE);
    from_rid = get_from_type(GDEXTENSION_VARIANT_TYPE_RID);
    to_bool = get_to_type(GDEXTENSION_VARIANT_TYPE_BOOL);
    to_int = get_to_type(GDEXTENSION_VARIANT_TYPE_INT);
    to_float = get_to_type(GDEXTENSION_VARIANT_TYPE_FLOAT);
    to_string = get_to_type(GDEXTENSION_VARIANT_TYPE_STRING);
    to_object = get_to_type(GDEXTENSION_VARIANT_TYPE_OBJECT);
    to_vector2 = get_to_type(GDEXTENSION_VARIANT_TYPE_VECTOR2);
    to_vector2i = get_to_type(GDEXTENSION_VARIANT_TYPE_VECTOR2I);
    to_vector3 = get_to_type(GDEXTENSION_VARIANT_TYPE_VECTOR3);
    to_vector3i = get_to_type(GDEXTENSION_VARIANT_TYPE_VECTOR3I);
    to_vector4 = get_to_type(GDEXTENSION_VARIANT_TYPE_VECTOR4);
    to_vector4i = get_to_type(GDEXTENSION_VARIANT_TYPE_VECTOR4I);
    to_rect2 = get_to_type(GDEXTENSION_VARIANT_TYPE_RECT2);
    to_rect2i = get_to_type(GDEXTENSION_VARIANT_TYPE_RECT2I);
    to_transform2d = get_to_type(GDEXTENSION_VARIANT_TYPE_TRANSFORM2D);
    to_plane = get_to_type(GDEXTENSION_VARIANT_TYPE_PLANE);
    to_quaternion = get_to_type(GDEXTENSION_VARIANT_TYPE_QUATERNION);
    to_aabb = get_to_type(GDEXTENSION_VARIANT_TYPE_AABB);
    to_basis = get_to_type(GDEXTENSION_VARIANT_TYPE_BASIS);
    to_transform3d = get_to_type(GDEXTENSION_VARIANT_TYPE_TRANSFORM3D);
    to_projection = get_to_type(GDEXTENSION_VARIANT_TYPE_PROJECTION);
    to_signal = get_to_type(GDEXTENSION_VARIANT_TYPE_SIGNAL);
    to_array = get_to_type(GDEXTENSION_VARIANT_TYPE_ARRAY);
    to_dict = get_to_type(GDEXTENSION_VARIANT_TYPE_DICTIONARY);
    to_packed_string = get_to_type(GDEXTENSION_VARIANT_TYPE_PACKED_STRING_ARRAY);
    to_color = get_to_type(GDEXTENSION_VARIANT_TYPE_COLOR);
    to_rid = get_to_type(GDEXTENSION_VARIANT_TYPE_RID);
    to_callable = get_to_type(GDEXTENSION_VARIANT_TYPE_CALLABLE);
    dtor_string = get_ptr_dtor(GDEXTENSION_VARIANT_TYPE_STRING);
    dtor_stringname = get_ptr_dtor(GDEXTENSION_VARIANT_TYPE_STRING_NAME);
    dtor_array = get_ptr_dtor(GDEXTENSION_VARIANT_TYPE_ARRAY);
    dtor_dict = get_ptr_dtor(GDEXTENSION_VARIANT_TYPE_DICTIONARY);
    dtor_packed_string = get_ptr_dtor(GDEXTENSION_VARIANT_TYPE_PACKED_STRING_ARRAY);
    dtor_callable = get_ptr_dtor(GDEXTENSION_VARIANT_TYPE_CALLABLE);
    dtor_signal = get_ptr_dtor(GDEXTENSION_VARIANT_TYPE_SIGNAL);
    r_initialization->minimum_initialization_level = GDEXTENSION_INITIALIZATION_SCENE;
    r_initialization->userdata = NULL;
    r_initialization->initialize = initialize;
    r_initialization->deinitialize = deinitialize;
    return 1;
}
