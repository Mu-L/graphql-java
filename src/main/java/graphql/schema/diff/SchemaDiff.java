package graphql.schema.diff;

import graphql.Assert;
import graphql.PublicSpi;
import graphql.introspection.IntrospectionResultToSchema;
import graphql.language.Argument;
import graphql.language.Directive;
import graphql.language.DirectivesContainer;
import graphql.language.Document;
import graphql.language.EnumTypeDefinition;
import graphql.language.EnumValueDefinition;
import graphql.language.FieldDefinition;
import graphql.language.InputObjectTypeDefinition;
import graphql.language.InputValueDefinition;
import graphql.language.InterfaceTypeDefinition;
import graphql.language.ObjectTypeDefinition;
import graphql.language.OperationTypeDefinition;
import graphql.language.ScalarTypeDefinition;
import graphql.language.SchemaDefinition;
import graphql.language.Type;
import graphql.language.TypeDefinition;
import graphql.language.TypeKind;
import graphql.language.TypeName;
import graphql.language.UnionTypeDefinition;
import graphql.language.Value;
import graphql.schema.diff.reporting.DifferenceReporter;
import graphql.schema.idl.TypeInfo;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

import static graphql.language.TypeKind.getTypeKind;
import static graphql.schema.idl.TypeInfo.getAstDesc;
import static graphql.schema.idl.TypeInfo.typeInfo;
import static graphql.util.StringKit.capitalize;

/**
 * The SchemaDiff is called with a {@link DiffSet} and will report the
 * differences in the graphql schema APIs by raising events to a
 * {@link graphql.schema.diff.reporting.DifferenceReporter}
 */
@SuppressWarnings("ConstantConditions")
@PublicSpi
public class SchemaDiff {

    /**
     * Options for controlling the diffing process
     */
    public static class Options {

        final boolean enforceDirectives;

        Options(boolean enforceDirectives) {
            this.enforceDirectives = enforceDirectives;
        }

        public Options enforceDirectives() {
            return new Options(true);
        }

        public static Options defaultOptions() {
            return new Options(false);
        }

    }

    private static class CountingReporter implements DifferenceReporter {
        final DifferenceReporter delegate;
        int breakingCount = 0;

        private CountingReporter(DifferenceReporter delegate) {
            this.delegate = delegate;
        }

        @Override
        public void report(DiffEvent differenceEvent) {
            if (differenceEvent.getLevel().equals(DiffLevel.BREAKING)) {
                breakingCount++;
            }
            delegate.report(differenceEvent);
        }

        @Override
        public void onEnd() {
            delegate.onEnd();
        }
    }

    private final Options options;

    /**
     * Constructs a differ using default options
     */
    public SchemaDiff() {
        this(Options.defaultOptions());
    }


    /**
     * Constructs a differ with the specified options
     *
     * @param options the controlling options
     */
    public SchemaDiff(Options options) {
        this.options = options;
    }


    /**
     * This will perform a difference on the two schemas.  The reporter callback
     * interface will be called when differences are encountered.
     *
     * @param diffSet  the two schemas to compare for difference
     * @param reporter the place to report difference events to
     *
     * @return the number of API breaking changes
     */
    @Deprecated(since = "2023-10-04")
    @SuppressWarnings("unchecked")
    public int diffSchema(DiffSet diffSet, DifferenceReporter reporter) {
        CountingReporter countingReporter = new CountingReporter(reporter);
        Document oldDoc = new IntrospectionResultToSchema().createSchemaDefinition(diffSet.getOld());
        Document newDoc = new IntrospectionResultToSchema().createSchemaDefinition(diffSet.getNew());
        diffSchemaImpl(oldDoc, newDoc, countingReporter);
        return countingReporter.breakingCount;
    }

    /**
     * This will perform a difference on the two schemas.  The reporter callback
     * interface will be called when differences are encountered.
     *
     * @param schemaDiffSet the two schemas to compare for difference
     * @param reporter      the place to report difference events to
     *
     * @return the number of API breaking changes
     */
    @SuppressWarnings("unchecked")
    public int diffSchema(SchemaDiffSet schemaDiffSet, DifferenceReporter reporter) {
        if (options.enforceDirectives) {
            Assert.assertTrue(schemaDiffSet.supportsEnforcingDirectives(), () ->
                    "The provided schema diff set implementation does not supporting enforcing directives during schema diff.");
        }

        CountingReporter countingReporter = new CountingReporter(reporter);
        diffSchemaImpl(schemaDiffSet.getOldSchemaDefinitionDoc(), schemaDiffSet.getNewSchemaDefinitionDoc(), countingReporter);
        return countingReporter.breakingCount;
    }

    private void diffSchemaImpl(Document oldDoc, Document newDoc, DifferenceReporter reporter) {
        DiffCtx ctx = new DiffCtx(reporter, oldDoc, newDoc);
        Optional<SchemaDefinition> oldSchemaDef = getSchemaDef(oldDoc);
        Optional<SchemaDefinition> newSchemaDef = getSchemaDef(newDoc);

        // check query operation
        checkOperation(ctx, "query", oldSchemaDef, newSchemaDef);
        checkOperation(ctx, "mutation", oldSchemaDef, newSchemaDef);
        checkOperation(ctx, "subscription", oldSchemaDef, newSchemaDef);

        reporter.onEnd();
    }

