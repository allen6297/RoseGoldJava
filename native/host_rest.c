#define RG_JSON_MAX_DEPTH 64
#define RG_RE_MAX_CAPS 32
#define RG_PATH_MAX 4096

#if defined(_WIN32)
#define rg_cwd _getcwd
#define rg_mkdir_one _mkdir
#define rg_unlink_one _unlink
#define rg_path_ncmp _strnicmp
#else
#define rg_cwd getcwd
#define rg_mkdir_one(p) mkdir((p), 0777)
#define rg_unlink_one unlink
#define rg_path_ncmp strncasecmp
#endif

typedef struct RGBuf {
    char *s;
    size_t n;
    size_t cap;
} RGBuf;

static void rg_buf_need(RGBuf *b, size_t extra) {
    size_t need;
    if (b->s == NULL) {
        b->cap = extra < 64 ? 64 : extra;
        b->s = (char *) malloc(b->cap);
        if (b->s == NULL) {
            rg_die("out of memory");
        }
        b->n = 0;
        return;
    }
    need = b->n + extra;
    if (need <= b->cap) {
        return;
    }
    while (b->cap < need) {
        b->cap *= 2;
    }
    b->s = (char *) realloc(b->s, b->cap);
    if (b->s == NULL) {
        rg_die("out of memory");
    }
}

static void rg_buf_ch(RGBuf *b, char c) {
    rg_buf_need(b, 1);
    b->s[b->n++] = c;
}

static void rg_buf_add(RGBuf *b, const char *s, size_t n) {
    if (s == NULL || n == 0) {
        return;
    }
    rg_buf_need(b, n);
    memcpy(b->s + b->n, s, n);
    b->n += n;
}

static void rg_buf_str(RGBuf *b, const char *s) {
    if (s != NULL) {
        rg_buf_add(b, s, strlen(s));
    }
}

static char *rg_buf_take(RGBuf *b) {
    rg_buf_ch(b, '\0');
    b->n--;
    return b->s;
}

static void rg_throw_str(const char *msg) {
    RGValue err;
    rg_set_string(&err, msg);
    rg_throw(&err);
}

static char *rg_sandbox_root(void) {
    static char root[RG_PATH_MAX];
    static int ready;
    if (!ready) {
        if (rg_cwd(root, (int) sizeof(root)) == NULL) {
            rg_die("cannot read working directory");
        }
        rg_slash(root);
        ready = 1;
    }
    return root;
}

static int rg_path_is_abs_raw(const char *s) {
    if (s == NULL || s[0] == '\0') {
        return 0;
    }
    if (s[0] == '/') {
        return 1;
    }
    return ((s[0] >= 'A' && s[0] <= 'Z') || (s[0] >= 'a' && s[0] <= 'z')) && s[1] == ':';
}

static char *rg_norm_abs(const char *in) {
    char *parts[256];
    int32_t nparts = 0;
    char *work;
    char *tok;
    char *save;
    RGBuf out = {0};
    int32_t i;
    int drive = 0;
    work = rg_strdup(in);
    rg_slash(work);
    if (((work[0] >= 'A' && work[0] <= 'Z') || (work[0] >= 'a' && work[0] <= 'z')) && work[1] == ':') {
        rg_buf_ch(&out, work[0]);
        rg_buf_ch(&out, ':');
        drive = 1;
        save = work + 2;
        if (*save == '/') {
            rg_buf_ch(&out, '/');
            save++;
        }
    } else if (work[0] == '/') {
        rg_buf_ch(&out, '/');
        save = work + 1;
    } else {
        save = work;
    }
    for (tok = save; *tok != '\0';) {
        char *slash = strchr(tok, '/');
        size_t n = slash == NULL ? strlen(tok) : (size_t) (slash - tok);
        if (n == 0 || (n == 1 && tok[0] == '.')) {
            tok = slash == NULL ? tok + n : slash + 1;
            continue;
        }
        if (n == 2 && tok[0] == '.' && tok[1] == '.') {
            if (nparts > 0) {
                nparts--;
            }
            tok = slash == NULL ? tok + n : slash + 1;
            continue;
        }
        if (nparts >= 256) {
            rg_die("path too long");
        }
        parts[nparts++] = rg_strndup(tok, n);
        tok = slash == NULL ? tok + n : slash + 1;
    }
    for (i = 0; i < nparts; i++) {
        if (out.n > 0 && out.s[out.n - 1] != '/') {
            rg_buf_ch(&out, '/');
        }
        rg_buf_str(&out, parts[i]);
        free(parts[i]);
    }
    if (out.n == 0) {
        rg_buf_str(&out, drive ? "" : ".");
    }
    free(work);
    return rg_buf_take(&out);
}

static char *rg_sandbox_path(const char *raw) {
    const char *root;
    char *joined;
    char *canon;
    size_t nr;
    if (raw == NULL || raw[0] == '\0') {
        rg_die("invalid path ''");
    }
    root = rg_sandbox_root();
    if (rg_path_is_abs_raw(raw)) {
        joined = rg_strdup(raw);
        rg_slash(joined);
    } else {
        size_t na = strlen(root);
        size_t nb = strlen(raw);
        joined = (char *) malloc(na + 1 + nb + 1);
        if (joined == NULL) {
            rg_die("out of memory");
        }
        memcpy(joined, root, na);
        joined[na] = '/';
        memcpy(joined + na + 1, raw, nb + 1);
        rg_slash(joined);
    }
    canon = rg_norm_abs(joined);
    free(joined);
    nr = strlen(root);
    if (rg_path_ncmp(canon, root, (int) nr) != 0
            || (canon[nr] != '\0' && canon[nr] != '/')) {
        char msg[RG_PATH_MAX + 40];
        snprintf(msg, sizeof(msg), "path outside sandbox '%s'", raw);
        rg_die(msg);
    }
    return canon;
}

