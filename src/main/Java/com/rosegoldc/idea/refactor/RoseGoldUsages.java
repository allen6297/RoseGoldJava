package com.rosegoldc.idea.refactor;

import com.rosegoldc.idea.RoseGoldFileType;
import com.rosegoldc.idea.psi.RoseGoldFile;
import com.rosegoldc.idea.psi.RoseGoldTokenTypes;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.search.FileTypeIndex;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.LocalSearchScope;
import com.intellij.psi.search.SearchScope;
import com.intellij.psi.tree.IElementType;
import com.rosegoldc.lang.Usages;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

final class RoseGoldUsages {

    private RoseGoldUsages() {
    }

    static boolean isSymbolLeaf(@Nullable PsiElement element) {
        if (element == null || !(element.getContainingFile() instanceof RoseGoldFile)) {
            return false;
        }
        if (element.getNode() == null) {
            return false;
        }
        IElementType type = element.getNode().getElementType();
        return type == RoseGoldTokenTypes.IDENTIFIER || type == RoseGoldTokenTypes.TYPE;
    }

    static List<PsiFile> filesInScope(@NotNull PsiFile origin, @NotNull SearchScope scope) {
        if (scope instanceof LocalSearchScope) {
            return List.of(origin);
        }
        Project project = origin.getProject();
        GlobalSearchScope global = scope instanceof GlobalSearchScope g
                ? g
                : GlobalSearchScope.projectScope(project);
        Collection<VirtualFile> vfs = FileTypeIndex.getFiles(RoseGoldFileType.INSTANCE, global);
        PsiManager manager = PsiManager.getInstance(project);
        List<PsiFile> files = new ArrayList<>();
        boolean sawOrigin = false;
        for (VirtualFile vf : vfs) {
            PsiFile psi = manager.findFile(vf);
            if (psi instanceof RoseGoldFile) {
                files.add(psi);
                if (psi == origin) {
                    sawOrigin = true;
                }
            }
        }
        if (!sawOrigin) {
            files.addFirst(origin);
        }
        return files;
    }

    static List<PsiFile> extraCrateFiles(@NotNull PsiFile origin, int offset, @NotNull Set<String> seen) {
        List<PsiFile> out = new ArrayList<>();
        PsiManager manager = PsiManager.getInstance(origin.getProject());
        for (Path extra : Usages.crateSourceFiles(origin.getText(), pathOf(origin), offset)) {
            if (!seen.add(norm(extra.toString()))) {
                continue;
            }
            VirtualFile vf = LocalFileSystem.getInstance().findFileByNioFile(extra);
            if (vf == null) {
                vf = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(extra);
            }
            if (vf == null) {
                continue;
            }
            PsiFile file = manager.findFile(vf);
            if (file != null) {
                out.add(file);
            }
        }
        return out;
    }

    static String pathOf(@NotNull PsiFile file) {
        return file.getVirtualFile() != null ? file.getVirtualFile().getPath() : file.getName();
    }

    static String norm(String path) {
        if (path == null || path.isEmpty()) {
            return "";
        }
        return Path.of(path).toAbsolutePath().normalize().toString().replace('\\', '/');
    }
}
