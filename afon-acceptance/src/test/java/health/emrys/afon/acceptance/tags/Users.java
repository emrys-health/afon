package health.emrys.afon.acceptance.tags;

import org.scalatest.TagAnnotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Tests exercising user/permission onboarding in KeyCloak and Polaris, independent of cloud. Filter with
 * {@code -DtagsToInclude=health.emrys.afon.acceptance.tags.Users}.
 */
@TagAnnotation
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface Users {
}
