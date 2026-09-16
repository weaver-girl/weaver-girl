package com.github.cc11001100.weavergirl.api.introduction;

import com.github.cc11001100.weavergirl.api.matcher.ClassMatcher;

/**
 * Describes which types should receive an introduction (mixin), and what interface / delegate
 * implementation should be added.
 *
 * <p>An introduction rewrites the target class so that it implements the given {@code
 * introductionType} and delegates its methods to the supplied {@code delegate}. This is the
 * runtime equivalent of implementing an interface by delegation.
 *
 * <p>The {@link ClassMatcher} selects target classes; only {@link
 * com.github.cc11001100.weavergirl.api.matcher.ClassMatcher.MatchType#EXACT_NAME}, {@link
 * com.github.cc11001100.weavergirl.api.matcher.ClassMatcher.MatchType#NAME_PATTERN}, {@link
 * com.github.cc11001100.weavergirl.api.matcher.ClassMatcher.MatchType#ANNOTATION}, {@link
 * com.github.cc11001100.weavergirl.api.matcher.ClassMatcher.MatchType#SUPER_CLASS}, and {@link
 * com.github.cc11001100.weavergirl.api.matcher.ClassMatcher.MatchType#INTERFACE} are supported.
 *
 * @since 2.0.0
 */
public class IntroductionDefinition {

  private final String name;
  private final ClassMatcher classMatcher;
  private final Class<?> introductionType;
  private final Object delegate;

  /**
   * Creates a new introduction definition.
   *
   * @param name unique name for this definition
   * @param classMatcher selects which classes receive the introduction
   * @param introductionType the interface type to add to the target class
   * @param delegate the delegate instance that will handle the introduced methods
   */
  public IntroductionDefinition(
      String name, ClassMatcher classMatcher, Class<?> introductionType, Object delegate) {
    if (name == null || name.isEmpty()) {
      throw new IllegalArgumentException("Introduction name must not be null or empty");
    }
    if (classMatcher == null) {
      throw new IllegalArgumentException("ClassMatcher must not be null for introduction: " + name);
    }
    if (introductionType == null) {
      throw new IllegalArgumentException("Introduction type must not be null for introduction: " + name);
    }
    if (delegate == null) {
      throw new IllegalArgumentException("Delegate must not be null for introduction: " + name);
    }
    if (!introductionType.isInterface()) {
      throw new IllegalArgumentException(
          "Introduction type must be an interface: " + introductionType.getName());
    }
    this.name = name;
    this.classMatcher = classMatcher;
    this.introductionType = introductionType;
    this.delegate = delegate;
  }

  public String getName() {
    return name;
  }

  public ClassMatcher getClassMatcher() {
    return classMatcher;
  }

  public Class<?> getIntroductionType() {
    return introductionType;
  }

  public Object getDelegate() {
    return delegate;
  }
}