static int rg_file_exists(const char *path) {
#if defined(_WIN32)
    DWORD attr = GetFileAttributesA(path);
    return attr != INVALID_FILE_ATTRIBUTES;
#else
    struct stat st;
    return stat(path, &st) == 0;
#endif
}

static void rg_mkdirs_parent(const char *path) {
    char *tmp = rg_strdup(path);
    char *slash = strrchr(tmp, '/');
    char *p;
    if (slash == NULL || slash == tmp) {
        free(tmp);
        return;
    }
    *slash = '\0';
    for (p = tmp + 1; *p != '\0'; p++) {
        if (*p == '/') {
            *p = '\0';
            if (tmp[0] != '\0') {
                rg_mkdir_one(tmp);
            }
            *p = '/';
        }
    }
    if (tmp[0] != '\0') {
        rg_mkdir_one(tmp);
    }
    free(tmp);
}

static char *rg_read_file(const char *path, int throwing) {
    FILE *f;
    long n;
    char *buf;
    f = fopen(path, "rb");
    if (f == NULL) {
        if (throwing) {
            char msg[RG_PATH_MAX + 32];
            snprintf(msg, sizeof(msg), "cannot read '%s'", path);
            rg_throw_str(rg_strdup(msg));
        }
        return NULL;
    }
    if (fseek(f, 0, SEEK_END) != 0) {
        fclose(f);
        if (throwing) {
            char msg[RG_PATH_MAX + 32];
            snprintf(msg, sizeof(msg), "cannot read '%s'", path);
            rg_throw_str(rg_strdup(msg));
        }
        return NULL;
    }
    n = ftell(f);
    if (n < 0) {
        fclose(f);
        if (throwing) {
            char msg[RG_PATH_MAX + 32];
            snprintf(msg, sizeof(msg), "cannot read '%s'", path);
            rg_throw_str(rg_strdup(msg));
        }
        return NULL;
    }
    rewind(f);
    buf = (char *) malloc((size_t) n + 1);
    if (buf == NULL) {
        fclose(f);
        rg_die("out of memory");
    }
    if (n > 0 && fread(buf, 1, (size_t) n, f) != (size_t) n) {
        free(buf);
        fclose(f);
        if (throwing) {
            char msg[RG_PATH_MAX + 32];
            snprintf(msg, sizeof(msg), "cannot read '%s'", path);
            rg_throw_str(rg_strdup(msg));
        }
        return NULL;
    }
    buf[n] = '\0';
    fclose(f);
    return buf;
}

static void rg_io_call(RGValue *dest, const char *name, RGValue *args, int32_t argc) {
    if (strcmp(name, "exists") == 0) {
        char *path;
        if (argc != 1) {
            rg_die("io.exists takes 1 argument");
        }
        path = rg_sandbox_path(rg_arg_str(args, 0, argc, "__io.exists"));
        rg_set_bool(dest, rg_file_exists(path));
        free(path);
        return;
    }
    if (strcmp(name, "remove") == 0) {
        char *path;
        int ok;
        if (argc != 1) {
            rg_die("io.remove takes 1 argument");
        }
        path = rg_sandbox_path(rg_arg_str(args, 0, argc, "__io.remove"));
        ok = rg_unlink_one(path) == 0;
        rg_set_bool(dest, ok);
        free(path);
        return;
    }
    if (strcmp(name, "read_text") == 0) {
        char *path;
        char *text;
        if (argc != 1) {
            rg_die("io.read_text takes 1 argument");
        }
        path = rg_sandbox_path(rg_arg_str(args, 0, argc, "__io.read_text"));
        text = rg_read_file(path, 1);
        if (text == NULL) {
            rg_set_void(dest);
            free(path);
            return;
        }
        rg_set_string(dest, text);
        free(path);
        return;
    }
    if (strcmp(name, "read_lines") == 0) {
        char *path;
        char *text;
        const char *p;
        RGArray *arr;
        if (argc != 1) {
            rg_die("io.read_lines takes 1 argument");
        }
        path = rg_sandbox_path(rg_arg_str(args, 0, argc, "__io.read_lines"));
        text = rg_read_file(path, 1);
        free(path);
        if (text == NULL) {
            rg_set_void(dest);
            return;
        }
        arr = rg_alloc_array(0);
        p = text;
        while (1) {
            const char *nl = strchr(p, '\n');
            size_t n;
            RGValue row;
            char *line;
            if (nl == NULL && p[0] == '\0') {
                break;
            }
            n = nl == NULL ? strlen(p) : (size_t) (nl - p);
            if (n > 0 && p[n - 1] == '\r') {
                n--;
            }
            line = rg_strndup(p, n);
            rg_set_string(&row, line);
            arr = rg_array_push(arr, &row);
            if (nl == NULL) {
                break;
            }
            p = nl + 1;
        }
        rg_set_array(dest, arr);
        free(text);
        return;
    }
    if (strcmp(name, "write_text") == 0) {
        char *path;
        const char *content;
        FILE *f;
        size_t n;
        if (argc != 2) {
            rg_die("io.write_text takes 2 arguments");
        }
        if (args[1].kind != RG_STRING) {
            rg_die("io.write_text expects String");
        }
        path = rg_sandbox_path(rg_arg_str(args, 0, argc, "__io.write_text"));
        content = args[1].p != NULL ? args[1].p : "";
        rg_mkdirs_parent(path);
        f = fopen(path, "wb");
        if (f == NULL) {
            char msg[RG_PATH_MAX + 32];
            snprintf(msg, sizeof(msg), "cannot write '%s'", path);
            rg_throw_str(rg_strdup(msg));
            rg_set_void(dest);
            free(path);
            return;
        }
        n = strlen(content);
        if (n > 0 && fwrite(content, 1, n, f) != n) {
            fclose(f);
            char msg[RG_PATH_MAX + 32];
            snprintf(msg, sizeof(msg), "cannot write '%s'", path);
            rg_throw_str(rg_strdup(msg));
            rg_set_void(dest);
            free(path);
            return;
        }
        fclose(f);
        rg_set_void(dest);
        free(path);
        return;
    }
    rg_die("unknown function __io");
}

