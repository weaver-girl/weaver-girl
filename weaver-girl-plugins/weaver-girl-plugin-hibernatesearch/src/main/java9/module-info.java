module com.github.cc11001100.weavergirl.plugin.hibernatesearch {
    requires com.github.cc11001100.weavergirl.api;
    requires org.slf4j;

    exports com.github.cc11001100.weavergirl.plugins.hibernatesearch;

    provides com.github.cc11001100.weavergirl.api.plugin.WeaverPlugin
        with
        com.github.cc11001100.weavergirl.plugins.hibernatesearch.HibernateSearchPlugin;
}
