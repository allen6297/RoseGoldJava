typedef struct RGImg {
    char *key;
    int32_t w;
    int32_t h;
    uint32_t *px;
    struct RGImg *next;
} RGImg;

static RGImg *g_imgs;

static int32_t rg_ui_path_abs(const char *s) {
    if (s == NULL || s[0] == '\0') {
        return 0;
    }
    if (s[0] == '/') {
        return 1;
    }
    return ((s[0] >= 'A' && s[0] <= 'Z') || (s[0] >= 'a' && s[0] <= 'z')) && s[1] == ':';
}

static char *rg_ui_abs_path(const char *raw) {
    char cwd[4096];
    size_t na;
    size_t nb;
    char *out;
    char *p;
    if (raw == NULL || raw[0] == '\0') {
        return NULL;
    }
    if (rg_ui_path_abs(raw)) {
        out = rg_strdup(raw);
    } else {
#if defined(_WIN32)
        if (_getcwd(cwd, (int) sizeof(cwd)) == NULL) {
            return rg_strdup(raw);
        }
#else
        if (getcwd(cwd, sizeof(cwd)) == NULL) {
            return rg_strdup(raw);
        }
#endif
        na = strlen(cwd);
        nb = strlen(raw);
        out = (char *) malloc(na + 2 + nb);
        if (out == NULL) {
            rg_die("out of memory");
        }
        memcpy(out, cwd, na);
        out[na] = '/';
        memcpy(out + na + 1, raw, nb + 1);
    }
    for (p = out; *p != '\0'; p++) {
        if (*p == '\\') {
            *p = '/';
        }
    }
    return out;
}

#if !defined(_WIN32)
static int _stricmp(const char *a, const char *b) {
    return strcasecmp(a, b);
}
#endif

static int32_t rg_ui_ends_with(const char *s, const char *suf) {
    size_t n;
    size_t m;
    if (s == NULL || suf == NULL) {
        return 0;
    }
    n = strlen(s);
    m = strlen(suf);
    if (n < m) {
        return 0;
    }
    return _stricmp(s + n - m, suf) == 0;
}

static uint32_t rg_ui_hex_color(const char *p) {
    unsigned r = 0;
    unsigned g = 0;
    unsigned b = 0;
    if (p == NULL) {
        return 0xFF000000u;
    }
    if (*p == '#') {
        p++;
    }
    if (sscanf(p, "%2x%2x%2x", &r, &g, &b) != 3) {
        return 0xFF000000u;
    }
    return (uint32_t) b | ((uint32_t) g << 8) | ((uint32_t) r << 16) | 0xFF000000u;
}

static const char *rg_ui_attr(const char *tag, const char *end, const char *name) {
    size_t n = strlen(name);
    const char *p = tag;
    while (p < end) {
        if (strncmp(p, name, n) == 0 && p[n] == '=') {
            p += n + 1;
            if (*p == '"' || *p == '\'') {
                return p + 1;
            }
            return p;
        }
        p++;
    }
    return NULL;
}

static int32_t rg_ui_attr_int(const char *tag, const char *end, const char *name, int32_t fallback) {
    const char *v = rg_ui_attr(tag, end, name);
    if (v == NULL) {
        return fallback;
    }
    return (int32_t) strtol(v, NULL, 10);
}

static void rg_ui_fill_rect_px(uint32_t *px, int32_t tw, int32_t th, int32_t x, int32_t y, int32_t w, int32_t h,
        uint32_t color) {
    int32_t row;
    int32_t x0 = x < 0 ? 0 : x;
    int32_t y0 = y < 0 ? 0 : y;
    int32_t x1 = x + w;
    int32_t y1 = y + h;
    if (x1 > tw) {
        x1 = tw;
    }
    if (y1 > th) {
        y1 = th;
    }
    for (row = y0; row < y1; row++) {
        int32_t col;
        for (col = x0; col < x1; col++) {
            px[(size_t) row * (size_t) tw + (size_t) col] = color;
        }
    }
}

static void rg_ui_fill_circle_px(uint32_t *px, int32_t tw, int32_t th, int32_t cx, int32_t cy, int32_t r, uint32_t color) {
    int32_t row;
    int32_t y0 = cy - r;
    int32_t y1 = cy + r;
    float rf = (float) r;
    if (y0 < 0) {
        y0 = 0;
    }
    if (y1 > th) {
        y1 = th;
    }
    for (row = y0; row < y1; row++) {
        int32_t col;
        for (col = 0; col < tw; col++) {
            float dx = (float) col + 0.5f - (float) cx;
            float dy = (float) row + 0.5f - (float) cy;
            if (dx * dx + dy * dy <= rf * rf) {
                px[(size_t) row * (size_t) tw + (size_t) col] = color;
            }
        }
    }
}

