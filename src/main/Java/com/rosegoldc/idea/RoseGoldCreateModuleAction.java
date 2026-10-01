package com.rosegoldc.idea;

import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiDirectory;
import com.rosegoldc.lang.Scaffold;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;

public final class RoseGoldCreateModuleAction extends AnAction implements DumbAware {

    public RoseGoldCreateModuleAction() {
        super("RoseGold Module", "Create a mapped module under [modules]", RoseGoldIcons.FILE);
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        PsiDirectory directory = RoseGoldCreateProjectAction.directory(e);
        e.getPresentation().setEnabledAndVisible(directory != null && hasProject(directory));
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project idea = e.getProject();
        PsiDirectory directory = RoseGoldCreateProjectAction.directory(e);
        if (idea == null || directory == null) {
            return;
        }
        String name = Messages.showInputDialog(
                idea,
                "Module name",
                "New RoseGold Module",
                RoseGoldIcons.FILE
        );
        if (name == null || name.isBlank()) {
            return;
        }
        Path dir = Path.of(directory.getVirtualFile().getPath());
        try {
            Scaffold.Result result = Scaffold.addModule(dir, name.trim());
            LocalFileSystem.getInstance().refresh(false);
            notify(idea, result.message, result.ok);
            if (result.ok && result.module != null) {
                VirtualFile vf = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(result.module);
                if (vf != null) {
                    FileEditorManager.getInstance(idea).openFile(vf, true);
                }
            }
        } catch (Exception ex) {
            notify(idea, ex.getMessage() == null ? "failed to create module" : ex.getMessage(), false);
        }
    }

    private static boolean hasProject(PsiDirectory directory) {
        try {
            return com.rosegoldc.lang.Project.locate(Path.of(directory.getVirtualFile().getPath())) != null;
        } catch (Exception ex) {
            return false;
        }
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
