module com.github.cc11001100.weavergirl.plugin.jfinal {
    requires com.github.cc11001100.weavergirl.api;
    requires org.slf4j;

    exports com.github.cc11001100.weavergirl.plugins.jfinal;

    provides com.github.cc11001100.weavergirl.api.plugin.WeaverPlugin
        with
        com.github.cc11001100.weavergirl.plugins.jfinal.JFinalPlugin;
}