    private void checkOperation(DiffCtx ctx, String opName, Optional<SchemaDefinition> oldSchemaDef, Optional<SchemaDefinition> newSchemaDef) {
        // if schema declaration is missing then it is assumed to contain Query / Mutation / Subscription
        Optional<OperationTypeDefinition> oldOpTypeDef;
        oldOpTypeDef = oldSchemaDef
                .map(schemaDefinition -> getOpDef(opName, schemaDefinition))
                .orElseGet(() -> synthOperationTypeDefinition(type -> ctx.getOldTypeDef(type, ObjectTypeDefinition.class), opName));

        Optional<OperationTypeDefinition> newOpTypeDef;
        newOpTypeDef = newSchemaDef
                .map(schemaDefinition -> getOpDef(opName, schemaDefinition))
                .orElseGet(() -> synthOperationTypeDefinition(type -> ctx.getNewTypeDef(type, ObjectTypeDefinition.class), opName));

        // must be new
        if (!oldOpTypeDef.isPresent()) {
            return;
        }

        ctx.report(DiffEvent.apiInfo()
                .typeName(capitalize(opName))
                .typeKind(TypeKind.Operation)
                .components(opName)
                .reasonMsg("Examining operation '%s' ...", capitalize(opName))
                .build());

        if (oldOpTypeDef.isPresent() && !newOpTypeDef.isPresent()) {
            ctx.report(DiffEvent.apiBreakage()
                    .category(DiffCategory.MISSING)
                    .typeName(capitalize(opName))
                    .typeKind(TypeKind.Operation)
                    .components(opName)
                    .reasonMsg("The new API no longer has the operation '%s'", opName)
                    .build());
            return;
        }

        OperationTypeDefinition oldOpTypeDefinition = oldOpTypeDef.get();
        OperationTypeDefinition newOpTypeDefinition = newOpTypeDef.get();

        Type oldType = oldOpTypeDefinition.getTypeName();
        //
        // if we have no old op, then it must have been added (which is ok)
        Optional<TypeDefinition> oldTD = ctx.getOldTypeDef(oldType, TypeDefinition.class);
        if (!oldTD.isPresent()) {
            return;
        }
        checkType(ctx, oldType, newOpTypeDefinition.getTypeName());
    }

    private void checkType(DiffCtx ctx, Type oldType, Type newType) {
        String typeName = getTypeName(oldType);

        // prevent circular references
        if (ctx.examiningType(typeName)) {
            return;
        }
        if (isSystemScalar(typeName)) {
            return;
        }
        if (isReservedType(typeName)) {
            return;
        }
        Optional<TypeDefinition> oldTD = ctx.getOldTypeDef(oldType, TypeDefinition.class);
        Optional<TypeDefinition> newTD = ctx.getNewTypeDef(newType, TypeDefinition.class);

        if (!oldTD.isPresent()) {
            ctx.report(DiffEvent.apiInfo()
                    .typeName(typeName)
                    .reasonMsg("Type '%s' is missing", typeName)
                    .build());
            return;

        }
        TypeDefinition oldDef = oldTD.get();

        ctx.report(DiffEvent.apiInfo()
                .typeName(typeName)
                .typeKind(getTypeKind(oldDef))
                .reasonMsg("Examining type '%s' ...", typeName)
                .build());

        if (!newTD.isPresent()) {
            ctx.report(DiffEvent.apiBreakage()
                    .category(DiffCategory.MISSING)
                    .typeName(typeName)
                    .typeKind(getTypeKind(oldDef))
                    .reasonMsg("The new API does not have a type called '%s'", typeName)
                    .build());
            ctx.exitType();
            return;
        }
        TypeDefinition newDef = newTD.get();
        if (!oldDef.getClass().equals(newDef.getClass())) {
            ctx.report(DiffEvent.apiBreakage()
                    .category(DiffCategory.INVALID)
                    .typeName(typeName)
                    .typeKind(getTypeKind(oldDef))
                    .components(getTypeKind(oldDef), getTypeKind(newDef))
                    .reasonMsg("The new API has changed '%s' from a '%s' to a '%s'", typeName, getTypeKind(oldDef), getTypeKind(newDef))
                    .build());
            ctx.exitType();
            return;
        }
        if (oldDef instanceof ObjectTypeDefinition) {
            checkObjectType(ctx, (ObjectTypeDefinition) oldDef, (ObjectTypeDefinition) newDef);
        }
        if (oldDef instanceof InterfaceTypeDefinition) {
            checkInterfaceType(ctx, (InterfaceTypeDefinition) oldDef, (InterfaceTypeDefinition) newDef);
        }
        if (oldDef instanceof UnionTypeDefinition) {
            checkUnionType(ctx, (UnionTypeDefinition) oldDef, (UnionTypeDefinition) newDef);
        }
        if (oldDef instanceof InputObjectTypeDefinition) {
            checkInputObjectType(ctx, (InputObjectTypeDefinition) oldDef, (InputObjectTypeDefinition) newDef);
        }
        if (oldDef instanceof EnumTypeDefinition) {
            checkEnumType(ctx, (EnumTypeDefinition) oldDef, (EnumTypeDefinition) newDef);
        }
        if (oldDef instanceof ScalarTypeDefinition) {
            checkScalarType(ctx, (ScalarTypeDefinition) oldDef, (ScalarTypeDefinition) newDef);
        }
        ctx.exitType();
    }