static RGImg *rg_ui_load_svg(const char *path) {
    FILE *f;
    long n;
    char *xml;
    const char *p;
    int32_t w;
    int32_t h;
    RGImg *img;
    f = fopen(path, "rb");
    if (f == NULL) {
        return NULL;
    }
    if (fseek(f, 0, SEEK_END) != 0) {
        fclose(f);
        return NULL;
    }
    n = ftell(f);
    if (n < 8 || n > 2 * 1024 * 1024) {
        fclose(f);
        return NULL;
    }
    rewind(f);
    xml = (char *) malloc((size_t) n + 1);
    if (xml == NULL) {
        fclose(f);
        rg_die("out of memory");
    }
    if (fread(xml, 1, (size_t) n, f) != (size_t) n) {
        free(xml);
        fclose(f);
        return NULL;
    }
    fclose(f);
    xml[n] = '\0';
    p = strstr(xml, "<svg");
    if (p == NULL) {
        free(xml);
        return NULL;
    }
    {
        const char *end = strchr(p, '>');
        if (end == NULL) {
            free(xml);
            return NULL;
        }
        w = rg_ui_attr_int(p, end, "width", 0);
        h = rg_ui_attr_int(p, end, "height", 0);
        if (w < 1 || h < 1) {
            w = 16;
            h = 16;
        }
        if (w > 8192 || h > 8192) {
            free(xml);
            return NULL;
        }
    }
    img = (RGImg *) calloc(1, sizeof(RGImg));
    if (img == NULL) {
        free(xml);
        rg_die("out of memory");
    }
    img->w = w;
    img->h = h;
    img->px = (uint32_t *) calloc((size_t) w * (size_t) h, sizeof(uint32_t));
    if (img->px == NULL) {
        free(xml);
        free(img);
        rg_die("out of memory");
    }
    for (p = xml; *p != '\0';) {
        const char *end;
        if (strncmp(p, "<rect", 5) == 0) {
            uint32_t color = 0xFF000000u;
            const char *fill;
            end = strchr(p, '>');
            if (end == NULL) {
                break;
            }
            fill = rg_ui_attr(p, end, "fill");
            if (fill != NULL) {
                color = rg_ui_hex_color(fill);
            }
            rg_ui_fill_rect_px(img->px, w, h, rg_ui_attr_int(p, end, "x", 0), rg_ui_attr_int(p, end, "y", 0),
                    rg_ui_attr_int(p, end, "width", w), rg_ui_attr_int(p, end, "height", h), color);
            p = end + 1;
            continue;
        }
        if (strncmp(p, "<circle", 7) == 0) {
            uint32_t color = 0xFFFFFFFFu;
            const char *fill;
            end = strchr(p, '>');
            if (end == NULL) {
                break;
            }
            fill = rg_ui_attr(p, end, "fill");
            if (fill != NULL) {
                color = rg_ui_hex_color(fill);
            }
            rg_ui_fill_circle_px(img->px, w, h, rg_ui_attr_int(p, end, "cx", w / 2),
                    rg_ui_attr_int(p, end, "cy", h / 2), rg_ui_attr_int(p, end, "r", w / 4), color);
            p = end + 1;
            continue;
        }
        p++;
    }
    free(xml);
    return img;
}

#if defined(_WIN32)
#define INITGUID
#define COBJMACROS
#define CINTERFACE
#include <objbase.h>
#include <wincodec.h>

static int32_t g_ui_com;

static void rg_ui_com_init(void) {
    HRESULT hr;
    if (g_ui_com) {
        return;
    }
    hr = CoInitializeEx(NULL, COINIT_MULTITHREADED);
    (void) hr;
    g_ui_com = 1;
}