typedef struct JsonP {
    const char *src;
    size_t n;
    size_t i;
    const char *err;
} JsonP;

static int json_ok(JsonP *p) {
    return p->err == NULL;
}

static void json_fail(JsonP *p, const char *msg) {
    if (p->err == NULL) {
        p->err = msg;
    }
}

static char json_peek(JsonP *p) {
    return p->i < p->n ? p->src[p->i] : '\0';
}

static char json_getc(JsonP *p) {
    if (p->i >= p->n) {
        json_fail(p, "invalid JSON");
        return '\0';
    }
    return p->src[p->i++];
}

static int json_space(char c) {
    return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\f' || c == 0x0B;
}

static void json_skip(JsonP *p) {
    while (p->i < p->n && json_space(p->src[p->i])) {
        p->i++;
    }
}

static int json_match(JsonP *p, const char *lit) {
    size_t n = strlen(lit);
    if (p->i + n > p->n || strncmp(p->src + p->i, lit, n) != 0) {
        return 0;
    }
    p->i += n;
    return 1;
}

static void json_utf8(RGBuf *b, unsigned int cp) {
    if (cp <= 0x7F) {
        rg_buf_ch(b, (char) cp);
    } else if (cp <= 0x7FF) {
        rg_buf_ch(b, (char) (0xC0 | (cp >> 6)));
        rg_buf_ch(b, (char) (0x80 | (cp & 0x3F)));
    } else if (cp <= 0xFFFF) {
        rg_buf_ch(b, (char) (0xE0 | (cp >> 12)));
        rg_buf_ch(b, (char) (0x80 | ((cp >> 6) & 0x3F)));
        rg_buf_ch(b, (char) (0x80 | (cp & 0x3F)));
    } else {
        rg_buf_ch(b, (char) (0xF0 | (cp >> 18)));
        rg_buf_ch(b, (char) (0x80 | ((cp >> 12) & 0x3F)));
        rg_buf_ch(b, (char) (0x80 | ((cp >> 6) & 0x3F)));
        rg_buf_ch(b, (char) (0x80 | (cp & 0x3F)));
    }
}

static int json_hex4(JsonP *p) {
    int n = 0;
    int k;
    for (k = 0; k < 4; k++) {
        char c = json_getc(p);
        n <<= 4;
        if (c >= '0' && c <= '9') {
            n += c - '0';
        } else if (c >= 'a' && c <= 'f') {
            n += c - 'a' + 10;
        } else if (c >= 'A' && c <= 'F') {
            n += c - 'A' + 10;
        } else {
            json_fail(p, "invalid JSON");
            return 0;
        }
    }
    return n;
}

static char *json_string(JsonP *p) {
    RGBuf b = {0};
    if (json_getc(p) != '"') {
        json_fail(p, "invalid JSON");
        return rg_strdup("");
    }
    while (json_ok(p)) {
        unsigned char c = (unsigned char) json_getc(p);
        if (!json_ok(p)) {
            break;
        }
        if (c == '"') {
            return rg_buf_take(&b);
        }
        if (c == '\\') {
            char e = json_getc(p);
            switch (e) {
                case '"':
                case '\\':
                case '/':
                    rg_buf_ch(&b, e);
                    break;
                case 'b':
                    rg_buf_ch(&b, '\b');
                    break;
                case 'f':
                    rg_buf_ch(&b, '\f');
                    break;
                case 'n':
                    rg_buf_ch(&b, '\n');
                    break;
                case 'r':
                    rg_buf_ch(&b, '\r');
                    break;
                case 't':
                    rg_buf_ch(&b, '\t');
                    break;
                case 'u':
                    json_utf8(&b, (unsigned int) json_hex4(p));
                    break;
                default:
                    json_fail(p, "invalid JSON");
                    break;
            }
        } else if (c < 0x20) {
            json_fail(p, "invalid JSON");
        } else {
            rg_buf_ch(&b, (char) c);
        }
    }
    return rg_buf_take(&b);
}

static int json_value(JsonP *p, RGValue *dest, int depth);

static void json_number(JsonP *p, RGValue *dest) {
    size_t start = p->i;
    int frac = 0;
    char *tok;
    if (json_peek(p) == '-') {
        p->i++;
    }
    if (json_peek(p) == '0') {
        p->i++;
        if (json_peek(p) >= '0' && json_peek(p) <= '9') {
            json_fail(p, "invalid JSON");
            return;
        }
    } else if (json_peek(p) >= '1' && json_peek(p) <= '9') {
        while (json_peek(p) >= '0' && json_peek(p) <= '9') {
            p->i++;
        }
    } else {
        json_fail(p, "invalid JSON");
        return;
    }
    if (json_peek(p) == '.') {
        frac = 1;
        p->i++;
        if (json_peek(p) < '0' || json_peek(p) > '9') {
            json_fail(p, "invalid JSON");
            return;
        }
        while (json_peek(p) >= '0' && json_peek(p) <= '9') {
            p->i++;
        }
    }
    if (json_peek(p) == 'e' || json_peek(p) == 'E') {
        frac = 1;
        p->i++;
        if (json_peek(p) == '+' || json_peek(p) == '-') {
            p->i++;
        }
        if (json_peek(p) < '0' || json_peek(p) > '9') {
            json_fail(p, "invalid JSON");
            return;
        }
        while (json_peek(p) >= '0' && json_peek(p) <= '9') {
            p->i++;
        }
    }
    tok = rg_strndup(p->src + start, p->i - start);
    if (!frac) {
        char *end = NULL;
        long long n = strtoll(tok, &end, 10);
        if (end != NULL && *end == '\0' && n != LLONG_MAX && n != LLONG_MIN) {
            rg_set_int(dest, (int64_t) n);
            free(tok);
            return;
        }
        if (end != NULL && *end == '\0') {
            /* fall through to float on overflow */
        } else if (!frac) {
            json_fail(p, "invalid JSON");
            free(tok);
            return;
        }
    }
    {
        char *end = NULL;
        double n = strtod(tok, &end);
        free(tok);
        if (end == NULL || *end != '\0' || !isfinite(n)) {
            json_fail(p, "invalid JSON");
            return;
        }
        rg_set_float(dest, n);
    }
}

