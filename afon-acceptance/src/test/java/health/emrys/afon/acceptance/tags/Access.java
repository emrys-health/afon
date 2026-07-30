package health.emrys.afon.acceptance.tags;

import org.scalatest.TagAnnotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Positive-path tests: write/read a table via Polaris and confirm access is granted. Filter with
 * {@code -DtagsToInclude=health.emrys.afon.acceptance.tags.Access}.
 */
@TagAnnotation
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface Access {
}