static RGImg *rg_ui_load_wic(const char *path) {
    IWICImagingFactory *fac = NULL;
    IWICBitmapDecoder *dec = NULL;
    IWICBitmapFrameDecode *frame = NULL;
    IWICFormatConverter *conv = NULL;
    wchar_t *wpath;
    UINT cw = 0;
    UINT ch = 0;
    RGImg *img;
    HRESULT hr;
    rg_ui_com_init();
    wpath = rg_ui_widen(path);
    hr = CoCreateInstance(&CLSID_WICImagingFactory, NULL, CLSCTX_INPROC_SERVER, &IID_IWICImagingFactory, (void **) &fac);
    if (FAILED(hr) || fac == NULL) {
        free(wpath);
        return NULL;
    }
    hr = IWICImagingFactory_CreateDecoderFromFilename(fac, wpath, NULL, GENERIC_READ, WICDecodeMetadataCacheOnLoad, &dec);
    free(wpath);
    if (FAILED(hr) || dec == NULL) {
        IWICImagingFactory_Release(fac);
        return NULL;
    }
    hr = IWICBitmapDecoder_GetFrame(dec, 0, &frame);
    if (FAILED(hr) || frame == NULL) {
        IWICBitmapDecoder_Release(dec);
        IWICImagingFactory_Release(fac);
        return NULL;
    }
    hr = IWICImagingFactory_CreateFormatConverter(fac, &conv);
    if (FAILED(hr) || conv == NULL) {
        IWICBitmapFrameDecode_Release(frame);
        IWICBitmapDecoder_Release(dec);
        IWICImagingFactory_Release(fac);
        return NULL;
    }
    hr = IWICFormatConverter_Initialize(conv, (IWICBitmapSource *) frame, &GUID_WICPixelFormat32bppBGRA,
            WICBitmapDitherTypeNone, NULL, 0.0, WICBitmapPaletteTypeCustom);
    if (FAILED(hr)) {
        IWICFormatConverter_Release(conv);
        IWICBitmapFrameDecode_Release(frame);
        IWICBitmapDecoder_Release(dec);
        IWICImagingFactory_Release(fac);
        return NULL;
    }
    IWICFormatConverter_GetSize(conv, &cw, &ch);
    if (cw < 1 || ch < 1 || cw > 8192 || ch > 8192) {
        IWICFormatConverter_Release(conv);
        IWICBitmapFrameDecode_Release(frame);
        IWICBitmapDecoder_Release(dec);
        IWICImagingFactory_Release(fac);
        return NULL;
    }
    img = (RGImg *) calloc(1, sizeof(RGImg));
    if (img == NULL) {
        rg_die("out of memory");
    }
    img->w = (int32_t) cw;
    img->h = (int32_t) ch;
    img->px = (uint32_t *) malloc((size_t) cw * (size_t) ch * sizeof(uint32_t));
    if (img->px == NULL) {
        rg_die("out of memory");
    }
    hr = IWICFormatConverter_CopyPixels(conv, NULL, cw * 4, cw * ch * 4, (BYTE *) img->px);
    IWICFormatConverter_Release(conv);
    IWICBitmapFrameDecode_Release(frame);
    IWICBitmapDecoder_Release(dec);
    IWICImagingFactory_Release(fac);
    if (FAILED(hr)) {
        free(img->px);
        free(img);
        return NULL;
    }
    return img;
}
#else
static RGImg *rg_ui_load_wic(const char *path) {
    (void) path;
    return NULL;
}
#endif

static RGImg *rg_ui_img(const char *raw) {
    char *abs;
    RGImg *it;
    RGImg *loaded;
    if (raw == NULL || raw[0] == '\0') {
        return NULL;
    }
    abs = rg_ui_abs_path(raw);
    if (abs == NULL) {
        return NULL;
    }
    for (it = g_imgs; it != NULL; it = it->next) {
        if (strcmp(it->key, abs) == 0) {
            free(abs);
            return it;
        }
    }
    loaded = NULL;
    if (rg_ui_ends_with(abs, ".svg")) {
        loaded = rg_ui_load_svg(abs);
    }
    if (loaded == NULL) {
        loaded = rg_ui_load_wic(abs);
    }
    if (loaded == NULL) {
        free(abs);
        return NULL;
    }
    loaded->key = abs;
    loaded->next = g_imgs;
    g_imgs = loaded;
    return loaded;
}

static void rg_ui_blit_img(RGWin *win, int32_t x, int32_t y, RGImg *img) {
    int32_t row;
    rg_ui_ensure_fb(win);
    if (img == NULL || img->px == NULL) {
        return;
    }
    for (row = 0; row < img->h; row++) {
        int32_t col;
        for (col = 0; col < img->w; col++) {
            uint32_t v = img->px[(size_t) row * (size_t) img->w + (size_t) col];
            int32_t a = (int32_t) ((v >> 24) & 255);
            uint32_t rgb = v & 0xFFFFFFu;
            if (a <= 0) {
                continue;
            }
            if (a >= 255) {
                rg_ui_plot(win, x + col, y + row, rgb);
            } else {
                int32_t dx = x + col;
                int32_t dy = y + row;
                if (dx < 0 || dy < 0 || dx >= win->fb_w || dy >= win->fb_h) {
                    continue;
                }
                if (win->nclips > 0) {
                    RGClip c = win->clips[win->nclips - 1];
                    if (dx < c.x0 || dy < c.y0 || dx >= c.x1 || dy >= c.y1) {
                        continue;
                    }
                }
                {
                    uint32_t *slot = &win->fb[(size_t) dy * (size_t) win->fb_w + (size_t) dx];
                    *slot = rg_ui_blend(*slot, rgb, a);
                }
            }
        }
    }
}
