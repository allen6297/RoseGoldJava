package com.rosegoldc.idea.editor;

import com.rosegoldc.idea.psi.RoseGoldDecl;
import com.rosegoldc.idea.psi.RoseGoldFile;
import com.rosegoldc.idea.psi.RoseGoldTypes;
import com.intellij.ide.structureView.StructureViewModel;
import com.intellij.ide.structureView.StructureViewModelBase;
import com.intellij.ide.structureView.StructureViewTreeElement;
import com.intellij.ide.structureView.TreeBasedStructureViewBuilder;
import com.intellij.lang.PsiStructureViewFactory;
import com.intellij.openapi.editor.Editor;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public final class RoseGoldStructureViewFactory implements PsiStructureViewFactory {

    @Override
    public @Nullable com.intellij.ide.structureView.StructureViewBuilder getStructureViewBuilder(@NotNull PsiFile psiFile) {
        if (!(psiFile instanceof RoseGoldFile)) {
            return null;
        }
        return new TreeBasedStructureViewBuilder() {
            @Override
            public @NotNull StructureViewModel createStructureViewModel(@Nullable Editor editor) {
                return new Model(psiFile, editor);
            }
        };
    }

    private static final class Model extends StructureViewModelBase implements StructureViewModel.ElementInfoProvider {
        private Model(@NotNull PsiFile file, @Nullable Editor editor) {
            super(file, editor, new Element(file));
        }

        @Override
        public boolean isAlwaysShowsPlus(StructureViewTreeElement element) {
            return false;
        }

        @Override
        public boolean isAlwaysLeaf(StructureViewTreeElement element) {
            Object value = element.getValue();
            return value instanceof RoseGoldDecl decl && decl.getNode().getElementType() == RoseGoldTypes.FN;
        }
    }

    private static final class Element implements StructureViewTreeElement {
        private final PsiElement element;

        private Element(@NotNull PsiElement element) {
            this.element = element;
        }

        @Override
        public Object getValue() {
            return element;
        }

        @Override
        public @NotNull com.intellij.navigation.ItemPresentation getPresentation() {
            return new com.intellij.navigation.ItemPresentation() {
                @Override
                public @Nullable String getPresentableText() {
                    if (element instanceof RoseGoldFile file) {
                        return file.getName();
                    }
                    if (element instanceof RoseGoldDecl decl) {
                        String name = decl.getName();
                        return name == null || name.isEmpty() ? decl.kindLabel() : name;
                    }
                    return element.getText();
                }

                @Override
                public @Nullable String getLocationString() {
                    return element instanceof RoseGoldDecl decl ? decl.kindLabel() : null;
                }

                @Override
                public @Nullable javax.swing.Icon getIcon(boolean unused) {
                    return element.getIcon(0);
                }
            };
        }

        @Override
        public void navigate(boolean requestFocus) {
            if (element instanceof com.intellij.pom.Navigatable nav && nav.canNavigate()) {
                nav.navigate(requestFocus);
            }
        }

        @Override
        public boolean canNavigate() {
            return element instanceof com.intellij.pom.Navigatable nav && nav.canNavigate();
        }

        @Override
        public boolean canNavigateToSource() {
            return canNavigate();
        }

        @Override
        public StructureViewTreeElement @NotNull [] getChildren() {
            List<StructureViewTreeElement> out = new ArrayList<>();
            Collection<RoseGoldDecl> decls = PsiTreeUtil.getChildrenOfTypeAsList(element, RoseGoldDecl.class);
            for (RoseGoldDecl decl : decls) {
                String name = decl.getName();
                if (name != null && !name.isEmpty()) {
                    out.add(new Element(decl));
                }
            }
            return out.toArray(StructureViewTreeElement[]::new);
        }
    }
}
