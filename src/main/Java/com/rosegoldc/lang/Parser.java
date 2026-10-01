package com.rosegoldc.lang;

import java.util.ArrayList;
import java.util.List;

public final class Parser {

    private static final class ParseError extends RuntimeException {
        ParseError() {
            super(null, null, false, false);
        }
    }

    private static final class FnAttrs {
        boolean isTest;
        boolean isDeprecated;
        boolean isConstexpr;
        boolean isUfcs;
        boolean isOptional;

        boolean any() {
            return isTest || isDeprecated || isConstexpr || isUfcs || isOptional;
        }

        boolean fnLike() {
            return isTest || isDeprecated || isConstexpr || isUfcs;
        }
    }

    private static final class MemberPrefix {
        final FnAttrs attrs = new FnAttrs();
        boolean isAbstract;
        boolean isFinal;
        Vis vis = Vis.Pub;
        boolean sawVis;
    }

    private final List<Token> tokens;
    private final String file;
    private final List<Diagnostic> errors;
    private int i;

    private Parser(List<Token> tokens, String file, List<Diagnostic> errors) {
        this.tokens = tokens;
        this.file = file == null ? "" : file;
        this.errors = errors;
    }

    public static Program parseSource(String source, String file, List<Diagnostic> errors) {
        List<Diagnostic> local = new ArrayList<>();
        List<Diagnostic> out = errors != null ? errors : local;
        Program program = new Parser(Lexer.tokenize(source, file, out), file, out).parse();
        if (errors == null && !local.isEmpty()) {
            throw new LangException(Diagnostic.locatedError(local.getFirst().kind, file,
                    local.getFirst().line, local.getFirst().col, local.getFirst().message), local.getFirst());
        }
        return program;
    }

    public static Expr parseExprSource(String source, String file, List<Diagnostic> errors) {
        List<Diagnostic> local = new ArrayList<>();
        List<Diagnostic> out = errors != null ? errors : local;
        try {
            Parser parser = new Parser(Lexer.tokenize(source, file, out), file, out);
            Expr expr = parser.parseExpr();
            if (!parser.check(Tok.Eof) && !parser.check(Tok.Semi)) {
                parser.errorHere("unexpected tokens after expression");
            }
            parser.match(Tok.Semi);
            if (errors == null && !local.isEmpty()) {
                Diagnostic d = local.getFirst();
                throw new LangException(Diagnostic.locatedError(d.kind.isEmpty() ? "parse error" : d.kind,
                        file, d.line, d.col, d.message), d);
            }
            return expr;
        } catch (ParseError ex) {
            if (errors == null && !local.isEmpty()) {
                Diagnostic d = local.getFirst();
                throw new LangException(Diagnostic.locatedError(d.kind.isEmpty() ? "parse error" : d.kind,
                        file, d.line, d.col, d.message), d);
            }
            if (errors == null) {
                throw new LangException("parse error", null);
            }
            return new Expr();
        }
    }

    private static boolean isComment(Tok k) {
        return k == Tok.LineComment || k == Tok.BlockComment;
    }

    private void discardComments() {
        while (i < tokens.size() && isComment(tokens.get(i).kind)) {
            i++;
        }
    }

    private int nextSignificant(int from) {
        while (from < tokens.size() && isComment(tokens.get(from).kind)) {
            from++;
        }
        return from;
    }

    private List<String> takeLeadingComments() {
        List<String> out = new ArrayList<>();
        while (i < tokens.size() && isComment(tokens.get(i).kind)) {
            out.add(tokens.get(i).text);
            i++;
        }
        return out;
    }

    private Token peek() {
        discardComments();
        return tokens.get(i);
    }

    private Token prev() {
        return tokens.get(i - 1);
    }

    private boolean check(Tok k) {
        return peek().kind == k;
    }

    private boolean match(Tok k) {
        if (!check(k)) {
            return false;
        }
        i++;
        return true;
    }

    private Token advance() {
        discardComments();
        return tokens.get(i++);
    }

    private void record(String msg) {
        Token t = peek();
        Diagnostic d = new Diagnostic(file, t.line, t.col, msg);
        d.kind = "parse error";
        if (errors != null) {
            for (Diagnostic prev : errors) {
                if (prev.file.equals(d.file) && prev.line == d.line && prev.col == d.col
                        && prev.message.equals(d.message)) {
                    return;
                }
            }
            errors.add(d);
        }
    }

    private void errorHere(String msg) {
        record(msg);
        throw new ParseError();
    }

    private void errorNote(String msg) {
        record(msg);
    }

    private void synchronizeStmt() {
        while (!check(Tok.Eof) && !check(Tok.RBrace) && !check(Tok.Semi)) {
            advance();
        }
        if (check(Tok.Semi)) {
            advance();
        }
    }

    private boolean atItemStart() {
        return check(Tok.Function) || check(Tok.Struct) || check(Tok.Data) || check(Tok.Class)
                || check(Tok.Trait) || check(Tok.Enum) || check(Tok.Implements) || check(Tok.Signal)
                || check(Tok.Module) || check(Tok.Import) || check(Tok.From) || check(Tok.At)
                || check(Tok.Pub) || check(Tok.Private) || check(Tok.Protected) || check(Tok.Abstract)
                || check(Tok.Final);
    }

    private void synchronizeItem() {
        if (check(Tok.Eof)) {
            return;
        }
        advance();
        while (!check(Tok.Eof)) {
            if (atItemStart()) {
                return;
            }
            if (check(Tok.RBrace) || check(Tok.Semi)) {
                advance();
                return;
            }
            advance();
        }
    }

    private Token expect(Tok k, String msg) {
        if (!check(k)) {
            errorHere(msg);
        }
        return advance();
    }

    private void expectSemi(Stmt s) {
        Token semi = expect(Tok.Semi, "expected ';'");
        if (i < tokens.size() && isComment(tokens.get(i).kind) && tokens.get(i).line == semi.line) {
            s.trailingComment = tokens.get(i).text;
            i++;
        }
    }

    private String parseType() {
        if (check(Tok.Function)) {
            expect(Tok.Function, "expected 'fn'");
            expect(Tok.LParen, "expected '('");
            List<String> params = new ArrayList<>();
            if (!check(Tok.RParen)) {
                do {
                    params.add(parseType());
                } while (match(Tok.Comma));
            }
            expect(Tok.RParen, "expected ')'");
            String ret = "Void";
            if (match(Tok.Colon)) {
                ret = parseType();
            }
            StringBuilder t = new StringBuilder("fn(");
            for (int n = 0; n < params.size(); n++) {
                if (n > 0) {
                    t.append(", ");
                }
                t.append(params.get(n));
            }
            t.append("):").append(ret);
            return t.toString();
        }
        Token name = expect(Tok.Identifier, "expected type name");
        String t = name.text;
        if (match(Tok.LArrow)) {
            t = wrapArgs(t, parseTypeArgList(Tok.RArrow, "expected '>'"));
        } else if (match(Tok.LBracket)) {
            List<String> args = new ArrayList<>();
            if (!check(Tok.RBracket)) {
                do {
                    args.add(parseType());
                } while (match(Tok.Comma));
            } else {
                errorNote("expected type argument");
            }
            expect(Tok.RBracket, "expected ']' after type argument");
            t = wrapArgs(t, args);
        }
        return t;
    }

    private List<String> parseTypeArgList(Tok close, String closeMsg) {
        List<String> args = new ArrayList<>();
        if (!check(close)) {
            do {
                args.add(parseType());
            } while (match(Tok.Comma));
        }
        expect(close, closeMsg);
        return args;
    }