    private boolean isDeprecated(DirectivesContainer<?> node) {
        return node.hasDirective("deprecated");
    }

    private boolean isReservedType(String typeName) {
        return typeName.startsWith("__");
    }

    private final static Set<String> SYSTEM_SCALARS = new LinkedHashSet<>();

    static {
        SYSTEM_SCALARS.add("ID");
        SYSTEM_SCALARS.add("Boolean");
        SYSTEM_SCALARS.add("String");
        SYSTEM_SCALARS.add("Byte");
        SYSTEM_SCALARS.add("Char");
        SYSTEM_SCALARS.add("Short");
        SYSTEM_SCALARS.add("Int");
        SYSTEM_SCALARS.add("Long");
        SYSTEM_SCALARS.add("Float");
        SYSTEM_SCALARS.add("Double");
        SYSTEM_SCALARS.add("BigInteger");
        SYSTEM_SCALARS.add("BigDecimal");
    }

    private boolean isSystemScalar(String typeName) {
        return SYSTEM_SCALARS.contains(typeName);
    }

    private void checkObjectType(DiffCtx ctx, ObjectTypeDefinition oldDef, ObjectTypeDefinition newDef) {
        Map<String, FieldDefinition> oldFields = sortedMap(oldDef.getFieldDefinitions(), FieldDefinition::getName);
        Map<String, FieldDefinition> newFields = sortedMap(newDef.getFieldDefinitions(), FieldDefinition::getName);

        checkFields(ctx, oldDef, oldFields, newDef, newFields);

        checkImplements(ctx, oldDef, oldDef.getImplements(), newDef.getImplements());

        checkDirectives(ctx, oldDef, newDef);
    }

    private void checkInterfaceType(DiffCtx ctx, InterfaceTypeDefinition oldDef, InterfaceTypeDefinition newDef) {
        Map<String, FieldDefinition> oldFields = sortedMap(oldDef.getFieldDefinitions(), FieldDefinition::getName);
        Map<String, FieldDefinition> newFields = sortedMap(newDef.getFieldDefinitions(), FieldDefinition::getName);

        checkFields(ctx, oldDef, oldFields, newDef, newFields);

        checkDirectives(ctx, oldDef, newDef);
    }

    private void checkUnionType(DiffCtx ctx, UnionTypeDefinition oldDef, UnionTypeDefinition newDef) {
        Map<String, Type> oldMemberTypes = sortedMap(oldDef.getMemberTypes(), SchemaDiff::getTypeName);
        Map<String, Type> newMemberTypes = sortedMap(newDef.getMemberTypes(), SchemaDiff::getTypeName);


        for (Map.Entry<String, Type> entry : oldMemberTypes.entrySet()) {
            String oldMemberTypeName = entry.getKey();
            if (!newMemberTypes.containsKey(oldMemberTypeName)) {
                ctx.report(DiffEvent.apiBreakage()
                        .category(DiffCategory.MISSING)
                        .typeName(oldDef.getName())
                        .typeKind(getTypeKind(oldDef))
                        .components(oldMemberTypeName)
                        .reasonMsg("The new API does not contain union member type '%s'", oldMemberTypeName)
                        .build());
            } else {
                // check type which is in the old and the new Union def
                checkType(ctx, entry.getValue(), newMemberTypes.get(oldMemberTypeName));
            }
        }
        for (Map.Entry<String, Type> entry : newMemberTypes.entrySet()) {
            String newMemberTypeName = entry.getKey();
            if (!oldMemberTypes.containsKey(newMemberTypeName)) {
                ctx.report(DiffEvent.apiDanger()
                        .category(DiffCategory.ADDITION)
                        .typeName(oldDef.getName())
                        .typeKind(getTypeKind(oldDef))
                        .components(newMemberTypeName)
                        .reasonMsg("The new API has added a new union member type '%s'", newMemberTypeName)
                        .build());
            }
        }
        checkDirectives(ctx, oldDef, newDef);
    }


    private void checkInputObjectType(DiffCtx ctx, InputObjectTypeDefinition oldDef, InputObjectTypeDefinition newDef) {

        checkInputFields(ctx, oldDef, oldDef.getInputValueDefinitions(), newDef.getInputValueDefinitions());

        checkDirectives(ctx, oldDef, newDef);
    }

