module com.github.cc11001100.weavergirl.plugin.awssdk2 {
    requires com.github.cc11001100.weavergirl.api;
    requires org.slf4j;

    exports com.github.cc11001100.weavergirl.plugins.awssdk2;

    provides com.github.cc11001100.weavergirl.api.plugin.WeaverPlugin
        with
        com.github.cc11001100.weavergirl.plugins.awssdk2.AwsSdk2Plugin;
}
