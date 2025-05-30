package graphql.execution.instrumentation.dataloader

import graphql.ExecutionInput
import graphql.ExecutionResult
import graphql.TestUtil
import graphql.execution.AsyncExecutionStrategy
import graphql.execution.AsyncSerialExecutionStrategy
import org.dataloader.DataLoaderRegistry
import spock.lang.Specification
import spock.lang.Unroll

import java.util.concurrent.TimeUnit

import static graphql.schema.idl.RuntimeWiring.newRuntimeWiring
import static graphql.schema.idl.TypeRuntimeWiring.newTypeWiring

class DataLoaderCompanyProductMutationTest extends Specification {

    @Unroll
    def "bug #1099 test mutation completes as expected and does not hang - running #note"() {

        DataLoaderCompanyProductBackend backend = new DataLoaderCompanyProductBackend(3, 5)

        def spec = '''

            type Project {
                id : ID!
                name : String!
            }
            
            type Company {
                id : ID!
                name : String!
                projects : [Project!]
            }
            
            type Query {
                companies : [Company!]
            }
            
            type Mutation {
                addCompany : Company
            }
        '''

        def wiring = newRuntimeWiring()
                .type(
                newTypeWiring("Company").dataFetcher("projects", {
                    environment ->
                        DataLoaderCompanyProductBackend.Company source = environment.getSource()
                        return environment.getDataLoader("projects-dl").load(source.getId())
                }))
                .type(
                        newTypeWiring("Query").dataFetcher("companies", {
                            environment -> backend.getCompanies()
                        }))
                .type(
                        newTypeWiring("Mutation").dataFetcher("addCompany", {
                            environment -> backend.addCompany()
                        }))
                .build()

        def registry = new DataLoaderRegistry()
        registry.register("projects-dl", backend.getProjectsLoader())

        def graphQL = TestUtil.graphQL(spec, wiring)
                .queryExecutionStrategy(queryES)
                .mutationExecutionStrategy(mutationES)
                .build()

        ExecutionInput executionInput = ExecutionInput.newExecutionInput()
                .query(query)
                .dataLoaderRegistry(registry)
                .graphQLContext([(DataLoaderDispatchingContextKeys.ENABLE_DATA_LOADER_CHAINING): false])
                .build()

        when:

        ExecutionResult result = graphQL.executeAsync(executionInput).get(5, TimeUnit.SECONDS)

        then:

        result != null
        result.errors.isEmpty()
        result.data != null

        where:

        note                            | query                                              | queryES                            | mutationES
        "mutation - spec compliant"     | "mutation { addCompany { name projects { name }}}" | new AsyncExecutionStrategy()       | new AsyncSerialExecutionStrategy()
        "mutation - all serial"         | "mutation { addCompany { name projects { name }}}" | new AsyncSerialExecutionStrategy() | new AsyncSerialExecutionStrategy()
        "mutation - non spec compliant" | "mutation { addCompany { name projects { name }}}" | new AsyncExecutionStrategy()       | new AsyncExecutionStrategy()

        "query - spec compliant"        | "query {companies { name projects { name }}}"      | new AsyncExecutionStrategy()       | new AsyncSerialExecutionStrategy()
        "query - all serial"            | "query {companies { name projects { name }}}"      | new AsyncSerialExecutionStrategy() | new AsyncSerialExecutionStrategy()
        "query - non spec compliant"    | "query {companies { name projects { name }}}"      | new AsyncExecutionStrategy()       | new AsyncExecutionStrategy()
    }
}
