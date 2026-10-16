module com.github.cc11001100.weavergirl.plugin.scalaforkjoin {
    requires com.github.cc11001100.weavergirl.api;
    requires org.slf4j;

    exports com.github.cc11001100.weavergirl.plugins.scalaforkjoin;

    provides com.github.cc11001100.weavergirl.api.plugin.WeaverPlugin
        with
        com.github.cc11001100.weavergirl.plugins.scalaforkjoin.ScalaForkJoinPlugin;
}
