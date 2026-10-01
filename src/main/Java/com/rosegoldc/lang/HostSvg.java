package com.rosegoldc.lang;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.LinearGradientPaint;
import java.awt.MultipleGradientPaint;
import java.awt.Paint;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class HostSvg {

    private static final int MAX_DIM = 8192;
    private static final Color NONE = new Color(0, 0, 0, 0);

    private HostSvg() {
    }

    static boolean isSvg(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".svg");
    }

    static BufferedImage rasterize(Path path) {
        String xml;
        try {
            xml = Files.readString(path, StandardCharsets.UTF_8);
        } catch (Exception ex) {
            return null;
        }
        return rasterizeXml(xml);
    }

    static BufferedImage rasterizeXml(String xml) {
        if (xml == null || xml.isBlank()) {
            return null;
        }
        Document doc;
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setExpandEntityReferences(false);
            factory.setXIncludeAware(false);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            byte[] bytes = xml.getBytes(StandardCharsets.UTF_8);
            doc = factory.newDocumentBuilder().parse(new ByteArrayInputStream(bytes));
        } catch (Exception ex) {
            return null;
        }
        Element root = doc.getDocumentElement();
        if (root == null || !"svg".equals(local(root))) {
            return null;
        }
        float[] view = {0, 0, 0, 0};
        boolean hasView = parseViewBox(attr(root, "viewBox"), view);
        float width = parseLen(attr(root, "width"), hasView ? view[2] : 0);
        float height = parseLen(attr(root, "height"), hasView ? view[3] : 0);
        if (width <= 0 && hasView) {
            width = view[2];
        }
        if (height <= 0 && hasView) {
            height = view[3];
        }
        if (width <= 0) {
            width = 1;
        }
        if (height <= 0) {
            height = 1;
        }
        int w = Math.max(1, Math.round(width));
        int h = Math.max(1, Math.round(height));
        if (w > MAX_DIM || h > MAX_DIM) {
            return null;
        }
        Map<String, Paint> paints = new LinkedHashMap<>();
        collectPaints(root, paints);
        BufferedImage argb = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = argb.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        if (hasView && view[2] > 0 && view[3] > 0) {
            g.scale(w / view[2], h / view[3]);
            g.translate(-view[0], -view[1]);
        }
        drawNode(g, root, Style.defaults(), paints, true);
        g.dispose();
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D og = out.createGraphics();
        og.setColor(Color.WHITE);
        og.fillRect(0, 0, w, h);
        og.drawImage(argb, 0, 0, null);
        og.dispose();
        return out;
    }

    private static void collectPaints(Element root, Map<String, Paint> paints) {
        NodeList nodes = root.getElementsByTagName("*");
        for (int i = 0; i < nodes.getLength(); i++) {
            Node n = nodes.item(i);
            if (!(n instanceof Element el)) {
                continue;
            }
            if (!"linearGradient".equals(local(el))) {
                continue;
            }
            String id = attr(el, "id");
            if (id.isEmpty()) {
                continue;
            }
            Paint paint = linearPaint(el);
            if (paint != null) {
                paints.put(id, paint);
            }
        }
    }

    private static Paint linearPaint(Element el) {
        float x1 = parseLen(first(attr(el, "x1"), "0"), 1);
        float y1 = parseLen(first(attr(el, "y1"), "0"), 1);
        float x2 = parseLen(first(attr(el, "x2"), "1"), 1);
        float y2 = parseLen(first(attr(el, "y2"), "0"), 1);
        List<Float> offsets = new ArrayList<>();
        List<Color> colors = new ArrayList<>();
        for (Node n = el.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (!(n instanceof Element stop) || !"stop".equals(local(stop))) {
                continue;
            }
            Style st = Style.defaults();
            applyStyle(st, stop);
            float off = parsePercent(first(attr(stop, "offset"), "0"));
            Color c = st.stopColor == null ? Color.BLACK : st.stopColor;
            float op = st.stopOpacity * st.opacity;
            colors.add(new Color(c.getRed(), c.getGreen(), c.getBlue(), clamp255(Math.round(255 * op))));
            offsets.add(off);
        }
        if (colors.isEmpty()) {
            return null;
        }
        if (colors.size() == 1) {
            return colors.getFirst();
        }
        float[] fracs = new float[offsets.size()];
        Color[] cols = new Color[colors.size()];
        float last = -1;
        for (int i = 0; i < offsets.size(); i++) {
            float f = Math.min(1f, Math.max(0f, offsets.get(i)));
            if (f <= last) {
                f = Math.min(1f, last + 0.0001f);
            }
            last = f;
            fracs[i] = f;
            cols[i] = colors.get(i);
        }
        if (x1 == x2 && y1 == y2) {
            return cols[cols.length - 1];
        }
        return new LinearGradientPaint(new Point2D.Float(x1, y1), new Point2D.Float(x2, y2), fracs, cols,
                MultipleGradientPaint.CycleMethod.NO_CYCLE);
    }

    private static void drawNode(Graphics2D g, Element el, Style parent, Map<String, Paint> paints, boolean skipSelf) {
        if ("none".equalsIgnoreCase(attr(el, "display"))) {
            return;
        }
        Style style = parent.copy();
        applyStyle(style, el);
        AffineTransform prev = g.getTransform();
        AffineTransform xf = parseTransform(attr(el, "transform"));
        if (xf != null) {
            g.transform(xf);
        }
        String tag = local(el);
        if (!skipSelf) {
            Shape shape = shapeOf(tag, el, style);
            if (shape != null) {
                paintShape(g, shape, style, paints);
            }
        }
        if ("defs".equals(tag) || "clipPath".equals(tag) || "mask".equals(tag) || "title".equals(tag)
                || "desc".equals(tag) || "metadata".equals(tag) || "style".equals(tag)
                || "linearGradient".equals(tag) || "radialGradient".equals(tag)) {
            g.setTransform(prev);
            return;
        }
        for (Node n = el.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element child) {
                drawNode(g, child, style, paints, false);
            }
        }
        g.setTransform(prev);
    }

    private static Shape shapeOf(String tag, Element el, Style style) {
        return switch (tag) {
            case "rect" -> {
                float x = parseLen(attr(el, "x"), 0);
                float y = parseLen(attr(el, "y"), 0);
                float w = parseLen(attr(el, "width"), 0);
                float h = parseLen(attr(el, "height"), 0);
                if (w <= 0 || h <= 0) {
                    yield null;
                }
                float rx = parseLen(first(attr(el, "rx"), attr(el, "ry")), 0);
                float ry = parseLen(first(attr(el, "ry"), attr(el, "rx")), 0);
                if (rx > 0 || ry > 0) {
                    yield new RoundRectangle2D.Float(x, y, w, h, rx * 2, ry * 2);
                }
                yield new Rectangle2D.Float(x, y, w, h);
            }
            case "circle" -> {
                float cx = parseLen(attr(el, "cx"), 0);
                float cy = parseLen(attr(el, "cy"), 0);
                float r = parseLen(attr(el, "r"), 0);
                if (r <= 0) {
                    yield null;
                }
                yield new Ellipse2D.Float(cx - r, cy - r, r * 2, r * 2);
            }
            case "ellipse" -> {
                float cx = parseLen(attr(el, "cx"), 0);
                float cy = parseLen(attr(el, "cy"), 0);
                float rx = parseLen(attr(el, "rx"), 0);
                float ry = parseLen(attr(el, "ry"), 0);
                if (rx <= 0 || ry <= 0) {
                    yield null;
                }
                yield new Ellipse2D.Float(cx - rx, cy - ry, rx * 2, ry * 2);
            }
            case "line" -> new Line2D.Float(
                    parseLen(attr(el, "x1"), 0), parseLen(attr(el, "y1"), 0),
                    parseLen(attr(el, "x2"), 0), parseLen(attr(el, "y2"), 0));
            case "polyline" -> winding(parsePoly(attr(el, "points"), false), style);
            case "polygon" -> winding(parsePoly(attr(el, "points"), true), style);
            case "path" -> winding(parsePath(attr(el, "d")), style);
            default -> null;
        };
    }

    private static Path2D winding(Path2D path, Style style) {
        if (path != null) {
            path.setWindingRule(style.evenOdd ? Path2D.WIND_EVEN_ODD : Path2D.WIND_NON_ZERO);
        }
        return path;
    }

    private static void paintShape(Graphics2D g, Shape shape, Style style, Map<String, Paint> paints) {
        float op = Math.min(1f, Math.max(0f, style.opacity));
        Paint fill = resolvePaint(style.fill, paints);
        if (fill != null && fill != NONE) {
            g.setPaint(withOpacity(fill, op * style.fillOpacity));
            g.fill(shape);
        }
        Paint stroke = resolvePaint(style.stroke, paints);
        if (stroke != null && stroke != NONE && style.strokeWidth > 0) {
            g.setPaint(withOpacity(stroke, op * style.strokeOpacity));
            int cap = switch (style.linecap) {
                case "round" -> BasicStroke.CAP_ROUND;
                case "square" -> BasicStroke.CAP_SQUARE;
                default -> BasicStroke.CAP_BUTT;
            };
            int join = switch (style.linejoin) {
                case "round" -> BasicStroke.JOIN_ROUND;
                case "bevel" -> BasicStroke.JOIN_BEVEL;
                default -> BasicStroke.JOIN_MITER;
            };
            g.setStroke(new BasicStroke(style.strokeWidth, cap, join, Math.max(1f, style.miter)));
            g.draw(shape);
        }
    }

    private static Paint resolvePaint(String spec, Map<String, Paint> paints) {
        if (spec == null || spec.isEmpty() || "none".equalsIgnoreCase(spec) || "transparent".equalsIgnoreCase(spec)) {
            return NONE;
        }
        String s = spec.trim();
        if (s.regionMatches(true, 0, "url(", 0, 4)) {
            int start = s.indexOf('#');
            int end = s.indexOf(')', Math.max(start, 0));
            if (start >= 0 && end > start) {
                Paint hit = paints.get(s.substring(start + 1, end).trim());
                if (hit != null) {
                    return hit;
                }
            }
            return NONE;
        }
        Color c = parseColor(s);
        return c == null ? NONE : c;
    }

    private static Paint withOpacity(Paint paint, float opacity) {
        if (opacity >= 0.999f || !(paint instanceof Color c)) {
            return paint;
        }
        int a = clamp255(Math.round(c.getAlpha() * Math.min(1f, Math.max(0f, opacity))));
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), a);
    }

    private static void applyStyle(Style style, Element el) {
        applyDecls(style, attr(el, "style"));
        applyAttr(style, "fill", attr(el, "fill"));
        applyAttr(style, "stroke", attr(el, "stroke"));
        applyAttr(style, "stroke-width", attr(el, "stroke-width"));
        applyAttr(style, "stroke-linecap", attr(el, "stroke-linecap"));
        applyAttr(style, "stroke-linejoin", attr(el, "stroke-linejoin"));
        applyAttr(style, "stroke-miterlimit", attr(el, "stroke-miterlimit"));
        applyAttr(style, "opacity", attr(el, "opacity"));
        applyAttr(style, "fill-opacity", attr(el, "fill-opacity"));
        applyAttr(style, "stroke-opacity", attr(el, "stroke-opacity"));
        applyAttr(style, "stop-color", attr(el, "stop-color"));
        applyAttr(style, "stop-opacity", attr(el, "stop-opacity"));
        applyAttr(style, "fill-rule", attr(el, "fill-rule"));
    }

    private static void applyDecls(Style style, String css) {
        if (css.isEmpty()) {
            return;
        }
        for (String part : css.split(";")) {
            int colon = part.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            applyAttr(style, part.substring(0, colon).trim(), part.substring(colon + 1).trim());
        }
    }

    private static void applyAttr(Style style, String key, String value) {
        if (value == null || value.isEmpty() || "inherit".equalsIgnoreCase(value)) {
            return;
        }
        switch (key) {
            case "fill" -> style.fill = value;
            case "stroke" -> style.stroke = value;
            case "stroke-width" -> style.strokeWidth = parseLen(value, style.strokeWidth);
            case "stroke-linecap" -> style.linecap = value.toLowerCase(Locale.ROOT);
            case "stroke-linejoin" -> style.linejoin = value.toLowerCase(Locale.ROOT);
            case "stroke-miterlimit" -> style.miter = parseLen(value, style.miter);
            case "opacity" -> style.opacity = parseLen(value, 1);
            case "fill-opacity" -> style.fillOpacity = parseLen(value, 1);
            case "stroke-opacity" -> style.strokeOpacity = parseLen(value, 1);
            case "stop-color" -> style.stopColor = parseColor(value);
            case "stop-opacity" -> style.stopOpacity = parseLen(value, 1);
            case "fill-rule" -> style.evenOdd = "evenodd".equalsIgnoreCase(value);
            default -> {
            }
        }
    }

    private static Path2D parsePoly(String points, boolean close) {
        Scanner sc = new Scanner(points);
        Path2D path = new Path2D.Float();
        boolean first = true;
        while (sc.hasNumber()) {
            float x = sc.number();
            if (!sc.hasNumber()) {
                break;
            }
            float y = sc.number();
            if (first) {
                path.moveTo(x, y);
                first = false;
            } else {
                path.lineTo(x, y);
            }
        }
        if (first) {
            return null;
        }
        if (close) {
            path.closePath();
        }
        return path;
    }

    private static Path2D parsePath(String d) {
        Scanner sc = new Scanner(d);
        Path2D path = new Path2D.Float();
        float cx = 0;
        float cy = 0;
        float sx = 0;
        float sy = 0;
        float ox = 0;
        float oy = 0;
        char prev = 0;
        char cmd = 0;
        boolean started = false;
        while (!sc.done()) {
            if (sc.hasCommand()) {
                cmd = sc.command();
            } else if (cmd == 0) {
                break;
            }
            boolean rel = Character.isLowerCase(cmd);
            char kind = Character.toUpperCase(cmd);
            switch (kind) {
                case 'M' -> {
                    float x = sc.number();
                    float y = sc.number();
                    if (rel) {
                        x += cx;
                        y += cy;
                    }
                    path.moveTo(x, y);
                    cx = sx = x;
                    cy = sy = y;
                    started = true;
                    while (sc.hasNumber()) {
                        x = sc.number();
                        y = sc.number();
                        if (rel) {
                            x += cx;
                            y += cy;
                        }
                        path.lineTo(x, y);
                        cx = x;
                        cy = y;
                    }
                    prev = 'L';
                }
                case 'L' -> {
                    do {
                        float x = sc.number();
                        float y = sc.number();
                        if (rel) {
                            x += cx;
                            y += cy;
                        }
                        path.lineTo(x, y);
                        cx = x;
                        cy = y;
                    } while (sc.hasNumber());
                    prev = 'L';
                }
                case 'H' -> {
                    do {
                        float x = sc.number();
                        if (rel) {
                            x += cx;
                        }
                        path.lineTo(x, cy);
                        cx = x;
                    } while (sc.hasNumber());
                    prev = 'H';
                }
                case 'V' -> {
                    do {
                        float y = sc.number();
                        if (rel) {
                            y += cy;
                        }
                        path.lineTo(cx, y);
                        cy = y;
                    } while (sc.hasNumber());
                    prev = 'V';
                }
                case 'C' -> {
                    do {
                        float x1 = sc.number();
                        float y1 = sc.number();
                        float x2 = sc.number();
                        float y2 = sc.number();
                        float x = sc.number();
                        float y = sc.number();
                        if (rel) {
                            x1 += cx;
                            y1 += cy;
                            x2 += cx;
                            y2 += cy;
                            x += cx;
                            y += cy;
                        }
                        path.curveTo(x1, y1, x2, y2, x, y);
                        ox = x2;
                        oy = y2;
                        cx = x;
                        cy = y;
                    } while (sc.hasNumber());
                    prev = 'C';
                }
                case 'S' -> {
                    do {
                        float x2 = sc.number();
                        float y2 = sc.number();
                        float x = sc.number();
                        float y = sc.number();
                        if (rel) {
                            x2 += cx;
                            y2 += cy;
                            x += cx;
                            y += cy;
                        }
                        float x1 = cx;
                        float y1 = cy;
                        if (prev == 'C' || prev == 'S') {
                            x1 = 2 * cx - ox;
                            y1 = 2 * cy - oy;
                        }
                        path.curveTo(x1, y1, x2, y2, x, y);
                        ox = x2;
                        oy = y2;
                        cx = x;
                        cy = y;
                    } while (sc.hasNumber());
                    prev = 'S';
                }
                case 'Q' -> {
                    do {
                        float x1 = sc.number();
                        float y1 = sc.number();
                        float x = sc.number();
                        float y = sc.number();
                        if (rel) {
                            x1 += cx;
                            y1 += cy;
                            x += cx;
                            y += cy;
                        }
                        path.quadTo(x1, y1, x, y);
                        ox = x1;
                        oy = y1;
                        cx = x;
                        cy = y;
                    } while (sc.hasNumber());
                    prev = 'Q';
                }
                case 'T' -> {
                    do {
                        float x = sc.number();
                        float y = sc.number();
                        if (rel) {
                            x += cx;
                            y += cy;
                        }
                        float x1 = cx;
                        float y1 = cy;
                        if (prev == 'Q' || prev == 'T') {
                            x1 = 2 * cx - ox;
                            y1 = 2 * cy - oy;
                        }
                        path.quadTo(x1, y1, x, y);
                        ox = x1;
                        oy = y1;
                        cx = x;
                        cy = y;
                    } while (sc.hasNumber());
                    prev = 'T';
                }
                case 'A' -> {
                    do {
                        float rx = Math.abs(sc.number());
                        float ry = Math.abs(sc.number());
                        float rot = sc.number();
                        int large = (int) sc.number();
                        int sweep = (int) sc.number();
                        float x = sc.number();
                        float y = sc.number();
                        if (rel) {
                            x += cx;
                            y += cy;
                        }
                        arcTo(path, cx, cy, rx, ry, rot, large != 0, sweep != 0, x, y);
                        cx = x;
                        cy = y;
                    } while (sc.hasNumber());
                    prev = 'A';
                }
                case 'Z' -> {
                    path.closePath();
                    cx = sx;
                    cy = sy;
                    prev = 'Z';
                }
                default -> {
                    sc.skip();
                    cmd = 0;
                }
            }
            if (kind != 'C' && kind != 'S' && kind != 'Q' && kind != 'T') {
                ox = cx;
                oy = cy;
            }
        }
        return started ? path : null;
    }

    private static void arcTo(Path2D path, float x1, float y1, float rx, float ry, float phiDeg,
            boolean large, boolean sweep, float x2, float y2) {
        if (rx == 0 || ry == 0) {
            path.lineTo(x2, y2);
            return;
        }
        double phi = Math.toRadians(phiDeg);
        double cos = Math.cos(phi);
        double sin = Math.sin(phi);
        double dx = (x1 - x2) / 2.0;
        double dy = (y1 - y2) / 2.0;
        double x1p = cos * dx + sin * dy;
        double y1p = -sin * dx + cos * dy;
        double rxsq = rx * rx;
        double rysq = ry * ry;
        double x1psq = x1p * x1p;
        double y1psq = y1p * y1p;
        double lambda = x1psq / rxsq + y1psq / rysq;
        if (lambda > 1) {
            double s = Math.sqrt(lambda);
            rx *= (float) s;
            ry *= (float) s;
            rxsq = rx * rx;
            rysq = ry * ry;
        }
        double num = rxsq * rysq - rxsq * y1psq - rysq * x1psq;
        double den = rxsq * y1psq + rysq * x1psq;
        double coef = den == 0 ? 0 : Math.sqrt(Math.max(0, num / den));
        if (large == sweep) {
            coef = -coef;
        }
        double cxp = coef * (rx * y1p) / ry;
        double cyp = coef * -(ry * x1p) / rx;
        double cx = cos * cxp - sin * cyp + (x1 + x2) / 2.0;
        double cy = sin * cxp + cos * cyp + (y1 + y2) / 2.0;
        double theta1 = vectorAngle(1, 0, (x1p - cxp) / rx, (y1p - cyp) / ry);
        double dtheta = vectorAngle((x1p - cxp) / rx, (y1p - cyp) / ry, (-x1p - cxp) / rx, (-y1p - cyp) / ry);
        if (!sweep && dtheta > 0) {
            dtheta -= Math.PI * 2;
        } else if (sweep && dtheta < 0) {
            dtheta += Math.PI * 2;
        }
        Arc2D arc = new Arc2D.Double(cx - rx, cy - ry, rx * 2.0, ry * 2.0, Math.toDegrees(-theta1),
                Math.toDegrees(-dtheta), Arc2D.OPEN);
        path.append(AffineTransform.getRotateInstance(phi, cx, cy).createTransformedShape(arc), true);
    }

    private static double vectorAngle(double ux, double uy, double vx, double vy) {
        double sign = ux * vy - uy * vx < 0 ? -1 : 1;
        double dot = ux * vx + uy * vy;
        double nu = Math.hypot(ux, uy);
        double nv = Math.hypot(vx, vy);
        if (nu == 0 || nv == 0) {
            return 0;
        }
        double c = Math.min(1, Math.max(-1, dot / (nu * nv)));
        return sign * Math.acos(c);
    }

    private static AffineTransform parseTransform(String raw) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        AffineTransform xf = new AffineTransform();
        int i = 0;
        String s = raw;
        while (i < s.length()) {
            while (i < s.length() && !Character.isLetter(s.charAt(i))) {
                i++;
            }
            int start = i;
            while (i < s.length() && Character.isLetter(s.charAt(i))) {
                i++;
            }
            if (start == i) {
                break;
            }
            String name = s.substring(start, i).toLowerCase(Locale.ROOT);
            while (i < s.length() && s.charAt(i) != '(' && !Character.isLetter(s.charAt(i))) {
                i++;
            }
            List<Float> nums = new ArrayList<>();
            if (i < s.length() && s.charAt(i) == '(') {
                int close = s.indexOf(')', i);
                if (close < 0) {
                    break;
                }
                Scanner sc = new Scanner(s.substring(i + 1, close));
                while (sc.hasNumber()) {
                    nums.add(sc.number());
                }
                i = close + 1;
            }
            switch (name) {
                case "translate" -> {
                    float x = nums.isEmpty() ? 0 : nums.get(0);
                    float y = nums.size() > 1 ? nums.get(1) : 0;
                    xf.translate(x, y);
                }
                case "scale" -> {
                    float x = nums.isEmpty() ? 1 : nums.get(0);
                    float y = nums.size() > 1 ? nums.get(1) : x;
                    xf.scale(x, y);
                }
                case "rotate" -> {
                    if (!nums.isEmpty()) {
                        if (nums.size() >= 3) {
                            xf.rotate(Math.toRadians(nums.get(0)), nums.get(1), nums.get(2));
                        } else {
                            xf.rotate(Math.toRadians(nums.get(0)));
                        }
                    }
                }
                case "matrix" -> {
                    if (nums.size() >= 6) {
                        xf.concatenate(new AffineTransform(nums.get(0), nums.get(1), nums.get(2), nums.get(3),
                                nums.get(4), nums.get(5)));
                    }
                }
                default -> {
                }
            }
        }
        return xf.isIdentity() ? null : xf;
    }

    private static boolean parseViewBox(String raw, float[] out) {
        Scanner sc = new Scanner(raw);
        if (!sc.hasNumber()) {
            return false;
        }
        out[0] = sc.number();
        if (!sc.hasNumber()) {
            return false;
        }
        out[1] = sc.number();
        if (!sc.hasNumber()) {
            return false;
        }
        out[2] = sc.number();
        if (!sc.hasNumber()) {
            return false;
        }
        out[3] = sc.number();
        return out[2] > 0 && out[3] > 0;
    }

    private static float parseLen(String raw, float percentOf) {
        if (raw == null) {
            return percentOf;
        }
        String s = raw.trim().toLowerCase(Locale.ROOT);
        if (s.isEmpty()) {
            return percentOf;
        }
        if (s.endsWith("%")) {
            return percentOf * parseFloat(s.substring(0, s.length() - 1), 0) / 100f;
        }
        float scale = 1f;
        if (s.endsWith("px")) {
            s = s.substring(0, s.length() - 2);
        } else if (s.endsWith("pt")) {
            s = s.substring(0, s.length() - 2);
            scale = 96f / 72f;
        } else if (s.endsWith("in")) {
            s = s.substring(0, s.length() - 2);
            scale = 96f;
        } else if (s.endsWith("cm")) {
            s = s.substring(0, s.length() - 2);
            scale = 96f / 2.54f;
        } else if (s.endsWith("mm")) {
            s = s.substring(0, s.length() - 2);
            scale = 96f / 25.4f;
        }
        return parseFloat(s, percentOf) * scale;
    }

    private static float parsePercent(String raw) {
        String s = raw.trim();
        if (s.endsWith("%")) {
            return parseFloat(s.substring(0, s.length() - 1), 0) / 100f;
        }
        return parseFloat(s, 0);
    }

    private static float parseFloat(String raw, float fallback) {
        try {
            return Float.parseFloat(raw.trim());
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private static Color parseColor(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.trim();
        if (s.isEmpty() || "none".equalsIgnoreCase(s) || "transparent".equalsIgnoreCase(s)) {
            return NONE;
        }
        if (s.startsWith("#")) {
            String hex = s.substring(1);
            if (hex.length() == 3 || hex.length() == 4) {
                StringBuilder wide = new StringBuilder();
                for (int i = 0; i < hex.length(); i++) {
                    wide.append(hex.charAt(i)).append(hex.charAt(i));
                }
                hex = wide.toString();
            }
            try {
                if (hex.length() == 6) {
                    int v = Integer.parseInt(hex, 16);
                    return new Color((v >> 16) & 255, (v >> 8) & 255, v & 255);
                }
                if (hex.length() == 8) {
                    int v = (int) Long.parseLong(hex, 16);
                    return new Color((v >> 16) & 255, (v >> 8) & 255, v & 255, (v >> 24) & 255);
                }
            } catch (NumberFormatException ex) {
                return null;
            }
        }
        if (s.regionMatches(true, 0, "rgb", 0, 3)) {
            Scanner sc = new Scanner(s);
            if (!sc.hasNumber()) {
                return null;
            }
            int r = clamp255(Math.round(sc.number()));
            int g = sc.hasNumber() ? clamp255(Math.round(sc.number())) : 0;
            int b = sc.hasNumber() ? clamp255(Math.round(sc.number())) : 0;
            int a = 255;
            if (sc.hasNumber()) {
                float av = sc.number();
                a = av <= 1f ? clamp255(Math.round(av * 255)) : clamp255(Math.round(av));
            }
            return new Color(r, g, b, a);
        }
        return namedColor(s.toLowerCase(Locale.ROOT));
    }

    private static Color namedColor(String name) {
        return switch (name) {
            case "black" -> Color.BLACK;
            case "white" -> Color.WHITE;
            case "red" -> Color.RED;
            case "green" -> new Color(0, 128, 0);
            case "blue" -> Color.BLUE;
            case "yellow" -> Color.YELLOW;
            case "cyan", "aqua" -> Color.CYAN;
            case "magenta", "fuchsia" -> Color.MAGENTA;
            case "gray", "grey" -> Color.GRAY;
            case "silver" -> new Color(192, 192, 192);
            case "maroon" -> new Color(128, 0, 0);
            case "olive" -> new Color(128, 128, 0);
            case "purple" -> new Color(128, 0, 128);
            case "teal" -> new Color(0, 128, 128);
            case "navy" -> new Color(0, 0, 128);
            case "orange" -> new Color(255, 165, 0);
            default -> null;
        };
    }

    private static int clamp255(int v) {
        return Math.min(255, Math.max(0, v));
    }

    private static String attr(Element el, String name) {
        String v = el.getAttribute(name);
        return v == null ? "" : v.trim();
    }

    private static String local(Element el) {
        String name = el.getLocalName();
        if (name == null || name.isEmpty()) {
            name = el.getTagName();
        }
        int colon = name.indexOf(':');
        return colon < 0 ? name : name.substring(colon + 1);
    }

    private static String first(String a, String b) {
        return a == null || a.isEmpty() ? (b == null ? "" : b) : a;
    }

    private static final class Style {
        String fill = "#000000";
        String stroke = "none";
        float strokeWidth = 1;
        String linecap = "butt";
        String linejoin = "miter";
        float miter = 4;
        float opacity = 1;
        float fillOpacity = 1;
        float strokeOpacity = 1;
        Color stopColor = Color.BLACK;
        float stopOpacity = 1;
        boolean evenOdd;

        static Style defaults() {
            return new Style();
        }

        Style copy() {
            Style s = new Style();
            s.fill = fill;
            s.stroke = stroke;
            s.strokeWidth = strokeWidth;
            s.linecap = linecap;
            s.linejoin = linejoin;
            s.miter = miter;
            s.opacity = opacity;
            s.fillOpacity = fillOpacity;
            s.strokeOpacity = strokeOpacity;
            s.stopColor = stopColor;
            s.stopOpacity = stopOpacity;
            s.evenOdd = evenOdd;
            return s;
        }
    }

    private static final class Scanner {
        final String s;
        int i;

        Scanner(String s) {
            this.s = s == null ? "" : s;
        }

        boolean done() {
            skipSep();
            return i >= s.length();
        }

        boolean hasCommand() {
            skipSep();
            return i < s.length() && Character.isLetter(s.charAt(i));
        }

        char command() {
            skipSep();
            return i < s.length() ? s.charAt(i++) : 0;
        }

        boolean hasNumber() {
            skipSep();
            if (i >= s.length()) {
                return false;
            }
            char c = s.charAt(i);
            return c == '+' || c == '-' || c == '.' || Character.isDigit(c);
        }

        float number() {
            skipSep();
            int start = i;
            if (i < s.length() && (s.charAt(i) == '+' || s.charAt(i) == '-')) {
                i++;
            }
            while (i < s.length() && Character.isDigit(s.charAt(i))) {
                i++;
            }
            if (i < s.length() && s.charAt(i) == '.') {
                i++;
                while (i < s.length() && Character.isDigit(s.charAt(i))) {
                    i++;
                }
            }
            if (i < s.length() && (s.charAt(i) == 'e' || s.charAt(i) == 'E')) {
                int save = i;
                i++;
                if (i < s.length() && (s.charAt(i) == '+' || s.charAt(i) == '-')) {
                    i++;
                }
                if (i >= s.length() || !Character.isDigit(s.charAt(i))) {
                    i = save;
                } else {
                    while (i < s.length() && Character.isDigit(s.charAt(i))) {
                        i++;
                    }
                }
            }
            if (start == i) {
                return 0;
            }
            return parseFloat(s.substring(start, i), 0);
        }

        void skip() {
            if (i < s.length()) {
                i++;
            }
        }

        private void skipSep() {
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c == ',' || Character.isWhitespace(c) || c == '(' || c == ')') {
                    i++;
                    continue;
                }
                break;
            }
        }
    }
}
