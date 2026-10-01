static int32_t rg_ui_forced_headless(void) {
    const char *e = getenv("RG_UI_HEADLESS");
    if (e == NULL || e[0] == '\0' || strcmp(e, "0") == 0) {
        return 0;
    }
#if defined(_WIN32)
    if (_stricmp(e, "false") == 0) {
        return 0;
    }
#else
    if (strcmp(e, "false") == 0) {
        return 0;
    }
#endif
    return 1;
}

static uint32_t rg_ui_pack(int64_t color) {
    uint32_t r = (uint32_t) ((color >> 16) & 255);
    uint32_t g = (uint32_t) ((color >> 8) & 255);
    uint32_t b = (uint32_t) (color & 255);
    return b | (g << 8) | (r << 16);
}

static void rg_ui_ensure_fb(RGWin *win) {
    int32_t w;
    int32_t h;
    uint32_t fill;
    size_t n;
    if (win == NULL) {
        return;
    }
    w = win->w > 0 ? win->w : 1;
    h = win->h > 0 ? win->h : 1;
    if (w > RG_UI_MAX) {
        w = RG_UI_MAX;
    }
    if (h > RG_UI_MAX) {
        h = RG_UI_MAX;
    }
    if (win->fb != NULL && win->fb_w == w && win->fb_h == h) {
        return;
    }
    free(win->fb);
    n = (size_t) w * (size_t) h;
    win->fb = (uint32_t *) malloc(n * sizeof(uint32_t));
    if (win->fb == NULL) {
        rg_die("out of memory");
    }
    fill = rg_ui_pack(0xF2F2F2);
    {
        size_t i;
        for (i = 0; i < n; i++) {
            win->fb[i] = fill;
        }
    }
    win->fb_w = w;
    win->fb_h = h;
}

static void rg_ui_clip_box(RGWin *win, int32_t *x0, int32_t *y0, int32_t *x1, int32_t *y1) {
    if (*x0 < 0) {
        *x0 = 0;
    }
    if (*y0 < 0) {
        *y0 = 0;
    }
    if (*x1 > win->fb_w) {
        *x1 = win->fb_w;
    }
    if (*y1 > win->fb_h) {
        *y1 = win->fb_h;
    }
    if (win->nclips > 0) {
        RGClip c = win->clips[win->nclips - 1];
        if (*x0 < c.x0) {
            *x0 = c.x0;
        }
        if (*y0 < c.y0) {
            *y0 = c.y0;
        }
        if (*x1 > c.x1) {
            *x1 = c.x1;
        }
        if (*y1 > c.y1) {
            *y1 = c.y1;
        }
    }
}

static void rg_ui_clear(RGWin *win, int64_t color) {
    uint32_t p;
    size_t n;
    size_t i;
    rg_ui_ensure_fb(win);
    p = rg_ui_pack(color);
    n = (size_t) win->fb_w * (size_t) win->fb_h;
    for (i = 0; i < n; i++) {
        win->fb[i] = p;
    }
}

static void rg_ui_fill(RGWin *win, int32_t x, int32_t y, int32_t w, int32_t h, int64_t color) {
    int32_t x0;
    int32_t y0;
    int32_t x1;
    int32_t y1;
    int32_t row;
    uint32_t p;
    rg_ui_ensure_fb(win);
    if (w < 1 || h < 1) {
        return;
    }
    x0 = x;
    y0 = y;
    x1 = x + w;
    y1 = y + h;
    rg_ui_clip_box(win, &x0, &y0, &x1, &y1);
    if (x0 >= x1 || y0 >= y1) {
        return;
    }
    p = rg_ui_pack(color);
    for (row = y0; row < y1; row++) {
        int32_t col;
        uint32_t *dest = win->fb + (size_t) row * (size_t) win->fb_w + (size_t) x0;
        for (col = x0; col < x1; col++) {
            *dest++ = p;
        }
    }
}

static uint32_t rg_ui_blend(uint32_t dst, uint32_t src, int32_t cover) {
    int32_t a = cover < 0 ? 0 : cover > 255 ? 255 : cover;
    int32_t na = 255 - a;
    int32_t db = (int32_t) (dst & 255);
    int32_t dg = (int32_t) ((dst >> 8) & 255);
    int32_t dr = (int32_t) ((dst >> 16) & 255);
    int32_t sb = (int32_t) (src & 255);
    int32_t sg = (int32_t) ((src >> 8) & 255);
    int32_t sr = (int32_t) ((src >> 16) & 255);
    return (uint32_t) ((sb * a + db * na) / 255)
            | ((uint32_t) ((sg * a + dg * na) / 255) << 8)
            | ((uint32_t) ((sr * a + dr * na) / 255) << 16);
}

static void rg_ui_plot(RGWin *win, int32_t x, int32_t y, uint32_t p) {
    if (x < 0 || y < 0 || x >= win->fb_w || y >= win->fb_h) {
        return;
    }
    if (win->nclips > 0) {
        RGClip c = win->clips[win->nclips - 1];
        if (x < c.x0 || y < c.y0 || x >= c.x1 || y >= c.y1) {
            return;
        }
    }
    win->fb[(size_t) y * (size_t) win->fb_w + (size_t) x] = p;
}

