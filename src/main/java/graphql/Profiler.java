package graphql;

import graphql.execution.EngineRunningObserver;
import graphql.execution.ResultPath;
import graphql.execution.instrumentation.Instrumentation;
import graphql.language.OperationDefinition;
import graphql.schema.DataFetcher;
import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLOutputType;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

@Internal
@NullMarked
public interface Profiler {


    Profiler NO_OP = new Profiler() {
    };


    default void setExecutionInputAndInstrumentation(ExecutionInput executionInput, Instrumentation instrumentation) {

    }

    default void dataLoaderUsed(String dataLoaderName) {


    }

    default void fieldFetched(Object fetchedObject, DataFetcher<?> originalDataFetcher, DataFetcher<?> dataFetcher, ResultPath path, GraphQLFieldDefinition fieldDef, GraphQLOutputType parentType) {

    }

    default @Nullable EngineRunningObserver wrapEngineRunningObserver(@Nullable EngineRunningObserver engineRunningObserver) {
        return engineRunningObserver;
    }

    default void operationDefinition(OperationDefinition operationDefinition) {

    }

    default void oldStrategyDispatchingAll(int level) {

    }

    default void batchLoadedOldStrategy(String name, int level, int count) {

    }


    default void batchLoadedNewStrategy(String dataLoaderName, Integer level, int count, boolean delayed, boolean chained) {

    }

    default <V> void manualDispatch(String dataLoaderName, int level, int count) {

    }
}
