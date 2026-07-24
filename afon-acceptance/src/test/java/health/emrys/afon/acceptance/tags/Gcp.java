package health.emrys.afon.acceptance.tags;

import org.scalatest.TagAnnotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Tests exercising GCP. Filter with
 * {@code -DtagsToInclude=health.emrys.afon.acceptance.tags.Gcp}.
 */
@TagAnnotation
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface Gcp {
}
