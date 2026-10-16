module com.github.cc11001100.weavergirl.plugin.async {
    requires com.github.cc11001100.weavergirl.api;
    requires org.slf4j;

    exports com.github.cc11001100.weavergirl.plugins.async;

    provides com.github.cc11001100.weavergirl.api.plugin.WeaverPlugin
        with
        com.github.cc11001100.weavergirl.plugins.async.CompletableFuturePropagationPlugin,
        com.github.cc11001100.weavergirl.plugins.async.AsyncContextPropagationPlugin;
}
