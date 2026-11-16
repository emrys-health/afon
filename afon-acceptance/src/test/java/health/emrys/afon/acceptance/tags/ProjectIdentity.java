package health.emrys.afon.acceptance.tags;

import org.scalatest.TagAnnotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The projects of the central identity: a client and a group per project, and tokens that name the one project they are for, in a KeyCloak
 * container, which needs docker and nothing else, independent of cloud.  A kind of {@link Project}: those specs have both tags.  Select only them
 * with {@code -Pidentity}.
 */
@TagAnnotation
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface ProjectIdentity {
}
