package org.prebid.server.model;

import io.vertx.core.MultiMap;
import org.apache.commons.lang3.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.Set;

public class CaseInsensitiveMultiMap {

    private static final CaseInsensitiveMultiMap EMPTY = builder().build();

    private final MultiMap delegate;

    private CaseInsensitiveMultiMap(MultiMap delegate) {
        this.delegate = delegate;
    }

    public static CaseInsensitiveMultiMap empty() {
        return EMPTY;
    }

    public String get(CharSequence name) {
        return this.delegate.get(name);
    }

    public String get(String name) {
        return this.delegate.get(name);
    }

    public List<String> getAll(String name) {
        return this.delegate.getAll(name);
    }

    public List<String> getAll(CharSequence name) {
        return this.delegate.getAll(name);
    }

    public List<Map.Entry<String, String>> entries() {
        return this.delegate.entries();
    }

    public boolean contains(String name) {
        return this.delegate.contains(name);
    }

    public boolean contains(CharSequence name) {
        return this.delegate.contains(name);
    }

    public boolean isEmpty() {
        return this.delegate.isEmpty();
    }

    public Set<String> names() {
        return this.delegate.names();
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final CaseInsensitiveMultiMap that = (CaseInsensitiveMultiMap) o;
        if (delegate == that.delegate) {
            return true;
        }

        return delegate != null && delegate.size() == that.delegate.size() && delegate.entries().stream()
                .allMatch(entry -> that.delegate.contains(entry.getKey(), entry.getValue(), true));
    }

    @Override
    public int hashCode() {
        return delegate != null ? delegate.hashCode() : 0;
    }

    public static class Builder {

        private final MultiMap delegate;

        public Builder() {
            this.delegate = MultiMap.caseInsensitiveMultiMap();
        }

        public CaseInsensitiveMultiMap build() {
            return new CaseInsensitiveMultiMap(delegate);
        }

        public Builder add(String name, String value) {
            delegate.add(name, value);
            return this;
        }

        public Builder add(CharSequence name, CharSequence value) {
            delegate.add(name, value);
            return this;
        }

        public Builder add(String name, Iterable<String> values) {
            delegate.add(name, values);
            return this;
        }

        public Builder add(CharSequence name, Iterable<CharSequence> values) {
            delegate.add(name, values);
            return this;
        }

        public Builder addAll(CaseInsensitiveMultiMap map) {
            map.entries().forEach(entry -> delegate.add(entry.getKey(), entry.getValue()));
            return this;
        }

        public Builder addAll(Map<String, String> map) {
            delegate.addAll(map);
            return this;
        }
    }

    @Override
    public String toString() {
        return delegate != null ? delegate.toString() : StringUtils.EMPTY;
    }
}