    private static String wrapArgs(String t, List<String> args) {
        if (args.isEmpty()) {
            return t;
        }
        StringBuilder s = new StringBuilder(t).append('[');
        for (int n = 0; n < args.size(); n++) {
            if (n > 0) {
                s.append(", ");
            }
            s.append(args.get(n));
        }
        return s.append(']').toString();
    }

    private List<TypeParam> parseTypeParams() {
        if (!check(Tok.LBracket) && !check(Tok.LArrow)) {
            return new ArrayList<>();
        }
        boolean angle = match(Tok.LArrow);
        if (!angle) {
            expect(Tok.LBracket, "expected '['");
        }
        List<TypeParam> out = new ArrayList<>();
        if ((angle && check(Tok.RArrow)) || (!angle && check(Tok.RBracket))) {
            errorNote("expected type parameter");
            if (angle) {
                expect(Tok.RArrow, "expected '>'");
            } else {
                expect(Tok.RBracket, "expected ']'");
            }
            return out;
        }
        do {
            Token name = expect(Tok.Identifier, "expected type parameter");
            TypeParam p = new TypeParam();
            p.name = name.text;
            if (match(Tok.Colon)) {
                do {
                    p.bounds.add(parseType());
                } while (match(Tok.Plus));
            }
            for (TypeParam prev : out) {
                if (prev.name.equals(p.name)) {
                    errorNote("duplicate type parameter '" + p.name + "'");
                }
            }
            out.add(p);
        } while (match(Tok.Comma));
        if (angle) {
            expect(Tok.RArrow, "expected '>' after type parameters");
        } else {
            expect(Tok.RBracket, "expected ']' after type parameters");
        }
        return out;
    }

    private String parseOptionalType() {
        if (!match(Tok.Colon)) {
            return "";
        }
        return parseType();
    }

    private Expr make(Expr.Kind kind, int line, int col) {
        Expr e = new Expr();
        e.kind = kind;
        e.line = line;
        e.col = col;
        return e;
    }

    private Expr parsePrimary() {
        Token t = peek();
        if (match(Tok.Integer)) {
            Expr e = make(Expr.Kind.Int, t.line, t.col);
            e.number = t.number;
            return e;
        }
        if (match(Tok.Float)) {
            Expr e = make(Expr.Kind.Float, t.line, t.col);
            e.real = t.real;
            e.text = t.text;
            return e;
        }
        if (match(Tok.String)) {
            Expr e = make(Expr.Kind.String, t.line, t.col);
            e.text = t.text;
            return e;
        }
        if (match(Tok.True) || match(Tok.False)) {
            Expr e = make(Expr.Kind.Bool, t.line, t.col);
            e.booleanValue = t.kind == Tok.True;
            return e;
        }
        if (match(Tok.Identifier) || match(Tok.Super)) {
            Expr e = make(Expr.Kind.Var, t.line, t.col);
            e.text = t.kind == Tok.Super ? "super" : t.text;
            return e;
        }
        if (match(Tok.LParen)) {
            Expr inner = parseExpr();
            expect(Tok.RParen, "expected ')'");
            return inner;
        }
        if (match(Tok.LBracket)) {
            Expr e = make(Expr.Kind.Array, t.line, t.col);
            if (!check(Tok.RBracket)) {
                do {
                    e.kids.add(parseExpr());
                } while (match(Tok.Comma));
            }
            expect(Tok.RBracket, "expected ']' after array");
            return e;
        }
        if (match(Tok.LBrace)) {
            Expr e = make(Expr.Kind.Map, t.line, t.col);
            if (!check(Tok.RBrace)) {
                while (true) {
                    e.kids.add(parseExpr());
                    expect(Tok.Colon, "expected ':' after map key");
                    e.kids.add(parseExpr());
                    if (!match(Tok.Comma)) {
                        break;
                    }
                    if (check(Tok.RBrace)) {
                        break;
                    }
                }
            }
            expect(Tok.RBrace, "expected '}' after map");
            return e;
        }
        if (check(Tok.Async)) {
            int n = nextSignificant(i + 1);
            if (n < tokens.size() && tokens.get(n).kind == Tok.Function) {
                int m = nextSignificant(n + 1);
                if (m < tokens.size()) {
                    Tok k = tokens.get(m).kind;
                    if (k == Tok.LParen || k == Tok.LBracket || k == Tok.LArrow) {
                        return parseLambda();
                    }
                }
            }
        }
        if (check(Tok.Function)) {
            int n = nextSignificant(i + 1);
            if (n < tokens.size()) {
                Tok k = tokens.get(n).kind;
                if (k == Tok.LParen || k == Tok.LBracket || k == Tok.LArrow) {
                    return parseLambda();
                }
            }
        }
        errorHere("expected expression");
        return new Expr();
    }

    private boolean looksLikeGenericApply() {
        if (!check(Tok.LBracket)) {
            return false;
        }
        int j = nextSignificant(i + 1);
        if (j >= tokens.size() || tokens.get(j).kind != Tok.Identifier) {
            return false;
        }
        int depth = 1;
        j++;
        while (j < tokens.size() && depth > 0) {
            Tok k = tokens.get(j).kind;
            if (isComment(k)) {
                j++;
                continue;
            }
            if (k == Tok.LBracket) {
                depth++;
            } else if (k == Tok.RBracket) {
                depth--;
            } else if (k != Tok.Identifier && k != Tok.Comma && k != Tok.LArrow && k != Tok.RArrow) {
                return false;
            }
            j++;
        }
        if (depth != 0 || j >= tokens.size()) {
            return false;
        }
        j = nextSignificant(j);
        return j < tokens.size() && (tokens.get(j).kind == Tok.LBrace || tokens.get(j).kind == Tok.LParen);
    }

    private List<String> parseBracketTypeArgs() {
        expect(Tok.LBracket, "expected '['");
        List<String> args = new ArrayList<>();
        if (check(Tok.RBracket)) {
            errorNote("expected type argument");
        } else {
            do {
                args.add(parseType());
            } while (match(Tok.Comma));
        }
        expect(Tok.RBracket, "expected ']' after type arguments");
        return args;
    }

    private boolean isStructLitStart() {
        if (!check(Tok.LBrace)) {
            return false;
        }
        int a = nextSignificant(i + 1);
        if (a >= tokens.size()) {
            return false;
        }
        if (tokens.get(a).kind == Tok.RBrace) {
            return true;
        }
        int b = nextSignificant(a + 1);
        if (b >= tokens.size()) {
            return false;
        }
        return tokens.get(a).kind == Tok.Identifier && tokens.get(b).kind == Tok.Colon;
    }

    private Expr parseStructLit(Expr typeName) {
        expect(Tok.LBrace, "expected '{'");
        Expr lit = make(Expr.Kind.StructLit, typeName.line, typeName.col);
        lit.text = typeName.text;
        if (!check(Tok.RBrace)) {
            while (true) {
                if (check(Tok.RBrace)) {
                    break;
                }
                Token field = expect(Tok.Identifier, "expected field name");
                expect(Tok.Colon, "expected ':' after field name");
                lit.names.add(field.text);
                lit.kids.add(parseExpr());
                if (!match(Tok.Comma)) {
                    break;
                }
            }
        }
        expect(Tok.RBrace, "expected '}' after struct fields");
        return lit;
    }

