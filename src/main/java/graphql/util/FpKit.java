package graphql.util;


import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import graphql.Internal;
import org.jspecify.annotations.NonNull;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.function.BinaryOperator;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static java.util.Collections.singletonList;

@Internal
public class FpKit {

    //
    // From a list of named things, get a map of them by name, merging them according to the merge function
    public static <T> Map<String, T> getByName(List<T> namedObjects, Function<T, String> nameFn, BinaryOperator<T> mergeFunc) {
        return toMap(namedObjects, nameFn, mergeFunc);
    }

    //
    // From a collection of keyed things, get a map of them by key, merging them according to the merge function
    public static <T, NewKey> Map<NewKey, T> toMap(Collection<T> collection, Function<T, NewKey> keyFunction, BinaryOperator<T> mergeFunc) {
        Map<NewKey, T> resultMap = new LinkedHashMap<>();
        for (T obj : collection) {
            NewKey key = keyFunction.apply(obj);
            if (resultMap.containsKey(key)) {
                T existingValue = resultMap.get(key);
                T mergedValue = mergeFunc.apply(existingValue, obj);
                resultMap.put(key, mergedValue);
            } else {
                resultMap.put(key, obj);
            }
        }
        return resultMap;
    }

    // normal groupingBy but with LinkedHashMap
    public static <T, NewKey> Map<NewKey, ImmutableList<T>> groupingBy(Collection<T> list, Function<T, NewKey> function) {
        return filterAndGroupingBy(list, ALWAYS_TRUE, function);
    }

    @SuppressWarnings("unchecked")
    public static <T, NewKey> Map<NewKey, ImmutableList<T>> filterAndGroupingBy(Collection<T> list,
                                                                                Predicate<? super T> predicate,
                                                                                Function<T, NewKey> function) {
        //
        // The cleanest version of this code would have two maps, one of immutable list builders and one
        // of the built immutable lists.  BUt we are trying to be performant and memory efficient so
        // we treat it as a map of objects and cast like its Java 4x
        //
        Map<NewKey, Object> resutMap = new LinkedHashMap<>();
        for (T item : list) {
            if (predicate.test(item)) {
                NewKey key = function.apply(item);
                // we have to use an immutable list builder as we built it out
                ((ImmutableList.Builder<Object>) resutMap.computeIfAbsent(key, k -> ImmutableList.builder()))
                        .add(item);
            }
        }
        if (resutMap.isEmpty()) {
            return Collections.emptyMap();
        }
        // Convert builders to ImmutableLists in place to avoid an extra allocation
        // yes the code is yuck -  but its more performant yuck!
        resutMap.replaceAll((key, builder) ->
                ((ImmutableList.Builder<Object>) builder).build());

        // make it the right shape - like as if generics were never invented
        return (Map<NewKey, ImmutableList<T>>) (Map<?, ?>) resutMap;
    }

    public static <T, NewKey> Map<NewKey, T> toMapByUniqueKey(Collection<T> list, Function<T, NewKey> keyFunction) {
        return toMap(list, keyFunction, throwingMerger());
    }


    private static final Predicate<Object> ALWAYS_TRUE = o -> true;

    private static final BinaryOperator<Object> THROWING_MERGER_SINGLETON = (u, v) -> {
        throw new IllegalStateException(String.format("Duplicate key %s", u));
    };


    private static <T> BinaryOperator<T> throwingMerger() {
        //noinspection unchecked
        return (BinaryOperator<T>) THROWING_MERGER_SINGLETON;
    }


    //
    // From a list of named things, get a map of them by name, merging them first one added
    public static <T> Map<String, T> getByName(List<T> namedObjects, Function<T, String> nameFn) {
        return getByName(namedObjects, nameFn, mergeFirst());
    }

    public static <T> BinaryOperator<T> mergeFirst() {
        return (o1, o2) -> o1;
    }