static int json_value(JsonP *p, RGValue *dest, int depth) {
    char c;
    if (depth > RG_JSON_MAX_DEPTH) {
        json_fail(p, "JSON nesting too deep");
        return 0;
    }
    json_skip(p);
    c = json_peek(p);
    if (c == '"') {
        rg_set_string(dest, json_string(p));
        return json_ok(p);
    }
    if (c == '{') {
        RGMap *m;
        json_getc(p);
        m = rg_alloc_map(4);
        json_skip(p);
        if (json_peek(p) == '}') {
            json_getc(p);
            rg_set_map(dest, m);
            return 1;
        }
        while (json_ok(p)) {
            char *key;
            RGValue val;
            json_skip(p);
            if (json_peek(p) != '"') {
                json_fail(p, "invalid JSON");
                return 0;
            }
            key = json_string(p);
            json_skip(p);
            if (json_getc(p) != ':') {
                json_fail(p, "invalid JSON");
                return 0;
            }
            rg_set_void(&val);
            if (!json_value(p, &val, depth + 1)) {
                return 0;
            }
            rg_map_put(m, key, &val);
            free(key);
            json_skip(p);
            c = json_getc(p);
            if (c == '}') {
                rg_set_map(dest, m);
                return 1;
            }
            if (c != ',') {
                json_fail(p, "invalid JSON");
                return 0;
            }
        }
        return 0;
    }
    if (c == '[') {
        RGArray *arr;
        json_getc(p);
        arr = rg_alloc_array(0);
        json_skip(p);
        if (json_peek(p) == ']') {
            json_getc(p);
            rg_set_array(dest, arr);
            return 1;
        }
        while (json_ok(p)) {
            RGValue item;
            rg_set_void(&item);
            if (!json_value(p, &item, depth + 1)) {
                return 0;
            }
            arr = rg_array_push(arr, &item);
            json_skip(p);
            c = json_getc(p);
            if (c == ']') {
                rg_set_array(dest, arr);
                return 1;
            }
            if (c != ',') {
                json_fail(p, "invalid JSON");
                return 0;
            }
        }
        return 0;
    }
    if (c == 't') {
        if (!json_match(p, "true")) {
            json_fail(p, "invalid JSON");
            return 0;
        }
        rg_set_bool(dest, 1);
        return 1;
    }
    if (c == 'f') {
        if (!json_match(p, "false")) {
            json_fail(p, "invalid JSON");
            return 0;
        }
        rg_set_bool(dest, 0);
        return 1;
    }
    if (c == 'n') {
        if (!json_match(p, "null")) {
            json_fail(p, "invalid JSON");
            return 0;
        }
        json_fail(p, "json null is not supported");
        return 0;
    }
    if (c == '-' || (c >= '0' && c <= '9')) {
        json_number(p, dest);
        return json_ok(p);
    }
    json_fail(p, "invalid JSON");
    return 0;
}

static const char *g_json_err;

static int json_parse_wrap(const char *src, RGValue *dest) {
    JsonP p;
    p.src = src == NULL ? "" : src;
    p.n = strlen(p.src);
    p.i = 0;
    p.err = NULL;
    if (!json_value(&p, dest, 0)) {
        g_json_err = p.err != NULL ? p.err : "invalid JSON";
        return 0;
    }
    json_skip(&p);
    if (p.i != p.n) {
        g_json_err = "invalid JSON";
        return 0;
    }
    g_json_err = NULL;
    return 1;
}

static void json_escape(RGBuf *b, const char *s) {
    static const char *hex = "0123456789abcdef";
    for (; s != NULL && *s != '\0'; s++) {
        unsigned char c = (unsigned char) *s;
        switch (c) {
            case '"':
                rg_buf_str(b, "\\\"");
                break;
            case '\\':
                rg_buf_str(b, "\\\\");
                break;
            case '\b':
                rg_buf_str(b, "\\b");
                break;
            case '\f':
                rg_buf_str(b, "\\f");
                break;
            case '\n':
                rg_buf_str(b, "\\n");
                break;
            case '\r':
                rg_buf_str(b, "\\r");
                break;
            case '\t':
                rg_buf_str(b, "\\t");
                break;
            default:
                if (c < 0x20) {
                    rg_buf_str(b, "\\u00");
                    rg_buf_ch(b, hex[c >> 4]);
                    rg_buf_ch(b, hex[c & 0xf]);
                } else {
                    rg_buf_ch(b, (char) c);
                }
                break;
        }
    }
}

static const char *json_stringify(const RGValue *v, RGBuf *b, int depth);

static void json_float(RGBuf *b, double n) {
    char buf[64];
    int i;
    int has_dot = 0;
    if (n == 0.0) {
        rg_buf_str(b, signbit(n) ? "-0" : "0");
        return;
    }
    snprintf(buf, sizeof(buf), "%.17g", n);
    for (i = 0; buf[i] != '\0'; i++) {
        if (buf[i] == '.' || buf[i] == 'e' || buf[i] == 'E') {
            has_dot = 1;
            break;
        }
    }
    rg_buf_str(b, buf);
    if (!has_dot) {
        rg_buf_str(b, ".0");
    }
}