    private Expr parseCall() {
        Expr expr = parsePrimary();
        while (true) {
            if (match(Tok.Dot)) {
                Token name = expect(Tok.Identifier, "expected name after '.'");
                List<String> targs = looksLikeGenericApply() ? parseBracketTypeArgs() : new ArrayList<>();
                if (match(Tok.LParen)) {
                    Expr call = make(Expr.Kind.MethodCall, name.line, name.col);
                    call.text = name.text;
                    call.typeArgs.addAll(targs);
                    call.kids.add(expr);
                    if (!check(Tok.RParen)) {
                        do {
                            call.kids.add(parseExpr());
                        } while (match(Tok.Comma));
                    }
                    expect(Tok.RParen, "expected ')'");
                    expr = call;
                } else if (!targs.isEmpty()) {
                    errorHere("expected '(' after type arguments");
                } else {
                    Expr mem = make(Expr.Kind.Member, name.line, name.col);
                    mem.text = name.text;
                    mem.kids.add(expr);
                    expr = mem;
                }
            } else if ((expr.kind == Expr.Kind.Var || expr.kind == Expr.Kind.Lambda) && looksLikeGenericApply()) {
                List<String> args = parseBracketTypeArgs();
                if (expr.kind == Expr.Kind.Var && isStructLitStart()) {
                    Expr lit = parseStructLit(expr);
                    lit.typeArgs.addAll(args);
                    expr = lit;
                } else if (match(Tok.LParen)) {
                    Expr call = make(Expr.Kind.Call, expr.line, expr.col);
                    call.typeArgs.addAll(args);
                    if (expr.kind == Expr.Kind.Var) {
                        call.text = expr.text;
                    } else {
                        call.kids.add(expr);
                    }
                    if (!check(Tok.RParen)) {
                        do {
                            call.kids.add(parseExpr());
                        } while (match(Tok.Comma));
                    }
                    expect(Tok.RParen, "expected ')'");
                    expr = call;
                } else {
                    errorHere("expected '(' or '{' after type arguments");
                }
            } else if (match(Tok.LParen)) {
                Expr call = make(Expr.Kind.Call, expr.line, expr.col);
                if (expr.kind == Expr.Kind.Var) {
                    call.text = expr.text;
                } else {
                    call.kids.add(expr);
                }
                if (!check(Tok.RParen)) {
                    do {
                        call.kids.add(parseExpr());
                    } while (match(Tok.Comma));
                }
                expect(Tok.RParen, "expected ')'");
                expr = call;
            } else if (match(Tok.LBracket)) {
                Expr idx = make(Expr.Kind.Index, expr.line, expr.col);
                idx.kids.add(expr);
                idx.kids.add(parseExpr());
                expect(Tok.RBracket, "expected ']' after index");
                expr = idx;
            } else if (expr.kind == Expr.Kind.Var && isStructLitStart()) {
                expr = parseStructLit(expr);
            } else {
                break;
            }
        }
        return expr;
    }

    private Expr parseUnary() {
        if (match(Tok.Await)) {
            Token op = prev();
            Expr e = make(Expr.Kind.Await, op.line, op.col);
            e.kids.add(parseUnary());
            return e;
        }
        if (match(Tok.Try)) {
            Token op = prev();
            Expr e = make(Expr.Kind.Try, op.line, op.col);
            e.kids.add(parseUnary());
            return e;
        }
        if (match(Tok.Minus) || match(Tok.Bang)) {
            Token op = prev();
            Expr e = make(Expr.Kind.Unary, op.line, op.col);
            e.text = op.text;
            e.kids.add(parseUnary());
            return e;
        }
        return parseCall();
    }

    private Expr binLoop(Expr left, Tok[] ops, java.util.function.Supplier<Expr> rhs) {
        while (true) {
            boolean hit = false;
            for (Tok op : ops) {
                if (match(op)) {
                    hit = true;
                    break;
                }
            }
            if (!hit) {
                return left;
            }
            Token op = prev();
            Expr e = make(Expr.Kind.Binary, op.line, op.col);
            e.text = op.text;
            e.kids.add(left);
            e.kids.add(rhs.get());
            left = e;
        }
    }

    private Expr parseFactor() {
        return binLoop(parseUnary(), new Tok[]{Tok.Star, Tok.Slash, Tok.Percent}, this::parseUnary);
    }

    private Expr parseTerm() {
        return binLoop(parseFactor(), new Tok[]{Tok.Plus, Tok.Minus}, this::parseFactor);
    }

    private Expr parseComparison() {
        return binLoop(parseTerm(), new Tok[]{Tok.LArrow, Tok.RArrow, Tok.LtEq, Tok.GtEq}, this::parseTerm);
    }

    private Expr parseEquality() {
        return binLoop(parseComparison(), new Tok[]{Tok.EqEq, Tok.NotEq}, this::parseComparison);
    }

    private Expr parseAnd() {
        return binLoop(parseEquality(), new Tok[]{Tok.AndAnd}, this::parseEquality);
    }

    private Expr parseOr() {
        return binLoop(parseAnd(), new Tok[]{Tok.OrOr}, this::parseAnd);
    }

    private Expr parseRange() {
        Expr left = parseOr();
        if (match(Tok.DotDot) || match(Tok.DotDotEq)) {
            Token op = prev();
            Expr e = make(Expr.Kind.Range, op.line, op.col);
            e.booleanValue = op.kind == Tok.DotDotEq;
            e.text = op.text;
            e.kids.add(left);
            e.kids.add(parseOr());
            return e;
        }
        return left;
    }

    private Expr parseExpr() {
        return parseRange();
    }

    private List<Stmt> parseBlock() {
        expect(Tok.LBrace, "expected '{'");
        List<Stmt> stmts = new ArrayList<>();
        while (true) {
            List<String> leading = takeLeadingComments();
            if (check(Tok.RBrace) || check(Tok.Eof)) {
                if (!leading.isEmpty()) {
                    Stmt c = new Stmt();
                    c.kind = Stmt.Kind.Comment;
                    c.leadingComments.addAll(leading);
                    stmts.add(c);
                }
                break;
            }
            try {
                Stmt s = parseStmt();
                s.leadingComments.addAll(leading);
                stmts.add(s);
            } catch (ParseError ex) {
                synchronizeStmt();
            }
        }
        expect(Tok.RBrace, "expected '}'");
        return stmts;
    }

    private Stmt parseIfAt(int line, int col) {
        Stmt s = new Stmt();
        s.kind = Stmt.Kind.If;
        s.line = line;
        s.col = col;
        s.expr = parseExpr();
        s.body.addAll(parseBlock());
        if (match(Tok.Elif)) {
            Token el = prev();
            s.elseBody.add(parseIfAt(el.line, el.col));
        } else if (match(Tok.Else)) {
            s.elseBody.addAll(parseBlock());
        }
        return s;
    }

    private static boolean isAssignTok(Tok k) {
        return k == Tok.Eq || k == Tok.PlusEq || k == Tok.MinusEq || k == Tok.StarEq || k == Tok.SlashEq;
    }

    private static String assignText(Tok k) {
        return switch (k) {
            case PlusEq -> "+=";
            case MinusEq -> "-=";
            case StarEq -> "*=";
            case SlashEq -> "/=";
            default -> "=";
        };
    }

    private boolean matchAssignOp(String[] op) {
        if (!isAssignTok(peek().kind)) {
            return false;
        }
        op[0] = assignText(peek().kind);
        advance();
        return true;
    }

