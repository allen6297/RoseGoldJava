package com.rosegoldc.idea;

import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiDirectory;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiManager;
import com.rosegoldc.lang.Scaffold;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;

public final class RoseGoldCreateProjectAction extends AnAction implements DumbAware {

    public RoseGoldCreateProjectAction() {
        super("RoseGold Project", "Create project.toml, main.rg, and tests.rg", RoseGoldIcons.FILE);
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        e.getPresentation().setEnabledAndVisible(directory(e) != null);
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project idea = e.getProject();
        PsiDirectory directory = directory(e);
        if (idea == null || directory == null) {
            return;
        }
        Path dir = Path.of(directory.getVirtualFile().getPath());
        try {
            Scaffold.Result result = Scaffold.create(dir);
            LocalFileSystem.getInstance().refresh(false);
            notify(idea, result.message, result.ok);
            if (result.ok && result.toml != null) {
                VirtualFile vf = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(result.toml);
                if (vf != null) {
                    FileEditorManager.getInstance(idea).openFile(vf, true);
                }
            }
        } catch (Exception ex) {
            notify(idea, ex.getMessage() == null ? "failed to create project" : ex.getMessage(), false);
        }
    }

    static @Nullable PsiDirectory directory(@NotNull AnActionEvent e) {
        Project idea = e.getProject();
        if (idea == null) {
            return null;
        }
        PsiElement element = e.getData(CommonDataKeys.PSI_ELEMENT);
        if (element instanceof PsiDirectory dir) {
            return dir;
        }
        VirtualFile vf = e.getData(CommonDataKeys.VIRTUAL_FILE);
        if (vf != null && vf.isDirectory()) {
            return PsiManager.getInstance(idea).findDirectory(vf);
        }
        return null;
    }

    private static void notify(Project idea, String message, boolean ok) {
        if (message == null || message.isEmpty()) {
            return;
        }
        NotificationGroupManager.getInstance()
                .getNotificationGroup("RoseGold")
                .createNotification(message, ok ? NotificationType.INFORMATION : NotificationType.ERROR)
                .notify(idea);
    }
}
