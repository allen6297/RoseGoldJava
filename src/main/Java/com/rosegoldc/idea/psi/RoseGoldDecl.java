package com.rosegoldc.idea.psi;

import com.intellij.icons.AllIcons;
import com.intellij.lang.ASTNode;
import com.intellij.navigation.ItemPresentation;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiNameIdentifierOwner;
import com.intellij.psi.PsiWhiteSpace;
import com.intellij.psi.tree.IElementType;
import com.intellij.util.IncorrectOperationException;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;

public final class RoseGoldDecl extends RoseGoldPsiElement implements PsiNameIdentifierOwner {

    public RoseGoldDecl(@NotNull ASTNode node) {
        super(node);
    }

    @Override
    public @Nullable String getName() {
        PsiElement id = getNameIdentifier();
        return id == null ? null : id.getText();
    }

    @Override
    public @Nullable PsiElement getNameIdentifier() {
        IElementType kind = getNode().getElementType();
        boolean sawKind = false;
        boolean sawFor = false;
        PsiElement fallback = null;
        for (PsiElement child = getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof PsiWhiteSpace) {
                continue;
            }
            IElementType type = child.getNode().getElementType();
            if (type == RoseGoldTokenTypes.COMMENT || type == RoseGoldTokenTypes.DECORATOR) {
                continue;
            }
            if (type == RoseGoldTokenTypes.KEYWORD) {
                String word = child.getText();
                if (!sawKind && isKindKeyword(word)) {
                    sawKind = true;
                    continue;
                }
                if ("for".equals(word)) {
                    sawFor = true;
                    continue;
                }
                continue;
            }
            if ((type == RoseGoldTokenTypes.IDENTIFIER || type == RoseGoldTokenTypes.TYPE) && sawKind) {
                if (kind == RoseGoldTypes.IMPL && !sawFor) {
                    fallback = child;
                    continue;
                }
                return child;
            }
        }
        return fallback;
    }

    @Override
    public PsiElement setName(@NonNls @NotNull String name) throws IncorrectOperationException {
        PsiElement id = getNameIdentifier();
        if (id instanceof RoseGoldIdent ident) {
            return ident.setName(name);
        }
        return this;
    }

    @Override
    public int getTextOffset() {
        PsiElement id = getNameIdentifier();
        return id != null ? id.getTextOffset() : super.getTextOffset();
    }

    @Override
    public @NotNull PsiElement getNavigationElement() {
        PsiElement id = getNameIdentifier();
        return id != null ? id : super.getNavigationElement();
    }

    @Override
    public Icon getIcon(int flags) {
        IElementType kind = getNode().getElementType();
        if (kind == RoseGoldTypes.FN) {
            return getParent() instanceof RoseGoldDecl ? AllIcons.Nodes.Method : AllIcons.Nodes.Function;
        }
        if (kind == RoseGoldTypes.CLASS) {
            return AllIcons.Nodes.Class;
        }
        if (kind == RoseGoldTypes.STRUCT) {
            return AllIcons.Nodes.Record;
        }
        if (kind == RoseGoldTypes.TRAIT) {
            return AllIcons.Nodes.Interface;
        }
        if (kind == RoseGoldTypes.ENUM) {
            return AllIcons.Nodes.Enum;
        }
        if (kind == RoseGoldTypes.MOD) {
            return AllIcons.Nodes.Package;
        }
        if (kind == RoseGoldTypes.SIGNAL) {
            return AllIcons.Nodes.Property;
        }
        if (kind == RoseGoldTypes.IMPL) {
            return AllIcons.Nodes.Class;
        }
        if (kind == RoseGoldTypes.IMPORT) {
            return AllIcons.Nodes.Include;
        }
        return AllIcons.Nodes.Tag;
    }

    @Override
    public ItemPresentation getPresentation() {
        return new ItemPresentation() {
            @Override
            public @NotNull String getPresentableText() {
                String name = getName();
                return name == null || name.isEmpty() ? kindLabel() : name;
            }

            @Override
            public @NotNull String getLocationString() {
                PsiFile file = getContainingFile();
                String where = file == null ? "" : file.getName();
                return where.isEmpty() ? kindLabel() : kindLabel() + " in " + where;
            }

            @Override
            public @Nullable Icon getIcon(boolean unused) {
                return RoseGoldDecl.this.getIcon(0);
            }
        };
    }

    @NotNull
    public String kindLabel() {
        IElementType kind = getNode().getElementType();
        if (kind == RoseGoldTypes.FN) {
            return getParent() instanceof RoseGoldDecl ? "method" : "function";
        }
        if (kind == RoseGoldTypes.CLASS) {
            return "class";
        }
        if (kind == RoseGoldTypes.STRUCT) {
            return firstKeyword("data") ? "data" : "struct";
        }
        if (kind == RoseGoldTypes.TRAIT) {
            return "trait";
        }
        if (kind == RoseGoldTypes.ENUM) {
            return "enum";
        }
        if (kind == RoseGoldTypes.MOD) {
            return "module";
        }
        if (kind == RoseGoldTypes.SIGNAL) {
            return "signal";
        }
        if (kind == RoseGoldTypes.IMPL) {
            return "impl";
        }
        if (kind == RoseGoldTypes.IMPORT) {
            return "import";
        }
        return "symbol";
    }

    private boolean firstKeyword(@NotNull String word) {
        for (PsiElement child = getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof PsiWhiteSpace || child.getNode().getElementType() == RoseGoldTokenTypes.COMMENT
                    || child.getNode().getElementType() == RoseGoldTokenTypes.DECORATOR) {
                continue;
            }
            if (child.getNode().getElementType() == RoseGoldTokenTypes.KEYWORD) {
                String text = child.getText();
                if (isMod(text)) {
                    continue;
                }
                return word.equals(text);
            }
            return false;
        }
        return false;
    }

    static boolean isKindKeyword(@Nullable String word) {
        return "fn".equals(word) || "struct".equals(word) || "data".equals(word) || "class".equals(word)
                || "trait".equals(word) || "enum".equals(word) || "impl".equals(word) || "mod".equals(word)
                || "signal".equals(word) || "import".equals(word) || "from".equals(word);
    }

    private static boolean isMod(@Nullable String word) {
        return "pub".equals(word) || "private".equals(word) || "protected".equals(word)
                || "abstract".equals(word) || "final".equals(word) || "async".equals(word);
    }
}