    private Stmt parseStmt() {
        Token t = peek();
        if (match(Tok.If)) {
            return parseIfAt(t.line, t.col);
        }
        if (match(Tok.Do)) {
            Stmt s = new Stmt();
            s.kind = Stmt.Kind.Do;
            s.line = t.line;
            s.col = t.col;
            s.body.addAll(parseBlock());
            if (match(Tok.Catch)) {
                s.name = expect(Tok.Identifier, "expected catch binding").text;
                s.elseBody.addAll(parseBlock());
            }
            return s;
        }
        if (match(Tok.Throw)) {
            Stmt s = new Stmt();
            s.kind = Stmt.Kind.Throw;
            s.line = t.line;
            s.col = t.col;
            s.expr = parseExpr();
            expectSemi(s);
            return s;
        }
        if (match(Tok.While)) {
            Stmt s = new Stmt();
            s.kind = Stmt.Kind.While;
            s.line = t.line;
            s.col = t.col;
            s.expr = parseExpr();
            s.body.addAll(parseBlock());
            return s;
        }
        if (match(Tok.For)) {
            Stmt s = new Stmt();
            s.kind = Stmt.Kind.For;
            s.line = t.line;
            s.col = t.col;
            s.name = expect(Tok.Identifier, "expected loop variable").text;
            expect(Tok.In, "expected 'in' after loop variable");
            s.expr = parseExpr();
            s.body.addAll(parseBlock());
            return s;
        }
        if (check(Tok.Match) || check(Tok.Switch)) {
            return parseMatchStmt();
        }
        if (match(Tok.Return)) {
            Stmt s = new Stmt();
            s.kind = Stmt.Kind.Return;
            s.line = t.line;
            s.col = t.col;
            if (!check(Tok.Semi)) {
                s.expr = parseExpr();
                s.hasExpr = true;
            }
            expectSemi(s);
            return s;
        }
        if (match(Tok.Pass) || match(Tok.Break) || match(Tok.Continue)) {
            Stmt s = new Stmt();
            Tok k = prev().kind;
            s.kind = k == Tok.Pass ? Stmt.Kind.Pass : k == Tok.Break ? Stmt.Kind.Break : Stmt.Kind.Continue;
            s.line = t.line;
            s.col = t.col;
            expectSemi(s);
            return s;
        }
        if (match(Tok.Variable) || match(Tok.Constant)) {
            boolean isConst = prev().kind == Tok.Constant;
            Token name = expect(Tok.Identifier, "expected variable name");
            String ty = parseOptionalType();
            expect(Tok.Eq, "expected '='");
            Stmt s = new Stmt();
            s.kind = isConst ? Stmt.Kind.Const : Stmt.Kind.Var;
            s.name = name.text;
            s.typeName = ty;
            s.expr = parseExpr();
            s.line = name.line;
            s.col = name.col;
            expectSemi(s);
            return s;
        }
        if (check(Tok.Identifier)) {
            int n = nextSignificant(i + 1);
            if (n < tokens.size() && isAssignTok(tokens.get(n).kind)) {
                Token name = advance();
                Tok opTok = advance().kind;
                Stmt s = new Stmt();
                s.kind = Stmt.Kind.Assign;
                s.name = name.text;
                s.op = assignText(opTok);
                s.expr = parseExpr();
                s.line = name.line;
                s.col = name.col;
                expectSemi(s);
                return s;
            }
        }
        Stmt s = new Stmt();
        s.kind = Stmt.Kind.Expr;
        s.expr = parseExpr();
        s.line = s.expr.line;
        s.col = s.expr.col;
        String[] op = {""};
        if (s.expr.kind == Expr.Kind.Member && matchAssignOp(op)) {
            s.kind = Stmt.Kind.FieldAssign;
            s.name = s.expr.text;
            s.op = op[0];
            s.target = s.expr.kids.getFirst();
            s.expr = parseExpr();
        } else if (s.expr.kind == Expr.Kind.Index && matchAssignOp(op)) {
            s.kind = Stmt.Kind.IndexAssign;
            s.op = op[0];
            s.target = s.expr;
            s.expr = parseExpr();
        }
        expectSemi(s);
        return s;
    }

    private MatchArm parseMatchArm() {
        MatchArm arm = new MatchArm();
        arm.line = peek().line;
        arm.col = peek().col;
        if (check(Tok.Integer)) {
            arm.pat = MatchArm.Pat.Int;
            arm.number = advance().number;
        } else if (check(Tok.Float)) {
            arm.pat = MatchArm.Pat.Float;
            Token ft = advance();
            arm.real = ft.real;
            arm.text = ft.text;
        } else if (check(Tok.String)) {
            arm.pat = MatchArm.Pat.String;
            arm.text = advance().text;
        } else if (match(Tok.True) || match(Tok.False)) {
            arm.pat = MatchArm.Pat.Bool;
            arm.booleanValue = prev().kind == Tok.True;
        } else {
            Token name = expect(Tok.Identifier, "expected match pattern");
            if (name.text.equals("_")) {
                arm.pat = MatchArm.Pat.Wildcard;
            } else {
                arm.pat = MatchArm.Pat.Variant;
                arm.name = name.text;
                while (match(Tok.Dot)) {
                    arm.name = expect(Tok.Identifier, "expected name after '.'").text;
                }
                if (match(Tok.LParen)) {
                    if (!check(Tok.RParen)) {
                        while (true) {
                            if (check(Tok.Identifier)) {
                                int n = nextSignificant(i + 1);
                                if (n < tokens.size() && tokens.get(n).kind == Tok.Colon) {
                                    arm.fieldNames.add(advance().text);
                                    expect(Tok.Colon, "expected ':' after field name");
                                    arm.binds.add(expect(Tok.Identifier, "expected binding name").text);
                                } else {
                                    arm.fieldNames.add("");
                                    arm.binds.add(expect(Tok.Identifier, "expected binding name").text);
                                }
                            } else {
                                arm.fieldNames.add("");
                                arm.binds.add(expect(Tok.Identifier, "expected binding name").text);
                            }
                            if (!match(Tok.Comma)) {
                                break;
                            }
                            if (check(Tok.RParen)) {
                                break;
                            }
                        }
                    }
                    expect(Tok.RParen, "expected ')' after pattern binding");
                }
            }
        }
        arm.body.addAll(parseBlock());
        return arm;
    }

    private Stmt parseMatchStmt() {
        Token kw = advance();
        Stmt s = new Stmt();
        s.kind = Stmt.Kind.Match;
        s.line = kw.line;
        s.col = kw.col;
        s.expr = parseExpr();
        expect(Tok.LBrace, "expected '{' before match arms");
        while (!check(Tok.RBrace) && !check(Tok.Eof)) {
            s.arms.add(parseMatchArm());
        }
        expect(Tok.RBrace, "expected '}' after match arms");
        match(Tok.Semi);
        return s;
    }

    private EnumDecl parseEnum() {
        Token enumTok = expect(Tok.Enum, "expected 'enum'");
        Token name = expect(Tok.Identifier, "expected enum name");
        EnumDecl e = new EnumDecl();
        e.name = name.text;
        e.line = enumTok.line;
        if (match(Tok.LArrow)) {
            int depth = 1;
            while (depth > 0 && !check(Tok.Eof)) {
                if (match(Tok.LArrow)) {
                    depth++;
                } else if (match(Tok.RArrow)) {
                    depth--;
                } else {
                    advance();
                }
            }
        }
        expect(Tok.LBrace, "expected '{'");
        while (!check(Tok.RBrace) && !check(Tok.Eof)) {
            match(Tok.Pub);
            Token vname = expect(Tok.Identifier, "expected variant name");
            EnumVariant v = new EnumVariant();
            v.name = vname.text;
            if (match(Tok.LParen)) {
                if (!check(Tok.RParen)) {
                    while (true) {
                        if (check(Tok.Identifier)) {
                            int n = nextSignificant(i + 1);
                            if (n < tokens.size() && tokens.get(n).kind == Tok.Colon) {
                                v.fieldNames.add(advance().text);
                                expect(Tok.Colon, "expected ':' after field name");
                                parseType();
                            } else {
                                v.fieldNames.add("");
                                parseType();
                            }
                        } else {
                            v.fieldNames.add("");
                            parseType();
                        }
                        v.arity++;
                        if (!match(Tok.Comma)) {
                            break;
                        }
                        if (check(Tok.RParen)) {
                            break;
                        }
                    }
                }
                expect(Tok.RParen, "expected ')' after variant types");
            }
            for (EnumVariant prev : e.variants) {
                if (prev.name.equals(v.name)) {
                    errorNote("duplicate variant '" + v.name + "'");
                }
            }
            e.variants.add(v);
            if (!match(Tok.Comma)) {
                break;
            }
        }
        expect(Tok.RBrace, "expected '}' after enum variants");
        return e;
    }