static void rg_ui_line(RGWin *win, int32_t x0, int32_t y0, int32_t x1, int32_t y1, int64_t color) {
    int32_t dx = x1 > x0 ? x1 - x0 : x0 - x1;
    int32_t sx = x0 < x1 ? 1 : -1;
    int32_t dy = y1 > y0 ? y0 - y1 : y1 - y0;
    int32_t sy = y0 < y1 ? 1 : -1;
    int32_t err = dx + dy;
    uint32_t p;
    rg_ui_ensure_fb(win);
    p = rg_ui_pack(color);
    for (;;) {
        rg_ui_plot(win, x0, y0, p);
        if (x0 == x1 && y0 == y1) {
            break;
        }
        {
            int32_t e2 = err * 2;
            if (e2 >= dy) {
                err += dy;
                x0 += sx;
            }
            if (e2 <= dx) {
                err += dx;
                y0 += sy;
            }
        }
    }
}

static void rg_ui_stroke_rect(RGWin *win, int32_t x, int32_t y, int32_t w, int32_t h, int64_t color) {
    if (w < 1 || h < 1) {
        return;
    }
    rg_ui_fill(win, x, y, w, 1, color);
    rg_ui_fill(win, x, y + h - 1, w, 1, color);
    rg_ui_fill(win, x, y, 1, h, color);
    rg_ui_fill(win, x + w - 1, y, 1, h, color);
}

static float rg_ui_sd_round(float px, float py, float hw, float hh, float r) {
    float ax = fabsf(px) - hw + r;
    float ay = fabsf(py) - hh + r;
    float qx = ax > 0 ? ax : 0;
    float qy = ay > 0 ? ay : 0;
    float mx = ax > ay ? ax : ay;
    float inside = mx < 0 ? mx : 0;
    return sqrtf(qx * qx + qy * qy) + inside - r;
}

static void rg_ui_round(RGWin *win, int32_t x, int32_t y, int32_t w, int32_t h, int32_t radius, int64_t color,
        int32_t fill) {
    int32_t x0;
    int32_t y0;
    int32_t x1;
    int32_t y1;
    int32_t row;
    uint32_t p;
    float hw;
    float hh;
    float r;
    float cx;
    float cy;
    rg_ui_ensure_fb(win);
    if (w < 1 || h < 1) {
        return;
    }
    if (radius < 0) {
        radius = 0;
    }
    if (radius * 2 > w) {
        radius = w / 2;
    }
    if (radius * 2 > h) {
        radius = h / 2;
    }
    if (radius <= 0) {
        if (fill) {
            rg_ui_fill(win, x, y, w, h, color);
        } else {
            rg_ui_stroke_rect(win, x, y, w, h, color);
        }
        return;
    }
    x0 = x;
    y0 = y;
    x1 = x + w;
    y1 = y + h;
    rg_ui_clip_box(win, &x0, &y0, &x1, &y1);
    if (x0 >= x1 || y0 >= y1) {
        return;
    }
    p = rg_ui_pack(color);
    hw = (float) w * 0.5f;
    hh = (float) h * 0.5f;
    r = (float) radius;
    cx = (float) x + hw;
    cy = (float) y + hh;
    for (row = y0; row < y1; row++) {
        int32_t col;
        for (col = x0; col < x1; col++) {
            float d = rg_ui_sd_round((float) col + 0.5f - cx, (float) row + 0.5f - cy, hw, hh, r);
            float cover = fill ? 0.5f - d : 1.0f - fabsf(d);
            int32_t a;
            if (cover <= 0) {
                continue;
            }
            if (cover >= 1) {
                win->fb[(size_t) row * (size_t) win->fb_w + (size_t) col] = p;
                continue;
            }
            a = (int32_t) (cover * 255.0f + 0.5f);
            if (a > 0) {
                uint32_t *slot = &win->fb[(size_t) row * (size_t) win->fb_w + (size_t) col];
                *slot = rg_ui_blend(*slot, p, a);
            }
        }
    }
}

static void rg_ui_blit_rgb(RGWin *win, int32_t x, int32_t y, int32_t iw, int32_t ih, RGArray *px) {
    int32_t row;
    rg_ui_ensure_fb(win);
    if (px == NULL || iw < 1 || ih < 1) {
        return;
    }
    for (row = 0; row < ih; row++) {
        int32_t col;
        for (col = 0; col < iw; col++) {
            int32_t i = row * iw + col;
            int64_t color;
            if (i >= px->len || px->items[i].kind != RG_INT) {
                continue;
            }
            color = px->items[i].i;
            rg_ui_plot(win, x + col, y + row, rg_ui_pack(color));
        }
    }
}

#if defined(_WIN32)

#ifndef DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2
#define DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2 ((HANDLE) (intptr_t) -4)
#endif

static int32_t g_ui_dpi;
static HFONT g_ui_font;
static int32_t g_ui_font_h;

static void rg_ui_enable_dpi(void) {
    static int32_t done;
    HMODULE user32;
    typedef BOOL (WINAPI *CtxFn)(HANDLE);
    typedef HRESULT (WINAPI *ShcoreFn)(int);
    CtxFn ctx;
    if (done) {
        return;
    }
    done = 1;
    user32 = GetModuleHandleW(L"user32.dll");
    ctx = user32 == NULL ? NULL : (CtxFn) GetProcAddress(user32, "SetProcessDpiAwarenessContext");
    if (ctx != NULL && ctx(DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2)) {
        return;
    }
    {
        HMODULE shcore = LoadLibraryW(L"shcore.dll");
        if (shcore != NULL) {
            ShcoreFn aware = (ShcoreFn) GetProcAddress(shcore, "SetProcessDpiAwareness");
            if (aware != NULL) {
                aware(2);
            }
        }
    }
    SetProcessDPIAware();
}

