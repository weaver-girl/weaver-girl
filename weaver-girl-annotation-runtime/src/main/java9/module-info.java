module com.github.cc11001100.weavergirl.annotation.runtime {
    requires com.github.cc11001100.weavergirl.api;
    requires com.github.cc11001100.weavergirl.annotation;
    requires org.slf4j;

    exports com.github.cc11001100.weavergirl.core.plugin;
    exports com.github.cc11001100.weavergirl.core.scanner;
}