    private FnDecl parseFn(FnAttrs attrs, boolean isPub, boolean abstractMethod) {
        boolean isAsync = match(Tok.Async);
        Token fnTok = expect(Tok.Function, "expected 'fn'");
        Token name = expect(Tok.Identifier, "expected function name");
        FnDecl fn = new FnDecl();
        fn.name = name.text;
        fn.isTest = attrs.isTest;
        fn.isDeprecated = attrs.isDeprecated;
        fn.isConstexpr = attrs.isConstexpr;
        fn.isUfcs = attrs.isUfcs;
        fn.isPub = isPub;
        fn.isAbstract = abstractMethod;
        fn.isAsync = isAsync;
        fn.line = fnTok.line;
        fn.typeParams.addAll(parseTypeParams());
        fillFnSig(fn);
        if (abstractMethod) {
            if (isAsync) {
                errorNote("abstract methods cannot be async");
            }
            expect(Tok.Semi, "expected ';' after abstract method");
            return fn;
        }
        fn.body.addAll(parseBlock());
        return fn;
    }

    private void fillFnSig(FnDecl fn) {
        expect(Tok.LParen, "expected '('");
        if (!check(Tok.RParen)) {
            do {
                Token param = expect(Tok.Identifier, "expected parameter name");
                fn.paramTypes.add(parseOptionalType());
                fn.params.add(param.text);
            } while (match(Tok.Comma));
        }
        expect(Tok.RParen, "expected ')'");
        fn.throwsEx = match(Tok.Throws);
        fn.returnType = parseOptionalType();
    }

    private Expr parseLambda() {
        boolean isAsync = match(Tok.Async);
        Token fnTok = expect(Tok.Function, "expected 'fn'");
        FnDecl fn = new FnDecl();
        fn.name = "<fn>";
        fn.line = fnTok.line;
        fn.isAsync = isAsync;
        fn.typeParams.addAll(parseTypeParams());
        fillFnSig(fn);
        fn.body.addAll(parseBlock());
        Expr e = make(Expr.Kind.Lambda, fnTok.line, fnTok.col);
        e.lambda = fn;
        return e;
    }

    private void bindSelf(FnDecl fn) {
        if (fn.params.isEmpty() || !fn.params.getFirst().equals("self")) {
            fn.params.addFirst("self");
            fn.paramTypes.addFirst("");
        }
    }

    private void checkMethodName(List<String> fields, List<FnDecl> methods, String name) {
        for (String f : fields) {
            if (f.equals(name)) {
                errorNote("method '" + name + "' conflicts with field '" + name + "'");
            }
        }
        for (FnDecl m : methods) {
            if (m.name.equals(name)) {
                errorNote("duplicate method '" + name + "'");
            }
        }
    }

    private void checkSignalDecl(List<String> fields, List<FnDecl> methods, List<SignalDecl> signals, String name) {
        for (String f : fields) {
            if (f.equals(name)) {
                errorNote("signal '" + name + "' conflicts with field '" + name + "'");
            }
        }
        for (FnDecl m : methods) {
            if (m.name.equals(name)) {
                errorNote("signal '" + name + "' conflicts with method '" + name + "'");
            }
        }
        for (SignalDecl s : signals) {
            if (s.name.equals(name)) {
                errorNote("duplicate signal '" + name + "'");
            }
        }
    }

    private boolean parseOneAttr(FnAttrs attrs) {
        if (!match(Tok.At)) {
            return false;
        }
        Token attr = expect(Tok.Identifier, "expected attribute name");
        switch (attr.text) {
            case "test" -> attrs.isTest = true;
            case "deprecated" -> attrs.isDeprecated = true;
            case "constexpr" -> attrs.isConstexpr = true;
            case "ufcs" -> attrs.isUfcs = true;
            case "optional" -> attrs.isOptional = true;
            default -> errorNote("unknown attribute @" + attr.text);
        }
        return true;
    }

    private void rejectMethodAttrs(FnAttrs attrs) {
        if (attrs.isTest) {
            errorNote("@test cannot apply to method");
        }
        if (attrs.isUfcs) {
            errorNote("@ufcs cannot apply to method");
        }
        if (attrs.isOptional) {
            errorNote("@optional cannot apply to method");
        }
    }

    private void rejectClassMods(boolean isAbstract, boolean isFinal, String what) {
        if (isAbstract) {
            errorNote("abstract cannot apply to " + what);
        }
        if (isFinal) {
            errorNote("final cannot apply to " + what);
        }
    }

    private void rejectProtected(boolean isProtected, String what) {
        if (isProtected) {
            errorNote("protected cannot apply to " + what);
        }
    }

    private void takeVis(MemberPrefix p, Vis v) {
        if (p.sawVis) {
            errorNote("cannot combine visibility modifiers");
        }
        p.sawVis = true;
        p.vis = v;
    }

    private void parseMemberPrefix(MemberPrefix p) {
        while (true) {
            if (parseOneAttr(p.attrs)) {
                continue;
            }
            if (match(Tok.Pub)) {
                takeVis(p, Vis.Pub);
                continue;
            }
            if (match(Tok.Private)) {
                takeVis(p, Vis.Private);
                continue;
            }
            if (match(Tok.Protected)) {
                takeVis(p, Vis.Protected);
                continue;
            }
            if (match(Tok.Abstract)) {
                p.isAbstract = true;
                continue;
            }
            if (match(Tok.Final)) {
                p.isFinal = true;
                continue;
            }
            break;
        }
    }

    private FnDecl parseMethod(boolean traitImpl) {
        MemberPrefix p = new MemberPrefix();
        parseMemberPrefix(p);
        rejectClassMods(p.isAbstract, p.isFinal, "impl method");
        if (traitImpl && p.vis != Vis.Pub) {
            errorNote("trait impl methods cannot be private or protected");
        }
        rejectMethodAttrs(p.attrs);
        FnDecl fn = parseFn(p.attrs, true, false);
        fn.vis = p.vis;
        bindSelf(fn);
        return fn;
    }