    private void checkInputFields(DiffCtx ctx, TypeDefinition old, List<InputValueDefinition> oldIVD, List<InputValueDefinition> newIVD) {
        Map<String, InputValueDefinition> oldDefinitionMap = sortedMap(oldIVD, InputValueDefinition::getName);
        Map<String, InputValueDefinition> newDefinitionMap = sortedMap(newIVD, InputValueDefinition::getName);

        for (String inputFieldName : oldDefinitionMap.keySet()) {
            InputValueDefinition oldField = oldDefinitionMap.get(inputFieldName);
            Optional<InputValueDefinition> newField = Optional.ofNullable(newDefinitionMap.get(inputFieldName));

            ctx.report(DiffEvent.apiInfo()
                    .typeName(old.getName())
                    .typeKind(getTypeKind(old))
                    .fieldName(oldField.getName())
                    .reasonMsg("\tExamining input field '%s' ...", mkDotName(old.getName(), oldField.getName()))
                    .build());


            if (!newField.isPresent()) {
                DiffCategory category;
                String message;
                if (isDeprecated(oldField)) {
                    category = DiffCategory.DEPRECATION_REMOVED;
                    message = "The new API has removed a deprecated field '%s'";
                } else {
                    category = DiffCategory.MISSING;
                    message = "The new API is missing an input field '%s'";
                }
                ctx.report(DiffEvent.apiBreakage()
                        .category(category)
                        .typeName(old.getName())
                        .typeKind(getTypeKind(old))
                        .fieldName(oldField.getName())
                        .reasonMsg(message, mkDotName(old.getName(), oldField.getName()))
                        .build());
            } else {
                DiffCategory category = checkTypeWithNonNullAndListOnInputOrArg(oldField.getType(), newField.get().getType());
                if (category != null) {
                    ctx.report(DiffEvent.apiBreakage()
                            .category(category)
                            .typeName(old.getName())
                            .typeKind(getTypeKind(old))
                            .fieldName(oldField.getName())
                            .components(getAstDesc(oldField.getType()), getAstDesc(newField.get().getType()))
                            .reasonMsg("The new API has changed input field '%s' from type '%s' to '%s'",
                                    oldField.getName(), getAstDesc(oldField.getType()), getAstDesc(newField.get().getType()))
                            .build());
                }

                //
                // recurse via input types
                //
                checkType(ctx, oldField.getType(), newField.get().getType());
            }
        }

        // check new fields are not mandatory
        for (String inputFieldName : newDefinitionMap.keySet()) {
            InputValueDefinition newField = newDefinitionMap.get(inputFieldName);
            Optional<InputValueDefinition> oldField = Optional.ofNullable(oldDefinitionMap.get(inputFieldName));

            if (!oldField.isPresent()) {
                // new fields MUST not be mandatory
                if (typeInfo(newField.getType()).isNonNull()) {
                    ctx.report(DiffEvent.apiBreakage()
                            .category(DiffCategory.STRICTER)
                            .typeName(old.getName())
                            .typeKind(getTypeKind(old))
                            .fieldName(newField.getName())
                            .reasonMsg("The new API has made the new input field '%s' non null and hence more strict for old consumers", newField.getName())
                            .build());
                }
            }
        }
    }

    private void checkEnumType(DiffCtx ctx, EnumTypeDefinition oldDef, EnumTypeDefinition newDef) {
        Map<String, EnumValueDefinition> oldDefinitionMap = sortedMap(oldDef.getEnumValueDefinitions(), EnumValueDefinition::getName);
        Map<String, EnumValueDefinition> newDefinitionMap = sortedMap(newDef.getEnumValueDefinitions(), EnumValueDefinition::getName);

        for (String enumName : oldDefinitionMap.keySet()) {
            EnumValueDefinition oldEnum = oldDefinitionMap.get(enumName);
            Optional<EnumValueDefinition> newEnum = Optional.ofNullable(newDefinitionMap.get(enumName));

            if (!newEnum.isPresent()) {
                DiffCategory category;
                String message;
                if (isDeprecated(oldEnum)) {
                    category = DiffCategory.DEPRECATION_REMOVED;
                    message = "The new API has removed a deprecated enum value '%s'";
                } else {
                    category = DiffCategory.MISSING;
                    message = "The new API is missing an enum value '%s'";
                }
                ctx.report(DiffEvent.apiBreakage()
                        .category(category)
                        .typeName(oldDef.getName())
                        .typeKind(getTypeKind(oldDef))
                        .components(oldEnum.getName())
                        .reasonMsg(message, oldEnum.getName())
                        .build());
            } else {
                checkDirectives(ctx, oldDef, oldEnum.getDirectives(), newEnum.get().getDirectives());
            }
        }
        for (String enumName : newDefinitionMap.keySet()) {
            EnumValueDefinition oldEnum = oldDefinitionMap.get(enumName);

            if (oldEnum == null) {
                ctx.report(DiffEvent.apiDanger()
                        .category(DiffCategory.ADDITION)
                        .typeName(oldDef.getName())
                        .typeKind(getTypeKind(oldDef))
                        .components(enumName)
                        .reasonMsg("The new API has added a new enum value '%s'", enumName)
                        .build());
            } else if (isDeprecated(newDefinitionMap.get(enumName)) && !isDeprecated(oldEnum)) {
                ctx.report(DiffEvent.apiDanger()
                        .category(DiffCategory.DEPRECATION_ADDED)
                        .typeName(oldDef.getName())
                        .typeKind(getTypeKind(oldDef))
                        .components(enumName)
                        .reasonMsg("The new API has deprecated an enum value '%s'", enumName)
                        .build());
            }
        }
        checkDirectives(ctx, oldDef, newDef);
    }

