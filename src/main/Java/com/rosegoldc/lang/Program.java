package com.rosegoldc.lang;

import java.util.ArrayList;
import java.util.List;

enum Vis {
    Pub,
    Protected,
    Private;

    String visName() {
        return switch (this) {
            case Private -> "private";
            case Protected -> "protected";
            default -> "pub";
        };
    }
}

enum ItemKind {
    Import,
    Fn,
    Struct,
    Class,
    Trait,
    Enum,
    Impl,
    Signal,
    Mod
}

final class TypeParam {
    String name = "";
    final List<String> bounds = new ArrayList<>();
}

final class Expr {
    enum Kind {
        Int, Float, String, Bool, Var, Unary, Binary, Call, MethodCall, Member,
        Index, Array, Map, StructLit, Range, Try, Lambda, Await
    }

    Kind kind;
    long number;
    double real;
    boolean booleanValue;
    String text = "";
    String module = "";
    final List<String> names = new ArrayList<>();
    final List<String> typeArgs = new ArrayList<>();
    final List<Expr> kids = new ArrayList<>();
    FnDecl lambda;
    int line = 1;
    int col = 1;
}

final class MatchArm {
    enum Pat { Wildcard, Int, Float, String, Bool, Variant }

    Pat pat;
    String name = "";
    long number;
    double real;
    boolean booleanValue;
    String text = "";
    final List<String> binds = new ArrayList<>();
    final List<String> fieldNames = new ArrayList<>();
    final List<Stmt> body = new ArrayList<>();
    int line = 1;
    int col = 1;
}

final class Stmt {
    enum Kind {
        Expr, Var, Const, Assign, FieldAssign, IndexAssign, Return, If, While, For,
        Match, Pass, Break, Continue, Throw, Do, Comment
    }

    Kind kind;
    String name = "";
    Expr expr;
    Expr target;
    final List<Stmt> body = new ArrayList<>();
    final List<Stmt> elseBody = new ArrayList<>();
    final List<MatchArm> arms = new ArrayList<>();
    String typeName = "";
    String op = "=";
    final List<String> leadingComments = new ArrayList<>();
    String trailingComment = "";
    boolean hasExpr;
    int line = 1;
    int col = 1;
}

final class FnDecl {
    String name = "";
    final List<TypeParam> typeParams = new ArrayList<>();
    final List<String> params = new ArrayList<>();
    final List<String> paramTypes = new ArrayList<>();
    String returnType = "";
    final List<Stmt> body = new ArrayList<>();
    String module = "";
    boolean isTest;
    boolean isDeprecated;
    boolean isConstexpr;
    boolean isUfcs;
    boolean throwsEx;
    boolean isAsync;
    boolean isPub = true;
    boolean isAbstract;
    boolean isFinal;
    Vis vis = Vis.Pub;
    int line = 1;
    String file = "";
    Bytecode.Fn code;
}

final class SignalDecl {
    String name = "";
    final List<String> params = new ArrayList<>();
    boolean isPub = true;
    int line = 1;
}

final class StructDecl {
    String name = "";
    final List<TypeParam> typeParams = new ArrayList<>();
    final List<String> fields = new ArrayList<>();
    final List<String> fieldTypes = new ArrayList<>();
    final List<Boolean> fieldOptional = new ArrayList<>();
    final List<Vis> fieldVis = new ArrayList<>();
    final List<FnDecl> methods = new ArrayList<>();
    final List<String> implTraits = new ArrayList<>();
    final List<SignalDecl> signals = new ArrayList<>();
    boolean isPub = true;
    boolean isData;
    int line = 1;

    boolean isOptionalField(String name) {
        for (int i = 0; i < fields.size(); i++) {
            if (!fields.get(i).equals(name)) {
                continue;
            }
            return i < fieldOptional.size() && Boolean.TRUE.equals(fieldOptional.get(i));
        }
        return false;
    }