    private StructDecl parseStruct(boolean isData) {
        Token tok = isData ? expect(Tok.Data, "expected 'data'") : expect(Tok.Struct, "expected 'struct'");
        Token name = expect(Tok.Identifier, isData ? "expected data name" : "expected struct name");
        StructDecl s = new StructDecl();
        s.name = name.text;
        s.line = tok.line;
        s.isData = isData;
        s.typeParams.addAll(parseTypeParams());
        String kind = isData ? "data" : "struct";
        if (match(Tok.Implements)) {
            do {
                s.implTraits.add(parseType());
            } while (match(Tok.Comma));
        }
        expect(Tok.LBrace, "expected '{'");
        while (!check(Tok.RBrace) && !check(Tok.Eof)) {
            try {
                MemberPrefix p = new MemberPrefix();
                parseMemberPrefix(p);
                if (check(Tok.Function) || check(Tok.Async)) {
                    rejectClassMods(p.isAbstract, p.isFinal, kind + " method");
                    if (p.vis == Vis.Protected) {
                        errorNote("protected cannot apply to " + kind + " method");
                    }
                    rejectMethodAttrs(p.attrs);
                    FnDecl fn = parseFn(p.attrs, true, false);
                    fn.vis = p.vis;
                    bindSelf(fn);
                    checkMethodName(s.fields, s.methods, fn.name);
                    for (SignalDecl sig : s.signals) {
                        if (sig.name.equals(fn.name)) {
                            errorNote("method '" + fn.name + "' conflicts with signal '" + fn.name + "'");
                        }
                    }
                    s.methods.add(fn);
                    continue;
                }
                if (check(Tok.Signal)) {
                    rejectClassMods(p.isAbstract, p.isFinal, "signal");
                    rejectProtected(p.vis == Vis.Protected, "signal");
                    if (p.attrs.any()) {
                        errorNote("attributes cannot apply to signal");
                    }
                    if (isData) {
                        errorNote("data cannot declare signals");
                    }
                    SignalDecl sig = parseSignal();
                    checkSignalDecl(s.fields, s.methods, s.signals, sig.name);
                    if (!isData) {
                        s.signals.add(sig);
                    }
                    continue;
                }
                rejectClassMods(p.isAbstract, p.isFinal, "field");
                if (p.vis == Vis.Protected) {
                    errorNote("protected cannot apply to " + kind + " field");
                }
                if (p.attrs.fnLike()) {
                    errorNote("attributes cannot apply to field");
                }
                Token field = expect(Tok.Identifier, "expected field name");
                String ty = parseOptionalType();
                expect(Tok.Semi, "expected ';'");
                for (String f : s.fields) {
                    if (f.equals(field.text)) {
                        errorNote("duplicate field '" + field.text + "'");
                    }
                }
                for (FnDecl m : s.methods) {
                    if (m.name.equals(field.text)) {
                        errorNote("field '" + field.text + "' conflicts with method '" + field.text + "'");
                    }
                }
                for (SignalDecl sig : s.signals) {
                    if (sig.name.equals(field.text)) {
                        errorNote("field '" + field.text + "' conflicts with signal '" + field.text + "'");
                    }
                }
                s.fields.add(field.text);
                s.fieldTypes.add(ty);
                s.fieldOptional.add(p.attrs.isOptional);
                s.fieldVis.add(p.vis);
            } catch (ParseError ex) {
                synchronizeStmt();
            }
        }
        expect(Tok.RBrace, "expected '}'");
        return s;
    }

    private ImplDecl parseImpl() {
        Token implTok = expect(Tok.Implements, "expected 'impl'");
        ImplDecl impl = new ImplDecl();
        impl.line = implTok.line;
        impl.typeParams.addAll(parseTypeParams());
        String first = parseType();
        if (match(Tok.For)) {
            impl.traitName = first;
            impl.typeName = parseType();
        } else {
            impl.typeName = first;
        }
        expect(Tok.LBrace, "expected '{'");
        while (!check(Tok.RBrace) && !check(Tok.Eof)) {
            try {
                FnDecl fn = parseMethod(!impl.traitName.isEmpty());
                checkMethodName(List.of(), impl.methods, fn.name);
                impl.methods.add(fn);
            } catch (ParseError ex) {
                synchronizeStmt();
            }
        }
        expect(Tok.RBrace, "expected '}'");
        return impl;
    }

    private TraitMethod parseTraitMethod() {
        Token fnTok = expect(Tok.Function, "expected 'fn'");
        Token name = expect(Tok.Identifier, "expected function name");
        expect(Tok.LParen, "expected '('");
        TraitMethod m = new TraitMethod();
        m.name = name.text;
        m.line = fnTok.line;
        if (!check(Tok.RParen)) {
            do {
                Token param = expect(Tok.Identifier, "expected parameter name");
                m.paramTypes.add(parseOptionalType());
                m.params.add(param.text);
            } while (match(Tok.Comma));
        }
        expect(Tok.RParen, "expected ')'");
        m.throwsEx = match(Tok.Throws);
        m.returnType = parseOptionalType();
        expect(Tok.Semi, "expected ';' after trait method (signatures only)");
        if (m.params.isEmpty() || !m.params.getFirst().equals("self")) {
            m.params.addFirst("self");
            m.paramTypes.addFirst("");
        }
        return m;
    }

    private TraitDecl parseTrait() {
        Token traitTok = expect(Tok.Trait, "expected 'trait'");
        Token name = expect(Tok.Identifier, "expected trait name");
        TraitDecl t = new TraitDecl();
        t.name = name.text;
        t.line = traitTok.line;
        t.typeParams.addAll(parseTypeParams());
        expect(Tok.LBrace, "expected '{'");
        while (!check(Tok.RBrace) && !check(Tok.Eof)) {
            try {
                match(Tok.Pub);
                if (check(Tok.Signal)) {
                    t.signals.add(parseSignal());
                    continue;
                }
                if (check(Tok.Function)) {
                    TraitMethod m = parseTraitMethod();
                    for (TraitMethod prev : t.methods) {
                        if (prev.name.equals(m.name)) {
                            errorNote("duplicate method '" + m.name + "'");
                        }
                    }
                    t.methods.add(m);
                    continue;
                }
                if (check(Tok.Variable) || check(Tok.Constant)) {
                    errorNote("traits cannot declare vars or consts");
                    synchronizeStmt();
                    continue;
                }
                errorHere("expected fn signature or signal in trait");
            } catch (ParseError ex) {
                synchronizeStmt();
            }
        }
        expect(Tok.RBrace, "expected '}'");
        return t;
    }