    private void checkScalarType(DiffCtx ctx, ScalarTypeDefinition oldDef, ScalarTypeDefinition newDef) {
        checkDirectives(ctx, oldDef, newDef);
    }

    private void checkImplements(DiffCtx ctx, ObjectTypeDefinition old, List<Type> oldImplements, List<Type> newImplements) {
        Map<String, Type> oldImplementsMap = sortedMap(oldImplements, t -> ((TypeName) t).getName());
        Map<String, Type> newImplementsMap = sortedMap(newImplements, t -> ((TypeName) t).getName());

        for (Map.Entry<String, Type> entry : oldImplementsMap.entrySet()) {
            Optional<InterfaceTypeDefinition> oldInterface = ctx.getOldTypeDef(entry.getValue(), InterfaceTypeDefinition.class);
            if (!oldInterface.isPresent()) {
                continue;
            }
            Optional<InterfaceTypeDefinition> newInterface = ctx.getNewTypeDef(newImplementsMap.get(entry.getKey()), InterfaceTypeDefinition.class);
            if (!newInterface.isPresent()) {
                ctx.report(DiffEvent.apiBreakage()
                        .category(DiffCategory.MISSING)
                        .typeName(old.getName())
                        .typeKind(getTypeKind(old))
                        .components(oldInterface.get().getName())
                        .reasonMsg("The new API is missing the interface named '%s'", oldInterface.get().getName())
                        .build());
            } else {
                checkInterfaceType(ctx, oldInterface.get(), newInterface.get());
            }
        }

        for (Map.Entry<String, Type> entry : newImplementsMap.entrySet()) {
            Optional<InterfaceTypeDefinition> newInterface = ctx.getNewTypeDef(entry.getValue(), InterfaceTypeDefinition.class);
            if (!oldImplementsMap.containsKey(entry.getKey())) {
                ctx.report(DiffEvent.apiInfo()
                        .category(DiffCategory.ADDITION)
                        .typeName(old.getName())
                        .typeKind(getTypeKind(old))
                        .components(newInterface.get().getName())
                        .reasonMsg("The new API has added the interface named '%s'", newInterface.get().getName())
                        .build());
            }
        }
    }


    private void checkFields(
            DiffCtx ctx,
            TypeDefinition oldDef,
            Map<String, FieldDefinition> oldFields,
            TypeDefinition newDef,
            Map<String, FieldDefinition> newFields) {

        checkFieldRemovals(ctx, oldDef, oldFields, newFields);
        checkFieldAdditions(ctx, newDef, oldFields, newFields);
    }

    private void checkFieldRemovals(
            DiffCtx ctx,
            TypeDefinition oldDef,
            Map<String, FieldDefinition> oldFields,
            Map<String, FieldDefinition> newFields) {

        for (Map.Entry<String, FieldDefinition> entry : oldFields.entrySet()) {

            String fieldName = entry.getKey();
            ctx.report(DiffEvent.apiInfo()
                    .typeName(oldDef.getName())
                    .typeKind(getTypeKind(oldDef))
                    .fieldName(fieldName)
                    .reasonMsg("\tExamining field '%s' ...", mkDotName(oldDef.getName(), fieldName))
                    .build());

            FieldDefinition newField = newFields.get(fieldName);
            if (newField == null) {
                DiffCategory category;
                String message;
                if (isDeprecated(entry.getValue())) {
                    category = DiffCategory.DEPRECATION_REMOVED;
                    message = "The new API has removed a deprecated field '%s'";
                } else {
                    category = DiffCategory.MISSING;
                    message = "The new API is missing the field '%s'";
                }
                ctx.report(DiffEvent.apiBreakage()
                        .category(category)
                        .typeName(oldDef.getName())
                        .typeKind(getTypeKind(oldDef))
                        .fieldName(fieldName)
                        .reasonMsg(message, mkDotName(oldDef.getName(), fieldName))
                        .build());
            } else {
                checkField(ctx, oldDef, entry.getValue(), newField);
            }
        }
    }

    private void checkFieldAdditions(
            DiffCtx ctx,
            TypeDefinition newDef,
            Map<String, FieldDefinition> oldFields,
            Map<String, FieldDefinition> newFields) {

        for (Map.Entry<String, FieldDefinition> entry : newFields.entrySet()) {

            String fieldName = entry.getKey();
            ctx.report(DiffEvent.apiInfo()
                    .typeName(newDef.getName())
                    .typeKind(getTypeKind(newDef))
                    .fieldName(fieldName)
                    .reasonMsg("\tExamining field '%s' ...", mkDotName(newDef.getName(), fieldName))
                    .build());

            FieldDefinition oldField = oldFields.get(fieldName);
            if (oldField == null) {
                ctx.report(DiffEvent.apiInfo()
                        .category(DiffCategory.ADDITION)
                        .typeName(newDef.getName())
                        .typeKind(getTypeKind(newDef))
                        .fieldName(fieldName)
                        .reasonMsg("The new API adds the field '%s'", mkDotName(newDef.getName(), fieldName))
                        .build());
            } else if (!isDeprecated(oldField) && isDeprecated(entry.getValue())) {
                ctx.report(DiffEvent.apiDanger()
                        .category(DiffCategory.DEPRECATION_ADDED)
                        .typeName(newDef.getName())
                        .typeKind(getTypeKind(newDef))
                        .fieldName(fieldName)
                        .reasonMsg("The new API deprecated a field '%s'", mkDotName(newDef.getName(), fieldName))
                        .build());
            }
        }
    }


