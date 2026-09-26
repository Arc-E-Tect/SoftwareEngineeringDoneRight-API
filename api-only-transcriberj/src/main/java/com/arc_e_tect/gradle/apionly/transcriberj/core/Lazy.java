package com.arc_e_tect.gradle.apionly.transcriberj.core;

import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Lazy sequences of candidates, each element computed only when asked for.
 *
 * <p>Not {@link java.util.stream.Stream}: on Java 21, which the plugin runs on, a
 * {@code flatMap} inside a {@code flatMap} evaluates its inner stream in full before a
 * short-circuiting operation sees the first element, and expanding a pattern or a schema
 * nests them deeply. None of these elements may be {@code null}.
 */
final class Lazy {

    private Lazy() {
    }

    /** Nothing. */
    static <T> Iterator<T> empty() {
        return List.<T>of().iterator();
    }

    /** One element. */
    static <T> Iterator<T> of(T value) {
        return List.of(value).iterator();
    }

    /** The integers from {@code from} to {@code to} inclusive, counting up or down. */
    static Iterator<Integer> range(int from, int to) {
        int step = from <= to ? 1 : -1;
        return new Iterator<>() {
            private int next = from;
            private boolean done = false;

            @Override
            public boolean hasNext() {
                return !done;
            }

            @Override
            public Integer next() {
                if (done) throw new NoSuchElementException();
                int out = next;
                if (next == to) {
                    done = true;
                } else {
                    next += step;
                }
                return out;
            }
        };
    }

    /** Each element mapped. */
    static <A, B> Iterator<B> map(Iterator<A> in, Function<? super A, ? extends B> f) {
        return new Iterator<>() {
            @Override
            public boolean hasNext() {
                return in.hasNext();
            }

            @Override
            public B next() {
                return f.apply(in.next());
            }
        };
    }

    /** The elements that pass. */
    static <T> Iterator<T> filter(Iterator<T> in, Predicate<? super T> keep) {
        return new Lookahead<>() {
            @Override
            T advance() {
                while (in.hasNext()) {
                    T candidate = in.next();
                    if (keep.test(candidate)) return candidate;
                }
                return null;
            }
        };
    }

    /** The elements of the sequence each element maps to, one after the other. */
    static <A, B> Iterator<B> flatMap(Iterator<A> in, Function<? super A, ? extends Iterator<? extends B>> f) {
        return new Lookahead<>() {
            private Iterator<? extends B> current = empty();

            @Override
            B advance() {
                while (!current.hasNext()) {
                    if (!in.hasNext()) return null;
                    current = f.apply(in.next());
                }
                return current.next();
            }
        };
    }

    /** Several sequences one after the other, each created only when the one before it is spent. */
    @SafeVarargs
    static <T> Iterator<T> concat(Supplier<? extends Iterator<? extends T>>... parts) {
        return flatMap(List.of(parts).iterator(), Supplier::get);
    }

    /** At most the first {@code n} elements. */
    static <T> Iterator<T> limit(Iterator<T> in, long n) {
        return new Iterator<>() {
            private long left = n;

            @Override
            public boolean hasNext() {
                return left > 0 && in.hasNext();
            }

            @Override
            public T next() {
                if (left <= 0) throw new NoSuchElementException();
                left--;
                return in.next();
            }
        };
    }

    /** The first element, if there is one. */
    static <T> Optional<T> first(Iterator<T> in) {
        return in.hasNext() ? Optional.of(in.next()) : Optional.empty();
    }

    /** An iterator that finds its next element ahead of being asked for it. */
    private abstract static class Lookahead<T> implements Iterator<T> {
        private T next;
        private boolean ready;

        /** The next element, or null when there is none. */
        abstract T advance();

        @Override
        public boolean hasNext() {
            if (!ready) {
                next = advance();
                ready = true;
            }
            return next != null;
        }

        @Override
        public T next() {
            if (!hasNext()) throw new NoSuchElementException();
            ready = false;
            return next;
        }
    }
}