    private ClassDecl parseClass() {
        Token classTok = expect(Tok.Class, "expected 'class'");
        Token name = expect(Tok.Identifier, "expected class name");
        ClassDecl c = new ClassDecl();
        c.name = name.text;
        c.line = classTok.line;
        c.typeParams.addAll(parseTypeParams());
        if (match(Tok.Extends)) {
            c.parent = parseType();
        }
        if (match(Tok.Implements)) {
            do {
                c.implTraits.add(parseType());
            } while (match(Tok.Comma));
        }
        expect(Tok.LBrace, "expected '{'");
        List<String> fieldNames = new ArrayList<>();
        while (!check(Tok.RBrace) && !check(Tok.Eof)) {
            try {
                MemberPrefix p = new MemberPrefix();
                parseMemberPrefix(p);
                if (check(Tok.Constant)) {
                    errorNote("class const is not v1 (use a module-level const)");
                    synchronizeStmt();
                    continue;
                }
                if (check(Tok.Implements)) {
                    if (p.attrs.any()) {
                        errorNote("attributes cannot apply to impl");
                    }
                    rejectClassMods(p.isAbstract, p.isFinal, "impl");
                    if (p.sawVis) {
                        errorNote("visibility cannot apply to impl");
                    }
                    expect(Tok.Implements, "expected 'impl'");
                    NestedImpl block = new NestedImpl();
                    block.typeParams.addAll(parseTypeParams());
                    block.traitName = parseType();
                    if (match(Tok.For)) {
                        errorNote("impl inside a class is `impl Trait { … }` (no `for`)");
                    }
                    expect(Tok.LBrace, "expected '{' after trait name");
                    while (!check(Tok.RBrace) && !check(Tok.Eof)) {
                        FnDecl fn = parseMethod(true);
                        checkMethodName(fieldNames, c.methods, fn.name);
                        for (NestedImpl b : c.traitImpls) {
                            checkMethodName(List.of(), b.methods, fn.name);
                        }
                        block.methods.add(fn);
                    }
                    expect(Tok.RBrace, "expected '}'");
                    c.traitImpls.add(block);
                    continue;
                }
                if (check(Tok.Function) || check(Tok.Async)) {
                    rejectMethodAttrs(p.attrs);
                    if (p.isAbstract && p.isFinal) {
                        errorNote("method cannot be both abstract and final");
                    }
                    if (p.isAbstract && p.attrs.isConstexpr) {
                        errorNote("@constexpr cannot apply to abstract method");
                    }
                    FnDecl fn = parseFn(p.attrs, true, p.isAbstract);
                    fn.isFinal = p.isFinal;
                    fn.vis = p.vis;
                    bindSelf(fn);
                    checkMethodName(fieldNames, c.methods, fn.name);
                    for (NestedImpl b : c.traitImpls) {
                        checkMethodName(List.of(), b.methods, fn.name);
                    }
                    for (SignalDecl sig : c.signals) {
                        if (sig.name.equals(fn.name)) {
                            errorNote("method '" + fn.name + "' conflicts with signal '" + fn.name + "'");
                        }
                    }
                    c.methods.add(fn);
                    continue;
                }
                if (check(Tok.Signal)) {
                    rejectClassMods(p.isAbstract, p.isFinal, "signal");
                    rejectProtected(p.vis == Vis.Protected, "signal");
                    if (p.attrs.any()) {
                        errorNote("attributes cannot apply to signal");
                    }
                    SignalDecl sig = parseSignal();
                    checkSignalDecl(fieldNames, c.methods, c.signals, sig.name);
                    for (NestedImpl b : c.traitImpls) {
                        for (FnDecl m : b.methods) {
                            if (m.name.equals(sig.name)) {
                                errorNote("signal '" + sig.name + "' conflicts with method '" + sig.name + "'");
                            }
                        }
                    }
                    c.signals.add(sig);
                    continue;
                }
                if (!check(Tok.Variable)) {
                    errorHere("expected var, fn, signal, or impl in class body");
                }
                rejectClassMods(p.isAbstract, p.isFinal, "field");
                if (p.attrs.fnLike()) {
                    errorNote("attributes cannot apply to field");
                }
                expect(Tok.Variable, "expected 'var'");
                Token field = expect(Tok.Identifier, "expected field name");
                ClassField f = new ClassField();
                f.name = field.text;
                f.line = field.line;
                f.col = field.col;
                f.type = parseOptionalType();
                f.optional = p.attrs.isOptional;
                f.vis = p.vis;
                if (match(Tok.Eq)) {
                    f.hasDefault = true;
                    f.defaultValue = parseExpr();
                }
                expect(Tok.Semi, "expected ';'");
                for (String prev : fieldNames) {
                    if (prev.equals(f.name)) {
                        errorNote("duplicate field '" + f.name + "'");
                    }
                }
                checkMethodName(fieldNames, c.methods, f.name);
                for (NestedImpl b : c.traitImpls) {
                    for (FnDecl m : b.methods) {
                        if (m.name.equals(f.name)) {
                            errorNote("field '" + f.name + "' conflicts with method '" + f.name + "'");
                        }
                    }
                }
                for (SignalDecl sig : c.signals) {
                    if (sig.name.equals(f.name)) {
                        errorNote("field '" + f.name + "' conflicts with signal '" + f.name + "'");
                    }
                }
                fieldNames.add(f.name);
                c.fields.add(f);
            } catch (ParseError ex) {
                synchronizeStmt();
            }
        }
        expect(Tok.RBrace, "expected '}'");
        c.shape.name = c.name;
        c.shape.line = c.line;
        c.shape.typeParams.addAll(c.typeParams);
        for (ClassField f : c.fields) {
            c.shape.fields.add(f.name);
            c.shape.fieldTypes.add(f.type);
            c.shape.fieldOptional.add(f.optional);
        }
        c.shape.signals.addAll(c.signals);
        return c;
    }

    private SignalDecl parseSignal() {
        Token sigTok = expect(Tok.Signal, "expected 'signal'");
        Token name = expect(Tok.Identifier, "expected signal name");
        expect(Tok.LParen, "expected '('");
        SignalDecl sig = new SignalDecl();
        sig.name = name.text;
        sig.line = sigTok.line;
        if (!check(Tok.RParen)) {
            do {
                Token param = expect(Tok.Identifier, "expected parameter name");
                parseOptionalType();
                sig.params.add(param.text);
            } while (match(Tok.Comma));
        }
        expect(Tok.RParen, "expected ')'");
        expect(Tok.Semi, "expected ';'");
        return sig;
    }

    private ImportDecl parseImport() {
        ImportDecl d = new ImportDecl();
        d.line = peek().line;
        d.col = peek().col;
        if (match(Tok.From)) {
            d.isFrom = true;
            Token mod = expect(Tok.Identifier, "expected module name");
            expect(Tok.Import, "expected 'import' after module name");
            Token item = expect(Tok.Identifier, "expected imported name");
            d.path.add(mod.text);
            d.path.add(item.text);
            while (match(Tok.Dot)) {
                d.path.add(expect(Tok.Identifier, "expected name after '.'").text);
            }
            if (match(Tok.As)) {
                d.alias = expect(Tok.Identifier, "expected alias").text;
            }
            expect(Tok.Semi, "expected ';' after import");
            return d;
        }
        expect(Tok.Import, "expected 'import'");
        d.path.add(expect(Tok.Identifier, "expected module name").text);
        while (match(Tok.Dot)) {
            d.path.add(expect(Tok.Identifier, "expected module name after '.'").text);
        }
        if (match(Tok.As)) {
            d.alias = expect(Tok.Identifier, "expected alias").text;
        }
        expect(Tok.Semi, "expected ';' after import");
        return d;
    }

