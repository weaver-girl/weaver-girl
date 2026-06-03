package com.github.cc11001100.weavergirl.api.topology;

import java.util.*;

/**
 * Represents a service in the topology graph.
 *
 * @since 1.2.0
 */
public class ServiceNode {

    private final String name;
    private final String type;
    private final Map<String, String> metadata;

    public ServiceNode(String name, String type, Map<String, String> metadata) {
        this.name = name;
        this.type = type;
        this.metadata = metadata != null
                ? Collections.unmodifiableMap(new LinkedHashMap<>(metadata))
                : Collections.emptyMap();
    }

    public ServiceNode(String name, String type) {
        this(name, type, null);
    }

    /** Service name (e.g. "order-service", "user-service"). */
    public String getName() { return name; }

    /** Service type (e.g. "http", "database", "cache", "messaging"). */
    public String getType() { return type; }

    /** Additional metadata about the service. */
    public Map<String, String> getMetadata() { return metadata; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ServiceNode that = (ServiceNode) o;
        return Objects.equals(name, that.name);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name);
    }

    @Override
    public String toString() {
        return "ServiceNode{name='" + name + "', type='" + type + "'}";
    }
}