static const char *json_stringify(const RGValue *v, RGBuf *b, int depth) {
    int32_t i;
    if (depth > RG_JSON_MAX_DEPTH) {
        return "json nesting too deep";
    }
    if (v == NULL) {
        return "cannot stringify value";
    }
    switch (v->kind) {
        case RG_BOOL:
            rg_buf_str(b, v->i ? "true" : "false");
            return NULL;
        case RG_INT: {
            char buf[32];
            snprintf(buf, sizeof(buf), "%lld", (long long) v->i);
            rg_buf_str(b, buf);
            return NULL;
        }
        case RG_FLOAT: {
            double n = rg_f64(v);
            if (!isfinite(n)) {
                return "cannot stringify non-finite Float";
            }
            json_float(b, n);
            return NULL;
        }
        case RG_STRING:
            rg_buf_ch(b, '"');
            json_escape(b, v->p);
            rg_buf_ch(b, '"');
            return NULL;
        case RG_ARRAY: {
            RGArray *arr = rg_as_array(v);
            rg_buf_ch(b, '[');
            if (arr != NULL) {
                for (i = 0; i < arr->len; i++) {
                    const char *err;
                    if (i > 0) {
                        rg_buf_ch(b, ',');
                    }
                    err = json_stringify(&arr->items[i], b, depth + 1);
                    if (err != NULL) {
                        return err;
                    }
                }
            }
            rg_buf_ch(b, ']');
            return NULL;
        }
        case RG_MAP: {
            RGMap *m = rg_as_map(v);
            int first = 1;
            rg_buf_ch(b, '{');
            if (m != NULL) {
                for (i = 0; i < m->len; i++) {
                    const char *err;
                    if (!first) {
                        rg_buf_ch(b, ',');
                    }
                    first = 0;
                    rg_buf_ch(b, '"');
                    json_escape(b, m->entries[i].key);
                    rg_buf_str(b, "\":");
                    err = json_stringify(&m->entries[i].value, b, depth + 1);
                    if (err != NULL) {
                        return err;
                    }
                }
            }
            rg_buf_ch(b, '}');
            return NULL;
        }
        case RG_STRUCT: {
            RGStruct *s = rg_as_struct(v);
            int first = 1;
            rg_buf_ch(b, '{');
            if (s != NULL) {
                for (i = 0; i < s->len; i++) {
                    const char *err;
                    if (!first) {
                        rg_buf_ch(b, ',');
                    }
                    first = 0;
                    rg_buf_ch(b, '"');
                    json_escape(b, s->fields != NULL ? s->fields[i] : "");
                    rg_buf_str(b, "\":");
                    err = json_stringify(s->values != NULL ? &s->values[i] : NULL, b, depth + 1);
                    if (err != NULL) {
                        return err;
                    }
                }
            }
            rg_buf_ch(b, '}');
            return NULL;
        }
        case RG_ENUM: {
            RGEnum *en = rg_as_enum(v);
            rg_buf_ch(b, '"');
            json_escape(b, en != NULL && en->variant != NULL ? en->variant : "");
            rg_buf_ch(b, '"');
            return NULL;
        }
        default:
            return "cannot stringify value";
    }
}

static void rg_json_call(RGValue *dest, const char *name, RGValue *args, int32_t argc) {
    if (strcmp(name, "parse") == 0) {
        const char *s;
        if (argc != 1) {
            rg_die("__json.parse takes 1 argument");
        }
        s = rg_arg_str(args, 0, argc, "__json.parse");
        if (!json_parse_wrap(s, dest)) {
            rg_throw_str(g_json_err != NULL ? g_json_err : "invalid JSON");
            rg_set_void(dest);
        }
        return;
    }
    if (strcmp(name, "valid") == 0) {
        RGValue tmp;
        const char *s;
        if (argc != 1) {
            rg_die("__json.valid takes 1 argument");
        }
        s = rg_arg_str(args, 0, argc, "__json.valid");
        rg_set_void(&tmp);
        rg_set_bool(dest, json_parse_wrap(s, &tmp));
        return;
    }
    if (strcmp(name, "stringify") == 0) {
        RGBuf b = {0};
        const char *err;
        if (argc != 1) {
            rg_die("__json.stringify takes 1 argument");
        }
        err = json_stringify(&args[0], &b, 0);
        if (err != NULL) {
            free(b.s);
            rg_die(err);
        }
        rg_set_string(dest, rg_buf_take(&b));
        return;
    }
    rg_die("unknown function __json");
}

enum {
    RK_LIT = 1,
    RK_ANY,
    RK_CLS,
    RK_BOL,
    RK_EOL,
    RK_ALT,
    RK_REP,
    RK_CAP
};

typedef struct RENode {
    int kind;
    int ch;
    int cap;
    int minv;
    int maxv;
    int neg;
    unsigned char cls[32];
    struct RENode *x;
    struct RENode *y;
    struct RENode *next;
} RENode;

typedef struct REParse {
    const char *s;
    size_t i;
    size_t n;
    const char *err;
    int ngroups;
} REParse;

typedef struct REMatch {
    const char *s;
    size_t n;
    int ngroups;
    int caps[RG_RE_MAX_CAPS * 2];
} REMatch;

static void re_cls_set(unsigned char *cls, int c) {
    cls[(c & 255) >> 3] |= (unsigned char) (1u << (c & 7));
}

static int re_cls_has(const unsigned char *cls, int c) {
    return (cls[(c & 255) >> 3] >> (c & 7)) & 1;
}

static void re_cls_d(unsigned char *cls) {
    int c;
    for (c = '0'; c <= '9'; c++) {
        re_cls_set(cls, c);
    }
}

static void re_cls_w(unsigned char *cls) {
    int c;
    re_cls_d(cls);
    for (c = 'A'; c <= 'Z'; c++) {
        re_cls_set(cls, c);
    }
    for (c = 'a'; c <= 'z'; c++) {
        re_cls_set(cls, c);
    }
    re_cls_set(cls, '_');
}