static int32_t rg_ui_system_dpi(void) {
    typedef UINT (WINAPI *SysFn)(void);
    HMODULE user32 = GetModuleHandleW(L"user32.dll");
    SysFn sys = user32 == NULL ? NULL : (SysFn) GetProcAddress(user32, "GetDpiForSystem");
    if (sys != NULL) {
        UINT d = sys();
        if (d > 0) {
            return (int32_t) d;
        }
    }
    {
        HDC hdc = GetDC(NULL);
        int32_t dpi = 96;
        if (hdc != NULL) {
            dpi = GetDeviceCaps(hdc, LOGPIXELSX);
            ReleaseDC(NULL, hdc);
        }
        return dpi > 0 ? dpi : 96;
    }
}

static int32_t rg_ui_hwnd_dpi(HWND hwnd) {
    typedef UINT (WINAPI *WinFn)(HWND);
    HMODULE user32 = GetModuleHandleW(L"user32.dll");
    WinFn fn = user32 == NULL ? NULL : (WinFn) GetProcAddress(user32, "GetDpiForWindow");
    if (fn != NULL && hwnd != NULL) {
        UINT d = fn(hwnd);
        if (d > 0) {
            return (int32_t) d;
        }
    }
    return rg_ui_system_dpi();
}

static int32_t rg_ui_scale(int32_t px, int32_t dpi) {
    if (dpi <= 0 || dpi == 96) {
        return px;
    }
    return (int32_t) (((int64_t) px * dpi + 48) / 96);
}

static void rg_ui_drop_font(void) {
    if (g_ui_font != NULL && g_ui_font != GetStockObject(DEFAULT_GUI_FONT)) {
        DeleteObject(g_ui_font);
    }
    g_ui_font = NULL;
    g_ui_font_h = 0;
}

static void rg_ui_set_dpi(int32_t dpi) {
    if (dpi < 96) {
        dpi = 96;
    }
    if (g_ui_dpi == dpi && g_ui_font != NULL) {
        return;
    }
    g_ui_dpi = dpi;
    rg_ui_drop_font();
}

static HFONT rg_ui_font(void) {
    int32_t dpi = g_ui_dpi > 0 ? g_ui_dpi : rg_ui_system_dpi();
    LOGFONTW lf;
    if (g_ui_font != NULL) {
        return g_ui_font;
    }
    memset(&lf, 0, sizeof(lf));
    lf.lfHeight = -MulDiv(12, dpi, 72);
    lf.lfWeight = FW_NORMAL;
    lf.lfCharSet = DEFAULT_CHARSET;
    lf.lfQuality = ANTIALIASED_QUALITY;
    lf.lfOutPrecision = OUT_TT_PRECIS;
    lf.lfClipPrecision = CLIP_DEFAULT_PRECIS;
    lf.lfPitchAndFamily = DEFAULT_PITCH | FF_DONTCARE;
    memcpy(lf.lfFaceName, L"Segoe UI", sizeof(L"Segoe UI"));
    g_ui_font = CreateFontIndirectW(&lf);
    if (g_ui_font == NULL) {
        memcpy(lf.lfFaceName, L"Segoe UI Variable", sizeof(L"Segoe UI Variable"));
        g_ui_font = CreateFontIndirectW(&lf);
    }
    if (g_ui_font == NULL) {
        g_ui_font = (HFONT) GetStockObject(DEFAULT_GUI_FONT);
    }
    return g_ui_font;
}

static void rg_ui_font_metrics(int32_t *height, HDC hdc) {
    TEXTMETRICW tm;
    HFONT old = (HFONT) SelectObject(hdc, rg_ui_font());
    GetTextMetricsW(hdc, &tm);
    SelectObject(hdc, old);
    if (height != NULL) {
        *height = tm.tmHeight > 0 ? (int32_t) tm.tmHeight : rg_ui_scale(16, g_ui_dpi > 0 ? g_ui_dpi : 96);
    }
}

static int32_t rg_ui_font_height(void) {
    HDC hdc;
    if (g_ui_font_h > 0) {
        return g_ui_font_h;
    }
    hdc = GetDC(NULL);
    if (hdc == NULL) {
        return rg_ui_scale(16, g_ui_dpi > 0 ? g_ui_dpi : 96);
    }
    rg_ui_font_metrics(&g_ui_font_h, hdc);
    ReleaseDC(NULL, hdc);
    if (g_ui_font_h < 1) {
        g_ui_font_h = rg_ui_scale(16, g_ui_dpi > 0 ? g_ui_dpi : 96);
    }
    return g_ui_font_h;
}

static wchar_t *rg_ui_widen(const char *s) {
    int n;
    wchar_t *w;
    if (s == NULL) {
        s = "";
    }
    n = MultiByteToWideChar(CP_UTF8, 0, s, -1, NULL, 0);
    if (n < 1) {
        w = (wchar_t *) malloc(sizeof(wchar_t));
        if (w == NULL) {
            rg_die("out of memory");
        }
        w[0] = 0;
        return w;
    }
    w = (wchar_t *) malloc((size_t) n * sizeof(wchar_t));
    if (w == NULL) {
        rg_die("out of memory");
    }
    MultiByteToWideChar(CP_UTF8, 0, s, -1, w, n);
    return w;
}

static int32_t rg_ui_text_width(const char *s) {
    HDC hdc;
    SIZE sz;
    wchar_t *w;
    HFONT old;
    if (s == NULL || s[0] == '\0') {
        return 0;
    }
    hdc = GetDC(NULL);
    if (hdc == NULL) {
        return (int32_t) strlen(s) * 8;
    }
    w = rg_ui_widen(s);
    old = (HFONT) SelectObject(hdc, rg_ui_font());
    if (GetTextExtentPoint32W(hdc, w, (int) wcslen(w), &sz)) {
        SelectObject(hdc, old);
        ReleaseDC(NULL, hdc);
        free(w);
        return sz.cx;
    }
    SelectObject(hdc, old);
    ReleaseDC(NULL, hdc);
    free(w);
    return (int32_t) strlen(s) * 8;
}

