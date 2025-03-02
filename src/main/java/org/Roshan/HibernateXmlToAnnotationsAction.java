package org.Roshan;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.SelectionModel;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.*;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.xml.*;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.stream.Collectors;

public class HibernateXmlToAnnotationsAction  extends AnAction {
    private static final String HIBERNATE_MAPPING_TAG = "hibernate-mapping";
    private static final String CLASS_TAG = "class";
    private static final String TABLE_TAG = "table";
    private static final String NAME_TAG = "name";
    private static final String COLUMN_TAG = "column";
    private static final String ID_TAG = "id";
    private static final String PROPERTY_TAG = "property";
    private static final String MANY_TO_ONE_TAG = "many-to-one";
    private static final String ONE_TO_MANY_TAG = "one-to-many";
    private static final String MANY_TO_MANY_TAG = "many-to-many";
    private static final String JOIN_COLUMN_TAG = "join-column";
    private static final String JOIN_TABLE_TAG = "join-table";
    private static final String KEY_COLUMN_TAG = "key-column";
    private static final String KEY_MANY_TO_MANY_TAG = "key-many-to-one";
    private static final String KEY_PROPERTY_REF_TAG = "key-property-ref";
    private static final String COLLECTION_ID_TAG = "collection-id";
    private static final String KEY_TAG = "key";
    private static final String VALUE_TAG = "value";
    private static final String COMPOSITE_ID_TAG = "composite-id";

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
        if (rootTag == null || !HIBERNATE_MAPPING_TAG.equals(rootTag.getName())) {
            return;
        }
        System.out.println(" root tag is : " + rootTag.getName());