static void re_cls_s(unsigned char *cls) {
    re_cls_set(cls, ' ');
    re_cls_set(cls, '\t');
    re_cls_set(cls, '\n');
    re_cls_set(cls, '\r');
    re_cls_set(cls, '\f');
    re_cls_set(cls, 0x0B);
}

static RENode *re_node(int kind) {
    RENode *n = (RENode *) calloc(1, sizeof(RENode));
    if (n == NULL) {
        rg_die("out of memory");
    }
    n->kind = kind;
    n->maxv = 1;
    n->minv = 1;
    return n;
}

static char re_peek(REParse *p) {
    return p->i < p->n ? p->s[p->i] : '\0';
}

static char re_getc(REParse *p) {
    if (p->i >= p->n) {
        p->err = "Unclosed group";
        return '\0';
    }
    return p->s[p->i++];
}

static RENode *re_parse_alt(REParse *p);

static int re_escape_class(REParse *p, unsigned char *cls, int *negated) {
    char e;
    unsigned char tmp[32];
    memset(tmp, 0, sizeof(tmp));
    e = re_getc(p);
    if (p->err != NULL) {
        return 0;
    }
    switch (e) {
        case 'd':
            re_cls_d(cls);
            return 1;
        case 'D':
            re_cls_d(tmp);
            *negated = 1;
            memcpy(cls, tmp, 32);
            return 1;
        case 'w':
            re_cls_w(cls);
            return 1;
        case 'W':
            re_cls_w(tmp);
            *negated = 1;
            memcpy(cls, tmp, 32);
            return 1;
        case 's':
            re_cls_s(cls);
            return 1;
        case 'S':
            re_cls_s(tmp);
            *negated = 1;
            memcpy(cls, tmp, 32);
            return 1;
        default:
            re_cls_set(cls, (unsigned char) e);
            return 1;
    }
}

static RENode *re_parse_class(REParse *p) {
    RENode *n = re_node(RK_CLS);
    int first = 1;
    if (re_peek(p) == '^') {
        p->i++;
        n->neg = 1;
    }
    while (p->err == NULL) {
        char c;
        if (re_peek(p) == '\0') {
            p->err = "Unclosed character class";
            return n;
        }
        if (re_peek(p) == ']' && !first) {
            p->i++;
            return n;
        }
        first = 0;
        if (re_peek(p) == '\\') {
            int dummy = 0;
            p->i++;
            re_escape_class(p, n->cls, &dummy);
            if (dummy) {
                n->neg = !n->neg;
            }
            continue;
        }
        c = re_getc(p);
        if (re_peek(p) == '-' && p->i + 1 < p->n && p->s[p->i + 1] != ']') {
            char d;
            int k;
            p->i++;
            d = re_getc(p);
            if ((unsigned char) c > (unsigned char) d) {
                char t = c;
                c = d;
                d = t;
            }
            for (k = (unsigned char) c; k <= (unsigned char) d; k++) {
                re_cls_set(n->cls, k);
            }
        } else {
            re_cls_set(n->cls, (unsigned char) c);
        }
    }
    return n;
}

static RENode *re_parse_atom(REParse *p) {
    char c = re_peek(p);
    RENode *n;
    if (c == '\0') {
        p->err = "Dangling meta character";
        return re_node(RK_LIT);
    }
    if (c == '^') {
        p->i++;
        return re_node(RK_BOL);
    }
    if (c == '$') {
        p->i++;
        return re_node(RK_EOL);
    }
    if (c == '.') {
        p->i++;
        return re_node(RK_ANY);
    }
    if (c == '[') {
        p->i++;
        return re_parse_class(p);
    }
    if (c == '(') {
        int cap;
        p->i++;
        p->ngroups++;
        cap = p->ngroups;
        n = re_node(RK_CAP);
        n->cap = cap;
        n->x = re_parse_alt(p);
        if (re_peek(p) != ')') {
            p->err = "Unclosed group";
            return n;
        }
        p->i++;
        return n;
    }
    if (c == ')') {
        p->err = "Unmatched closing ')'";
        return re_node(RK_LIT);
    }
    if (c == '*' || c == '+' || c == '?' || c == '|') {
        p->err = "Dangling meta character";
        return re_node(RK_LIT);
    }
    if (c == '\\') {
        unsigned char cls[32];
        int neg = 0;
        p->i++;
        memset(cls, 0, sizeof(cls));
        if (re_peek(p) == '\0') {
            p->err = "Unclosed group";
            return re_node(RK_LIT);
        }
        {
            char e = re_peek(p);
            if (e == 'd' || e == 'D' || e == 'w' || e == 'W' || e == 's' || e == 'S') {
                p->i++;
                n = re_node(RK_CLS);
                if (e == 'd') {
                    re_cls_d(n->cls);
                } else if (e == 'D') {
                    re_cls_d(n->cls);
                    n->neg = 1;
                } else if (e == 'w') {
                    re_cls_w(n->cls);
                } else if (e == 'W') {
                    re_cls_w(n->cls);
                    n->neg = 1;
                } else if (e == 's') {
                    re_cls_s(n->cls);
                } else {
                    re_cls_s(n->cls);
                    n->neg = 1;
                }
                (void) neg;
                (void) cls;
                return n;
            }
        }
        n = re_node(RK_LIT);
        n->ch = (unsigned char) re_getc(p);
        return n;
    }
    n = re_node(RK_LIT);
    n->ch = (unsigned char) re_getc(p);
    return n;
}

static RENode *re_parse_quant(REParse *p) {
    RENode *atom = re_parse_atom(p);
    char q = re_peek(p);
    RENode *rep;
    if (p->err != NULL) {
        return atom;
    }
    if (q != '*' && q != '+' && q != '?') {
        return atom;
    }
    p->i++;
    rep = re_node(RK_REP);
    rep->x = atom;
    if (q == '*') {
        rep->minv = 0;
        rep->maxv = -1;
    } else if (q == '+') {
        rep->minv = 1;
        rep->maxv = -1;
    } else {
        rep->minv = 0;
        rep->maxv = 1;
    }
    return rep;
}