static void rg_ui_text(RGWin *win, int32_t x, int32_t y, const char *s, int64_t color) {
    BITMAPINFO bmi;
    void *bits = NULL;
    HDC hdc;
    HDC mem;
    HBITMAP dib;
    HBITMAP old_bmp;
    HFONT old_font;
    wchar_t *w;
    uint32_t *src;
    uint32_t *dst;
    int32_t row;
    COLORREF rgb;
    rg_ui_ensure_fb(win);
    if (s == NULL || s[0] == '\0' || win->fb_w < 1 || win->fb_h < 1) {
        return;
    }
    memset(&bmi, 0, sizeof(bmi));
    bmi.bmiHeader.biSize = sizeof(BITMAPINFOHEADER);
    bmi.bmiHeader.biWidth = win->fb_w;
    bmi.bmiHeader.biHeight = -win->fb_h;
    bmi.bmiHeader.biPlanes = 1;
    bmi.bmiHeader.biBitCount = 32;
    bmi.bmiHeader.biCompression = BI_RGB;
    hdc = GetDC(NULL);
    if (hdc == NULL) {
        return;
    }
    mem = CreateCompatibleDC(hdc);
    dib = CreateDIBSection(hdc, &bmi, DIB_RGB_COLORS, &bits, NULL, 0);
    if (mem == NULL || dib == NULL || bits == NULL) {
        if (dib != NULL) {
            DeleteObject(dib);
        }
        if (mem != NULL) {
            DeleteDC(mem);
        }
        ReleaseDC(NULL, hdc);
        return;
    }
    memcpy(bits, win->fb, (size_t) win->fb_w * (size_t) win->fb_h * sizeof(uint32_t));
    old_bmp = (HBITMAP) SelectObject(mem, dib);
    old_font = (HFONT) SelectObject(mem, rg_ui_font());
    SetBkMode(mem, TRANSPARENT);
    SetTextAlign(mem, TA_LEFT | TA_TOP);
    rgb = RGB((int) ((color >> 16) & 255), (int) ((color >> 8) & 255), (int) (color & 255));
    SetTextColor(mem, rgb);
    if (win->nclips > 0) {
        RGClip c = win->clips[win->nclips - 1];
        IntersectClipRect(mem, c.x0, c.y0, c.x1, c.y1);
    }
    w = rg_ui_widen(s);
    TextOutW(mem, x, y, w, (int) wcslen(w));
    free(w);
    SelectObject(mem, old_font);
    SelectObject(mem, old_bmp);
    src = (uint32_t *) bits;
    dst = win->fb;
    for (row = 0; row < win->fb_h; row++) {
        memcpy(dst + (size_t) row * (size_t) win->fb_w, src + (size_t) row * (size_t) win->fb_w,
                (size_t) win->fb_w * sizeof(uint32_t));
    }
    DeleteObject(dib);
    DeleteDC(mem);
    ReleaseDC(NULL, hdc);
}

static RGWin *rg_ui_from_hwnd(HWND hwnd) {
    if (hwnd == NULL) {
        return NULL;
    }
    return (RGWin *) GetWindowLongPtrW(hwnd, GWLP_USERDATA);
}

static void rg_ui_feed_key(RGWin *win, int32_t code, const char *text) {
    if (win == NULL) {
        return;
    }
    win->key_code = code;
    free(win->key_text);
    win->key_text = rg_strdup(text == NULL ? "" : text);
    win->keyp = 1;
}

static void rg_ui_present(RGWin *win) {
    PAINTSTRUCT ps;
    HDC hdc;
    BITMAPINFO bmi;
    if (win == NULL || win->hwnd == NULL) {
        return;
    }
    rg_ui_ensure_fb(win);
    hdc = BeginPaint(win->hwnd, &ps);
    if (hdc == NULL) {
        hdc = GetDC(win->hwnd);
        if (hdc == NULL) {
            return;
        }
        memset(&bmi, 0, sizeof(bmi));
        bmi.bmiHeader.biSize = sizeof(BITMAPINFOHEADER);
        bmi.bmiHeader.biWidth = win->fb_w;
        bmi.bmiHeader.biHeight = -win->fb_h;
        bmi.bmiHeader.biPlanes = 1;
        bmi.bmiHeader.biBitCount = 32;
        bmi.bmiHeader.biCompression = BI_RGB;
        SetDIBitsToDevice(hdc, 0, 0, (UINT) win->fb_w, (UINT) win->fb_h, 0, 0, 0, (UINT) win->fb_h, win->fb, &bmi,
                DIB_RGB_COLORS);
        ReleaseDC(win->hwnd, hdc);
        return;
    }
    memset(&bmi, 0, sizeof(bmi));
    bmi.bmiHeader.biSize = sizeof(BITMAPINFOHEADER);
    bmi.bmiHeader.biWidth = win->fb_w;
    bmi.bmiHeader.biHeight = -win->fb_h;
    bmi.bmiHeader.biPlanes = 1;
    bmi.bmiHeader.biBitCount = 32;
    bmi.bmiHeader.biCompression = BI_RGB;
    SetDIBitsToDevice(hdc, 0, 0, (UINT) win->fb_w, (UINT) win->fb_h, 0, 0, 0, (UINT) win->fb_h, win->fb, &bmi,
            DIB_RGB_COLORS);
    EndPaint(win->hwnd, &ps);
}