    /**
     * Converts an object that should be an Iterable into a Collection efficiently, leaving
     * it alone if it is already is one.  Useful when you want to get the size of something
     *
     * @param iterableResult the result object
     * @param <T>            the type of thing
     *
     * @return an Iterable from that object
     *
     * @throws java.lang.ClassCastException if it's not an Iterable
     */
    @SuppressWarnings("unchecked")
    public static <T> Collection<T> toCollection(Object iterableResult) {
        if (iterableResult instanceof Collection) {
            return (Collection<T>) iterableResult;
        }
        Iterable<T> iterable = toIterable(iterableResult);
        Iterator<T> iterator = iterable.iterator();
        List<T> list = new ArrayList<>();
        while (iterator.hasNext()) {
            list.add(iterator.next());
        }
        return list;
    }

    /**
     * Creates an {@link ArrayList} sized appropriately to the collection, typically for copying
     *
     * @param collection the collection of a certain size
     * @param <T>        to two
     *
     * @return a new {@link ArrayList} initially sized to the same as the collection
     */
    public static <T> @NonNull List<T> arrayListSizedTo(@NonNull Collection<?> collection) {
        return new ArrayList<>(collection.size());
    }


    /**
     * Converts a value into a list if it's really a collection or array of things
     * else it turns it into a singleton list containing that one value
     *
     * @param possibleIterable the possible
     * @param <T>              for two
     *
     * @return an list one way or another
     */
    @SuppressWarnings("unchecked")
    public static <T> List<T> toListOrSingletonList(Object possibleIterable) {
        if (possibleIterable instanceof List) {
            return (List<T>) possibleIterable;
        }
        if (isIterable(possibleIterable)) {
            return ImmutableList.copyOf(toIterable(possibleIterable));
        }
        return ImmutableList.of((T) possibleIterable);
    }

    public static boolean isIterable(Object result) {
        return result.getClass().isArray() || result instanceof Iterable || result instanceof Stream || result instanceof Iterator;
    }


    @SuppressWarnings("unchecked")
    public static <T> Iterable<T> toIterable(Object iterableResult) {
        if (iterableResult instanceof Iterable) {
            return ((Iterable<T>) iterableResult);
        }

        if (iterableResult instanceof Stream) {
            return ((Stream<T>) iterableResult)::iterator;
        }

        if (iterableResult instanceof Iterator) {
            return () -> (Iterator<T>) iterableResult;
        }

        if (iterableResult.getClass().isArray()) {
            return () -> new ArrayIterator<>(iterableResult);
        }

        throw new ClassCastException("not Iterable: " + iterableResult.getClass());
    }

    private static class ArrayIterator<T> implements Iterator<T> {

        private final Object array;
        private final int size;
        private int i;

        private ArrayIterator(Object array) {
            this.array = array;
            this.size = Array.getLength(array);
            this.i = 0;
        }

        @Override
        public boolean hasNext() {
            return i < size;
        }

