package health.emrys.afon.acceptance.tags;

import org.scalatest.TagAnnotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Cross-cloud federation tests: one Polaris instance proxying to an external catalog (another Polaris,
 * Glue, or Databricks Unity Catalog). Filter with
 * {@code -DtagsToInclude=health.emrys.afon.acceptance.tags.External}.
 */
@TagAnnotation
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface External {
}