static LPCSTR rg_ui_cursor(int32_t kind) {
    if (kind == 1) {
        return IDC_HAND;
    }
    if (kind == 2) {
        return IDC_IBEAM;
    }
    return IDC_ARROW;
}

static LRESULT CALLBACK rg_ui_wndproc(HWND hwnd, UINT msg, WPARAM wparam, LPARAM lparam) {
    RGWin *win = rg_ui_from_hwnd(hwnd);
    switch (msg) {
        case WM_NCCREATE: {
            CREATESTRUCTW *cs = (CREATESTRUCTW *) lparam;
            typedef BOOL (WINAPI *NcFn)(HWND);
            HMODULE user32 = GetModuleHandleW(L"user32.dll");
            NcFn enable = user32 == NULL ? NULL : (NcFn) GetProcAddress(user32, "EnableNonClientDpiScaling");
            if (enable != NULL) {
                enable(hwnd);
            }
            if (cs != NULL) {
                SetWindowLongPtrW(hwnd, GWLP_USERDATA, (LONG_PTR) cs->lpCreateParams);
            }
            return DefWindowProcW(hwnd, msg, wparam, lparam);
        }
        case WM_CLOSE:
            if (win != NULL) {
                win->alive = 0;
            }
            DestroyWindow(hwnd);
            return 0;
        case WM_DESTROY:
            if (win != NULL) {
                win->hwnd = NULL;
                win->alive = 0;
            }
            return 0;
        case WM_PAINT:
            if (win != NULL) {
                rg_ui_present(win);
                return 0;
            }
            break;
        case WM_DPICHANGED:
            if (win != NULL) {
                RECT *nr = (RECT *) lparam;
                int32_t dpi = (int32_t) HIWORD(wparam);
                rg_ui_set_dpi(dpi);
                win->dpi = dpi;
                if (nr != NULL) {
                    SetWindowPos(hwnd, NULL, nr->left, nr->top, nr->right - nr->left, nr->bottom - nr->top,
                            SWP_NOZORDER | SWP_NOACTIVATE);
                }
            }
            return 0;
        case WM_SIZE:
            if (win != NULL && wparam != SIZE_MINIMIZED) {
                int32_t w = LOWORD(lparam);
                int32_t h = HIWORD(lparam);
                if (w > 0 && h > 0 && w <= RG_UI_MAX && h <= RG_UI_MAX) {
                    win->w = w;
                    win->h = h;
                    rg_ui_ensure_fb(win);
                }
            }
            return 0;
        case WM_MOUSEMOVE:
            if (win != NULL) {
                win->mx = GET_X_LPARAM(lparam);
                win->my = GET_Y_LPARAM(lparam);
            }
            return 0;
        case WM_LBUTTONDOWN:
            if (win != NULL) {
                win->mx = GET_X_LPARAM(lparam);
                win->my = GET_Y_LPARAM(lparam);
                win->down = 1;
                SetCapture(hwnd);
            }
            return 0;
        case WM_LBUTTONUP:
            if (win != NULL) {
                win->mx = GET_X_LPARAM(lparam);
                win->my = GET_Y_LPARAM(lparam);
                win->down = 0;
                win->click = 1;
                ReleaseCapture();
            }
            return 0;
        case WM_RBUTTONUP:
            if (win != NULL) {
                win->mx = GET_X_LPARAM(lparam);
                win->my = GET_Y_LPARAM(lparam);
                win->rclick = 1;
            }
            return 0;
        case WM_MOUSEWHEEL:
            if (win != NULL) {
                int32_t delta = GET_WHEEL_DELTA_WPARAM(wparam);
                int32_t px;
                win->wheel_acc += delta;
                px = win->wheel_acc * 48 / 120;
                if (px != 0) {
                    win->wheel_acc -= px * 120 / 48;
                    if (!win->scrollp) {
                        win->sdx = 0;
                        win->sdy = 0;
                    }
                    win->sdy += -px;
                    win->scrollp = 1;
                }
            }
            return 0;
        case WM_SETCURSOR:
            if (win != NULL && LOWORD(lparam) == HTCLIENT) {
                SetCursor(LoadCursorA(NULL, rg_ui_cursor(win->cursor)));
                return TRUE;
            }
            break;
        case WM_KEYDOWN:
            if (win != NULL) {
                int32_t vk = (int32_t) wparam;
                int32_t shift = (GetKeyState(VK_SHIFT) & 0x8000) != 0;
                int32_t ctrl = (GetKeyState(VK_CONTROL) & 0x8000) != 0;
                const char *mod = shift && ctrl ? "ctrl+shift" : shift ? "shift" : ctrl ? "ctrl" : "";
                if (vk == VK_TAB) {
                    rg_ui_feed_key(win, vk, shift ? "shift" : "");
                    return 0;
                }
                if (vk == VK_LEFT || vk == VK_RIGHT || vk == VK_HOME || vk == VK_END || vk == VK_DELETE
                        || vk == VK_UP || vk == VK_DOWN || vk == VK_PRIOR || vk == VK_NEXT || vk == VK_ESCAPE) {
                    rg_ui_feed_key(win, vk, mod);
                    return 0;
                }
                if (ctrl && (vk == 'A' || vk == 'C' || vk == 'X' || vk == 'V')) {
                    rg_ui_feed_key(win, vk, "ctrl");
                    return 0;
                }
            }
            break;
        case WM_CHAR:
            if (win != NULL) {
                wchar_t ch = (wchar_t) wparam;
                if (ch == 9) {
                    return 0;
                }
                if (ch == 8 || ch == 13) {
                    char buf[2] = { (char) ch, 0 };
                    rg_ui_feed_key(win, (int32_t) ch, buf);
                } else if (ch >= 32 && ch != 127) {
                    char utf8[8];
                    int n = WideCharToMultiByte(CP_UTF8, 0, &ch, 1, utf8, (int) sizeof(utf8) - 1, NULL, NULL);
                    if (n > 0) {
                        utf8[n] = 0;
                        rg_ui_feed_key(win, (int32_t) ch, utf8);
                    }
                }
                return 0;
            }
            break;
        default:
            break;
    }
    return DefWindowProcW(hwnd, msg, wparam, lparam);
}

