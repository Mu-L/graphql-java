package graphql.normalized.nf;

import graphql.Internal;
import graphql.introspection.Introspection;
import graphql.language.Argument;
import graphql.language.AstComparator;
import graphql.language.Directive;
import graphql.schema.GraphQLInterfaceType;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLSchema;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

@Internal
public class NormalizedFieldsMerger {

    public static void merge(
            NormalizedField parent,
            List<NormalizedField> childrenWithSameResultKey,
            GraphQLSchema schema
    ) {
        // they have all the same result key
        // we can only merge the fields if they have the same field name + arguments + all children are the same
        List<Set<NormalizedField>> possibleGroupsToMerge = new ArrayList<>();
        for (NormalizedField field : childrenWithSameResultKey) {
            boolean addToGroup = false;
            overPossibleGroups:
            for (Set<NormalizedField> group : possibleGroupsToMerge) {
                for (NormalizedField fieldInGroup : group) {
                    if (field.getFieldName().equals(Introspection.TypeNameMetaFieldDef.getName())) {
                        addToGroup = true;
                        group.add(field);
                        continue overPossibleGroups;
                    }
                    if (field.getFieldName().equals(fieldInGroup.getFieldName()) &&
                            sameArguments(field.getAstArguments(), fieldInGroup.getAstArguments())
                            && isFieldInSharedInterface(field, fieldInGroup, schema)
                    ) {
                        addToGroup = true;
                        group.add(field);
                        continue overPossibleGroups;
                    }
                }
            }
            if (!addToGroup) {
                LinkedHashSet<NormalizedField> group = new LinkedHashSet<>();
                group.add(field);
                possibleGroupsToMerge.add(group);
            }
        }
        for (Set<NormalizedField> groupOfFields : possibleGroupsToMerge) {
            // for each group we check if it could be merged
            List<Set<NormalizedField>> listOfChildrenForGroup = new ArrayList<>();
            for (NormalizedField fieldInGroup : groupOfFields) {
                Set<NormalizedField> childrenSets = new LinkedHashSet<>(fieldInGroup.getChildren());
                listOfChildrenForGroup.add(childrenSets);
            }
            boolean mergeable = areFieldSetsTheSame(listOfChildrenForGroup);
            if (mergeable) {
                Set<String> mergedObjects = new LinkedHashSet<>();
                List<Directive> mergedDirectives = new ArrayList<>();
                groupOfFields.forEach(f -> mergedObjects.addAll(f.getObjectTypeNames()));
                groupOfFields.forEach(f -> mergedDirectives.addAll(f.getAstDirectives()));
                // patching the first one to contain more objects, remove all others
                Iterator<NormalizedField> iterator = groupOfFields.iterator();
                NormalizedField first = iterator.next();

                while (iterator.hasNext()) {
                    NormalizedField next = iterator.next();
                    parent.getChildren().remove(next);
                }
                first.setObjectTypeNames(mergedObjects);
                first.setAstDirectives(mergedDirectives);
            }
        }
    }

    private static boolean isFieldInSharedInterface(NormalizedField fieldOne, NormalizedField fieldTwo, GraphQLSchema schema) {

        /*
         * we can get away with only checking one of the object names, because all object names in one ENF are guaranteed to be the same field.
         * This comes from how the ENFs are created in the factory before.
         */
        String firstObject = fieldOne.getSingleObjectTypeName();
        String secondObject = fieldTwo.getSingleObjectTypeName();
        // we know that the field names are the same, therefore we can just take the first one
        String fieldName = fieldOne.getFieldName();

        GraphQLObjectType objectTypeOne = schema.getObjectType(firstObject);
        GraphQLObjectType objectTypeTwo = schema.getObjectType(secondObject);
        List<GraphQLInterfaceType> interfacesOne = (List) objectTypeOne.getInterfaces();
        List<GraphQLInterfaceType> interfacesTwo = (List) objectTypeTwo.getInterfaces();

        Optional<GraphQLInterfaceType> firstInterfaceFound = interfacesOne.stream().filter(singleInterface -> singleInterface.getFieldDefinition(fieldName) != null).findFirst();
        Optional<GraphQLInterfaceType> secondInterfaceFound = interfacesTwo.stream().filter(singleInterface -> singleInterface.getFieldDefinition(fieldName) != null).findFirst();
        if (!firstInterfaceFound.isPresent() || !secondInterfaceFound.isPresent()) {
            return false;
        }
        return firstInterfaceFound.get().getName().equals(secondInterfaceFound.get().getName());
    }


    private static boolean areFieldSetsTheSame(List<Set<NormalizedField>> listOfSets) {
        if (listOfSets.size() == 0 || listOfSets.size() == 1) {
            return true;
        }
        Set<NormalizedField> first = listOfSets.get(0);
        Iterator<Set<NormalizedField>> iterator = listOfSets.iterator();
        iterator.next();
        while (iterator.hasNext()) {
            Set<NormalizedField> set = iterator.next();
            if (!compareTwoFieldSets(first, set)) {
                return false;
            }
        }
        List<Set<NormalizedField>> nextLevel = new ArrayList<>();
        for (Set<NormalizedField> set : listOfSets) {
            for (NormalizedField fieldInSet : set) {
                nextLevel.add(new LinkedHashSet<>(fieldInSet.getChildren()));
            }
        }
        return areFieldSetsTheSame(nextLevel);
    }

    private static boolean compareTwoFieldSets(Set<NormalizedField> setOne, Set<NormalizedField> setTwo) {
        if (setOne.size() != setTwo.size()) {
            return false;
        }
        for (NormalizedField field : setOne) {
            if (!isContained(field, setTwo)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isContained(NormalizedField searchFor, Set<NormalizedField> set) {
        for (NormalizedField field : set) {
            if (compareWithoutChildren(searchFor, field)) {
                return true;
            }
        }
        return false;
    }

    private static boolean compareWithoutChildren(NormalizedField one, NormalizedField two) {

        if (!one.getObjectTypeNames().equals(two.getObjectTypeNames())) {
            return false;
        }
        if (!Objects.equals(one.getAlias(), two.getAlias())) {
            return false;
        }
        if (!Objects.equals(one.getFieldName(), two.getFieldName())) {
            return false;
        }
        if (!sameArguments(one.getAstArguments(), two.getAstArguments())) {
            return false;
        }
        return true;
    }

    // copied from graphql.validation.rules.OverlappingFieldsCanBeMerged
    private static boolean sameArguments(List<Argument> arguments1, List<Argument> arguments2) {
        if (arguments1.size() != arguments2.size()) {
            return false;
        }
        for (Argument argument : arguments1) {
            Argument matchedArgument = findArgumentByName(argument.getName(), arguments2);
            if (matchedArgument == null) {
                return false;
            }
            if (!AstComparator.sameValue(argument.getValue(), matchedArgument.getValue())) {
                return false;
            }
        }
        return true;
    }

    private static Argument findArgumentByName(String name, List<Argument> arguments) {
        for (Argument argument : arguments) {
            if (argument.getName().equals(name)) {
                return argument;
            }
        }
        return null;
    }

}
