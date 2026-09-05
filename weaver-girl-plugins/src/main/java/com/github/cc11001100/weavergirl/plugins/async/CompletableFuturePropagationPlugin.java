package com.github.cc11001100.weavergirl.plugins.async;

import com.github.cc11001100.weavergirl.api.context.ContextCompletableFuture;
import com.github.cc11001100.weavergirl.api.context.ContextExecutor;
import com.github.cc11001100.weavergirl.api.plugin.AbstractPlugin;
import com.github.cc11001100.weavergirl.api.plugin.PluginContext;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * CompletableFuture context-propagation plugin (capability placeholder).
 *
 * <p>{@code CompletableFuture.supplyAsync}/{@code runAsync} cannot be woven
 * transparently by the agent:</p>
 * <ul>
 *   <li>{@code java.util.concurrent.CompletableFuture} lives on the bootstrap
 *       classloader, where {@code ARGUMENT_REWRITE} advice is skipped
 *       (see {@code WeaverTransformer.install}) and inlined advice cannot
 *       resolve the agent's own classes;</li>
 *   <li>the no-executor overloads use {@code ForkJoinPool.commonPool()}
 *       internally, also a bootstrap class.</li>
 * </ul>
 *
 * <p>For context propagation through CompletableFuture stages, use the
 * {@link ContextCompletableFuture} API instead:</p>
 * <pre>
 * // Instead of:
 * CompletableFuture.supplyAsync(() -&gt; doWork(), executor);
 * // Use:
 * ContextCompletableFuture.supplyAsync(() -&gt; doWork(), executor);
 * </pre>
 *
 * <p>This plugin currently registers no interceptor definitions and exists as
 * a discoverable capability marker: if the transformer ever gains bootstrap
 * {@code ARGUMENT_REWRITE} support (see {@code ContextExecutor} for the
 * wrapping strategy), the {@code supplyAsync}/{@code runAsync} factory-method
 * hooks belong here.</p>
 *
 * <p>Configuration:</p>
 * <ul>
 *   <li>{@code enabled} — Enable/disable (default: true)</li>
 * </ul>
 *
 * @see ContextCompletableFuture
 * @see ContextExecutor
 * @since 1.9.0
 */
public class CompletableFuturePropagationPlugin extends AbstractPlugin {

    private static final Logger log = LoggerFactory.getLogger(CompletableFuturePropagationPlugin.class);

    static final String COMPLETABLE_FUTURE = "java.util.concurrent.CompletableFuture";

    private boolean enabled = true;

    @Override
    public String name() {
        return "completable-future-context-propagation";
    }

    @Override
    public void init(PluginContext context) {
        enabled = context.getConfigBoolean("enabled", true);
    }

    @Override
    public void registerInterceptors(InterceptorRegistry registry) {
        if (!enabled) {
            return;
        }
        // Intentionally registers no definitions: weaving CompletableFuture
        // factory methods is not supported while ARGUMENT_REWRITE advice is
        // skipped for bootstrap classes. Users get propagation via
        // ContextCompletableFuture instead.
        if (log.isInfoEnabled()) {
            log.info("[completable-future-propagation] CompletableFuture weaving is not supported "
                    + "on the bootstrap classloader — use ContextCompletableFuture API for propagation");
        }
    }
}