static ATOM rg_ui_register(void) {
    static ATOM atom;
    WNDCLASSW wc;
    if (atom != 0) {
        return atom;
    }
    rg_ui_enable_dpi();
    memset(&wc, 0, sizeof(wc));
    wc.style = CS_HREDRAW | CS_VREDRAW | CS_OWNDC;
    wc.lpfnWndProc = rg_ui_wndproc;
    wc.hInstance = GetModuleHandleW(NULL);
    wc.hCursor = LoadCursorA(NULL, IDC_ARROW);
    wc.hbrBackground = (HBRUSH) (COLOR_WINDOW + 1);
    wc.lpszClassName = L"RoseGoldWin";
    atom = RegisterClassW(&wc);
    return atom;
}

static void rg_ui_destroy_hwnd(RGWin *win) {
    if (win != NULL && win->hwnd != NULL) {
        HWND hwnd = win->hwnd;
        win->hwnd = NULL;
        DestroyWindow(hwnd);
    }
}

static void rg_ui_create_hwnd(RGWin *win) {
    RECT rc;
    RECT client;
    DWORD style = WS_OVERLAPPEDWINDOW;
    wchar_t *title;
    int32_t cw;
    int32_t ch;
    int32_t dpi;
    if (win == NULL || win->hwnd != NULL || rg_ui_forced_headless()) {
        return;
    }
    if (rg_ui_register() == 0) {
        return;
    }
    dpi = rg_ui_system_dpi();
    rg_ui_set_dpi(dpi);
    if (win->dpi <= 0) {
        win->w = rg_ui_scale(win->w, dpi);
        win->h = rg_ui_scale(win->h, dpi);
    }
    win->dpi = dpi;
    rg_ui_ensure_fb(win);
    rc.left = 0;
    rc.top = 0;
    rc.right = win->w;
    rc.bottom = win->h;
    AdjustWindowRect(&rc, style, FALSE);
    cw = rc.right - rc.left;
    ch = rc.bottom - rc.top;
    title = rg_ui_widen(win->title != NULL ? win->title : "RoseGold");
    win->hwnd = CreateWindowExW(0, L"RoseGoldWin", title, style, CW_USEDEFAULT, CW_USEDEFAULT, cw, ch, NULL, NULL,
            GetModuleHandleW(NULL), win);
    free(title);
    if (win->hwnd == NULL) {
        return;
    }
    dpi = rg_ui_hwnd_dpi(win->hwnd);
    rg_ui_set_dpi(dpi);
    win->dpi = dpi;
    if (GetClientRect(win->hwnd, &client)) {
        int32_t nw = client.right - client.left;
        int32_t nh = client.bottom - client.top;
        if (nw > 0 && nh > 0) {
            win->w = nw;
            win->h = nh;
            rg_ui_ensure_fb(win);
        }
    }
    ShowWindow(win->hwnd, win->mapped ? SW_SHOW : SW_HIDE);
    UpdateWindow(win->hwnd);
}

static int32_t rg_ui_any_hwnd(void) {
    RGWin *win;
    for (win = g_wins; win != NULL; win = win->next) {
        if (win->alive && win->hwnd != NULL) {
            return 1;
        }
    }
    return 0;
}

static void rg_ui_pump(void) {
    MSG msg;
    while (PeekMessageW(&msg, NULL, 0, 0, PM_REMOVE)) {
        TranslateMessage(&msg);
        DispatchMessageW(&msg);
    }
}

static char *rg_ui_clipboard_os(void) {
    HANDLE mem;
    wchar_t *w;
    int n;
    char *utf8;
    if (!OpenClipboard(NULL)) {
        return NULL;
    }
    mem = GetClipboardData(CF_UNICODETEXT);
    if (mem == NULL) {
        CloseClipboard();
        return NULL;
    }
    w = (wchar_t *) GlobalLock(mem);
    if (w == NULL) {
        CloseClipboard();
        return NULL;
    }
    n = WideCharToMultiByte(CP_UTF8, 0, w, -1, NULL, 0, NULL, NULL);
    utf8 = n > 0 ? (char *) malloc((size_t) n) : NULL;
    if (utf8 != NULL) {
        WideCharToMultiByte(CP_UTF8, 0, w, -1, utf8, n, NULL, NULL);
    }
    GlobalUnlock(mem);
    CloseClipboard();
    return utf8;
}

static void rg_ui_clipboard_set_os(const char *s) {
    wchar_t *w;
    size_t n;
    HGLOBAL mem;
    wchar_t *dest;
    w = rg_ui_widen(s);
    n = (wcslen(w) + 1) * sizeof(wchar_t);
    mem = GlobalAlloc(GMEM_MOVEABLE, n);
    if (mem == NULL) {
        free(w);
        return;
    }
    dest = (wchar_t *) GlobalLock(mem);
    if (dest == NULL) {
        GlobalFree(mem);
        free(w);
        return;
    }
    memcpy(dest, w, n);
    GlobalUnlock(mem);
    free(w);
    if (!OpenClipboard(NULL)) {
        GlobalFree(mem);
        return;
    }
    EmptyClipboard();
    SetClipboardData(CF_UNICODETEXT, mem);
    CloseClipboard();
}