    private void checkField(DiffCtx ctx, TypeDefinition old, FieldDefinition oldField, FieldDefinition newField) {
        Type oldFieldType = oldField.getType();
        Type newFieldType = newField.getType();

        DiffCategory category = checkTypeWithNonNullAndListOnObjectOrInterface(oldFieldType, newFieldType);
        if (category != null) {
            ctx.report(DiffEvent.apiBreakage()
                    .category(category)
                    .typeName(old.getName())
                    .typeKind(getTypeKind(old))
                    .fieldName(oldField.getName())
                    .components(getAstDesc(oldFieldType), getAstDesc(newFieldType))
                    .reasonMsg("The new API has changed field '%s' from type '%s' to '%s'", mkDotName(old.getName(), oldField.getName()), getAstDesc(oldFieldType), getAstDesc(newFieldType))
                    .build());
        }

        checkFieldArguments(ctx, old, oldField, oldField.getInputValueDefinitions(), newField.getInputValueDefinitions());

        checkDirectives(ctx, old, oldField.getDirectives(), newField.getDirectives());
        //
        // and down we go again recursively via fields
        //
        checkType(ctx, oldFieldType, newFieldType);
    }

    private void checkFieldArguments(DiffCtx ctx, TypeDefinition oldDef, FieldDefinition oldField, List<InputValueDefinition> oldInputValueDefinitions, List<InputValueDefinition> newInputValueDefinitions) {
        Map<String, InputValueDefinition> oldArgsMap = sortedMap(oldInputValueDefinitions, InputValueDefinition::getName);
        Map<String, InputValueDefinition> newArgMap = sortedMap(newInputValueDefinitions, InputValueDefinition::getName);

        if (oldArgsMap.size() > newArgMap.size()) {
            ctx.report(DiffEvent.apiBreakage()
                    .category(DiffCategory.MISSING)
                    .typeName(oldDef.getName())
                    .typeKind(getTypeKind(oldDef))
                    .fieldName(oldField.getName())
                    .reasonMsg("The new API has less arguments on field '%s' of type '%s' than the old API", mkDotName(oldDef.getName(), oldField.getName()), oldDef.getName())
                    .build());
            return;
        }

        for (Map.Entry<String, InputValueDefinition> entry : oldArgsMap.entrySet()) {

            String argName = entry.getKey();
            ctx.report(DiffEvent.apiInfo()
                    .typeName(oldDef.getName())
                    .typeKind(getTypeKind(oldDef))
                    .fieldName(oldField.getName())
                    .reasonMsg("\tExamining field argument '%s' ...", mkDotName(oldDef.getName(), oldField.getName(), argName))
                    .build());


            InputValueDefinition newArg = newArgMap.get(argName);
            if (newArg == null) {
                ctx.report(DiffEvent.apiBreakage()
                        .category(DiffCategory.MISSING)
                        .typeName(oldDef.getName())
                        .typeKind(getTypeKind(oldDef))
                        .fieldName(oldField.getName())
                        .components(argName)
                        .reasonMsg("The new API is missing the field argument '%s'", mkDotName(oldDef.getName(), oldField.getName(), argName))
                        .build());
            } else {
                checkFieldArg(ctx, oldDef, oldField, entry.getValue(), newArg);
            }
        }

        // check new fields are not mandatory
        for (Map.Entry<String, InputValueDefinition> entry : newArgMap.entrySet()) {
            InputValueDefinition newArg = entry.getValue();
            Optional<InputValueDefinition> oldArg = Optional.ofNullable(oldArgsMap.get(newArg.getName()));

            if (!oldArg.isPresent()) {
                // new args MUST not be mandatory
                if (typeInfo(newArg.getType()).isNonNull()) {
                    ctx.report(DiffEvent.apiBreakage()
                            .category(DiffCategory.STRICTER)
                            .typeName(oldDef.getName())
                            .typeKind(getTypeKind(oldDef))
                            .fieldName(oldField.getName())
                            .components(newArg.getName())
                            .reasonMsg("The new API has made the new argument '%s' on field '%s' non null and hence more strict for old consumers", newArg.getName(), mkDotName(oldDef.getName(), oldField.getName()))
                            .build());
                }
            }
        }

    }

