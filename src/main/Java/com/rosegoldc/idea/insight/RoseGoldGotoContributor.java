package com.rosegoldc.idea.insight;

import com.rosegoldc.idea.RoseGoldFileType;
import com.rosegoldc.idea.psi.RoseGoldDecl;
import com.rosegoldc.idea.psi.RoseGoldFile;
import com.rosegoldc.idea.psi.RoseGoldTypes;
import com.intellij.navigation.ChooseByNameContributorEx;
import com.intellij.navigation.NavigationItem;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.search.FileTypeIndex;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.tree.IElementType;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.util.Processor;
import com.intellij.util.indexing.FindSymbolParameters;
import com.intellij.util.indexing.IdFilter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

abstract class RoseGoldGotoContributor implements ChooseByNameContributorEx, DumbAware {

    private final Predicate<RoseGoldDecl> accept;

    RoseGoldGotoContributor(@NotNull Predicate<RoseGoldDecl> accept) {
        this.accept = accept;
    }

    static boolean isType(@NotNull RoseGoldDecl decl) {
        IElementType kind = decl.getNode().getElementType();
        return kind == RoseGoldTypes.CLASS || kind == RoseGoldTypes.STRUCT
                || kind == RoseGoldTypes.TRAIT || kind == RoseGoldTypes.ENUM
                || kind == RoseGoldTypes.MOD;
    }

    static boolean isSymbol(@NotNull RoseGoldDecl decl) {
        IElementType kind = decl.getNode().getElementType();
        return kind == RoseGoldTypes.FN || kind == RoseGoldTypes.SIGNAL || isType(decl);
    }

    @Override
    public void processNames(
            @NotNull Processor<? super String> processor,
            @NotNull GlobalSearchScope scope,
            @Nullable IdFilter filter
    ) {
        processDecls(scope.getProject(), scope, decl -> {
            String name = decl.getName();
            return name == null || name.isEmpty() || processor.process(name);
        });
    }

    @Override
    public void processElementsWithName(
            @NotNull String name,
            @NotNull Processor<? super NavigationItem> processor,
            @NotNull FindSymbolParameters parameters
    ) {
        processDecls(parameters.getProject(), parameters.getSearchScope(), decl -> {
            if (!name.equals(decl.getName())) {
                return true;
            }
            return processor.process(decl);
        });
    }

    private void processDecls(
            @Nullable Project project,
            @NotNull GlobalSearchScope scope,
            @NotNull Processor<? super RoseGoldDecl> processor
    ) {
        if (project == null || project.isDisposed()) {
            return;
        }
        Collection<VirtualFile> files = FileTypeIndex.getFiles(RoseGoldFileType.INSTANCE, scope);
        PsiManager psi = PsiManager.getInstance(project);
        Set<String> seen = new LinkedHashSet<>();
        for (VirtualFile file : files) {
            if (project.isDisposed()) {
                return;
            }
            seen.add(norm(file.getPath()));
            if (!visit(psi, file, processor)) {
                return;
            }
        }
        for (Path extra : extraModuleFiles(project, files)) {
            if (project.isDisposed()) {
                return;
            }
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
            if (!visit(psi, vf, processor)) {
                return;
            }
        }
    }

    private boolean visit(PsiManager psi, VirtualFile file, Processor<? super RoseGoldDecl> processor) {
        if (!(psi.findFile(file) instanceof RoseGoldFile rg)) {
            return true;
        }
        for (RoseGoldDecl decl : PsiTreeUtil.findChildrenOfType(rg, RoseGoldDecl.class)) {
            if (!accept.test(decl)) {
                continue;
            }
            if (!processor.process(decl)) {
                return false;
            }
        }
        return true;
    }

    private static List<Path> extraModuleFiles(@NotNull Project idea, Collection<VirtualFile> indexed) {
        LinkedHashSet<Path> starts = new LinkedHashSet<>();
        String base = idea.getBasePath();
        if (base != null && !base.isEmpty()) {
            starts.add(Path.of(base));
        }
        for (VirtualFile vf : indexed) {
            if (vf != null && vf.isInLocalFileSystem()) {
                starts.add(Path.of(vf.getPath()));
            }
        }
        LinkedHashSet<Path> out = new LinkedHashSet<>();
        Set<String> tomlSeen = new LinkedHashSet<>();
        String from = base != null && !base.isEmpty() ? base : "";
        if (from.isEmpty()) {
            for (VirtualFile vf : indexed) {
                if (vf != null && vf.isInLocalFileSystem()) {
                    from = vf.getPath();
                    break;
                }
            }
        }
        for (Path std : com.rosegoldc.lang.Project.stdlibSourcesNear(from)) {
            out.add(std);
        }
        for (Path start : starts) {
            try {
                com.rosegoldc.lang.Project rg = com.rosegoldc.lang.Project.find(start);
                if (rg == null || !tomlSeen.add(norm(rg.file.toString()))) {
                    continue;
                }
                out.addAll(rg.moduleSourceFiles());
            } catch (Exception ignored) {
            }
        }
        return new ArrayList<>(out);
    }

    private static String norm(String path) {
        if (path == null || path.isEmpty()) {
            return "";
        }
        return Path.of(path).toAbsolutePath().normalize().toString().replace('\\', '/');
    }
}