#else

static int32_t rg_ui_font_height(void) {
    return 16;
}

static int32_t rg_ui_text_width(const char *s) {
    return s == NULL ? 0 : (int32_t) strlen(s) * 8;
}

static void rg_ui_text(RGWin *win, int32_t x, int32_t y, const char *s, int64_t color) {
    (void) win;
    (void) x;
    (void) y;
    (void) s;
    (void) color;
}

static void rg_ui_create_hwnd(RGWin *win) {
    (void) win;
}

static void rg_ui_destroy_hwnd(RGWin *win) {
    (void) win;
}

static int32_t rg_ui_any_hwnd(void) {
    return 0;
}

static void rg_ui_pump(void) {
}

static void rg_ui_present(RGWin *win) {
    (void) win;
}

static char *rg_ui_clipboard_os(void) {
    return NULL;
}

static void rg_ui_clipboard_set_os(const char *s) {
    (void) s;
}

#endif

#include "host_img.c"

static void rg_ui_kill_all(void) {
    RGWin *win;
    for (win = g_wins; win != NULL; win = win->next) {
        win->alive = 0;
        rg_ui_destroy_hwnd(win);
    }
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
#if defined(_WIN32)
        rg_set_string(dest, "win32");
#else
        rg_set_string(dest, rg_ui_forced_headless() ? "headless" : "linux");
#endif
        return;
    }
    if (strcmp(name, "platform") == 0) {
#if defined(_WIN32)
        rg_set_string(dest, "windows");
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
        rg_set_int(dest, rg_ui_font_height());
        return;
    }
    if (strcmp(name, "clipboard_get") == 0) {
        char *os = rg_ui_clipboard_os();
        if (os != NULL) {
            rg_set_string(dest, os);
            free(os);
        } else {
            rg_set_string(dest, g_clip != NULL ? g_clip : "");
        }
        return;
    }
    if (strcmp(name, "clipboard_set") == 0) {
        const char *s = rg_ui_str(args, 0, argc);
        g_clip = rg_strdup(s);
        rg_ui_clipboard_set_os(s);
        rg_set_void(dest);
        return;
    }
    if (strcmp(name, "text_width") == 0) {
        rg_set_int(dest, rg_ui_text_width(rg_ui_str(args, 0, argc)));
        return;
    }
    if (strcmp(name, "image_width") == 0) {
        RGImg *img = rg_ui_img(rg_ui_str(args, 0, argc));
        rg_set_int(dest, img == NULL ? 0 : img->w);
        return;
    }
    if (strcmp(name, "image_height") == 0) {
        RGImg *img = rg_ui_img(rg_ui_str(args, 0, argc));
        rg_set_int(dest, img == NULL ? 0 : img->h);
        return;
    }
    if (strcmp(name, "run") == 0) {
        rg_set_void(dest);
        return;
    }
    if (strcmp(name, "wait") == 0) {
        rg_ui_pump();
        if (rg_ui_forced_headless()) {
            rg_ui_kill_all();
        } else if (rg_ui_any_hwnd()) {
#if defined(_WIN32)
            MsgWaitForMultipleObjects(0, NULL, FALSE, 16, QS_ALLINPUT);
#endif
            rg_ui_pump();
        } else {
#if defined(_WIN32)
            Sleep(16);
#else
            rg_sleep_ms(16);
#endif
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
        rg_ui_ensure_fb(win);
        if (win->mapped) {
            rg_ui_create_hwnd(win);
        }
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
            rg_ui_destroy_hwnd(win);
            rg_ui_pump();
        }
        rg_set_void(dest);
        return;
    }
    if (strcmp(name, "alive") == 0) {
        rg_set_bool(dest, win != NULL);
        return;
    }
    if (strcmp(name, "poll") == 0) {
        rg_ui_pump();
        win = argc >= 1 && args != NULL && args[0].kind == RG_INT ? rg_ui_find(args[0].i) : NULL;
        rg_set_bool(dest, win != NULL);
        return;
    }
    if (strcmp(name, "show") == 0) {
        if (win != NULL) {
            win->mapped = 1;
            if (win->hwnd == NULL) {
                rg_ui_create_hwnd(win);
            }
#if defined(_WIN32)
            else {
                ShowWindow(win->hwnd, SW_SHOW);
            }
#endif
        }
        rg_set_void(dest);
        return;
    }
    if (strcmp(name, "hide") == 0) {
        if (win != NULL) {
            win->mapped = 0;
#if defined(_WIN32)
            if (win->hwnd != NULL) {
                ShowWindow(win->hwnd, SW_HIDE);
            }
#endif
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
#if defined(_WIN32)
            if (win->hwnd != NULL) {
                wchar_t *w = rg_ui_widen(win->title);
                SetWindowTextW(win->hwnd, w);
                free(w);
            }
#endif
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
            rg_ui_ensure_fb(win);
#if defined(_WIN32)
            if (win->hwnd != NULL) {
                RECT rc;
                DWORD style = (DWORD) GetWindowLongPtrW(win->hwnd, GWL_STYLE);
                rc.left = 0;
                rc.top = 0;
                rc.right = win->w;
                rc.bottom = win->h;
                AdjustWindowRect(&rc, style, FALSE);
                SetWindowPos(win->hwnd, NULL, 0, 0, rc.right - rc.left, rc.bottom - rc.top,
                        SWP_NOMOVE | SWP_NOZORDER);
            }
#endif
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
    if (strcmp(name, "clear") == 0) {
        if (win != NULL) {
            rg_ui_clear(win, rg_ui_int(args, 1, argc));
        }
        rg_set_void(dest);
        return;
    }
    if (strcmp(name, "fill") == 0) {
        if (win != NULL) {
            rg_ui_fill(win, (int32_t) rg_ui_int(args, 1, argc), (int32_t) rg_ui_int(args, 2, argc),
                    (int32_t) rg_ui_int(args, 3, argc), (int32_t) rg_ui_int(args, 4, argc), rg_ui_int(args, 5, argc));
        }
        rg_set_void(dest);
        return;
    }
    if (strcmp(name, "line") == 0) {
        if (win != NULL) {
            rg_ui_line(win, (int32_t) rg_ui_int(args, 1, argc), (int32_t) rg_ui_int(args, 2, argc),
                    (int32_t) rg_ui_int(args, 3, argc), (int32_t) rg_ui_int(args, 4, argc), rg_ui_int(args, 5, argc));
        }
        rg_set_void(dest);
        return;
    }
    if (strcmp(name, "stroke_rect") == 0) {
        if (win != NULL) {
            rg_ui_stroke_rect(win, (int32_t) rg_ui_int(args, 1, argc), (int32_t) rg_ui_int(args, 2, argc),
                    (int32_t) rg_ui_int(args, 3, argc), (int32_t) rg_ui_int(args, 4, argc), rg_ui_int(args, 5, argc));
        }
        rg_set_void(dest);
        return;
    }
    if (strcmp(name, "fill_round") == 0) {
        if (win != NULL) {
            rg_ui_round(win, (int32_t) rg_ui_int(args, 1, argc), (int32_t) rg_ui_int(args, 2, argc),
                    (int32_t) rg_ui_int(args, 3, argc), (int32_t) rg_ui_int(args, 4, argc),
                    (int32_t) rg_ui_int(args, 5, argc), rg_ui_int(args, 6, argc), 1);
        }
        rg_set_void(dest);
        return;
    }
    if (strcmp(name, "stroke_round") == 0) {
        if (win != NULL) {
            rg_ui_round(win, (int32_t) rg_ui_int(args, 1, argc), (int32_t) rg_ui_int(args, 2, argc),
                    (int32_t) rg_ui_int(args, 3, argc), (int32_t) rg_ui_int(args, 4, argc),
                    (int32_t) rg_ui_int(args, 5, argc), rg_ui_int(args, 6, argc), 0);
        }
        rg_set_void(dest);
        return;
    }
    if (strcmp(name, "image_rgb") == 0) {
        if (win != NULL && argc >= 6) {
            rg_ui_blit_rgb(win, (int32_t) rg_ui_int(args, 1, argc), (int32_t) rg_ui_int(args, 2, argc),
                    (int32_t) rg_ui_int(args, 3, argc), (int32_t) rg_ui_int(args, 4, argc), rg_as_array(&args[5]));
        }
        rg_set_void(dest);
        return;
    }
    if (strcmp(name, "image") == 0) {
        if (win != NULL) {
            rg_ui_blit_img(win, (int32_t) rg_ui_int(args, 1, argc), (int32_t) rg_ui_int(args, 2, argc),
                    rg_ui_img(rg_ui_str(args, 3, argc)));
        }
        rg_set_void(dest);
        return;
    }
    if (strcmp(name, "clip_push") == 0) {
        if (win != NULL && win->nclips < RG_UI_CLIP_MAX) {
            RGClip c;
            c.x0 = (int32_t) rg_ui_int(args, 1, argc);
            c.y0 = (int32_t) rg_ui_int(args, 2, argc);
            c.x1 = c.x0 + (int32_t) rg_ui_int(args, 3, argc);
            c.y1 = c.y0 + (int32_t) rg_ui_int(args, 4, argc);
            if (win->nclips > 0) {
                RGClip p = win->clips[win->nclips - 1];
                if (c.x0 < p.x0) {
                    c.x0 = p.x0;
                }
                if (c.y0 < p.y0) {
                    c.y0 = p.y0;
                }
                if (c.x1 > p.x1) {
                    c.x1 = p.x1;
                }
                if (c.y1 > p.y1) {
                    c.y1 = p.y1;
                }
            }
            win->clips[win->nclips++] = c;
        }
        rg_set_void(dest);
        return;
    }
    if (strcmp(name, "clip_pop") == 0) {
        if (win != NULL && win->nclips > 0) {
            win->nclips--;
        }
        rg_set_void(dest);
        return;
    }
    if (strcmp(name, "text") == 0) {
        if (win != NULL) {
            rg_ui_text(win, (int32_t) rg_ui_int(args, 1, argc), (int32_t) rg_ui_int(args, 2, argc),
                    rg_ui_str(args, 3, argc), rg_ui_int(args, 4, argc));
        }
        rg_set_void(dest);
        return;
    }
    if (strcmp(name, "present") == 0) {
        if (win != NULL) {
#if defined(_WIN32)
            if (win->hwnd != NULL) {
                InvalidateRect(win->hwnd, NULL, FALSE);
                UpdateWindow(win->hwnd);
            }
#else
            rg_ui_present(win);
#endif
        }
        rg_set_void(dest);
        return;
    }
    if (strcmp(name, "cursor") == 0) {
        if (win != NULL) {
            int32_t kind = (int32_t) rg_ui_int(args, 1, argc);
            win->cursor = kind < 0 || kind > 2 ? 0 : kind;
        }
        rg_set_void(dest);
        return;
    }
    fprintf(stderr, "unknown function __ui.%s\n", name);
    exit(1);
}
