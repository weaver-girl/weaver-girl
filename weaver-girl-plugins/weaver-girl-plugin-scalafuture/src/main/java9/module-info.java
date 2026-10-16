module com.github.cc11001100.weavergirl.plugin.scalafuture {
    requires com.github.cc11001100.weavergirl.api;
    requires org.slf4j;

    exports com.github.cc11001100.weavergirl.plugins.scalafuture;

    provides com.github.cc11001100.weavergirl.api.plugin.WeaverPlugin
        with
        com.github.cc11001100.weavergirl.plugins.scalafuture.ScalaFuturePlugin;
}