static RENode *re_parse_seq(REParse *p) {
    RENode *head = NULL;
    RENode *tail = NULL;
    while (p->err == NULL) {
        char c = re_peek(p);
        RENode *n;
        if (c == '\0' || c == '|' || c == ')') {
            break;
        }
        n = re_parse_quant(p);
        if (head == NULL) {
            head = n;
            tail = n;
        } else {
            tail->next = n;
            tail = n;
        }
        while (tail != NULL && tail->next != NULL) {
            tail = tail->next;
        }
    }
    return head;
}

static RENode *re_parse_alt(REParse *p) {
    RENode *left = re_parse_seq(p);
    if (re_peek(p) != '|') {
        return left;
    }
    p->i++;
    {
        RENode *n = re_node(RK_ALT);
        n->x = left;
        n->y = re_parse_alt(p);
        return n;
    }
}

static RENode *re_compile(const char *pat, int *ngroups, const char **err) {
    REParse p;
    RENode *root;
    p.s = pat == NULL ? "" : pat;
    p.i = 0;
    p.n = strlen(p.s);
    p.err = NULL;
    p.ngroups = 0;
    root = re_parse_alt(&p);
    if (p.err == NULL && p.i != p.n) {
        p.err = "Dangling meta character";
    }
    *ngroups = p.ngroups;
    *err = p.err;
    return root;
}

static int re_exec(RENode *n, REMatch *m, size_t i, size_t *out);

static int re_consume(RENode *n, REMatch *m, size_t i, size_t *out) {
    unsigned char c;
    int hit;
    if (n == NULL) {
        *out = i;
        return 1;
    }
    switch (n->kind) {
        case RK_LIT:
            if (i < m->n && (unsigned char) m->s[i] == (unsigned char) n->ch) {
                *out = i + 1;
                return 1;
            }
            return 0;
        case RK_ANY:
            if (i < m->n && m->s[i] != '\n') {
                *out = i + 1;
                return 1;
            }
            return 0;
        case RK_BOL:
            if (i == 0) {
                *out = i;
                return 1;
            }
            return 0;
        case RK_EOL:
            if (i == m->n) {
                *out = i;
                return 1;
            }
            return 0;
        case RK_CLS:
            if (i >= m->n) {
                return 0;
            }
            c = (unsigned char) m->s[i];
            hit = re_cls_has(n->cls, c);
            if (n->neg ? !hit : hit) {
                *out = i + 1;
                return 1;
            }
            return 0;
        default:
            return 0;
    }
}

static int re_rep(RENode *child, int minv, int maxv, RENode *rest, REMatch *m, size_t i, int count, size_t *out) {
    size_t j;
    if (maxv < 0) {
        maxv = (int) m->n + 1;
    }
    if (count < maxv && re_exec(child, m, i, &j)) {
        if (j != i || count < minv) {
            if (re_rep(child, minv, maxv, rest, m, j, count + 1, out)) {
                return 1;
            }
        }
    }
    if (count >= minv) {
        return re_exec(rest, m, i, out);
    }
    return 0;
}

static int re_exec(RENode *n, REMatch *m, size_t i, size_t *out) {
    size_t j;
    if (n == NULL) {
        *out = i;
        return 1;
    }
    if (n->kind == RK_REP) {
        return re_rep(n->x, n->minv, n->maxv, n->next, m, i, 0, out);
    }
    if (n->kind == RK_ALT) {
        if (re_exec(n->x, m, i, &j) && re_exec(n->next, m, j, out)) {
            return 1;
        }
        return re_exec(n->y, m, i, &j) && re_exec(n->next, m, j, out);
    }
    if (n->kind == RK_CAP) {
        int olds = n->cap >= 0 && n->cap < RG_RE_MAX_CAPS ? m->caps[n->cap * 2] : -1;
        int olde = n->cap >= 0 && n->cap < RG_RE_MAX_CAPS ? m->caps[n->cap * 2 + 1] : -1;
        if (n->cap >= 0 && n->cap < RG_RE_MAX_CAPS) {
            m->caps[n->cap * 2] = (int) i;
        }
        if (!re_exec(n->x, m, i, &j)) {
            if (n->cap >= 0 && n->cap < RG_RE_MAX_CAPS) {
                m->caps[n->cap * 2] = olds;
                m->caps[n->cap * 2 + 1] = olde;
            }
            return 0;
        }
        if (n->cap >= 0 && n->cap < RG_RE_MAX_CAPS) {
            m->caps[n->cap * 2 + 1] = (int) j;
        }
        return re_exec(n->next, m, j, out);
    }
    if (!re_consume(n, m, i, &j)) {
        return 0;
    }
    return re_exec(n->next, m, j, out);
}

static int re_search(RENode *root, const char *text, REMatch *m) {
    size_t pos;
    int g;
    m->s = text == NULL ? "" : text;
    m->n = strlen(m->s);
    for (pos = 0; pos <= m->n; pos++) {
        size_t end = pos;
        for (g = 0; g < RG_RE_MAX_CAPS * 2; g++) {
            m->caps[g] = -1;
        }
        if (re_exec(root, m, pos, &end)) {
            m->caps[0] = (int) pos;
            m->caps[1] = (int) end;
            return 1;
        }
    }
    return 0;
}

static char *re_slice(const char *s, int start, int end) {
    size_t n;
    if (s == NULL || start < 0 || end < start) {
        return rg_strdup("");
    }
    n = (size_t) (end - start);
    return rg_strndup(s + start, n);
}