    String typeOfField(String name) {
        for (int i = 0; i < fields.size(); i++) {
            if (!fields.get(i).equals(name)) {
                continue;
            }
            return i < fieldTypes.size() ? fieldTypes.get(i) : "";
        }
        return "";
    }
}

final class ImplDecl {
    final List<TypeParam> typeParams = new ArrayList<>();
    String typeName = "";
    String traitName = "";
    final List<FnDecl> methods = new ArrayList<>();
    int line = 1;
}

final class ClassField {
    String name = "";
    String type = "";
    boolean hasDefault;
    boolean optional;
    Vis vis = Vis.Pub;
    Expr defaultValue;
    int line = 1;
    int col = 1;
}

final class NestedImpl {
    final List<TypeParam> typeParams = new ArrayList<>();
    String traitName = "";
    final List<FnDecl> methods = new ArrayList<>();
}

final class ClassDecl {
    String name = "";
    final List<TypeParam> typeParams = new ArrayList<>();
    String parent = "";
    final List<String> implTraits = new ArrayList<>();
    final List<ClassField> fields = new ArrayList<>();
    final List<FnDecl> methods = new ArrayList<>();
    final List<NestedImpl> traitImpls = new ArrayList<>();
    final List<SignalDecl> signals = new ArrayList<>();
    StructDecl shape = new StructDecl();
    boolean isPub = true;
    boolean isAbstract;
    boolean isFinal;
    int line = 1;
}

final class TraitMethod {
    String name = "";
    final List<String> params = new ArrayList<>();
    final List<String> paramTypes = new ArrayList<>();
    String returnType = "";
    boolean throwsEx;
    int line = 1;
}

final class TraitDecl {
    String name = "";
    final List<TypeParam> typeParams = new ArrayList<>();
    final List<TraitMethod> methods = new ArrayList<>();
    final List<SignalDecl> signals = new ArrayList<>();
    boolean isPub = true;
    int line = 1;
}

final class EnumVariant {
    String name = "";
    final List<String> fieldNames = new ArrayList<>();
    int arity;
}

final class EnumDecl {
    String name = "";
    final List<EnumVariant> variants = new ArrayList<>();
    boolean isPub = true;
    int line = 1;
}

final class ImportDecl {
    final List<String> path = new ArrayList<>();
    String alias = "";
    boolean isFrom;
    int line = 1;
    int col = 1;
}

final class OrderedItem {
    ItemKind kind;
    int index;
    final List<String> leadingComments = new ArrayList<>();
}

final class ModDecl {
    String name = "";
    final List<ImportDecl> imports = new ArrayList<>();
    final List<FnDecl> fns = new ArrayList<>();
    final List<StructDecl> structs = new ArrayList<>();
    final List<ClassDecl> classes = new ArrayList<>();
    final List<TraitDecl> traits = new ArrayList<>();
    final List<EnumDecl> enums = new ArrayList<>();
    final List<ImplDecl> impls = new ArrayList<>();
    final List<SignalDecl> signals = new ArrayList<>();
    final List<ModDecl> mods = new ArrayList<>();
    final List<OrderedItem> items = new ArrayList<>();
    final List<String> trailingComments = new ArrayList<>();
    boolean isPub = true;
    int line = 1;
}

public final class Program {
    public final List<ImportDecl> imports = new ArrayList<>();
    public final List<FnDecl> fns = new ArrayList<>();
    public final List<StructDecl> structs = new ArrayList<>();
    public final List<ClassDecl> classes = new ArrayList<>();
    public final List<TraitDecl> traits = new ArrayList<>();
    public final List<EnumDecl> enums = new ArrayList<>();
    public final List<ImplDecl> impls = new ArrayList<>();
    public final List<SignalDecl> signals = new ArrayList<>();
    public final List<ModDecl> mods = new ArrayList<>();
    public final List<OrderedItem> items = new ArrayList<>();
    public final List<String> trailingComments = new ArrayList<>();
}