            convertHibernateXmlToAnnotations(project, xmlFile, editor);

    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        PsiFile psiFile = e.getData(CommonDataKeys.PSI_FILE);
        e.getPresentation().setEnabledAndVisible(
                project != null && psiFile instanceof XmlFile
        );
    }
    protected void convertHibernateXmlToAnnotations(Project project, XmlFile xmlFile, Editor editor) {
        XmlTag rootTag = xmlFile.getRootTag();

        JavaPsiFacade psiFacade = JavaPsiFacade.getInstance(project);
        PsiElementFactory elementFactory = PsiElementFactory.getInstance(project);

        WriteCommandAction.runWriteCommandAction(project, () -> {
            List<XmlTag> classesToProcess = getClassTagsToProcess(rootTag, editor);
            processHibernateClasses(classesToProcess, psiFacade, elementFactory);
        });
    }

    private List<XmlTag> getClassTagsToProcess(XmlTag rootTag, Editor editor) {
        SelectionModel selectionModel = editor.getSelectionModel();

        if (!selectionModel.hasSelection()) {
            return Arrays.stream(rootTag.getSubTags())
                    .filter(tag -> CLASS_TAG.equals(tag.getName()))
                    .collect(Collectors.toList());
        }

        int start = selectionModel.getSelectionStart();
        int end = selectionModel.getSelectionEnd();

        return PsiTreeUtil.findChildrenOfType(rootTag, XmlTag.class).stream()
                .filter(tag -> {
                    TextRange tagRange = tag.getTextRange();
                    return tagRange.getStartOffset() >= start &&
                            tagRange.getEndOffset() <= end &&
                            CLASS_TAG.equals(tag.getName());
                })
                .collect(Collectors.toList());
    }

    private void processHibernateClasses(List<XmlTag> classTags,
                                         JavaPsiFacade psiFacade,
                                         PsiElementFactory elementFactory) {
        classTags.forEach(classTag -> processHibernateClass(classTag, psiFacade, elementFactory));
    }

    private void processHibernateClass(XmlTag classTag,
                                       JavaPsiFacade psiFacade,
                                       PsiElementFactory elementFactory) {
        String className = classTag.getAttributeValue(NAME_TAG);
        if (className == null) return;

        PsiClass psiClass = psiFacade.findClass(className,
                GlobalSearchScope.projectScope(psiFacade.getProject()));
        if (psiClass == null) return;

        addEntityAnnotations(psiClass, classTag, elementFactory);
        processClassElements(psiClass, classTag, elementFactory);
    }

    private void addEntityAnnotations(PsiClass psiClass, XmlTag classTag, PsiElementFactory elementFactory) {
        // Add @Entity
        addAnnotationIfNotPresent(psiClass, "@Entity", elementFactory);

        // Add @Table with name and schema if specified
        String tableName = classTag.getAttributeValue(TABLE_TAG);
        String schema = classTag.getAttributeValue("schema");
        if (tableName != null) {
            StringBuilder tableAnnotation = new StringBuilder("@Table(");
            tableAnnotation.append(String.format("name = \"%s\"", tableName));
            if (schema != null) {
                tableAnnotation.append(String.format(", schema = \"%s\"", schema));
            }
            String catalog = classTag.getAttributeValue("catalog");
            if (catalog != null) {
                tableAnnotation.append(String.format(", catalog = \"%s\"", catalog));
            }
            tableAnnotation.append(")");
            addAnnotationIfNotPresent(psiClass, tableAnnotation.toString(), elementFactory);
        }
    }


    private void processClassElements(PsiClass psiClass, XmlTag classTag, PsiElementFactory elementFactory) {
        Arrays.stream(classTag.getSubTags()).forEach(tag -> {
            switch (tag.getName()) {
                case ID_TAG -> processId(psiClass, tag, elementFactory);
                case PROPERTY_TAG -> processProperty(psiClass, tag, elementFactory);
                case MANY_TO_ONE_TAG -> processManyToOne(psiClass, tag, elementFactory);
                case ONE_TO_MANY_TAG -> processOneToMany(psiClass, tag, elementFactory);
                case MANY_TO_MANY_TAG -> processManyToMany(psiClass, tag, elementFactory);
                case COMPOSITE_ID_TAG -> processCompositeId(psiClass, tag, elementFactory);
            }
        });
    }

    private void processId(PsiClass psiClass, XmlTag idTag, PsiElementFactory elementFactory) {
        String fieldName = idTag.getAttributeValue(NAME_TAG);
        if (fieldName == null) return;

        PsiField field = psiClass.findFieldByName(fieldName, false);
        if (field == null) return;

        // Add @Id
        addAnnotationIfNotPresent(field, "@Id", elementFactory);

        // Process generator strategy
        String generator = idTag.getAttributeValue("generator-class");
        if (generator != null) {
            String strategy = getGeneratorStrategy(generator);
            String generatorAnnotation = String.format(
                    "@GeneratedValue(strategy = GenerationType.%s)",
                    strategy
            );
            addAnnotationIfNotPresent(field, generatorAnnotation, elementFactory);
        }
        // Process column details
        processColumnDetails(field, idTag, elementFactory);
    }

    private void processProperty(PsiClass psiClass, @NotNull XmlTag propertyTag, PsiElementFactory elementFactory) {
        String fieldName = propertyTag.getAttributeValue(NAME_TAG);
        if (fieldName == null) return;

        PsiField field = psiClass.findFieldByName(fieldName, false);
        if (field == null) return;

        processColumnDetails(field, propertyTag, elementFactory);
        // Handle temporal types for Date fields
        String type = propertyTag.getAttributeValue("type");
        if (type != null && type.contains("timestamp")) {
            addAnnotationIfNotPresent(field,
                    "@Temporal(TemporalType.TIMESTAMP)",
                    elementFactory);
        }
    }

    private void processManyToOne(PsiClass psiClass, XmlTag relationTag, PsiElementFactory elementFactory) {
        String fieldName = relationTag.getAttributeValue(NAME_TAG);
        if (fieldName == null) return;

        PsiField field = psiClass.findFieldByName(fieldName, false);
        if (field == null) return;

        // Add @ManyToOne
        StringBuilder annotation = new StringBuilder("@ManyToOne(");
        String fetch = relationTag.getAttributeValue("fetch");
        if (fetch != null) {
            annotation.append(String.format("fetch = FetchType.%s", fetch.toUpperCase()));
        }
        annotation.append(")");
        addAnnotationIfNotPresent(field, annotation.toString(), elementFactory);

        // Process JoinColumn
        String column = relationTag.getAttributeValue(COLUMN_TAG);
        if (column != null) {
            String joinColumnAnnotation = String.format(
                    "@JoinColumn(name = \"%s\")",
                    column
            );
            addAnnotationIfNotPresent(field, joinColumnAnnotation, elementFactory);
        }
    }

    private void processOneToMany(PsiClass psiClass, XmlTag relationTag, PsiElementFactory elementFactory) {
        String fieldName = relationTag.getAttributeValue(NAME_TAG);
        if (fieldName == null) return;

        PsiField field = psiClass.findFieldByName(fieldName, false);
        if (field == null) return;

        StringBuilder annotation = new StringBuilder("@OneToMany(");
        List<String> attributes = new ArrayList<>();

        String mappedBy = relationTag.getAttributeValue("mapped-by");
        if (mappedBy != null) {
            attributes.add(String.format("mappedBy = \"%s\"", mappedBy));
        }

        String fetch = relationTag.getAttributeValue("fetch");
        if (fetch != null) {
            attributes.add(String.format("fetch = FetchType.%s", fetch.toUpperCase()));
        }

        annotation.append(String.join(", ", attributes)).append(")");
        addAnnotationIfNotPresent(field, annotation.toString(), elementFactory);
    }

    private void processManyToMany(PsiClass psiClass, XmlTag relationTag, PsiElementFactory elementFactory) {
        String fieldName = relationTag.getAttributeValue(NAME_TAG);
        if (fieldName == null) return;

        PsiField field = psiClass.findFieldByName(fieldName, false);
        if (field == null) return;

        StringBuilder annotation = new StringBuilder("@ManyToMany(");
        List<String> attributes = new ArrayList<>();

        String mappedBy = relationTag.getAttributeValue("mapped-by");
        if (mappedBy != null) {
            attributes.add(String.format("mappedBy = \"%s\"", mappedBy));
        }

        String fetch = relationTag.getAttributeValue("fetch");
        if (fetch != null) {
            attributes.add(String.format("fetch = FetchType.%s", fetch.toUpperCase()));
        }

        annotation.append(String.join(", ", attributes)).append(")");
        addAnnotationIfNotPresent(field, annotation.toString(), elementFactory);

        // Process JoinTable if present
        XmlTag joinTable = relationTag.findFirstSubTag("join-table");
        if (joinTable != null) {
            processJoinTable(field, joinTable, elementFactory);
        }
    }

    private void processColumnDetails(PsiField field, XmlTag tag, PsiElementFactory elementFactory) {
        String column = tag.getAttributeValue("column");
        if (column == null) return;

        List<String> columnAttributes = new ArrayList<>();
        columnAttributes.add(String.format("name = \"%s\"", column));

        String length = tag.getAttributeValue("length");
        if (length != null) {
            columnAttributes.add(String.format("length = %s", length));
        }

        String nullable = tag.getAttributeValue("not-null");
        if (nullable != null) {
            columnAttributes.add(String.format("nullable = %s", !Boolean.parseBoolean(nullable)));
        }

        String unique = tag.getAttributeValue("unique");
        if (unique != null) {
            columnAttributes.add(String.format("unique = %s", unique));
        }

        String columnAnnotation = "@Column(" +
                String.join(", ", columnAttributes) +
                ")";
        addAnnotationIfNotPresent(field, columnAnnotation, elementFactory);
    }

    private void processJoinTable(PsiField field, XmlTag joinTableTag, PsiElementFactory elementFactory) {
        StringBuilder annotation = new StringBuilder("@JoinTable(");
        List<String> attributes = new ArrayList<>();

        String tableName = joinTableTag.getAttributeValue(NAME_TAG);
        if (tableName != null) {
            attributes.add(String.format("name = \"%s\"", tableName));
        }

        // Process join columns
        processJoinColumns(joinTableTag, attributes);

        annotation.append(String.join(", ", attributes)).append(")");
        addAnnotationIfNotPresent(field, annotation.toString(), elementFactory);
    }

    private void processJoinColumns(XmlTag joinTableTag, List<String> attributes) {
        XmlTag[] joinColumns = joinTableTag.findSubTags("join-column");
        if (joinColumns.length > 0) {
            StringBuilder joinColumnsStr = new StringBuilder("joinColumns = {");
            String columns = Arrays.stream(joinColumns)
                    .map(this::createJoinColumnString)
                    .collect(Collectors.joining(", "));
            joinColumnsStr.append(columns).append("}");
            attributes.add(joinColumnsStr.toString());
        }
    }

    private String createJoinColumnString(XmlTag joinColumnTag) {
        List<String> attributes = new ArrayList<>();

        String name = joinColumnTag.getAttributeValue(NAME_TAG);
        if (name != null) {
            attributes.add(String.format("name = \"%s\"", name));
        }

        String referencedColumnName = joinColumnTag.getAttributeValue("referenced-column-name");
        if (referencedColumnName != null) {
            attributes.add(String.format("referencedColumnName = \"%s\"", referencedColumnName));
        }

        return "@JoinColumn(" +
                String.join(", ", attributes) +
                ")";
    }

    private void addColumnAnnotation(PsiField field,
                                     XmlTag tag,
                                     PsiElementFactory elementFactory) {
        List<String> attributes = new ArrayList<>();

        String column = tag.getAttributeValue("column");
        if (column != null) {
            attributes.add(String.format("name = \"%s\"", column));
        }

        String length = tag.getAttributeValue("length");
        if (length != null) {
            attributes.add(String.format("length = %s", length));
        }

        String nullable = tag.getAttributeValue("not-null");
        if (nullable != null) {
            attributes.add(String.format("nullable = %s", !Boolean.parseBoolean(nullable)));
        }

        String unique = tag.getAttributeValue("unique");
        if (unique != null) {
            attributes.add(String.format("unique = %s", unique));
        }

        if (!attributes.isEmpty()) {
            String annotation = "@Column(" +
                    String.join(", ", attributes) +
                    ")";
            addAnnotationIfNotPresent(field, annotation, elementFactory);
        }
    }

    private String getGeneratorStrategy(String generator) {
        return switch (generator.toLowerCase()) {
            case "identity" -> "IDENTITY";
            case "sequence" -> "SEQUENCE";
            case "table" -> "TABLE";
            default -> "AUTO";
        };
    }
    /**
     * Adds an annotation to a PsiModifierListOwner (class, field, or method) if it's not already present.
     * Handles both simple and complex annotations with attributes.
     *
     * @param element        The element to add the annotation to
     * @param annotationText The full annotation text
     * @param elementFactory The PsiElementFactory instance
     */
    private void addAnnotationIfNotPresent(PsiModifierListOwner element,
                                           String annotationText,
                                           PsiElementFactory elementFactory) {
        PsiModifierList modifierList = element.getModifierList();
        if (modifierList == null) return;

        // Extract the full qualified name of the annotation
        String qualifiedName = extractQualifiedName(annotationText);
        if (qualifiedName == null) return;

        // Check if annotation already exists
        if (hasAnnotation(modifierList, qualifiedName)) {
            return;
        }

        try {
            // Create and add the annotation
            PsiAnnotation annotation = elementFactory.createAnnotationFromText(
                    annotationText,
                    element
            );
            modifierList.addAfter(annotation, null);
        } catch (Exception e) {
            // Log error or handle exception
            System.out.println(
                    "Failed to add annotation: " + annotationText);
        }
    }

    /**
     * Extracts the fully qualified name from an annotation text.
     *
     * @param annotationText The full annotation text
     * @return The qualified name of the annotation
     */
    private String extractQualifiedName(String annotationText) {
        if (annotationText == null || annotationText.isEmpty()) {
            return null;
        }

        // Remove the @ symbol if present
        String text = annotationText.startsWith("@") ?
                annotationText.substring(1) : annotationText;

        // Extract the name part before any parentheses
        int parenthesesIndex = text.indexOf('(');
        return parenthesesIndex > 0 ?
                text.substring(0, parenthesesIndex).trim() :
                text.trim();
    }

    /**
     * Checks if the modifier list already contains an annotation with the given qualified name.
     *
     * @param modifierList  The modifier list to check
     * @param qualifiedName The qualified name of the annotation
     * @return true if the annotation exists, false otherwise
     */
    private boolean hasAnnotation(PsiModifierList modifierList, String qualifiedName) {
        // Check for existing annotation using both qualified and simple names
        String simpleName = getSimpleName(qualifiedName);

        return Arrays.stream(modifierList.getAnnotations())
                .anyMatch(existing -> {
                    String existingQualifiedName = existing.getQualifiedName();
                    if (existingQualifiedName == null) return false;

                    return existingQualifiedName.equals(qualifiedName) ||
                            existingQualifiedName.equals(simpleName);
                });
    }

    /**
     * Gets the simple name of an annotation from its qualified name.
     *
     * @param qualifiedName The qualified name of the annotation
     * @return The simple name
     */
    private String getSimpleName(String qualifiedName) {
        int lastDot = qualifiedName.lastIndexOf('.');
        return lastDot > 0 ? qualifiedName.substring(lastDot + 1) : qualifiedName;
    }

    /**
     * Utility method to create a complex annotation with multiple attributes.
     *
     * @param annotationName The name of the annotation
     * @param attributes     Map of attribute names and values
     * @return The complete annotation text
     */
    private String buildAnnotationText(String annotationName,
                                       Map<String, String> attributes) {
        if (attributes.isEmpty()) {
            return "@" + annotationName;
        }

        StringBuilder builder = new StringBuilder("@").append(annotationName).append("(");

        String attributesStr = attributes.entrySet().stream()
                .map(entry -> entry.getKey() + " = " + entry.getValue())
                .collect(Collectors.joining(", "));

        return builder.append(attributesStr).append(")").toString();
    }


    /**
     * Adds multiple annotations at once
     *
     * @param element        The element to add annotations to
     * @param annotations    List of annotation texts
     * @param elementFactory The PsiElementFactory instance
     */
    private void addMultipleAnnotations(PsiModifierListOwner element,
                                        List<String> annotations,
                                        PsiElementFactory elementFactory) {
        annotations.forEach(annotation ->
                addAnnotationIfNotPresent(element, annotation, elementFactory));
    }
    /**
     * Processes composite-id elements and converts them to appropriate JPA annotations
     */
    private void processCompositeId(PsiClass psiClass, XmlTag compositeIdTag, PsiElementFactory elementFactory) {
        // Add @IdClass or @EmbeddedId annotation based on the mapping strategy
        if (isEmbeddableStrategy(compositeIdTag)) {
            processEmbeddedIdStrategy(psiClass, compositeIdTag, elementFactory);
        } else {
            processIdClassStrategy(psiClass, compositeIdTag, elementFactory);
        }
    }

    /**
     * Determines if the composite-id should use @EmbeddedId strategy
     */
    private boolean isEmbeddableStrategy(XmlTag compositeIdTag) {
        XmlTag classTag = compositeIdTag.findFirstSubTag("class");
        return classTag != null;
    }

    /**
     * Processes composite-id using @EmbeddedId strategy
     */
    private void processEmbeddedIdStrategy(PsiClass psiClass, XmlTag compositeIdTag, PsiElementFactory elementFactory) {
        // Find the id field
        String idFieldName = compositeIdTag.getAttributeValue(NAME_TAG);
        if (idFieldName == null) return;

        PsiField idField = psiClass.findFieldByName(idFieldName, false);
        if (idField == null) return;

        // Add @EmbeddedId annotation to the field
        addAnnotationIfNotPresent(idField, "@EmbeddedId", elementFactory);

        // Process the embedded class
        XmlTag classTag = compositeIdTag.findFirstSubTag("class");
        if (classTag != null) {
            String embeddedClassName = classTag.getAttributeValue(NAME_TAG);
            PsiClass embeddedClass = JavaPsiFacade.getInstance(psiClass.getProject())
                    .findClass(embeddedClassName, GlobalSearchScope.allScope(psiClass.getProject()));
            if (embeddedClass != null) {
                processEmbeddableClass(embeddedClass, classTag, elementFactory);
            }
        }
    }


    /**
     * Processes the embeddable class for composite id
     */
    private void processEmbeddableClass(PsiClass embeddedClass, XmlTag classTag, PsiElementFactory elementFactory) {
        // Add @Embeddable annotation
        addAnnotationIfNotPresent(embeddedClass, "@Embeddable", elementFactory);

        // Process key properties
        Arrays.stream(classTag.findSubTags("key-property"))
                .forEach(keyPropertyTag -> processKeyProperty(embeddedClass, keyPropertyTag, elementFactory));

        // Process many-to-one references in composite key
        Arrays.stream(classTag.findSubTags("key-many-to-one"))
                .forEach(keyManyToOneTag -> processKeyManyToOne(embeddedClass, keyManyToOneTag, elementFactory));
    }

    /**
     * Processes key-property elements within composite-id
     */
    private void processKeyProperty(PsiClass embeddedClass, XmlTag keyPropertyTag, PsiElementFactory elementFactory) {
        String propertyName = keyPropertyTag.getAttributeValue(NAME_TAG);
        if (propertyName == null) return;

        PsiField field = embeddedClass.findFieldByName(propertyName, false);
        if (field == null) return;

        // Add @Column annotation with appropriate attributes
        Map<String, String> columnAttributes = new HashMap<>();

        String column = keyPropertyTag.getAttributeValue("column");
        if (column != null) {
            columnAttributes.put("name", "\"" + column + "\"");
        }

        String length = keyPropertyTag.getAttributeValue("length");
        if (length != null) {
            columnAttributes.put("length", length);
        }

        addAnnotationIfNotPresent(field,
                buildAnnotationText("Column", columnAttributes),
                elementFactory);
    }

    /**
     * Processes key-many-to-one elements within composite-id
     */
    private void processKeyManyToOne(PsiClass embeddedClass, XmlTag keyManyToOneTag, PsiElementFactory elementFactory) {
        String propertyName = keyManyToOneTag.getAttributeValue(NAME_TAG);
        if (propertyName == null) return;

        PsiField field = embeddedClass.findFieldByName(propertyName, false);
        if (field == null) return;

        // Add @ManyToOne annotation
        addAnnotationIfNotPresent(field, "@ManyToOne", elementFactory);

        // Add @JoinColumn annotation
        String column = keyManyToOneTag.getAttributeValue("column");
        if (column != null) {
            Map<String, String> joinColumnAttributes = new HashMap<>();
            joinColumnAttributes.put("name", "\"" + column + "\"");

            addAnnotationIfNotPresent(field,
                    buildAnnotationText("JoinColumn", joinColumnAttributes),
                    elementFactory);
        }
    }

    /**
     * Processes composite-id using @IdClass strategy
     */
    private void processIdClassStrategy(PsiClass psiClass, XmlTag compositeIdTag, PsiElementFactory elementFactory) {
        // Add @IdClass annotation to the entity class
        String idClassName = compositeIdTag.getAttributeValue("class");
        if (idClassName != null) {
            String idClassAnnotation = String.format("@IdClass(%s.class)", idClassName);
            addAnnotationIfNotPresent(psiClass, idClassAnnotation, elementFactory);
        }

        // Process key properties
        Arrays.stream(compositeIdTag.findSubTags("key-property"))
                .forEach(keyPropertyTag -> processIdClassProperty(psiClass, keyPropertyTag, elementFactory));

        // Process many-to-one references
        Arrays.stream(compositeIdTag.findSubTags("key-many-to-one"))
                .forEach(keyManyToOneTag -> processIdClassManyToOne(psiClass, keyManyToOneTag, elementFactory));
    }

    /**
     * Processes properties for @IdClass strategy
     */
    private void processIdClassProperty(PsiClass psiClass, XmlTag keyPropertyTag, PsiElementFactory elementFactory) {
        String propertyName = keyPropertyTag.getAttributeValue(NAME_TAG);
        if (propertyName == null) return;

        PsiField field = psiClass.findFieldByName(propertyName, false);
        if (field == null) return;

        // Add @Id annotation
        addAnnotationIfNotPresent(field, "@Id", elementFactory);

        // Add @Column annotation
        Map<String, String> columnAttributes = new HashMap<>();

        String column = keyPropertyTag.getAttributeValue("column");
        if (column != null) {
            columnAttributes.put("name", "\"" + column + "\"");
        }

        addAnnotationIfNotPresent(field,
                buildAnnotationText("Column", columnAttributes),
                elementFactory);
    }

    /**
     * Processes many-to-one references for @IdClass strategy
     */
    private void processIdClassManyToOne(PsiClass psiClass, XmlTag keyManyToOneTag, PsiElementFactory elementFactory) {
        String propertyName = keyManyToOneTag.getAttributeValue(NAME_TAG);
        if (propertyName == null) return;

        PsiField field = psiClass.findFieldByName(propertyName, false);
        if (field == null) return;

        // Add @Id annotation
        addAnnotationIfNotPresent(field, "@Id", elementFactory);

        // Add @ManyToOne annotation
        addAnnotationIfNotPresent(field, "@ManyToOne", elementFactory);

        // Add @JoinColumn annotation
        String column = keyManyToOneTag.getAttributeValue("column");
        if (column != null) {
            Map<String, String> joinColumnAttributes = new HashMap<>();
            joinColumnAttributes.put("name", "\"" + column + "\"");

            addAnnotationIfNotPresent(field,
                    buildAnnotationText("JoinColumn", joinColumnAttributes),
                    elementFactory);
        }
    }
}
