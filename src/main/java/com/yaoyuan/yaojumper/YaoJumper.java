package com.yaoyuan.yaojumper;

import com.intellij.codeInsight.navigation.NavigationUtil;
import com.intellij.ide.actions.SearchEverywherePsiRenderer;
import com.intellij.ide.actions.searcheverywhere.SearchEverywhereContributor;
import com.intellij.ide.actions.searcheverywhere.SearchEverywhereContributorFactory;
import com.intellij.ide.util.EditSourceUtil;
import com.intellij.ide.util.gotoByName.GotoFileModel;
import com.intellij.navigation.NavigationItem;
import com.intellij.navigation.TargetPresentation;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.util.ProgressIndicatorUtils;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.pom.Navigatable;
import com.intellij.psi.*;
import com.intellij.psi.search.FilenameIndex;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.util.PsiUtilCore;
import com.intellij.util.Processor;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.util.ArrayList;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


/**
 * YaoJumper idea小插件，可以用来跳转到指定的java方法、类
 * editor 姚远
 * gmt_create 2023年4月27日20:15:30
 */
public class YaoJumper implements SearchEverywhereContributor<Object>, SearchEverywhereContributorFactory<Object> {
    Project project;
    GotoFileModel myModelForRenderer;

    private static final Pattern ourPatternToDetectLinesAndColumns = Pattern.compile(
            "(.+?)" + // name, non-greedy matching
                    "(?::|@|,| |#|#L|\\?l=| on line | at line |:?\\(|:?\\[)" + // separator
                    "(\\d+)?(?:\\W(\\d+)?)?" + // line + column
                    "[)\\]]?" // possible closing paren/brace
    );


    @Override
    public @NotNull String getSearchProviderId() {
        return "yaoJumper.SearchEverywhereContributor";
    }

    @Override
    public @NotNull @Nls String getGroupName() {
        return "DchainJumper";
    }

    @Override
    public int getSortWeight() {
        return 0;
    }

    @Override
    public boolean showInFindResults() {
        return true;
    }

    @Override
    public void fetchElements(@NotNull String pattern, @NotNull ProgressIndicator progressIndicator, @NotNull Processor<? super Object> consumer) {
        Runnable fetchRunnable = () -> {
            @NotNull PsiFile[] psiFiles = FilenameIndex.getFilesByName(project, "Main.java", GlobalSearchScope.projectScope(project));
            ArrayList<PsiMethod> list = new ArrayList<>();
            for (PsiFile psiFile : psiFiles) {
                PsiJavaFile psiJavaFile = (PsiJavaFile) psiFile;
                PsiMethod[] methods = psiJavaFile.getClasses()[0].getMethods();
                for (PsiMethod method : methods) {
                    if (method.getName().equals(pattern)) {
                        list.add(method);
                    }
                }
            }

            for (PsiMethod psiMethod : list) {
                consumer.process(psiMethod);
            }
        };

        Application application = ApplicationManager.getApplication();
        if (application.isUnitTestMode() && application.isDispatchThread()) {
            fetchRunnable.run();
        }
        else {
            ProgressIndicatorUtils.yieldToPendingWriteActions();
            ProgressIndicatorUtils.runInReadActionWithWriteActionPriority(fetchRunnable, progressIndicator);
        }

    }

    @Override
    public boolean processSelectedItem(@NotNull Object selected, int modifiers, @NotNull String searchText) {
        if (selected instanceof PsiElement) {
            if (!((PsiElement)selected).isValid()) {
                System.out.println("Cannot navigate to invalid PsiElement");
                return true;
            }

            PsiElement psiElement = preparePsi((PsiElement)selected, modifiers, searchText);
            Navigatable extNavigatable = createExtendedNavigatable(psiElement, searchText, modifiers);
            if (extNavigatable != null && extNavigatable.canNavigate()) {
                extNavigatable.navigate(true);
                return true;
            }

            NavigationUtil.activateFileWithPsiElement(psiElement, true);
        }
        else {
            EditSourceUtil.navigate(((NavigationItem)selected), true, false);
        }

        return true;
    }

    @Override
    public @NotNull ListCellRenderer<? super Object> getElementsRenderer() {
        return new SearchEverywherePsiRenderer(this) {
            @NotNull
            @Override
            public ItemMatchers getItemMatchers(@NotNull JList list, @NotNull Object value) {
                ItemMatchers defaultMatchers = super.getItemMatchers(list, value);
                if (!(value instanceof PsiFileSystemItem) || myModelForRenderer == null) {
                    return defaultMatchers;
                }

                return GotoFileModel.convertToFileItemMatchers(defaultMatchers, (PsiFileSystemItem)value, myModelForRenderer);
            }
        };
    }

    @Override
    public @Nullable Object getDataForItem(@NotNull Object element, @NotNull String dataId) {
        return null;
    }

    @Override
    public @NotNull SearchEverywhereContributor<Object> createContributor(@NotNull AnActionEvent initEvent) {
        Project project = initEvent.getProject();
        YaoJumper yaoJumper = new YaoJumper();
        yaoJumper.project = project;
        myModelForRenderer = new GotoFileModel(project);
        return yaoJumper;
    }

    private Function<PsiElement, TargetPresentation> createCalculator() {
        SearchEverywherePsiRenderer renderer = (SearchEverywherePsiRenderer)this.getElementsRenderer();
        return element -> renderer.computePresentation(element);
    }

    public static class PsiItemWithPresentation extends Pair<PsiElement, TargetPresentation> {
        /**
         * @param first
         * @param second
         * @see #create(Object, Object)
         */
        PsiItemWithPresentation(PsiElement first, TargetPresentation second) {
            super(first, second);
        }

        public PsiElement getItem() {
            return first;
        }

        public TargetPresentation getPresentation() {
            return second;
        }
    }

    protected PsiElement preparePsi(PsiElement psiElement, int modifiers, String searchText) {
        return psiElement.getNavigationElement();
    }

    protected Navigatable createExtendedNavigatable(PsiElement psi, String searchText, int modifiers) {
        VirtualFile file = PsiUtilCore.getVirtualFile(psi);
        Pair<Integer, Integer> position = getLineAndColumn(searchText);
        boolean positionSpecified = position.first >= 0 || position.second >= 0;
        if (file != null && positionSpecified) {
            return new OpenFileDescriptor(psi.getProject(), file, position.first, position.second);
        }

        return null;
    }

    protected Pair<Integer, Integer> getLineAndColumn(String text) {
        int line = getLineAndColumnRegexpGroup(text, 2);
        int column = getLineAndColumnRegexpGroup(text, 3);

        if (line == -1 && column != -1) {
            line = 0;
        }

        return new Pair<>(line, column);
    }

    private int getLineAndColumnRegexpGroup(String text, int groupNumber) {
        final Matcher matcher = ourPatternToDetectLinesAndColumns.matcher(text);
        if (matcher.matches()) {
            try {
                if (groupNumber <= matcher.groupCount()) {
                    final String group = matcher.group(groupNumber);
                    if (group != null) return Integer.parseInt(group) - 1;
                }
            }
            catch (NumberFormatException ignored) {
            }
        }

        return -1;
    }
}