static int re_expand(const char *with, REMatch *m, RGBuf *b) {
    const char *p = with == NULL ? "" : with;
    while (*p != '\0') {
        if (*p != '$') {
            rg_buf_ch(b, *p++);
            continue;
        }
        p++;
        if (*p == '$') {
            rg_buf_ch(b, '$');
            p++;
            continue;
        }
        if (*p < '0' || *p > '9') {
            return 0;
        }
        {
            int g = 0;
            while (*p >= '0' && *p <= '9') {
                g = g * 10 + (*p - '0');
                p++;
            }
            if (g < 0 || g >= RG_RE_MAX_CAPS || m->caps[g * 2] < 0) {
                if (g >= RG_RE_MAX_CAPS) {
                    return 0;
                }
            } else {
                int a = m->caps[g * 2];
                int e = m->caps[g * 2 + 1];
                if (a >= 0 && e >= a) {
                    rg_buf_add(b, m->s + a, (size_t) (e - a));
                }
            }
        }
    }
    return 1;
}

static void rg_regex_call(RGValue *dest, const char *name, RGValue *args, int32_t argc) {
    const char *err = NULL;
    RENode *root;
    int ngroups = 0;
    if (strcmp(name, "valid") == 0) {
        if (argc != 1) {
            rg_die("regex.valid takes 1 argument");
        }
        root = re_compile(rg_arg_str(args, 0, argc, "__regex.valid"), &ngroups, &err);
        rg_set_bool(dest, err == NULL);
        (void) root;
        return;
    }
    {
        const char *pat;
        const char *text;
        REMatch m;
        m.ngroups = 0;
        if (strcmp(name, "replace") == 0) {
            if (argc != 3) {
                rg_die("regex.replace takes 3 arguments");
            }
        } else if (strcmp(name, "is_match") == 0 || strcmp(name, "find") == 0 || strcmp(name, "find_match") == 0
                || strcmp(name, "captures") == 0 || strcmp(name, "findall") == 0 || strcmp(name, "split") == 0) {
            if (argc != 2) {
                rg_die("regex takes 2 arguments");
            }
        } else {
            rg_die("unknown function __regex");
        }
        pat = rg_arg_str(args, 0, argc, "__regex");
        text = rg_arg_str(args, 1, argc, "__regex");
        root = re_compile(pat, &ngroups, &err);
        if (err != NULL) {
            char msg[256];
            snprintf(msg, sizeof(msg), "invalid regex: %s", err);
            rg_die(msg);
        }
        m.ngroups = ngroups;
        if (strcmp(name, "is_match") == 0) {
            rg_set_bool(dest, re_search(root, text, &m));
            return;
        }
        if (strcmp(name, "find") == 0) {
            if (!re_search(root, text, &m)) {
                rg_set_int(dest, -1);
                return;
            }
            rg_set_int(dest, m.caps[0]);
            return;
        }
        if (strcmp(name, "find_match") == 0) {
            if (!re_search(root, text, &m)) {
                rg_set_string(dest, "");
                return;
            }
            rg_set_string(dest, re_slice(text, m.caps[0], m.caps[1]));
            return;
        }
        if (strcmp(name, "captures") == 0) {
            RGArray *arr;
            int g;
            if (!re_search(root, text, &m)) {
                rg_set_array(dest, rg_alloc_array(0));
                return;
            }
            arr = rg_alloc_array(ngroups + 1);
            for (g = 0; g <= ngroups; g++) {
                int a = m.caps[g * 2];
                int e = m.caps[g * 2 + 1];
                rg_set_string(&arr->items[g], re_slice(text, a, e));
            }
            rg_set_array(dest, arr);
            return;
        }
        if (strcmp(name, "findall") == 0 || strcmp(name, "replace") == 0 || strcmp(name, "split") == 0) {
            size_t pos = 0;
            size_t n = strlen(text);
            RGArray *arr = rg_alloc_array(0);
            RGBuf b = {0};
            size_t last = 0;
            int any = 0;
            while (pos <= n) {
                size_t end;
                int g;
                REMatch cur;
                cur.s = text;
                cur.n = n;
                cur.ngroups = ngroups;
                for (g = 0; g < RG_RE_MAX_CAPS * 2; g++) {
                    cur.caps[g] = -1;
                }
                if (!re_exec(root, &cur, pos, &end)) {
                    size_t k;
                    int found = 0;
                    for (k = pos + 1; k <= n; k++) {
                        for (g = 0; g < RG_RE_MAX_CAPS * 2; g++) {
                            cur.caps[g] = -1;
                        }
                        if (re_exec(root, &cur, k, &end)) {
                            pos = k;
                            found = 1;
                            break;
                        }
                    }
                    if (!found) {
                        break;
                    }
                }
                cur.caps[0] = (int) pos;
                cur.caps[1] = (int) end;
                any = 1;
                if (strcmp(name, "findall") == 0) {
                    RGValue item;
                    rg_set_string(&item, re_slice(text, (int) pos, (int) end));
                    arr = rg_array_push(arr, &item);
                } else if (strcmp(name, "split") == 0) {
                    RGValue item;
                    rg_set_string(&item, re_slice(text, (int) last, (int) pos));
                    arr = rg_array_push(arr, &item);
                    last = end;
                } else {
                    rg_buf_add(&b, text + last, pos - last);
                    if (!re_expand(rg_arg_str(args, 2, argc, "__regex.replace"), &cur, &b)) {
                        rg_die("invalid regex replacement");
                    }
                    last = end;
                }
                if (end == pos) {
                    pos++;
                } else {
                    pos = end;
                }
            }
            if (strcmp(name, "findall") == 0) {
                rg_set_array(dest, arr);
                return;
            }
            if (strcmp(name, "split") == 0) {
                RGValue item;
                rg_set_string(&item, re_slice(text, (int) last, (int) n));
                arr = rg_array_push(arr, &item);
                rg_set_array(dest, arr);
                (void) any;
                return;
            }
            rg_buf_add(&b, text + last, n - last);
            rg_set_string(dest, rg_buf_take(&b));
            return;
        }
    }
}
