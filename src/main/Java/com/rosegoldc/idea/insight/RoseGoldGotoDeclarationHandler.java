package com.rosegoldc.idea.insight;

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiDirectory;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.rosegoldc.idea.RoseGoldProjects;
import com.rosegoldc.idea.psi.RoseGoldFile;
import com.rosegoldc.lang.Goto;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;

public final class RoseGoldGotoDeclarationHandler implements GotoDeclarationHandler {

    @Override
    public PsiElement @Nullable [] getGotoDeclarationTargets(
            @Nullable PsiElement sourceElement,
            int offset,
            Editor editor
    ) {
        if (sourceElement == null) {
            return null;
        }
        PsiFile file = sourceElement.getContainingFile();
        if (file == null) {
            return null;
        }
        VirtualFile vf = file.getVirtualFile();
        if (RoseGoldProjects.isProjectToml(vf)) {
            return projectTomlTarget(file, vf, offset);
        }
        if (!(file instanceof RoseGoldFile rg)) {
            return null;
        }
        String path = rg.getVirtualFile() != null ? rg.getVirtualFile().getPath() : rg.getName();
        String text = rg.getText();
        int at = Math.clamp(offset, 0, text.length());
        Goto.Loc loc = Goto.at(text, path, at);
        if (loc == null) {
            return null;
        }
        PsiFile targetFile = resolveFile(rg, loc.path);
        if (targetFile == null) {
            return null;
        }
        int start = Math.clamp(loc.start, 0, Math.max(0, targetFile.getTextLength()));
        PsiElement target = targetFile.findElementAt(start);
        if (target == null) {
            target = targetFile;
        }
        return new PsiElement[]{target};
    }

    private static PsiElement @Nullable [] projectTomlTarget(PsiFile file, VirtualFile vf, int offset) {
        String text = file.getText();
        int at = Math.clamp(offset, 0, text.length());
        Path nio = Path.of(vf.getPath());
        Path target;
        try {
            target = com.rosegoldc.lang.Project.parse(nio, text).targetAt(text, at);
        } catch (RuntimeException ex) {
            return null;
        }
        if (target == null) {
            return null;
        }
        VirtualFile dest = LocalFileSystem.getInstance().findFileByNioFile(target);
        if (dest == null) {
            dest = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(target);
        }
        if (dest == null) {
            return null;
        }
        PsiManager manager = PsiManager.getInstance(file.getProject());
        if (dest.isDirectory()) {
            PsiDirectory dir = manager.findDirectory(dest);
            return dir == null ? null : new PsiElement[]{dir};
        }
        PsiFile found = manager.findFile(dest);
        return found == null ? null : new PsiElement[]{found};
    }

    private static PsiFile resolveFile(RoseGoldFile origin, String locPath) {
        if (locPath == null || locPath.isEmpty()) {
            return origin;
        }
        VirtualFile originVf = origin.getVirtualFile();
        if (originVf != null && pathsEqual(originVf.getPath(), locPath)) {
            return origin;
        }
        Project idea = origin.getProject();
        VirtualFile vf = LocalFileSystem.getInstance().findFileByNioFile(Path.of(locPath));
        if (vf == null) {
            vf = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(Path.of(locPath));
        }
        if (vf == null) {
            return originVf != null && locPath.equals(origin.getName()) ? origin : null;
        }
        PsiFile found = PsiManager.getInstance(idea).findFile(vf);
        return found != null ? found : origin;
    }

    private static boolean pathsEqual(String a, String b) {
        try {
            return Path.of(a).toAbsolutePath().normalize().equals(Path.of(b).toAbsolutePath().normalize());
        } catch (Exception ex) {
            return a.replace('\\', '/').equalsIgnoreCase(b.replace('\\', '/'));
        }
    }
}