    private void checkFieldArg(DiffCtx ctx, TypeDefinition oldDef, FieldDefinition oldField, InputValueDefinition oldArg, InputValueDefinition newArg) {

        Type oldArgType = oldArg.getType();
        Type newArgType = newArg.getType();

        DiffCategory category = checkTypeWithNonNullAndListOnInputOrArg(oldArgType, newArgType);
        if (category != null) {
            ctx.report(DiffEvent.apiBreakage()
                    .category(category)
                    .typeName(oldDef.getName())
                    .typeKind(getTypeKind(oldDef))
                    .fieldName(oldField.getName())
                    .components(getAstDesc(oldArgType), getAstDesc(newArgType))
                    .reasonMsg("The new API has changed field '%s' argument '%s' from type '%s' to '%s'", mkDotName(oldDef.getName(), oldField.getName()), oldArg.getName(), getAstDesc(oldArgType), getAstDesc(newArgType))
                    .build());
        } else {
            //
            // and down we go again recursively via arg types
            //
            checkType(ctx, oldArgType, newArgType);
        }

        boolean changedDefaultValue = false;
        Value oldValue = oldArg.getDefaultValue();
        Value newValue = newArg.getDefaultValue();
        if (oldValue != null && newValue != null) {
            if (!oldValue.getClass().equals(newValue.getClass())) {
                ctx.report(DiffEvent.apiBreakage()
                        .category(DiffCategory.INVALID)
                        .typeName(oldDef.getName())
                        .typeKind(getTypeKind(oldDef))
                        .fieldName(oldField.getName())
                        .components(oldArg.getName())
                        .reasonMsg("The new API has changed default value types on argument named '%s' on field '%s' of type '%s", oldArg.getName(), mkDotName(oldDef.getName(), oldField.getName()), oldDef.getName())
                        .build());
            }
            if (!oldValue.isEqualTo(newValue)) {
                changedDefaultValue = true;
            }
        }
        if (oldValue == null && newValue != null) {
            changedDefaultValue = true;
        }
        if (oldValue != null && newValue == null) {
            changedDefaultValue = true;
        }
        if (changedDefaultValue) {
            ctx.report(DiffEvent.apiDanger()
                    .category(DiffCategory.DIFFERENT)
                    .typeName(oldDef.getName())
                    .typeKind(getTypeKind(oldDef))
                    .fieldName(oldField.getName())
                    .components(oldArg.getName())
                    .reasonMsg("The new API has changed default value on argument named '%s' on field '%s' of type '%s", oldArg.getName(), mkDotName(oldDef.getName(), oldField.getName()), oldDef.getName())
                    .build());
        }

        checkDirectives(ctx, oldDef, oldArg.getDirectives(), newArg.getDirectives());
    }

    private void checkDirectives(DiffCtx ctx, TypeDefinition oldDef, TypeDefinition newDef) {
        List<Directive> oldDirectives = oldDef.getDirectives();
        List<Directive> newDirectives = newDef.getDirectives();

        checkDirectives(ctx, oldDef, oldDirectives, newDirectives);
    }

    void checkDirectives(DiffCtx ctx, TypeDefinition old, List<Directive> oldDirectives, List<Directive> newDirectives) {
        if (!options.enforceDirectives) {
            return;
        }

        Map<String, Directive> oldDirectivesMap = sortedMap(oldDirectives, Directive::getName);
        Map<String, Directive> newDirectivesMap = sortedMap(newDirectives, Directive::getName);

        for (String directiveName : oldDirectivesMap.keySet()) {
            Directive oldDirective = oldDirectivesMap.get(directiveName);
            Optional<Directive> newDirective = Optional.ofNullable(newDirectivesMap.get(directiveName));
            if (!newDirective.isPresent()) {
                ctx.report(DiffEvent.apiBreakage()
                        .category(DiffCategory.MISSING)
                        .typeName(old.getName())
                        .typeKind(getTypeKind(old))
                        .components(directiveName)
                        .reasonMsg("The new API does not have a directive named '%s' on type '%s'", directiveName, old.getName())
                        .build());
                continue;
            }


            Map<String, Argument> oldArgumentsByName = new TreeMap<>(oldDirective.getArgumentsByName());
            Map<String, Argument> newArgumentsByName = new TreeMap<>(newDirective.get().getArgumentsByName());

            if (oldArgumentsByName.size() > newArgumentsByName.size()) {
                ctx.report(DiffEvent.apiBreakage()
                        .category(DiffCategory.MISSING)
                        .typeName(old.getName())
                        .typeKind(getTypeKind(old))
                        .components(directiveName)
                        .reasonMsg("The new API has less arguments on directive '%s' on type '%s' than the old API", directiveName, old.getName())
                        .build());
                return;
            }

            for (String argName : oldArgumentsByName.keySet()) {
                Argument oldArgument = oldArgumentsByName.get(argName);
                Optional<Argument> newArgument = Optional.ofNullable(newArgumentsByName.get(argName));

                if (!newArgument.isPresent()) {
                    ctx.report(DiffEvent.apiBreakage()
                            .category(DiffCategory.MISSING)
                            .typeName(old.getName())
                            .typeKind(getTypeKind(old))
                            .components(directiveName, argName)
                            .reasonMsg("The new API does not have an argument named '%s' on directive '%s' on type '%s'", argName, directiveName, old.getName())
                            .build());
                } else {
                    Value oldValue = oldArgument.getValue();
                    Value newValue = newArgument.get().getValue();
                    if (oldValue != null && newValue != null) {
                        if (!oldValue.getClass().equals(newValue.getClass())) {
                            ctx.report(DiffEvent.apiBreakage()
                                    .category(DiffCategory.INVALID)
                                    .typeName(old.getName())
                                    .typeKind(getTypeKind(old))
                                    .components(directiveName, argName)
                                    .reasonMsg("The new API has changed value types on argument named '%s' on directive '%s' on type '%s'", argName, directiveName, old.getName())
                                    .build());
                        }
                    }
                }
            }
        }
    }

