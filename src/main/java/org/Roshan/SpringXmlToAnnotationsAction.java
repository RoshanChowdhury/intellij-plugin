package org.Roshan;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.editor.SelectionModel;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Editor;
import com.intellij.psi.*;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.xml.XmlFile;
import com.intellij.psi.xml.XmlTag;
import org.jetbrains.annotations.NotNull;

import java.util.Collections;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

public class SpringXmlToAnnotationsAction extends AnAction {
    private static final String BEANS_TAG = "beans";
    private static final String BEAN_TAG = "bean";

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getRequiredData(CommonDataKeys.PROJECT);
        Editor editor = e.getRequiredData(CommonDataKeys.EDITOR);
        PsiFile psiFile = e.getRequiredData(CommonDataKeys.PSI_FILE);

        if (!(psiFile instanceof XmlFile xmlFile)) {
            return;
        }
        System.out.println(" Main root tag is : ");
        XmlTag rootTag = xmlFile.getRootTag();
        if (rootTag == null || !BEANS_TAG.equals(rootTag.getName())) {
            return;
        }
        System.out.println(" root tag is : " + rootTag.getName());
        convertSpringXmlToAnnotations(project, xmlFile, editor);

    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        PsiFile psiFile = e.getData(CommonDataKeys.PSI_FILE);
        e.getPresentation().setEnabledAndVisible(
                project != null && psiFile instanceof XmlFile
        );
    }

    protected void convertSpringXmlToAnnotations(Project project, XmlFile xmlFile, Editor editor) {
        XmlTag rootTag = xmlFile.getRootTag();
        if (rootTag == null || !BEANS_TAG.equals(rootTag.getName())) {
            return;
        }

        JavaPsiFacade psiFacade = JavaPsiFacade.getInstance(project);
        PsiElementFactory elementFactory = PsiElementFactory.getInstance(project);

        // Get selected tags or all tags
        List<XmlTag> tagsToProcess = getTagsToProcess(xmlFile, editor);

        // Process the tags
        tagsToProcess.stream()
                .filter(tag -> BEAN_TAG.equals(tag.getName()))
                .forEach(beanTag -> processBean(project, beanTag, psiFacade, elementFactory));

    }
    private List<XmlTag> getTagsToProcess(XmlFile xmlFile, Editor editor) {
        SelectionModel selectionModel = editor.getSelectionModel();

        if (!selectionModel.hasSelection()) {
            // If no selection, process all tags under root
            XmlTag rootTag = xmlFile.getRootTag();
            return rootTag != null ? Arrays.asList(rootTag.getSubTags()) : Collections.emptyList();
        }

        // Get the selected text range
        int start = selectionModel.getSelectionStart();
        int end = selectionModel.getSelectionEnd();

        // Find all XML tags within the selection
        return findTagsInRange(xmlFile, start, end);
    }

    private List<XmlTag> findTagsInRange(XmlFile xmlFile, int start, int end) {
        return PsiTreeUtil.findChildrenOfType(xmlFile, XmlTag.class).stream()
                .filter(tag -> {
                    TextRange tagRange = tag.getTextRange();
                    return tagRange.getStartOffset() >= start &&
                            tagRange.getEndOffset() <= end &&
                            BEAN_TAG.equals(tag.getName());
                })
                .collect(Collectors.toList());
    }
    private void processBean(Project project, XmlTag beanTag,
                             JavaPsiFacade psiFacade, PsiElementFactory elementFactory) {
        String className = beanTag.getAttributeValue("class");
        if (className == null) return;

        PsiClass psiClass = psiFacade.findClass(className,
                GlobalSearchScope.projectScope(project));
        if (psiClass == null) return;

        WriteCommandAction.runWriteCommandAction(project, () -> {
            // Add Component annotation if not present
            addComponentAnnotation(psiClass, elementFactory);

            // Process properties
            for (XmlTag propertyTag : beanTag.getSubTags()) {
                if ("property".equals(propertyTag.getName())) {
                    processProperty(propertyTag, psiClass, elementFactory);
                }
            }

            // Process constructor args if present
            XmlTag[] constructorArgs = beanTag.findSubTags("constructor-arg");
            if (constructorArgs.length > 0) {
                processConstructorInjection(constructorArgs, psiClass, elementFactory);
            }
        });
    }

    private void addComponentAnnotation(PsiClass psiClass, PsiElementFactory elementFactory) {
        // Check if @Component or its stereotypes are already present
        if (!hasComponentAnnotation(psiClass)) {
            PsiAnnotation annotation = elementFactory.createAnnotationFromText(
                    "@org.springframework.stereotype.Component", psiClass);
            psiClass.getModifierList().addAfter(annotation, null);

            // Add import
            addImportIfNeeded(psiClass, "org.springframework.stereotype.Component");
        }
    }

    private boolean hasComponentAnnotation(PsiClass psiClass) {
        PsiAnnotation[] annotations = psiClass.getModifierList().getAnnotations();
        for (PsiAnnotation annotation : annotations) {
            String qualifiedName = annotation.getQualifiedName();
            if (qualifiedName != null && (
                    qualifiedName.equals("org.springframework.stereotype.Component") ||
                            qualifiedName.equals("org.springframework.stereotype.Service") ||
                            qualifiedName.equals("org.springframework.stereotype.Repository") ||
                            qualifiedName.equals("org.springframework.stereotype.Controller") ||
                            qualifiedName.equals("org.springframework.stereotype.RestController"))) {
                return true;
            }
        }
        return false;
    }

    private void processProperty(XmlTag propertyTag, PsiClass psiClass,
                                 PsiElementFactory elementFactory) {
        String propertyName = propertyTag.getAttributeValue("name");
        if (propertyName == null) return;

        // Find the field
        PsiField field = psiClass.findFieldByName(propertyName, false);
        if (field == null) return;

        // Add @Autowired annotation if it's a reference
        if (propertyTag.getAttributeValue("ref") != null) {
            if (!hasAutowiredAnnotation(field)) {
                PsiAnnotation autowiredAnnotation = elementFactory.createAnnotationFromText(
                        "@org.springframework.beans.factory.annotation.Autowired", field);
                field.getModifierList().addAfter(autowiredAnnotation, null);

                // Add import
                addImportIfNeeded(psiClass,
                        "org.springframework.beans.factory.annotation.Autowired");
            }
        }

        // Add @Value annotation if it's a value
        String value = propertyTag.getAttributeValue("value");
        if (value != null) {
            PsiAnnotation valueAnnotation = elementFactory.createAnnotationFromText(
                    "@org.springframework.beans.factory.annotation.Value(\"" + value + "\")",
                    field);
            field.getModifierList().addAfter(valueAnnotation, null);

            // Add import
            addImportIfNeeded(psiClass,
                    "org.springframework.beans.factory.annotation.Value");
        }
    }

    private void processConstructorInjection(XmlTag[] constructorArgs,
                                             PsiClass psiClass,
                                             PsiElementFactory elementFactory) {
        // Find the constructor with matching parameters
        PsiMethod[] constructors = psiClass.getConstructors();
        for (PsiMethod constructor : constructors) {
            if (constructor.getParameterList().getParametersCount() == constructorArgs.length) {
                // Add @Autowired to constructor
                if (!hasAutowiredAnnotation(constructor)) {
                    PsiAnnotation autowiredAnnotation = elementFactory.createAnnotationFromText(
                            "@org.springframework.beans.factory.annotation.Autowired",
                            constructor);
                    constructor.getModifierList().addAfter(autowiredAnnotation, null);

                    // Add import
                    addImportIfNeeded(psiClass,
                            "org.springframework.beans.factory.annotation.Autowired");
                }
                break;
            }
        }
    }

    private boolean hasAutowiredAnnotation(PsiModifierListOwner element) {
        PsiModifierList modifierList = element.getModifierList();
        if (modifierList != null) {
            for (PsiAnnotation annotation : modifierList.getAnnotations()) {
                if ("org.springframework.beans.factory.annotation.Autowired"
                        .equals(annotation.getQualifiedName())) {
                    return true;
                }
            }
        }
        return false;
    }

    private void addImportIfNeeded(PsiClass psiClass, String qualifiedName) {
        PsiJavaFile javaFile = (PsiJavaFile) psiClass.getContainingFile();
        PsiImportList importList = javaFile.getImportList();

        if (importList != null) {
            boolean hasImport = false;
            for (PsiImportStatement importStatement : importList.getImportStatements()) {
                if (Objects.equals(importStatement.getQualifiedName(), qualifiedName)) {
                    hasImport = true;
                    break;
                }
            }

            if (!hasImport) {
                PsiElementFactory elementFactory =
                        PsiElementFactory.getInstance(psiClass.getProject());
                importList.add(elementFactory.createImportStatement(
                        Objects.requireNonNull(JavaPsiFacade.getInstance(psiClass.getProject())
                                .findClass(qualifiedName,
                                        GlobalSearchScope.allScope(psiClass.getProject())))
                ));
            }
        }
    }


}
