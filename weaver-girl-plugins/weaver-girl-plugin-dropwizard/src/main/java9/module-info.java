module com.github.cc11001100.weavergirl.plugin.dropwizard {
    requires com.github.cc11001100.weavergirl.api;
    requires org.slf4j;

    exports com.github.cc11001100.weavergirl.plugins.dropwizard;

    provides com.github.cc11001100.weavergirl.api.plugin.WeaverPlugin
        with
        com.github.cc11001100.weavergirl.plugins.dropwizard.DropwizardPlugin;
}
