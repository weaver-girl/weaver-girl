module com.github.cc11001100.weavergirl.plugin.jetty12http {
    requires com.github.cc11001100.weavergirl.api;
    requires org.slf4j;

    exports com.github.cc11001100.weavergirl.plugins.jetty12http;

    provides com.github.cc11001100.weavergirl.api.plugin.WeaverPlugin
        with
        com.github.cc11001100.weavergirl.plugins.jetty12http.Jetty12HttpPlugin;
}
