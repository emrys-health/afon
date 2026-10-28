package health.emrys.afon.acceptance.tags;

import org.scalatest.TagAnnotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Tests exercising the signing of Docker images and their verification at admission by Kyverno, in a kind cluster, independent of cloud. Filter with
 * {@code -DtagsToInclude=health.emrys.afon.acceptance.tags.Signing}.
 */
@TagAnnotation
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface Signing {
}