    private void parseItem(Program prog, ModDecl mod) {
        boolean inMod = mod != null;
        List<String> leading = takeLeadingComments();
        java.util.function.BiConsumer<ItemKind, Integer> note = (kind, index) -> {
            OrderedItem item = new OrderedItem();
            item.kind = kind;
            item.index = index;
            item.leadingComments.addAll(leading);
            leading.clear();
            if (inMod) {
                mod.items.add(item);
            } else {
                prog.items.add(item);
            }
        };
        boolean isPub = !inMod;
        boolean isAbstract = false;
        boolean isFinal = false;
        boolean isProtected = false;
        boolean sawVis = false;
        FnAttrs attrs = new FnAttrs();
        while (true) {
            if (parseOneAttr(attrs)) {
                continue;
            }
            if (match(Tok.Pub)) {
                if (sawVis) {
                    errorNote("cannot combine visibility modifiers");
                }
                sawVis = true;
                isPub = true;
                isProtected = false;
                continue;
            }
            if (match(Tok.Private)) {
                if (sawVis) {
                    errorNote("cannot combine visibility modifiers");
                }
                sawVis = true;
                isPub = false;
                isProtected = false;
                continue;
            }
            if (match(Tok.Protected)) {
                if (sawVis) {
                    errorNote("cannot combine visibility modifiers");
                }
                sawVis = true;
                isProtected = true;
                continue;
            }
            if (match(Tok.Abstract)) {
                isAbstract = true;
                continue;
            }
            if (match(Tok.Final)) {
                isFinal = true;
                continue;
            }
            break;
        }
        if (check(Tok.Import) || check(Tok.From)) {
            if (attrs.any()) {
                errorNote("attributes cannot apply to import");
            }
            rejectClassMods(isAbstract, isFinal, "import");
            rejectProtected(isProtected, "import");
            ImportDecl im = parseImport();
            if (inMod) {
                mod.imports.add(im);
                note.accept(ItemKind.Import, mod.imports.size() - 1);
            } else {
                prog.imports.add(im);
                note.accept(ItemKind.Import, prog.imports.size() - 1);
            }
            return;
        }
        if (check(Tok.Module)) {
            if (attrs.any()) {
                errorNote("attributes cannot apply to mod");
            }
            rejectClassMods(isAbstract, isFinal, "mod");
            rejectProtected(isProtected, "mod");
            ModDecl nested = parseMod();
            nested.isPub = isPub;
            if (inMod) {
                mod.mods.add(nested);
                note.accept(ItemKind.Mod, mod.mods.size() - 1);
            } else {
                prog.mods.add(nested);
                note.accept(ItemKind.Mod, prog.mods.size() - 1);
            }
            return;
        }
        if (check(Tok.Struct) || check(Tok.Data)) {
            boolean isData = check(Tok.Data);
            String kind = isData ? "data" : "struct";
            if (attrs.isTest) {
                errorNote("@test cannot apply to " + kind);
            }
            if (attrs.isConstexpr) {
                errorNote("@constexpr cannot apply to " + kind);
            }
            if (attrs.isUfcs) {
                errorNote("@ufcs cannot apply to " + kind);
            }
            if (attrs.isOptional) {
                errorNote("@optional cannot apply to " + kind);
            }
            rejectClassMods(isAbstract, isFinal, kind);
            rejectProtected(isProtected, kind);
            StructDecl s = parseStruct(isData);
            s.isPub = isPub;
            if (inMod) {
                mod.structs.add(s);
                note.accept(ItemKind.Struct, mod.structs.size() - 1);
            } else {
                prog.structs.add(s);
                note.accept(ItemKind.Struct, prog.structs.size() - 1);
            }
            return;
        }
        if (check(Tok.Class)) {
            if (attrs.isTest) {
                errorNote("@test cannot apply to class");
            }
            if (attrs.isConstexpr) {
                errorNote("@constexpr cannot apply to class");
            }
            if (attrs.isUfcs) {
                errorNote("@ufcs cannot apply to class");
            }
            if (attrs.isOptional) {
                errorNote("@optional cannot apply to class");
            }
            if (isAbstract && isFinal) {
                errorNote("class cannot be both abstract and final");
            }
            rejectProtected(isProtected, "class");
            ClassDecl c = parseClass();
            c.isPub = isPub;
            c.isAbstract = isAbstract;
            c.isFinal = isFinal;
            c.shape.isPub = isPub;
            if (inMod) {
                mod.classes.add(c);
                note.accept(ItemKind.Class, mod.classes.size() - 1);
            } else {
                prog.classes.add(c);
                note.accept(ItemKind.Class, prog.classes.size() - 1);
            }
            return;
        }
        if (check(Tok.Trait)) {
            if (attrs.isTest) {
                errorNote("@test cannot apply to trait");
            }
            if (attrs.isConstexpr) {
                errorNote("@constexpr cannot apply to trait");
            }
            if (attrs.isUfcs) {
                errorNote("@ufcs cannot apply to trait");
            }
            if (attrs.isOptional) {
                errorNote("@optional cannot apply to trait");
            }
            rejectClassMods(isAbstract, isFinal, "trait");
            rejectProtected(isProtected, "trait");
            TraitDecl t = parseTrait();
            t.isPub = isPub;
            if (inMod) {
                mod.traits.add(t);
                note.accept(ItemKind.Trait, mod.traits.size() - 1);
            } else {
                prog.traits.add(t);
                note.accept(ItemKind.Trait, prog.traits.size() - 1);
            }
            return;
        }
        if (check(Tok.Enum)) {
            if (attrs.isTest) {
                errorNote("@test cannot apply to enum");
            }
            if (attrs.isConstexpr) {
                errorNote("@constexpr cannot apply to enum");
            }
            if (attrs.isUfcs) {
                errorNote("@ufcs cannot apply to enum");
            }
            if (attrs.isOptional) {
                errorNote("@optional cannot apply to enum");
            }
            rejectClassMods(isAbstract, isFinal, "enum");
            rejectProtected(isProtected, "enum");
            EnumDecl e = parseEnum();
            e.isPub = isPub;
            if (inMod) {
                mod.enums.add(e);
                note.accept(ItemKind.Enum, mod.enums.size() - 1);
            } else {
                prog.enums.add(e);
                note.accept(ItemKind.Enum, prog.enums.size() - 1);
            }
            return;
        }
        if (check(Tok.Implements)) {
            if (attrs.any()) {
                errorNote("attributes cannot apply to impl");
            }
            rejectClassMods(isAbstract, isFinal, "impl");
            rejectProtected(isProtected, "impl");
            ImplDecl impl = parseImpl();
            if (inMod) {
                mod.impls.add(impl);
                note.accept(ItemKind.Impl, mod.impls.size() - 1);
            } else {
                prog.impls.add(impl);
                note.accept(ItemKind.Impl, prog.impls.size() - 1);
            }
            return;
        }
        if (check(Tok.Signal)) {
            if (attrs.any()) {
                errorNote("attributes cannot apply to signal");
            }
            rejectClassMods(isAbstract, isFinal, "signal");
            rejectProtected(isProtected, "signal");
            SignalDecl sig = parseSignal();
            sig.isPub = isPub;
            if (inMod) {
                mod.signals.add(sig);
                note.accept(ItemKind.Signal, mod.signals.size() - 1);
            } else {
                prog.signals.add(sig);
                note.accept(ItemKind.Signal, prog.signals.size() - 1);
            }
            return;
        }
        rejectClassMods(isAbstract, isFinal, "function");
        rejectProtected(isProtected, "function");
        FnDecl fn = parseFn(attrs, isPub, false);
        if (attrs.isOptional) {
            errorNote("@optional cannot apply to function");
        }
        if (inMod) {
            mod.fns.add(fn);
            note.accept(ItemKind.Fn, mod.fns.size() - 1);
        } else {
            prog.fns.add(fn);
            note.accept(ItemKind.Fn, prog.fns.size() - 1);
        }
    }

    private ModDecl parseMod() {
        Token modTok = expect(Tok.Module, "expected 'mod'");
        Token name = expect(Tok.Identifier, "expected module name");
        ModDecl m = new ModDecl();
        m.name = name.text;
        m.line = modTok.line;
        expect(Tok.LBrace, "expected '{' after module name");
        while (true) {
            int j = nextSignificant(i);
            if (j >= tokens.size() || tokens.get(j).kind == Tok.Eof || tokens.get(j).kind == Tok.RBrace) {
                break;
            }
            try {
                parseItem(null, m);
            } catch (ParseError ex) {
                synchronizeItem();
            }
        }
        m.trailingComments.addAll(takeLeadingComments());
        expect(Tok.RBrace, "expected '}' after module body");
        return m;
    }

    private Program parse() {
        Program program = new Program();
        while (true) {
            int j = nextSignificant(i);
            if (j >= tokens.size() || tokens.get(j).kind == Tok.Eof) {
                break;
            }
            try {
                parseItem(program, null);
            } catch (ParseError ex) {
                synchronizeItem();
            }
        }
        program.trailingComments.addAll(takeLeadingComments());
        return program;
    }
}