    DiffCategory checkTypeWithNonNullAndListOnInputOrArg(Type oldType, Type newType) {
        TypeInfo oldTypeInfo = typeInfo(oldType);
        TypeInfo newTypeInfo = typeInfo(newType);

        if (!oldTypeInfo.getName().equals(newTypeInfo.getName())) {
            return DiffCategory.INVALID;
        }

        while (true) {
            if (oldTypeInfo.isNonNull()) {
                if (newTypeInfo.isNonNull()) {
                    // if they're both non-null, compare the unwrapped types
                    oldTypeInfo = oldTypeInfo.unwrapOne();
                    newTypeInfo = newTypeInfo.unwrapOne();
                } else {
                    // non-null to nullable is valid, as long as the underlying types are also valid
                    oldTypeInfo = oldTypeInfo.unwrapOne();
                }
            } else if (oldTypeInfo.isList()) {
                if (newTypeInfo.isList()) {
                    // if they're both lists, compare the unwrapped types
                    oldTypeInfo = oldTypeInfo.unwrapOne();
                    newTypeInfo = newTypeInfo.unwrapOne();
                } else if (newTypeInfo.isNonNull()) {
                    // nullable to non-null creates a stricter input requirement for clients to specify
                    return DiffCategory.STRICTER;
                } else {
                    // list to non-list is not valid
                    return DiffCategory.INVALID;
                }
            } else {
                if (newTypeInfo.isNonNull()) {
                    // nullable to non-null creates a stricter input requirement for clients to specify
                    return DiffCategory.STRICTER;
                } else if (newTypeInfo.isList()) {
                    // non-list to list is not valid
                    return DiffCategory.INVALID;
                } else {
                    return null;
                }
            }
        }
    }

    DiffCategory checkTypeWithNonNullAndListOnObjectOrInterface(Type oldType, Type newType) {
        TypeInfo oldTypeInfo = typeInfo(oldType);
        TypeInfo newTypeInfo = typeInfo(newType);

        if (!oldTypeInfo.getName().equals(newTypeInfo.getName())) {
            return DiffCategory.INVALID;
        }

        while (true) {
            if (oldTypeInfo.isNonNull()) {
                if (newTypeInfo.isNonNull()) {
                    // if they're both non-null, compare the unwrapped types
                    oldTypeInfo = oldTypeInfo.unwrapOne();
                    newTypeInfo = newTypeInfo.unwrapOne();
                } else {
                    // non-null to nullable requires a stricter check from clients since it removes the guarantee of presence
                    return DiffCategory.STRICTER;
                }
            } else if (oldTypeInfo.isList()) {
                if (newTypeInfo.isList()) {
                    // if they're both lists, compare the unwrapped types
                    oldTypeInfo = oldTypeInfo.unwrapOne();
                    newTypeInfo = newTypeInfo.unwrapOne();
                } else if (newTypeInfo.isNonNull()) {
                    // nullable to non-null is valid, as long as the underlying types are also valid
                    newTypeInfo = newTypeInfo.unwrapOne();
                } else {
                    // list to non-list is not valid
                    return DiffCategory.INVALID;
                }
            } else {
                if (newTypeInfo.isNonNull()) {
                    // nullable to non-null is valid, as long as the underlying types are also valid
                    newTypeInfo = newTypeInfo.unwrapOne();
                } else if (newTypeInfo.isList()) {
                    // non-list to list is not valid
                    return DiffCategory.INVALID;
                } else {
                    return null;
                }
            }
        }
    }


    static String getTypeName(Type type) {
        if (type == null) {
            return null;
        }
        return typeInfo(type).getName();
    }

    @SuppressWarnings("ConstantConditions")
    private Optional<SchemaDefinition> getSchemaDef(Document document) {
        return document.getDefinitions().stream()
                .filter(d -> d instanceof SchemaDefinition)
                .map(SchemaDefinition.class::cast)
                .findFirst();
    }

    private Optional<OperationTypeDefinition> getOpDef(String opName, SchemaDefinition schemaDef) {
        return schemaDef.getOperationTypeDefinitions()
                .stream()
                .filter(otd -> otd.getName().equals(opName))
                .findFirst();
    }


    // looks for a type called `Query|Mutation|Subscription` and if it exist then assumes it as an operation def

    private Optional<OperationTypeDefinition> synthOperationTypeDefinition(Function<Type, Optional<ObjectTypeDefinition>> typeRetriever, String opName) {
        TypeName type = TypeName.newTypeName().name(capitalize(opName)).build();
        Optional<ObjectTypeDefinition> typeDef = typeRetriever.apply(type);
        return typeDef.map(objectTypeDefinition -> OperationTypeDefinition.newOperationTypeDefinition().name(opName).typeName(type).build());
    }

    private <T> Map<String, T> sortedMap(List<T> listOfNamedThings, Function<T, String> nameFunc) {
        Map<String, T> map = listOfNamedThings.stream().collect(Collectors.toMap(nameFunc, Function.identity(), (x, y) -> y));
        return new TreeMap<>(map);
    }


    private String mkDotName(String... objectNames) {
        return String.join(".", objectNames);
    }
}
