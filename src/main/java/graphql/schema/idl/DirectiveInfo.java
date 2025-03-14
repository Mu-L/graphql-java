package graphql.schema.idl;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import graphql.Directives;
import graphql.PublicApi;
import graphql.schema.GraphQLDirective;

import java.util.Map;
import java.util.Set;

/**
 * Info on all the directives provided by graphql specification
 */
@PublicApi
public class DirectiveInfo {


    /**
     * A map from directive name to directive which provided by specification
     */
    public static final Map<String, GraphQLDirective> GRAPHQL_SPECIFICATION_DIRECTIVE_MAP = ImmutableMap.of(
            Directives.IncludeDirective.getName(), Directives.IncludeDirective,
            Directives.SkipDirective.getName(), Directives.SkipDirective,
            Directives.DeprecatedDirective.getName(), Directives.DeprecatedDirective,
            Directives.SpecifiedByDirective.getName(), Directives.SpecifiedByDirective,
            Directives.OneOfDirective.getName(), Directives.OneOfDirective,
            // technically this is NOT yet in spec - but it is added by default by graphql-java so we include it
            // we should also do @defer at some future time soon
            Directives.DeferDirective.getName(), Directives.DeferDirective,
            Directives.ExperimentalDisableErrorPropagationDirective.getName(), Directives.ExperimentalDisableErrorPropagationDirective
            );
    /**
     * A set of directives which provided by graphql specification
     */
    public static final Set<GraphQLDirective> GRAPHQL_SPECIFICATION_DIRECTIVES =ImmutableSet.copyOf(GRAPHQL_SPECIFICATION_DIRECTIVE_MAP.values());

    /**
     * Returns true if a directive with provided directiveName has been defined in graphql specification
     *
     * @param directiveName the name of directive in question
     *
     * @return true if the directive provided by graphql specification, and false otherwise
     */
    public static boolean isGraphqlSpecifiedDirective(String directiveName) {
        return GRAPHQL_SPECIFICATION_DIRECTIVE_MAP.containsKey(directiveName);
    }

    /**
     * Returns true if the provided directive has been defined in graphql specification
     *
     * @param graphQLDirective the directive in question
     *
     * @return true if the directive provided by graphql specification, and false otherwise
     */
    public static boolean isGraphqlSpecifiedDirective(GraphQLDirective graphQLDirective) {
        return isGraphqlSpecifiedDirective(graphQLDirective.getName());
    }


}
