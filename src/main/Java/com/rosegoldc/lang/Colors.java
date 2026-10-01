package com.rosegoldc.lang;

import java.util.ArrayList;
import java.util.List;

public final class Colors {

    public static final class Span {
        public final int start;
        public final int end;
        public final int r;
        public final int g;
        public final int b;
        public final int a;
        public final String text;

        Span(int start, int end, int r, int g, int b, int a, String text) {
            this.start = start;
            this.end = end;
            this.r = r;
            this.g = g;
            this.b = b;
            this.a = a;
            this.text = text == null ? "" : text;
        }

        public boolean contains(int offset) {
            return offset >= start && offset < end;
        }
    }

    private Colors() {
    }

    public static List<Span> collect(String source) {
        if (source == null || source.isEmpty()) {
            return List.of();
        }
        List<Token> tokens;
        try {
            tokens = Lexer.tokenize(source, "", new ArrayList<>());
        } catch (RuntimeException ex) {
            return List.of();
        }
        List<Span> out = new ArrayList<>();
        for (int i = 0; i < tokens.size(); i++) {
            Token t = tokens.get(i);
            if (t.kind != Tok.Identifier) {
                continue;
            }
            String name = t.text;
            if (name.equals("Color") && i + 2 < tokens.size()
                    && tokens.get(i + 1).kind == Tok.Dot
                    && tokens.get(i + 2).kind == Tok.Identifier) {
                String var = tokens.get(i + 2).text;
                int[] named = namedCss(var);
                if (named != null) {
                    out.add(span(source, tokens.get(i), tokens.get(i + 2), named[0], named[1], named[2], 255));
                    continue;
                }
                boolean rgbCtor = var.equalsIgnoreCase("Rgb");
                boolean argbCtor = var.equalsIgnoreCase("Argb");
                if ((rgbCtor || argbCtor) && i + 3 < tokens.size()) {
                    int[] args = readColorArgs(tokens, i + 3);
                    if (args == null) {
                        continue;
                    }
                    Token close = tokens.get(args[args.length - 1]);
                    if (rgbCtor && args.length == 1) {
                        out.add(span(source, tokens.get(i), close, 0, 0, 0, 255));
                    } else if (rgbCtor && args.length == 4) {
                        out.add(span(source, tokens.get(i), close, clamp(args[0]), clamp(args[1]), clamp(args[2]), 255));
                    } else if (argbCtor && args.length == 1) {
                        out.add(span(source, tokens.get(i), close, 0, 0, 0, 255));
                    } else if (argbCtor && args.length == 5) {
                        out.add(span(source, tokens.get(i), close, clamp(args[1]), clamp(args[2]), clamp(args[3]), clamp(args[0])));
                    }
                }
                continue;
            }
            boolean ctor = name.equals("rgb") || name.equals("argb");
            int nameIdx = i;
            if (ctor && i >= 2 && tokens.get(i - 1).kind == Tok.Dot) {
                continue;
            }
            if (!ctor && name.equals("ui") && i + 2 < tokens.size()
                    && tokens.get(i + 1).kind == Tok.Dot
                    && tokens.get(i + 2).kind == Tok.Identifier
                    && (tokens.get(i + 2).text.equals("rgb") || tokens.get(i + 2).text.equals("argb"))) {
                ctor = true;
                nameIdx = i + 2;
            }
            if (!ctor) {
                continue;
            }
            boolean isArgb = tokens.get(nameIdx).text.equals("argb");
            int[] args = readColorArgs(tokens, nameIdx + 1);
            if (args == null) {
                continue;
            }
            Token close = tokens.get(args[args.length - 1]);
            if (!isArgb && args.length == 4) {
                out.add(span(source, tokens.get(i), close, clamp(args[0]), clamp(args[1]), clamp(args[2]), 255));
            } else if (isArgb && args.length == 5) {
                out.add(span(source, tokens.get(i), close, clamp(args[1]), clamp(args[2]), clamp(args[3]), clamp(args[0])));
            }
        }
        return out;
    }

    public static Span at(String source, int offset) {
        for (Span s : collect(source)) {
            if (s.contains(offset)) {
                return s;
            }
        }
        return null;
    }

    public static String rewrite(Span span, int r, int g, int b, int a) {
        r = clamp(r);
        g = clamp(g);
        b = clamp(b);
        a = clamp(a);
        String original = span == null ? "" : span.text;
        boolean ui = original.startsWith("ui.");
        boolean colorDot = original.startsWith("Color.");
        if (a < 255) {
            String args = a + ", " + r + ", " + g + ", " + b;
            if (ui) {
                return "ui.argb(" + args + ")";
            }
            if (colorDot || original.contains("Color.")) {
                return "Color.Argb(" + args + ")";
            }
            return "argb(" + args + ")";
        }
        String args = r + ", " + g + ", " + b;
        if (ui) {
            return "ui.rgb(" + args + ")";
        }
        if (colorDot || original.contains("Color.")) {
            return "Color.Rgb(" + args + ")";
        }
        return "rgb(" + args + ")";
    }

    private static Span span(String source, Token start, Token endTok, int r, int g, int b, int a) {
        int s = SourcePos.offset(source, start.line, start.col);
        int e = SourcePos.offset(source, endTok.line, endTok.col) + Math.max(1, endTok.text.length());
        e = Math.min(e, source.length());
        String text = s >= 0 && e > s ? source.substring(s, e) : "";
        return new Span(s, e, r, g, b, a, text);
    }

    /**
     * Returns argument values followed by the index of the closing {@code )} token, or null.
     */
    private static int[] readColorArgs(List<Token> tokens, int i) {
        if (i >= tokens.size() || tokens.get(i).kind != Tok.LParen) {
            return null;
        }
        i++;
        List<Long> args = new ArrayList<>();
        while (i < tokens.size() && tokens.get(i).kind != Tok.RParen) {
            boolean neg = false;
            if (tokens.get(i).kind == Tok.Minus) {
                neg = true;
                i++;
                if (i >= tokens.size()) {
                    return null;
                }
            }
            if (tokens.get(i).kind != Tok.Integer) {
                return null;
            }
            long v = tokens.get(i).number;
            if (neg) {
                v = -v;
            }
            args.add(v);
            i++;
            if (i < tokens.size() && tokens.get(i).kind == Tok.Comma) {
                i++;
            }
        }
        if (i >= tokens.size() || tokens.get(i).kind != Tok.RParen) {
            return null;
        }
        int[] out = new int[args.size() + 1];
        for (int n = 0; n < args.size(); n++) {
            out[n] = args.get(n).intValue();
        }
        out[args.size()] = i;
        return out;
    }

    private static int[] namedCss(String name) {
        return switch (name) {
            case "Black" -> new int[]{0, 0, 0};
            case "White" -> new int[]{255, 255, 255};
            case "Red" -> new int[]{255, 0, 0};
            case "Green" -> new int[]{0, 255, 0};
            case "Blue" -> new int[]{0, 0, 255};
            case "Yellow" -> new int[]{255, 255, 0};
            case "Magenta" -> new int[]{255, 0, 255};
            case "Cyan" -> new int[]{0, 255, 255};
            default -> null;
        };
    }

    private static int clamp(int v) {
        return Math.clamp(v, 0, 255);
    }
}
