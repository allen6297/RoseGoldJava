package com.rosegoldc.lang;

import javax.imageio.ImageIO;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.Timer;
import javax.swing.WindowConstants;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.EventQueue;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import java.awt.event.MouseWheelEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class HostUi {

    private static final int MAX_SIZE = 16384;

    static boolean forceBitmapFont;

    private static Font sysFont;
    private static FontMetrics sysMetrics;
    private static int sysFontH = HostFont.CELL;

    private final Map<Long, Win> wins = new LinkedHashMap<>();
    private final Map<Long, Value> frameFns = new LinkedHashMap<>();
    private final Map<String, RgbImage> images = new LinkedHashMap<>();
    private long nextId = 1;
    private String clipboard = "";
    private Interp interp;
    private boolean inFrame;

    Value call(Interp interp, String name, List<Value> args, int line, int col) {
        this.interp = interp;
        return switch (name) {
            case "backend" -> {
                requireArity(interp, name, args, 0, line, col);
                yield Value.makeString(backendName());
            }
            case "platform" -> {
                requireArity(interp, name, args, 0, line, col);
                yield Value.makeString(platform());
            }
            case "open" -> open(interp, args, line, col);
            case "close" -> {
                requireArity(interp, name, args, 1, line, col);
                Win win = findAlive(needInt(interp, name, args, 0, line, col));
                if (win != null) {
                    frameFns.remove(win.id);
                    win.alive = false;
                    if (hasNative(win)) {
                        nativeClose(win);
                    }
                    nativePoll();
                }
                yield Value.makeVoid();
            }
            case "alive" -> {
                requireArity(interp, name, args, 1, line, col);
                yield Value.makeBool(findAlive(needInt(interp, name, args, 0, line, col)) != null);
            }
            case "show" -> {
                requireArity(interp, name, args, 1, line, col);
                Win win = findAlive(needInt(interp, name, args, 0, line, col));
                if (win != null) {
                    win.mapped = true;
                    nativeShow(win);
                }
                yield Value.makeVoid();
            }
            case "hide" -> {
                requireArity(interp, name, args, 1, line, col);
                Win win = findAlive(needInt(interp, name, args, 0, line, col));
                if (win != null) {
                    win.mapped = false;
                    nativeHide(win);
                }
                yield Value.makeVoid();
            }
            case "title" -> {
                requireArity(interp, name, args, 1, line, col);
                Win win = findAlive(needInt(interp, name, args, 0, line, col));
                yield Value.makeString(win == null ? "" : win.title);
            }
            case "set_title" -> {
                requireArity(interp, name, args, 2, line, col);
                Win win = findAlive(needInt(interp, name, args, 0, line, col));
                String title = needStr(interp, name, args, 1, line, col);
                if (win != null) {
                    win.title = title.isEmpty() ? "RoseGold" : title;
                    nativeSetTitle(win);
                }
                yield Value.makeVoid();
            }
            case "width" -> {
                requireArity(interp, name, args, 1, line, col);
                Win win = findAlive(needInt(interp, name, args, 0, line, col));
                yield Value.makeInt(win == null ? 0 : win.width);
            }
            case "height" -> {
                requireArity(interp, name, args, 1, line, col);
                Win win = findAlive(needInt(interp, name, args, 0, line, col));
                yield Value.makeInt(win == null ? 0 : win.height);
            }
            case "set_size" -> {
                requireArity(interp, name, args, 3, line, col);
                Win win = findAlive(needInt(interp, name, args, 0, line, col));
                int w = (int) needInt(interp, name, args, 1, line, col);
                int h = (int) needInt(interp, name, args, 2, line, col);
                if (w < 1 || h < 1 || w > MAX_SIZE || h > MAX_SIZE) {
                    throw interp.runtime("window size must be between 1 and 16384", line, col);
                }
                if (win != null) {
                    win.width = w;
                    win.height = h;
                    nativeSetSize(win);
                    ensureFb(win);
                }
                yield Value.makeVoid();
            }
            case "clear" -> {
                requireArity(interp, name, args, 2, line, col);
                Win win = findAlive(needInt(interp, name, args, 0, line, col));
                if (win != null) {
                    fbClear(win, needInt(interp, name, args, 1, line, col));
                }
                yield Value.makeVoid();
            }
            case "fill" -> {
                requireArity(interp, name, args, 6, line, col);
                Win win = findAlive(needInt(interp, name, args, 0, line, col));
                if (win != null) {
                    fbFill(win, (int) needInt(interp, name, args, 1, line, col),
                            (int) needInt(interp, name, args, 2, line, col),
                            (int) needInt(interp, name, args, 3, line, col),
                            (int) needInt(interp, name, args, 4, line, col),
                            needInt(interp, name, args, 5, line, col));
                }
                yield Value.makeVoid();
            }
            case "line" -> {
                requireArity(interp, name, args, 6, line, col);
                Win win = findAlive(needInt(interp, name, args, 0, line, col));
                if (win != null) {
                    fbLine(win, (int) needInt(interp, name, args, 1, line, col),
                            (int) needInt(interp, name, args, 2, line, col),
                            (int) needInt(interp, name, args, 3, line, col),
                            (int) needInt(interp, name, args, 4, line, col),
                            needInt(interp, name, args, 5, line, col));
                }
                yield Value.makeVoid();
            }
            case "stroke_rect" -> {
                requireArity(interp, name, args, 6, line, col);
                Win win = findAlive(needInt(interp, name, args, 0, line, col));
                if (win != null) {
                    fbStrokeRect(win, (int) needInt(interp, name, args, 1, line, col),
                            (int) needInt(interp, name, args, 2, line, col),
                            (int) needInt(interp, name, args, 3, line, col),
                            (int) needInt(interp, name, args, 4, line, col),
                            needInt(interp, name, args, 5, line, col));
                }
                yield Value.makeVoid();
            }
            case "fill_round" -> {
                requireArity(interp, name, args, 7, line, col);
                Win win = findAlive(needInt(interp, name, args, 0, line, col));
                if (win != null) {
                    fbFillRound(win, (int) needInt(interp, name, args, 1, line, col),
                            (int) needInt(interp, name, args, 2, line, col),
                            (int) needInt(interp, name, args, 3, line, col),
                            (int) needInt(interp, name, args, 4, line, col),
                            (int) needInt(interp, name, args, 5, line, col),
                            needInt(interp, name, args, 6, line, col));
                }
                yield Value.makeVoid();
            }
            case "stroke_round" -> {
                requireArity(interp, name, args, 7, line, col);
                Win win = findAlive(needInt(interp, name, args, 0, line, col));
                if (win != null) {
                    fbStrokeRound(win, (int) needInt(interp, name, args, 1, line, col),
                            (int) needInt(interp, name, args, 2, line, col),
                            (int) needInt(interp, name, args, 3, line, col),
                            (int) needInt(interp, name, args, 4, line, col),
                            (int) needInt(interp, name, args, 5, line, col),
                            needInt(interp, name, args, 6, line, col));
                }
                yield Value.makeVoid();
            }
            case "image_rgb" -> imageRgb(interp, args, line, col);
            case "image" -> {
                requireArity(interp, name, args, 4, line, col);
                Win win = findAlive(needInt(interp, name, args, 0, line, col));
                int x = (int) needInt(interp, name, args, 1, line, col);
                int y = (int) needInt(interp, name, args, 2, line, col);
                RgbImage img = cachedImage(interp.sandboxPath(needStr(interp, name, args, 3, line, col), line, col));
                if (win != null && img != null) {
                    fbBlitRgb(win, x, y, img.w, img.h, img.px);
                }
                yield Value.makeVoid();
            }
            case "image_width" -> {
                requireArity(interp, name, args, 1, line, col);
                RgbImage img = cachedImage(interp.sandboxPath(needStr(interp, name, args, 0, line, col), line, col));
                yield Value.makeInt(img == null ? 0 : img.w);
            }
            case "image_height" -> {
                requireArity(interp, name, args, 1, line, col);
                RgbImage img = cachedImage(interp.sandboxPath(needStr(interp, name, args, 0, line, col), line, col));
                yield Value.makeInt(img == null ? 0 : img.h);
            }
            case "clip_push" -> {
                requireArity(interp, name, args, 5, line, col);
                Win win = findAlive(needInt(interp, name, args, 0, line, col));
                if (win != null) {
                    Clip c = new Clip();
                    c.x0 = (int) needInt(interp, name, args, 1, line, col);
                    c.y0 = (int) needInt(interp, name, args, 2, line, col);
                    c.x1 = c.x0 + (int) needInt(interp, name, args, 3, line, col);
                    c.y1 = c.y0 + (int) needInt(interp, name, args, 4, line, col);
                    if (!win.clips.isEmpty()) {
                        Clip p = win.clips.getLast();
                        c.x0 = Math.max(c.x0, p.x0);
                        c.y0 = Math.max(c.y0, p.y0);
                        c.x1 = Math.min(c.x1, p.x1);
                        c.y1 = Math.min(c.y1, p.y1);
                    }
                    win.clips.add(c);
                }
                yield Value.makeVoid();
            }
            case "clip_pop" -> {
                requireArity(interp, name, args, 1, line, col);
                Win win = findAlive(needInt(interp, name, args, 0, line, col));
                if (win != null && !win.clips.isEmpty()) {
                    win.clips.removeLast();
                }
                yield Value.makeVoid();
            }
            case "text" -> {
                requireArity(interp, name, args, 5, line, col);
                Win win = findAlive(needInt(interp, name, args, 0, line, col));
                if (win != null) {
                    fbText(win, (int) needInt(interp, name, args, 1, line, col),
                            (int) needInt(interp, name, args, 2, line, col),
                            needStr(interp, name, args, 3, line, col),
                            needInt(interp, name, args, 4, line, col));
                }
                yield Value.makeVoid();
            }
            case "text_width" -> {
                requireArity(interp, name, args, 1, line, col);
                yield Value.makeInt(textWidth(needStr(interp, name, args, 0, line, col)));
            }
            case "font_height" -> {
                requireArity(interp, name, args, 0, line, col);
                yield Value.makeInt(fontHeight());
            }
            case "present" -> {
                requireArity(interp, name, args, 1, line, col);
                Win win = findAlive(needInt(interp, name, args, 0, line, col));
                if (win != null) {
                    nativePresent(win);
                }
                yield Value.makeVoid();
            }
            case "wait" -> {
                requireArity(interp, name, args, 0, line, col);
                if (anyNative()) {
                    nativeWait();
                }
                yield Value.makeVoid();
            }
            case "run" -> {
                requireArity(interp, name, args, 0, line, col);
                if (anyNative()) {
                    nativeRun();
                }
                nativePoll();
                yield Value.makeVoid();
            }
            case "cursor" -> {
                requireArity(interp, name, args, 2, line, col);
                Win win = findAlive(needInt(interp, name, args, 0, line, col));
                if (win != null) {
                    int kind = (int) needInt(interp, name, args, 1, line, col);
                    win.cursor = kind < 0 || kind > 2 ? 0 : kind;
                    nativeApplyCursor(win);
                }
                yield Value.makeVoid();
            }
            case "mouse_x" -> {
                requireArity(interp, name, args, 1, line, col);
                Win win = findAlive(needInt(interp, name, args, 0, line, col));
                yield Value.makeInt(win == null ? 0 : win.mouseX);
            }
            case "mouse_y" -> {
                requireArity(interp, name, args, 1, line, col);
                Win win = findAlive(needInt(interp, name, args, 0, line, col));
                yield Value.makeInt(win == null ? 0 : win.mouseY);
            }
            case "mouse_down" -> {
                requireArity(interp, name, args, 1, line, col);
                Win win = findAlive(needInt(interp, name, args, 0, line, col));
                yield Value.makeBool(win != null && win.mouseDown);
            }
            case "take_click" -> {
                requireArity(interp, name, args, 1, line, col);
                Win win = findAlive(needInt(interp, name, args, 0, line, col));
                if (win == null) {
                    yield Value.makeBool(false);
                }
                boolean click = win.mouseClick;
                win.mouseClick = false;
                yield Value.makeBool(click);
            }
            case "take_right_click" -> {
                requireArity(interp, name, args, 1, line, col);
                Win win = findAlive(needInt(interp, name, args, 0, line, col));
                if (win == null) {
                    yield Value.makeBool(false);
                }
                boolean click = win.mouseRightClick;
                win.mouseRightClick = false;
                yield Value.makeBool(click);
            }
            case "feed_click" -> {
                requireArity(interp, name, args, 3, line, col);
                Win win = findAlive(needInt(interp, name, args, 0, line, col));
                if (win != null) {
                    win.mouseX = (int) needInt(interp, name, args, 1, line, col);
                    win.mouseY = (int) needInt(interp, name, args, 2, line, col);
                    win.mouseDown = false;
                    win.mouseClick = true;
                }
                yield Value.makeVoid();
            }
            case "feed_right_click" -> {
                requireArity(interp, name, args, 3, line, col);
                Win win = findAlive(needInt(interp, name, args, 0, line, col));
                if (win != null) {
                    win.mouseX = (int) needInt(interp, name, args, 1, line, col);
                    win.mouseY = (int) needInt(interp, name, args, 2, line, col);
                    win.mouseDown = false;
                    win.mouseRightClick = true;
                }
                yield Value.makeVoid();
            }
            case "feed_mouse" -> {
                requireArity(interp, name, args, 3, line, col);
                Win win = findAlive(needInt(interp, name, args, 0, line, col));
                if (win != null) {
                    win.mouseX = (int) needInt(interp, name, args, 1, line, col);
                    win.mouseY = (int) needInt(interp, name, args, 2, line, col);
                }
                yield Value.makeVoid();
            }
            case "feed_down" -> {
                requireArity(interp, name, args, 2, line, col);
                Win win = findAlive(needInt(interp, name, args, 0, line, col));
                if (win != null) {
                    win.mouseDown = needBool(interp, name, args, 1, line, col);
                    if (!win.mouseDown) {
                        win.mouseClick = false;
                    }
                }
                yield Value.makeVoid();
            }
            case "take_key" -> {
                requireArity(interp, name, args, 1, line, col);
                Win win = findAlive(needInt(interp, name, args, 0, line, col));
                if (win == null) {
                    yield Value.makeBool(false);
                }
                boolean pending = win.keyPending;
                win.keyPending = false;
                yield Value.makeBool(pending);
            }
            case "key_code" -> {
                requireArity(interp, name, args, 1, line, col);
                Win win = findAlive(needInt(interp, name, args, 0, line, col));
                yield Value.makeInt(win == null ? 0 : win.keyCode);
            }
            case "key_text" -> {
                requireArity(interp, name, args, 1, line, col);
                Win win = findAlive(needInt(interp, name, args, 0, line, col));
                yield Value.makeString(win == null ? "" : win.keyText);
            }
            case "feed_key" -> {
                requireArity(interp, name, args, 3, line, col);
                Win win = findAlive(needInt(interp, name, args, 0, line, col));
                if (win != null) {
                    win.keyCode = (int) needInt(interp, name, args, 1, line, col);
                    win.keyText = needStr(interp, name, args, 2, line, col);
                    win.keyPending = true;
                }
                yield Value.makeVoid();
            }
            case "take_scroll" -> {
                requireArity(interp, name, args, 1, line, col);
                Win win = findAlive(needInt(interp, name, args, 0, line, col));
                if (win == null) {
                    yield Value.makeBool(false);
                }
                boolean pending = win.scrollPending;
                win.scrollPending = false;
                yield Value.makeBool(pending);
            }
            case "scroll_dx" -> {
                requireArity(interp, name, args, 1, line, col);
                Win win = findAlive(needInt(interp, name, args, 0, line, col));
                yield Value.makeInt(win == null ? 0 : win.scrollDx);
            }
            case "scroll_dy" -> {
                requireArity(interp, name, args, 1, line, col);
                Win win = findAlive(needInt(interp, name, args, 0, line, col));
                yield Value.makeInt(win == null ? 0 : win.scrollDy);
            }
            case "feed_scroll" -> {
                requireArity(interp, name, args, 3, line, col);
                Win win = findAlive(needInt(interp, name, args, 0, line, col));
                if (win != null) {
                    int dx = (int) needInt(interp, name, args, 1, line, col);
                    int dy = (int) needInt(interp, name, args, 2, line, col);
                    if (!win.scrollPending) {
                        win.scrollDx = 0;
                        win.scrollDy = 0;
                    }
                    win.scrollDx += dx;
                    win.scrollDy += dy;
                    win.scrollPending = true;
                }
                yield Value.makeVoid();
            }
            case "set_frame" -> {
                requireArity(interp, name, args, 2, line, col);
                long id = needInt(interp, name, args, 0, line, col);
                if (args.get(1).kind != Value.Kind.FnRef) {
                    throw interp.runtime("__ui.set_frame expects a function", line, col);
                }
                if (findAlive(id) != null) {
                    frameFns.put(id, args.get(1));
                }
                yield Value.makeVoid();
            }
            case "clipboard_get" -> {
                requireArity(interp, name, args, 0, line, col);
                nativeClipboardGet();
                yield Value.makeString(clipboard);
            }
            case "clipboard_set" -> {
                requireArity(interp, name, args, 1, line, col);
                clipboard = needStr(interp, name, args, 0, line, col);
                nativeClipboardSet();
                yield Value.makeVoid();
            }
            case "poll" -> {
                requireArity(interp, name, args, 1, line, col);
                nativePoll();
                yield Value.makeBool(findAlive(needInt(interp, name, args, 0, line, col)) != null);
            }
            case "next_frame" -> {
                requireArity(interp, name, args, 1, line, col);
                long id = needInt(interp, name, args, 0, line, col);
                Value.FutureData fut = new Value.FutureData();
                interp.frameJobs.add(new Interp.FrameJob(id, fut));
                yield Value.makeFuture(fut);
            }
            case "count" -> {
                requireArity(interp, name, args, 0, line, col);
                long n = 0;
                for (Win win : wins.values()) {
                    if (win.alive) {
                        n++;
                    }
                }
                yield Value.makeInt(n);
            }
            default -> throw interp.runtime("unknown function __ui." + name, line, col);
        };
    }

    private Value open(Interp interp, List<Value> args, int line, int col) {
        if (args.size() != 4) {
            throw interp.runtime("__ui.open takes 4 arguments", line, col);
        }
        String title = needStr(interp, "open", args, 0, line, col);
        long w = needInt(interp, "open", args, 1, line, col);
        long h = needInt(interp, "open", args, 2, line, col);
        boolean visible = needBool(interp, "open", args, 3, line, col);
        if (w < 1 || h < 1 || w > MAX_SIZE || h > MAX_SIZE) {
            throw new Interp.ThrowEscape(Value.makeString("window size must be between 1 and 16384"), line, col);
        }
        if (title.isEmpty()) {
            title = "RoseGold";
        }
        Win win = new Win();
        win.alive = true;
        win.mapped = visible;
        win.width = (int) w;
        win.height = (int) h;
        win.title = title;
        win.id = nextId++;
        wins.put(win.id, win);
        if (!nativeOpen(win, title, win.width, win.height, visible)) {
            if (visible) {
                wins.remove(win.id);
                throw new Interp.ThrowEscape(Value.makeString("cannot create window"), line, col);
            }
        } else if (visible) {
            nativeShow(win);
        } else {
            nativeHide(win);
        }
        ensureFb(win);
        nativePoll();
        return Value.makeInt(win.id);
    }

    private Value imageRgb(Interp interp, List<Value> args, int line, int col) {
        if (args.size() != 6) {
            throw interp.runtime("__ui.image_rgb takes 6 arguments", line, col);
        }
        Win win = findAlive(needInt(interp, "image_rgb", args, 0, line, col));
        int x = (int) needInt(interp, "image_rgb", args, 1, line, col);
        int y = (int) needInt(interp, "image_rgb", args, 2, line, col);
        int iw = (int) needInt(interp, "image_rgb", args, 3, line, col);
        int ih = (int) needInt(interp, "image_rgb", args, 4, line, col);
        if (args.get(5).kind != Value.Kind.Array || args.get(5).items == null) {
            throw interp.runtime("__ui.image_rgb expects Array[Int] pixels", line, col);
        }
        if (iw < 1 || ih < 1 || iw > 8192 || ih > 8192) {
            throw interp.runtime("__ui.image_rgb size out of range", line, col);
        }
        List<Value> items = args.get(5).items;
        int need = iw * ih;
        if (items.size() < need) {
            throw interp.runtime("__ui.image_rgb pixel array too short", line, col);
        }
        if (win != null) {
            int[] px = new int[need];
            for (int i = 0; i < need; i++) {
                if (items.get(i).kind != Value.Kind.Int) {
                    throw interp.runtime("__ui.image_rgb pixels must be Int", line, col);
                }
                px[i] = packRgb(items.get(i).i);
            }
            fbBlitRgb(win, x, y, iw, ih, px);
        }
        return Value.makeVoid();
    }

    private Win findAlive(long id) {
        Win win = wins.get(id);
        if (win == null || !win.alive) {
            return null;
        }
        return win;
    }

    boolean isAlive(long id) {
        return findAlive(id) != null;
    }

    private static String backendName() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            return "win32";
        }
        if (os.contains("mac")) {
            return "cocoa";
        }
        String wayland = System.getenv("WAYLAND_DISPLAY");
        if (wayland != null && !wayland.isEmpty()) {
            return "wayland";
        }
        String display = System.getenv("DISPLAY");
        if (display != null && !display.isEmpty()) {
            return "x11";
        }
        return "none";
    }

    private static boolean nativeAvailable() {
        try {
            return !GraphicsEnvironment.isHeadless();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private boolean anyNative() {
        for (Win win : wins.values()) {
            if (win.alive && hasNative(win)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasNative(Win win) {
        return win != null && win.frame != null;
    }

    private static void onEdt(Runnable action) {
        if (EventQueue.isDispatchThread()) {
            action.run();
            return;
        }
        try {
            EventQueue.invokeAndWait(action);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } catch (InvocationTargetException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            if (cause instanceof Error err) {
                throw err;
            }
            throw new RuntimeException(cause);
        }
    }

    private boolean nativeOpen(Win win, String title, int w, int h, boolean visible) {
        if (!nativeAvailable()) {
            return false;
        }
        boolean[] ok = {false};
        try {
            onEdt(() -> {
                JFrame frame = new JFrame(title);
                frame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
                if (!visible) {
                    frame.setType(Window.Type.UTILITY);
                    frame.setFocusableWindowState(false);
                    frame.setAutoRequestFocus(false);
                }
                FbView view = new FbView(win);
                view.setPreferredSize(new Dimension(w, h));
                frame.setContentPane(view);
                frame.pack();
                frame.addWindowListener(new WindowAdapter() {
                    @Override
                    public void windowClosing(WindowEvent e) {
                        win.alive = false;
                        nativeDestroy(win);
                    }
                });
                Timer timer = new Timer(500, e -> win.needsFrame = true);
                timer.setRepeats(true);
                timer.start();
                win.frame = frame;
                win.view = view;
                win.timer = timer;
                ok[0] = true;
            });
        } catch (RuntimeException ignored) {
            nativeDestroy(win);
            return false;
        }
        return ok[0] && hasNative(win);
    }

    private static void nativeDestroy(Win win) {
        if (win == null) {
            return;
        }
        Timer timer = win.timer;
        JFrame frame = win.frame;
        win.timer = null;
        win.frame = null;
        win.view = null;
        Runnable drop = () -> {
            if (timer != null) {
                timer.stop();
            }
            if (frame != null) {
                frame.dispose();
            }
        };
        try {
            onEdt(drop);
        } catch (RuntimeException ignored) {
        }
    }

    private void nativeClose(Win win) {
        nativeDestroy(win);
    }

    private static void nativeShow(Win win) {
        if (!hasNative(win)) {
            return;
        }
        onEdt(() -> {
            win.frame.setVisible(true);
            win.view.requestFocusInWindow();
            win.mapped = true;
        });
    }

    private static void nativeHide(Win win) {
        if (!hasNative(win)) {
            return;
        }
        onEdt(() -> {
            win.frame.setVisible(false);
            win.mapped = false;
        });
    }

    private static void nativeSetTitle(Win win) {
        if (!hasNative(win)) {
            return;
        }
        onEdt(() -> win.frame.setTitle(win.title));
    }

    private static void nativeSetSize(Win win) {
        if (!hasNative(win)) {
            return;
        }
        onEdt(() -> {
            win.view.setPreferredSize(new Dimension(win.width, win.height));
            win.frame.pack();
        });
    }

    private static void nativePresent(Win win) {
        if (!hasNative(win) || win.fb == null || win.fb.length == 0) {
            return;
        }
        onEdt(() -> win.view.present());
    }

    private static void nativeApplyCursor(Win win) {
        if (!hasNative(win)) {
            return;
        }
        onEdt(() -> win.view.setCursor(cursorFor(win.cursor)));
    }

    private static Cursor cursorFor(int kind) {
        if (kind == 1) {
            return Cursor.getPredefinedCursor(Cursor.HAND_CURSOR);
        }
        if (kind == 2) {
            return Cursor.getPredefinedCursor(Cursor.TEXT_CURSOR);
        }
        return Cursor.getPredefinedCursor(Cursor.DEFAULT_CURSOR);
    }

    private void nativePoll() {
        if (!nativeAvailable()) {
            return;
        }
        try {
            onEdt(() -> {
            });
        } catch (RuntimeException ignored) {
        }
        runDueFrames();
    }

    private void nativeWait() {
        nativePoll();
        if (!anyNative()) {
            return;
        }
        try {
            Thread.sleep(16);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
        nativePoll();
    }

    private void nativeRun() {
        while (anyAliveWindows() && anyNative()) {
            nativePoll();
            if (!anyAliveWindows() || !anyNative()) {
                break;
            }
            try {
                Thread.sleep(16);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private boolean anyAliveWindows() {
        for (Win win : wins.values()) {
            if (win.alive) {
                return true;
            }
        }
        return false;
    }

    private void nativeClipboardGet() {
        if (!nativeAvailable()) {
            return;
        }
        try {
            var contents = Toolkit.getDefaultToolkit().getSystemClipboard().getContents(null);
            if (contents != null && contents.isDataFlavorSupported(DataFlavor.stringFlavor)) {
                Object data = contents.getTransferData(DataFlavor.stringFlavor);
                if (data instanceof String text) {
                    clipboard = text;
                }
            }
        } catch (Exception ignored) {
        }
    }

    private void nativeClipboardSet() {
        if (!nativeAvailable()) {
            return;
        }
        try {
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(clipboard), null);
        } catch (Exception ignored) {
        }
    }

    private void runDueFrames() {
        if (interp == null || inFrame) {
            return;
        }
        List<Long> due = new ArrayList<>();
        for (Win win : wins.values()) {
            if (win.alive && win.needsFrame) {
                win.needsFrame = false;
                due.add(win.id);
            }
        }
        for (long id : due) {
            runFrame(id);
        }
    }

    private void runFrame(long id) {
        if (interp == null || inFrame || id <= 0) {
            return;
        }
        Value fn = frameFns.get(id);
        if (fn == null || fn.kind != Value.Kind.FnRef) {
            return;
        }
        inFrame = true;
        try {
            interp.callFnValue(fn, List.of(), 1, 1);
        } catch (RuntimeException ignored) {
        } finally {
            inFrame = false;
        }
    }

    private static void applyClientSize(Win win, int w, int h) {
        if (w < 1 || h < 1) {
            return;
        }
        if (w > MAX_SIZE) {
            w = MAX_SIZE;
        }
        if (h > MAX_SIZE) {
            h = MAX_SIZE;
        }
        win.width = w;
        win.height = h;
        ensureFb(win);
        win.needsFrame = true;
    }

    private static void feedKey(Win win, int code, String text) {
        win.keyCode = code;
        win.keyText = text == null ? "" : text;
        win.keyPending = true;
        win.needsFrame = true;
    }

    private static int wheelDeltaToPixels(Win win, int delta) {
        final int unit = 120;
        final int pixelsPerNotch = 48;
        win.wheelAcc += delta;
        int px = win.wheelAcc * pixelsPerNotch / unit;
        if (px != 0) {
            win.wheelAcc -= px * unit / pixelsPerNotch;
        }
        return px;
    }

    private static String platform() {
        String os = System.getProperty("os.name", "linux").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            return "windows";
        }
        if (os.contains("mac")) {
            return "macos";
        }
        return "linux";
    }

    private static void ensureSysFont() {
        if (sysFont != null && sysMetrics != null) {
            return;
        }
        int dpi = 96;
        try {
            dpi = Toolkit.getDefaultToolkit().getScreenResolution();
        } catch (Throwable ignored) {
        }
        if (dpi < 72) {
            dpi = 96;
        }
        int px = Math.max(8, Math.round(13f * dpi / 96f));
        String[] names = switch (backendName()) {
            case "win32" -> new String[] {"Segoe UI", Font.DIALOG, Font.SANS_SERIF};
            case "cocoa" -> new String[] {".AppleSystemUIFont", "Helvetica Neue", "Lucida Grande", Font.DIALOG};
            default -> new String[] {Font.DIALOG, "DejaVu Sans", Font.SANS_SERIF};
        };
        String family = Font.SANS_SERIF;
        try {
            String[] available = GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames();
            for (String want : names) {
                if (want.equals(Font.DIALOG) || want.equals(Font.SANS_SERIF)) {
                    family = want;
                    break;
                }
                for (String have : available) {
                    if (want.equalsIgnoreCase(have)) {
                        family = have;
                        want = null;
                        break;
                    }
                }
                if (want == null) {
                    break;
                }
            }
        } catch (Throwable ignored) {
        }
        sysFont = new Font(family, Font.PLAIN, px);
        BufferedImage scratch = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = scratch.createGraphics();
        applyTextHints(g);
        g.setFont(sysFont);
        sysMetrics = g.getFontMetrics();
        sysFontH = Math.max(8, sysMetrics.getHeight());
        g.dispose();
    }

    private static void applyTextHints(Graphics2D g) {
        try {
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);
        } catch (RuntimeException ignored) {
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        }
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
    }

    private static int fontHeight() {
        if (!forceBitmapFont) {
            try {
                ensureSysFont();
                return sysFontH;
            } catch (Throwable ignored) {
            }
        }
        return HostFont.CELL;
    }

    private static int textWidth(String s) {
        if (s == null || s.isEmpty()) {
            return 0;
        }
        if (!forceBitmapFont) {
            try {
                ensureSysFont();
                int max = 0;
                int start = 0;
                while (start <= s.length()) {
                    int end = s.indexOf('\n', start);
                    if (end < 0) {
                        end = s.length();
                    }
                    max = Math.max(max, sysMetrics.stringWidth(s.substring(start, end)));
                    if (end == s.length()) {
                        break;
                    }
                    start = end + 1;
                }
                return max;
            } catch (Throwable ignored) {
            }
        }
        return HostFont.width(s);
    }

    private static void requireArity(Interp interp, String name, List<Value> args, int n, int line, int col) {
        if (args.size() != n) {
            throw interp.runtime("__ui." + name + " takes " + n + " argument" + (n == 1 ? "" : "s"), line, col);
        }
    }

    private static long needInt(Interp interp, String name, List<Value> args, int i, int line, int col) {
        if (args.get(i).kind != Value.Kind.Int) {
            throw interp.runtime("__ui." + name + " expects Int", line, col);
        }
        return args.get(i).i;
    }

    private static String needStr(Interp interp, String name, List<Value> args, int i, int line, int col) {
        if (args.get(i).kind != Value.Kind.String) {
            throw interp.runtime("__ui." + name + " expects String", line, col);
        }
        return args.get(i).s;
    }

    private static boolean needBool(Interp interp, String name, List<Value> args, int i, int line, int col) {
        if (args.get(i).kind != Value.Kind.Bool) {
            throw interp.runtime("__ui." + name + " expects Bool", line, col);
        }
        return args.get(i).b;
    }

    private static int packRgb(long color) {
        int r = (int) ((color >> 16) & 255);
        int g = (int) ((color >> 8) & 255);
        int b = (int) (color & 255);
        return b | (g << 8) | (r << 16);
    }

    private static void ensureFb(Win win) {
        int w = win.width > 0 ? win.width : 1;
        int h = win.height > 0 ? win.height : 1;
        if (w > MAX_SIZE) {
            w = MAX_SIZE;
        }
        if (h > MAX_SIZE) {
            h = MAX_SIZE;
        }
        if (win.fbW == w && win.fbH == h && win.fb != null && win.fb.length == w * h) {
            return;
        }
        win.fb = new int[w * h];
        int fill = packRgb(0xF2F2F2);
        java.util.Arrays.fill(win.fb, fill);
        win.fbW = w;
        win.fbH = h;
    }

    private static void fbClear(Win win, long color) {
        ensureFb(win);
        java.util.Arrays.fill(win.fb, packRgb(color));
    }

    private static void clipBounds(Win win, int[] box) {
        if (box[0] < 0) {
            box[0] = 0;
        }
        if (box[1] < 0) {
            box[1] = 0;
        }
        if (box[2] > win.fbW) {
            box[2] = win.fbW;
        }
        if (box[3] > win.fbH) {
            box[3] = win.fbH;
        }
        if (!win.clips.isEmpty()) {
            Clip c = win.clips.getLast();
            box[0] = Math.max(box[0], c.x0);
            box[1] = Math.max(box[1], c.y0);
            box[2] = Math.min(box[2], c.x1);
            box[3] = Math.min(box[3], c.y1);
        }
    }

    private static void fbFill(Win win, int x, int y, int w, int h, long color) {
        ensureFb(win);
        if (w < 1 || h < 1) {
            return;
        }
        int[] box = {x, y, x + w, y + h};
        clipBounds(win, box);
        if (box[0] >= box[2] || box[1] >= box[3]) {
            return;
        }
        int p = packRgb(color);
        for (int row = box[1]; row < box[3]; row++) {
            int dest = row * win.fbW + box[0];
            java.util.Arrays.fill(win.fb, dest, dest + (box[2] - box[0]), p);
        }
    }

    private static void fbPlot(Win win, int x, int y, int p) {
        if (x < 0 || y < 0 || x >= win.fbW || y >= win.fbH) {
            return;
        }
        if (!win.clips.isEmpty()) {
            Clip c = win.clips.getLast();
            if (x < c.x0 || y < c.y0 || x >= c.x1 || y >= c.y1) {
                return;
            }
        }
        win.fb[y * win.fbW + x] = p;
    }

    private static void fbPlotCover(Win win, int x, int y, int src, int cover) {
        if (cover <= 0) {
            return;
        }
        if (x < 0 || y < 0 || x >= win.fbW || y >= win.fbH) {
            return;
        }
        if (!win.clips.isEmpty()) {
            Clip c = win.clips.getLast();
            if (x < c.x0 || y < c.y0 || x >= c.x1 || y >= c.y1) {
                return;
            }
        }
        if (cover >= 256) {
            win.fb[y * win.fbW + x] = src;
            return;
        }
        int dst = win.fb[y * win.fbW + x];
        int inv = 256 - cover;
        int r = ((((src >> 16) & 255) * cover) + (((dst >> 16) & 255) * inv)) >> 8;
        int g = ((((src >> 8) & 255) * cover) + (((dst >> 8) & 255) * inv)) >> 8;
        int b = (((src & 255) * cover) + ((dst & 255) * inv)) >> 8;
        win.fb[y * win.fbW + x] = b | (g << 8) | (r << 16);
    }

    private static void fbLine(Win win, int x0, int y0, int x1, int y1, long color) {
        ensureFb(win);
        int p = packRgb(color);
        int dx = x1 - x0;
        int dy = y1 - y0;
        int steps = Math.max(Math.abs(dx), Math.abs(dy));
        if (steps == 0) {
            fbPlot(win, x0, y0, p);
            return;
        }
        for (int i = 0; i <= steps; i++) {
            fbPlot(win, x0 + dx * i / steps, y0 + dy * i / steps, p);
        }
    }

    private static void fbStrokeRect(Win win, int x, int y, int w, int h, long color) {
        if (w < 1 || h < 1) {
            return;
        }
        fbFill(win, x, y, w, 1, color);
        fbFill(win, x, y + h - 1, w, 1, color);
        fbFill(win, x, y, 1, h, color);
        fbFill(win, x + w - 1, y, 1, h, color);
    }

    private static int clampRoundRadius(int w, int h, int radius) {
        int r = Math.max(radius, 0);
        int lim = Math.min(w, h) / 2;
        return Math.min(r, lim);
    }

    private static float sdRoundBox(float px, float py, float hw, float hh, float radius) {
        float r = Math.max(radius, 0f);
        float maxR = Math.min(hw, hh);
        if (r > maxR) {
            r = maxR;
        }
        float ax = Math.abs(px);
        float ay = Math.abs(py);
        float qx = ax - hw + r;
        float qy = ay - hh + r;
        float mx = Math.max(qx, 0f);
        float my = Math.max(qy, 0f);
        float outside = (float) Math.sqrt(mx * mx + my * my);
        float inside = Math.max(qx, qy);
        if (inside > 0f) {
            inside = 0f;
        }
        return outside + inside - r;
    }

    private static void fbFillRound(Win win, int x, int y, int w, int h, int radius, long color) {
        ensureFb(win);
        if (w < 1 || h < 1) {
            return;
        }
        int r = clampRoundRadius(w, h, radius);
        if (r == 0) {
            fbFill(win, x, y, w, h, color);
            return;
        }
        int p = packRgb(color);
        float cx = x + w * 0.5f;
        float cy = y + h * 0.5f;
        float hw = w * 0.5f;
        float hh = h * 0.5f;
        int[] box = {x, y, x + w, y + h};
        clipBounds(win, box);
        for (int row = box[1]; row < box[3]; row++) {
            for (int col = box[0]; col < box[2]; col++) {
                float sd = sdRoundBox(col + 0.5f - cx, row + 0.5f - cy, hw, hh, r);
                float cov = 0.5f - sd;
                if (cov <= 0f) {
                    continue;
                }
                if (cov >= 1f) {
                    fbPlot(win, col, row, p);
                } else {
                    fbPlotCover(win, col, row, p, (int) (cov * 256f));
                }
            }
        }
    }

    private static void fbStrokeRound(Win win, int x, int y, int w, int h, int radius, long color) {
        ensureFb(win);
        if (w < 1 || h < 1) {
            return;
        }
        int r = clampRoundRadius(w, h, radius);
        if (r == 0) {
            fbStrokeRect(win, x, y, w, h, color);
            return;
        }
        int p = packRgb(color);
        float cx = x + w * 0.5f;
        float cy = y + h * 0.5f;
        float hw = w * 0.5f - 0.5f;
        float hh = h * 0.5f - 0.5f;
        if (hw < 1f || hh < 1f) {
            fbStrokeRect(win, x, y, w, h, color);
            return;
        }
        float rf = Math.max(r - 0.5f, 0f);
        int[] box = {x, y, x + w, y + h};
        clipBounds(win, box);
        for (int row = box[1]; row < box[3]; row++) {
            for (int col = box[0]; col < box[2]; col++) {
                float sd = sdRoundBox(col + 0.5f - cx, row + 0.5f - cy, hw, hh, rf);
                float d = Math.abs(sd);
                float cov = 1f - (d - 0.25f) / 0.75f;
                if (cov <= 0f) {
                    continue;
                }
                if (cov >= 1f) {
                    fbPlot(win, col, row, p);
                } else {
                    fbPlotCover(win, col, row, p, (int) (cov * 256f));
                }
            }
        }
    }

    private static void fbBlitRgb(Win win, int x, int y, int iw, int ih, int[] src) {
        ensureFb(win);
        if (src == null || iw < 1 || ih < 1) {
            return;
        }
        for (int row = 0; row < ih; row++) {
            for (int col = 0; col < iw; col++) {
                fbPlot(win, x + col, y + row, src[row * iw + col]);
            }
        }
    }

    private static void fbText(Win win, int x, int y, String s, long color) {
        ensureFb(win);
        if (s == null || s.isEmpty()) {
            return;
        }
        if (!sysText(win, x, y, s, color)) {
            HostFont.draw(s, x, y, packRgb(color), (px, py, p) -> fbPlot(win, px, py, p));
        }
    }

    private static boolean sysText(Win win, int x, int y, String s, long color) {
        if (forceBitmapFont) {
            return false;
        }
        try {
            ensureSysFont();
        } catch (Throwable ignored) {
            return false;
        }
        if (sysFont == null || sysMetrics == null) {
            return false;
        }
        Color fg = new Color((int) ((color >> 16) & 255), (int) ((color >> 8) & 255), (int) (color & 255));
        int cy = y;
        int start = 0;
        while (start <= s.length()) {
            int end = s.indexOf('\n', start);
            if (end < 0) {
                end = s.length();
            }
            String line = s.substring(start, end);
            int tw = line.isEmpty() ? 0 : sysMetrics.stringWidth(line);
            int th = sysFontH;
            if (tw > 0 && th > 0) {
                BufferedImage dib = new BufferedImage(tw, th, BufferedImage.TYPE_INT_RGB);
                int[] rowPx = new int[tw];
                for (int row = 0; row < th; row++) {
                    int fy = cy + row;
                    for (int col = 0; col < tw; col++) {
                        int fx = x + col;
                        int p = packRgb(color);
                        if (fx >= 0 && fy >= 0 && fx < win.fbW && fy < win.fbH) {
                            p = win.fb[fy * win.fbW + fx];
                        }
                        rowPx[col] = p | 0xFF000000;
                    }
                    dib.setRGB(0, row, tw, 1, rowPx, 0, tw);
                }
                Graphics2D g = dib.createGraphics();
                applyTextHints(g);
                g.setFont(sysFont);
                g.setColor(fg);
                g.drawString(line, 0, sysMetrics.getAscent());
                g.dispose();
                for (int row = 0; row < th; row++) {
                    dib.getRGB(0, row, tw, 1, rowPx, 0, tw);
                    for (int col = 0; col < tw; col++) {
                        fbPlot(win, x + col, cy + row, rowPx[col] & 0xFFFFFF);
                    }
                }
            }
            if (end == s.length()) {
                break;
            }
            cy += sysFontH;
            start = end + 1;
        }
        return true;
    }

    private RgbImage cachedImage(Path path) {
        String key = path.toAbsolutePath().normalize().toString();
        RgbImage hit = images.get(key);
        if (hit != null) {
            return hit;
        }
        if (!Files.isRegularFile(path)) {
            return null;
        }
        try {
            BufferedImage img = HostSvg.isSvg(path) ? HostSvg.rasterize(path) : ImageIO.read(path.toFile());
            if (img == null) {
                return null;
            }
            if (img.getWidth() < 1 || img.getHeight() < 1 || img.getWidth() > 8192 || img.getHeight() > 8192) {
                return null;
            }
            RgbImage out = new RgbImage();
            out.w = img.getWidth();
            out.h = img.getHeight();
            out.px = new int[out.w * out.h];
            for (int row = 0; row < out.h; row++) {
                for (int col = 0; col < out.w; col++) {
                    int argb = img.getRGB(col, row);
                    out.px[row * out.w + col] = packRgb(argb);
                }
            }
            images.put(key, out);
            return out;
        } catch (IOException ex) {
            return null;
        }
    }

    private static final class Clip {
        int x0;
        int y0;
        int x1;
        int y1;
    }

    private static final class RgbImage {
        int w;
        int h;
        int[] px = new int[0];
    }

    private static final class Win {
        volatile boolean alive;
        volatile boolean mapped;
        volatile boolean needsFrame;
        int width;
        int height;
        long id;
        String title = "";
        int[] fb = new int[0];
        int fbW;
        int fbH;
        volatile int mouseX;
        volatile int mouseY;
        volatile boolean mouseDown;
        volatile boolean mouseClick;
        volatile boolean mouseRightClick;
        int cursor;
        volatile boolean keyPending;
        volatile int keyCode;
        volatile String keyText = "";
        volatile boolean scrollPending;
        volatile int scrollDx;
        volatile int scrollDy;
        int wheelAcc;
        JFrame frame;
        FbView view;
        Timer timer;
        final List<Clip> clips = new ArrayList<>();
    }

    private static final class FbView extends JComponent {
        private final Win win;
        private BufferedImage shot;

        FbView(Win win) {
            this.win = win;
            setOpaque(true);
            setFocusable(true);
            setCursor(cursorFor(win.cursor));
            addMouseListener(new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    win.mouseX = e.getX();
                    win.mouseY = e.getY();
                    if (e.getButton() == MouseEvent.BUTTON1) {
                        win.mouseDown = true;
                    }
                    win.needsFrame = true;
                }

                @Override
                public void mouseReleased(MouseEvent e) {
                    win.mouseX = e.getX();
                    win.mouseY = e.getY();
                    if (e.getButton() == MouseEvent.BUTTON1) {
                        win.mouseDown = false;
                        win.mouseClick = true;
                    } else if (e.getButton() == MouseEvent.BUTTON3) {
                        win.mouseRightClick = true;
                    }
                    win.needsFrame = true;
                }
            });
            addMouseMotionListener(new MouseMotionAdapter() {
                @Override
                public void mouseMoved(MouseEvent e) {
                    win.mouseX = e.getX();
                    win.mouseY = e.getY();
                    win.needsFrame = true;
                }

                @Override
                public void mouseDragged(MouseEvent e) {
                    win.mouseX = e.getX();
                    win.mouseY = e.getY();
                    win.needsFrame = true;
                }
            });
            addMouseWheelListener(this::onWheel);
            addKeyListener(new KeyAdapter() {
                @Override
                public void keyPressed(KeyEvent e) {
                    onKeyPressed(e);
                }

                @Override
                public void keyTyped(KeyEvent e) {
                    onKeyTyped(e);
                }
            });
            addComponentListener(new ComponentAdapter() {
                @Override
                public void componentResized(ComponentEvent e) {
                    applyClientSize(win, getWidth(), getHeight());
                }
            });
        }

        void present() {
            int w = win.fbW;
            int h = win.fbH;
            if (w < 1 || h < 1 || win.fb == null || win.fb.length < w * h) {
                return;
            }
            if (shot == null || shot.getWidth() != w || shot.getHeight() != h) {
                shot = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            }
            shot.setRGB(0, 0, w, h, win.fb, 0, w);
            Graphics g = getGraphics();
            if (g != null) {
                g.drawImage(shot, 0, 0, Math.max(1, getWidth()), Math.max(1, getHeight()), null);
                g.dispose();
            } else {
                repaint();
            }
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            if (shot != null) {
                g.drawImage(shot, 0, 0, Math.max(1, getWidth()), Math.max(1, getHeight()), null);
            } else if (win.fb != null && win.fbW > 0 && win.fbH > 0) {
                present();
            }
        }

        private void onWheel(MouseWheelEvent e) {
            win.mouseX = e.getX();
            win.mouseY = e.getY();
            int delta = (int) Math.round(-e.getPreciseWheelRotation() * 120.0);
            int px = wheelDeltaToPixels(win, delta);
            if (px != 0) {
                if (!win.scrollPending) {
                    win.scrollDx = 0;
                    win.scrollDy = 0;
                }
                win.scrollDy += px;
                win.scrollPending = true;
                win.needsFrame = true;
            }
        }

        private void onKeyPressed(KeyEvent e) {
            int vk = e.getKeyCode();
            boolean shift = e.isShiftDown();
            boolean ctrl = e.isControlDown();
            String mod = shift && ctrl ? "ctrl+shift" : shift ? "shift" : ctrl ? "ctrl" : "";
            if (vk == KeyEvent.VK_TAB) {
                feedKey(win, vk, shift ? "shift" : "");
                e.consume();
                return;
            }
            if (vk == KeyEvent.VK_LEFT || vk == KeyEvent.VK_RIGHT || vk == KeyEvent.VK_HOME || vk == KeyEvent.VK_END
                    || vk == KeyEvent.VK_DELETE || vk == KeyEvent.VK_UP || vk == KeyEvent.VK_DOWN
                    || vk == KeyEvent.VK_PAGE_UP || vk == KeyEvent.VK_PAGE_DOWN || vk == KeyEvent.VK_ESCAPE) {
                feedKey(win, vk, mod);
                return;
            }
            if (ctrl && (vk == KeyEvent.VK_A || vk == KeyEvent.VK_C || vk == KeyEvent.VK_X || vk == KeyEvent.VK_V)) {
                feedKey(win, vk, "ctrl");
            }
        }

        private void onKeyTyped(KeyEvent e) {
            char ch = e.getKeyChar();
            if (ch == 9) {
                return;
            }
            if (ch == 8 || ch == 13) {
                feedKey(win, ch, "");
            } else if (ch >= 32 && ch != 127) {
                feedKey(win, ch, String.valueOf(ch));
            }
        }
    }
}