        @SuppressWarnings("unchecked")
        @Override
        public T next() {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }
            return (T) Array.get(array, i++);
        }

    }

    public static OptionalInt toSize(Object iterableResult) {
        if (iterableResult instanceof Collection) {
            return OptionalInt.of(((Collection<?>) iterableResult).size());
        }

        if (iterableResult.getClass().isArray()) {
            return OptionalInt.of(Array.getLength(iterableResult));
        }

        return OptionalInt.empty();
    }

    /**
     * Concatenates (appends) a single elements to an existing list
     *
     * @param l   the list onto which to append the element
     * @param t   the element to append
     * @param <T> the type of elements of the list
     *
     * @return a <strong>new</strong> list composed of the first list elements and the new element
     */
    public static <T> List<T> concat(List<T> l, T t) {
        return concat(l, singletonList(t));
    }

    /**
     * Concatenates two lists into one
     *
     * @param l1  the first list to concatenate
     * @param l2  the second list to concatenate
     * @param <T> the type of element of the lists
     *
     * @return a <strong>new</strong> list composed of the two concatenated lists elements
     */
    public static <T> List<T> concat(List<T> l1, List<T> l2) {
        ArrayList<T> l = new ArrayList<>(l1);
        l.addAll(l2);
        l.trimToSize();
        return l;
    }

    //
    // quickly turn a map of values into its list equivalent
    public static <T> List<T> valuesToList(Map<?, T> map) {
        return new ArrayList<>(map.values());
    }

    public static <T> List<List<T>> transposeMatrix(List<? extends List<T>> matrix) {
        int rowCount = matrix.size();
        int colCount = matrix.get(0).size();
        List<List<T>> result = new ArrayList<>();
        for (int i = 0; i < rowCount; i++) {
            for (int j = 0; j < colCount; j++) {
                T val = matrix.get(i).get(j);
                if (result.size() <= j) {
                    result.add(j, new ArrayList<>());
                }
                result.get(j).add(i, val);
            }
        }
        return result;
    }

    public static <T> Optional<T> findOne(Collection<T> list, Predicate<T> filter) {
        for (T t : list) {
            if (filter.test(t)) {
                return Optional.of(t);
            }
        }
        return Optional.empty();
    }

    public static <T> T findOneOrNull(List<T> list, Predicate<T> filter) {
        return findOne(list, filter).orElse(null);
    }

    public static <T> int findIndex(List<T> list, Predicate<T> filter) {
        for (int i = 0; i < list.size(); i++) {
            if (filter.test(list.get(i))) {
                return i;
            }
        }
        return -1;
    }

    public static <T> List<T> filterList(Collection<T> list, Predicate<T> filter) {
        List<T> result = arrayListSizedTo(list);
        for (T t : list) {
            if (filter.test(t)) {
                result.add(t);
            }
        }
        return result;
    }

    public static <T> Set<T> filterSet(Collection<T> input, Predicate<T> filter) {
        ImmutableSet.Builder<T> result = ImmutableSet.builder();
        for (T t : input) {
            if (filter.test(t)) {
                result.add(t);
            }
        }
        return result.build();
    }

    /**
     * Used in simple {@link Map#computeIfAbsent(Object, java.util.function.Function)} cases
     *
     * @param <K> for Key
     * @param <V> for Value
     *
     * @return a function that allocates a list
     */
    public static <K, V> Function<K, List<V>> newList() {
        return k -> new ArrayList<>();
    }

    /**
     * This will memoize the Supplier within the current thread's visibility, that is it does not
     * use volatile reads but rather use a sentinel check and re-reads the delegate supplier
     * value if the read has not stuck to this thread.  This means that it's possible that your delegate
     * supplier MAY be called more than once across threads, but only once on the same thread.
     *
     * @param delegate the supplier to delegate to
     * @param <T>      for two
     *
     * @return a supplier that will memoize values in the context of the current thread
     */
    public static <T> Supplier<T> intraThreadMemoize(Supplier<T> delegate) {
        return new IntraThreadMemoizedSupplier<>(delegate);
    }

    /**
     * This will memoize the Supplier across threads and make sure the Supplier is exactly called once.
     * <p>
     * Use for potentially costly actions. Otherwise consider {@link #intraThreadMemoize(Supplier)}
     *
     * @param delegate the supplier to delegate to
     * @param <T>      for two
     *
     * @return a supplier that will memoize values in the context of the all the threads
     */
    public static <T> Supplier<T> interThreadMemoize(Supplier<T> delegate) {
        return new InterThreadMemoizedSupplier<>(delegate);
    }

    /**
     * Faster set intersection.
     *
     * @param <T>  for two
     * @param set1 first set
     * @param set2 second set
     *
     * @return intersection set
     */
    public static <T> Set<T> intersection(Set<T> set1, Set<T> set2) {
        // Set intersection calculation is expensive when either set is large. Often, either set has only one member.
        // When either set contains only one member, it is equivalent and much cheaper to calculate intersection via contains.
        if (set1.size() == 1 && set2.contains(set1.iterator().next())) {
            return set1;
        } else if (set2.size() == 1 && set1.contains(set2.iterator().next())) {
            return set2;
        }

        // Guava's Sets.intersection is faster when the smaller set is passed as the first argument.
        if (set1.size() < set2.size()) {
            return Sets.intersection(set1, set2);
        }
        return Sets.intersection(set2, set1);
    }

}
